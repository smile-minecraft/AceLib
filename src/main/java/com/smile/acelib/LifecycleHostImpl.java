package com.smile.acelib;

import com.smile.acelib.lifecycle.LifecycleHost;
import com.smile.acelib.lifecycle.LifecycleModule;
import com.smile.acelib.lifecycle.LifecycleModule.Context;
import com.smile.acelib.lifecycle.LifecycleModule.Handle;
import com.smile.acelib.lifecycle.LifecycleResult.Code;
import com.smile.acelib.lifecycle.LifecycleResult.Outcome;
import com.smile.acelib.lifecycle.LifecycleResult.Problem;
import com.smile.acelib.lifecycle.LifecycleResult;
import com.smile.acelib.lifecycle.LifecycleHost.Status;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/** AceLib 內部生命週期宿主實作；下游只接觸 {@link LifecycleHost}。 */
final class LifecycleHostImpl implements LifecycleHost {

    private final Supplier<AceLibApi.AceLibProvider> providerSupplier;
    private final Logger logger;
    private final Map<String, OwnedModule> modules = new LinkedHashMap<>();
    private final Map<String, OwnedModule> orphanHandles = new LinkedHashMap<>();
    private Status status = Status.NOT_READY;
    private LifecycleResult lastResult = success(Status.NOT_READY, List.of());
    private boolean transitionInProgress;
    private boolean reloadRetryPending;

    LifecycleHostImpl(Supplier<AceLibApi.AceLibProvider> providerSupplier, Logger logger) {
        this.providerSupplier = Objects.requireNonNull(providerSupplier, "providerSupplier");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    synchronized LifecycleResult activate() {
        if (status == Status.NOT_READY) {
            status = Status.READY;
            return record(success(status, List.of()));
        }
        if (status == Status.READY) {
            return record(success(status, List.of()));
        }
        return reject(Code.INVALID_STATE, null, List.of(),
            "host cannot activate while " + status);
    }

    @Override
    public synchronized Status status() {
        return status;
    }

    @Override
    public synchronized LifecycleResult lastResult() {
        return lastResult;
    }

    @Override
    public synchronized LifecycleResult register(Plugin owner,
            Collection<LifecycleModule> declarations) {
        if (owner == null) {
            return reject(Code.INVALID_MODULE, null, List.of(),
                "module owner must not be null");
        }
        if (declarations == null) {
            return reject(Code.INVALID_MODULE, null, List.of(),
                "module batch must not be null");
        }
        if (status != Status.READY || transitionInProgress || reloadRetryPending) {
            return reject(Code.INVALID_STATE, null, List.of(),
                reloadRetryPending
                    ? "host requires a reload retry before accepting registrations"
                    : "host does not accept registration while " + status);
        }

        List<LifecycleModule> batch = new ArrayList<>(declarations);
        List<Problem> problems = new ArrayList<>();
        Map<String, LifecycleModule> added = new TreeMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (LifecycleModule module : batch) {
            if (module == null) {
                problems.add(problem(Code.INVALID_MODULE, null, List.of(),
                    "module batch contains a null declaration"));
                continue;
            }
            counts.merge(module.id(), 1, Integer::sum);
            added.putIfAbsent(module.id(), module);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > 1 || modules.containsKey(entry.getKey())) {
                problems.add(problem(Code.DUPLICATE_ID, entry.getKey(), List.of(entry.getKey()),
                    "module id is already present in this batch or host graph"));
            }
        }

        Map<String, LifecycleModule> graph = definitions();
        for (Map.Entry<String, LifecycleModule> entry : added.entrySet()) {
            if (!modules.containsKey(entry.getKey())) {
                graph.put(entry.getKey(), entry.getValue());
            }
        }
        validateDependencies(graph, problems);
        List<String> orderedIds = topologicalOrder(graph);
        findCycles(graph, problems);
        List<String> affected = added.keySet().stream().sorted().toList();
        if (!problems.isEmpty()) {
            return record(new LifecycleResult(Outcome.REJECTED, status, problems, affected));
        }

        transitionInProgress = true;
        List<OwnedModule> started = new ArrayList<>();
        try {
            AceLibApi.AceLibProvider provider = providerSupplier.get();
            if (provider == null) {
                return reject(Code.INVALID_STATE, null, affected,
                    "AceLib API provider is not available");
            }
            for (String id : orderedIds) {
                LifecycleModule declaration = added.get(id);
                if (declaration == null) {
                    continue;
                }
                try {
                    Handle handle = declaration.starter().enable(new Context(owner, provider));
                    if (handle == null) {
                        throw new IllegalStateException("module enable returned a null handle");
                    }
                    started.add(new OwnedModule(owner, declaration, handle));
                } catch (Throwable failure) {
                    logFailure(Code.ENABLE_FAILED, id, failure);
                    problems.add(problem(Code.ENABLE_FAILED, id, List.of(id),
                        "module enable callback failed"));
                    compensate(started, problems);
                    if (hasOpenOrphans()) {
                        status = Status.FAILED;
                    }
                    return record(new LifecycleResult(Outcome.FAILED, status, problems, affected));
                }
            }
            for (OwnedModule startedModule : started) {
                modules.put(startedModule.declaration.id(), startedModule);
            }
            return record(success(status, affected));
        } finally {
            transitionInProgress = false;
        }
    }

    @Override
    public synchronized LifecycleResult unregister(Plugin owner) {
        if (owner == null) {
            return reject(Code.INVALID_MODULE, null, List.of(),
                "module owner must not be null");
        }
        Map<String, OwnedModule> entries = allHandleEntries();
        Set<String> ownedIds = new TreeSet<>();
        for (OwnedModule module : entries.values()) {
            if (module.owner == owner) {
                ownedIds.add(module.declaration.id());
            }
        }
        if (ownedIds.isEmpty()) {
            return record(success(status, List.of()));
        }
        boolean failedClosed = status == Status.FAILED;
        if ((!failedClosed && status != Status.READY) || transitionInProgress) {
            return reject(Code.INVALID_STATE, null, List.copyOf(ownedIds),
                "host does not accept unregister while " + status);
        }

        if (!failedClosed) {
            Set<String> blockers = dependentOwnersOutside(owner, ownedIds);
            if (!blockers.isEmpty()) {
                return reject(Code.ACTIVE_DEPENDENTS, null, List.copyOf(blockers),
                    "other plugin modules still depend on modules owned by " + owner.getName());
            }
        }

        transitionInProgress = true;
        List<Problem> problems = new ArrayList<>();
        try {
            closeInReverseOrder(entries, ownedIds, problems);
            if (!problems.isEmpty()) {
                status = Status.FAILED;
                reloadRetryPending = false;
                return record(new LifecycleResult(Outcome.FAILED, status, problems,
                    List.copyOf(ownedIds)));
            }
            ownedIds.forEach(modules::remove);
            ownedIds.forEach(orphanHandles::remove);
            return record(success(status, List.copyOf(ownedIds)));
        } finally {
            transitionInProgress = false;
        }
    }

    synchronized LifecycleResult ownerDisabled(Plugin owner) {
        LifecycleResult result = unregister(owner);
        boolean blocked = result.problems().stream()
            .anyMatch(problem -> problem.code() == Code.ACTIVE_DEPENDENTS);
        if (blocked) {
            status = Status.FAILED;
            reloadRetryPending = false;
            result = new LifecycleResult(Outcome.FAILED, status, result.problems(),
                result.moduleIds());
            record(result);
        }
        return result;
    }

    synchronized LifecycleResult beginReload() {
        if (status != Status.READY || transitionInProgress) {
            return reject(Code.INVALID_STATE, null, List.of(),
                "host cannot begin reload while " + status);
        }
        transitionInProgress = true;
        status = Status.RELOADING;
        List<Problem> problems = new ArrayList<>();
        try {
            closeInReverseOrder(allHandleEntries(), null, problems);
            if (!problems.isEmpty()) {
                status = Status.FAILED;
                reloadRetryPending = false;
                return record(new LifecycleResult(Outcome.FAILED, status, problems,
                    List.copyOf(modules.keySet())));
            }
            orphanHandles.clear();
            return record(success(status, List.copyOf(modules.keySet())));
        } finally {
            transitionInProgress = false;
        }
    }

    synchronized LifecycleResult finishReload() {
        if (status != Status.RELOADING || transitionInProgress) {
            return reject(Code.INVALID_STATE, null, List.of(),
                "host cannot finish reload while " + status);
        }
        transitionInProgress = true;
        List<Problem> problems = new ArrayList<>();
        List<OwnedModule> started = new ArrayList<>();
        List<String> orderedIds = topologicalOrder(definitions());
        try {
            AceLibApi.AceLibProvider provider = providerSupplier.get();
            if (provider == null) {
                problems.add(problem(Code.RELOAD_FAILED, null, List.of(),
                    "AceLib API provider is not available after core reload"));
                status = Status.FAILED;
                reloadRetryPending = false;
                return record(new LifecycleResult(Outcome.FAILED, status, problems,
                    orderedIds));
            }
            for (String id : orderedIds) {
                OwnedModule existing = modules.get(id);
                if (existing == null) {
                    continue;
                }
                try {
                    Handle handle = existing.declaration.starter().enable(
                        new Context(existing.owner, provider));
                    if (handle == null) {
                        throw new IllegalStateException("module enable returned a null handle");
                    }
                    started.add(new OwnedModule(existing.owner, existing.declaration, handle));
                } catch (Throwable failure) {
                    logFailure(Code.ENABLE_FAILED, id, failure);
                    problems.add(problem(Code.ENABLE_FAILED, id, List.of(id),
                        "module failed to rebuild after core reload"));
                    break;
                }
            }
            if (!problems.isEmpty()) {
                compensate(started, problems);
                problems.add(problem(Code.RELOAD_FAILED, null, moduleIds(started),
                    "core reload committed but lifecycle modules did not fully rebuild"));
                status = Status.FAILED;
                reloadRetryPending = false;
                return record(new LifecycleResult(Outcome.FAILED, status, problems,
                    orderedIds));
            }
            for (OwnedModule startedModule : started) {
                modules.put(startedModule.declaration.id(), startedModule);
            }
            status = Status.READY;
            reloadRetryPending = false;
            return record(success(status, orderedIds));
        } finally {
            transitionInProgress = false;
        }
    }

    synchronized LifecycleResult failReload(Throwable failure) {
        status = Status.FAILED;
        reloadRetryPending = false;
        if (failure != null) {
            logFailure(Code.RELOAD_FAILED, null, failure);
        }
        String detail = failure == null
            ? "core reload did not commit; host remains fail-closed"
            : "core reload failed after lifecycle modules were stopped; host remains fail-closed";
        return record(new LifecycleResult(Outcome.FAILED, status,
            List.of(problem(Code.RELOAD_FAILED, null, List.of(), detail)),
            List.copyOf(modules.keySet())));
    }

    synchronized LifecycleResult retryableReloadFailure(Throwable failure) {
        if (status != Status.RELOADING || transitionInProgress) {
            return reject(Code.INVALID_STATE, null, List.copyOf(modules.keySet()),
                "host cannot mark reload retryable while " + status);
        }
        status = Status.READY;
        reloadRetryPending = true;
        if (failure != null) {
            logFailure(Code.RELOAD_FAILED, null, failure);
        }
        String detail = failure == null
            ? "core reload did not commit; module handles are dormant and reload may be retried"
            : "core reload failed and can be retried; module handles remain dormant";
        return record(new LifecycleResult(Outcome.FAILED, status,
            List.of(problem(Code.RELOAD_FAILED, null, List.of(), detail)),
            List.copyOf(modules.keySet())));
    }

    synchronized LifecycleResult shutdown() {
        if (transitionInProgress) {
            return reject(Code.INVALID_STATE, null, List.copyOf(modules.keySet()),
                "host cannot shut down during another lifecycle operation");
        }
        transitionInProgress = true;
        List<Problem> problems = new ArrayList<>();
        List<String> ids = new ArrayList<>(modules.keySet());
        try {
            closeInReverseOrder(allHandleEntries(), null, problems);
            if (problems.isEmpty()) {
                modules.clear();
                orphanHandles.clear();
            }
            status = Status.SHUTDOWN;
            reloadRetryPending = false;
            if (problems.isEmpty()) {
                return record(success(status, ids));
            }
            return record(new LifecycleResult(Outcome.FAILED, status, problems, ids));
        } finally {
            transitionInProgress = false;
        }
    }

    private void validateDependencies(Map<String, LifecycleModule> graph,
            List<Problem> problems) {
        for (LifecycleModule module : graph.values()) {
            List<String> missing = module.dependsOn().stream()
                .filter(dependency -> !graph.containsKey(dependency)).sorted().toList();
            for (String dependency : missing) {
                problems.add(problem(Code.MISSING_DEPENDENCY, module.id(), List.of(dependency),
                    "module depends on an id that is not registered"));
            }
        }
    }

    private void findCycles(Map<String, LifecycleModule> graph, List<Problem> problems) {
        Map<String, Integer> indexById = new HashMap<>();
        Map<String, Integer> lowLinkById = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] nextIndex = {0};
        for (String id : new TreeSet<>(graph.keySet())) {
            if (!indexById.containsKey(id)) {
                strongConnect(id, graph, indexById, lowLinkById, stack, onStack,
                    nextIndex, problems);
            }
        }
    }

    private void strongConnect(String id, Map<String, LifecycleModule> graph,
            Map<String, Integer> indexById, Map<String, Integer> lowLinkById,
            Deque<String> stack, Set<String> onStack, int[] nextIndex,
            List<Problem> problems) {
        int index = nextIndex[0]++;
        indexById.put(id, index);
        lowLinkById.put(id, index);
        stack.push(id);
        onStack.add(id);
        for (String dependency : graph.get(id).dependsOn().stream().sorted().toList()) {
            if (!graph.containsKey(dependency)) {
                continue;
            }
            if (!indexById.containsKey(dependency)) {
                strongConnect(dependency, graph, indexById, lowLinkById,
                    stack, onStack, nextIndex, problems);
                lowLinkById.put(id, Math.min(lowLinkById.get(id), lowLinkById.get(dependency)));
            } else if (onStack.contains(dependency)) {
                lowLinkById.put(id, Math.min(lowLinkById.get(id), indexById.get(dependency)));
            }
        }
        if (!lowLinkById.get(id).equals(indexById.get(id))) {
            return;
        }
        Set<String> component = new TreeSet<>();
        String member;
        do {
            member = stack.pop();
            onStack.remove(member);
            component.add(member);
        } while (!member.equals(id));
        boolean selfCycle = component.size() == 1
            && graph.get(id).dependsOn().contains(id);
        if (component.size() > 1 || selfCycle) {
            problems.add(problem(Code.DEPENDENCY_CYCLE, null, List.copyOf(component),
                "module dependency graph contains a cycle"));
        }
    }

    private List<String> topologicalOrder(Map<String, LifecycleModule> graph) {
        Map<String, Integer> dependencyCount = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (String id : graph.keySet()) {
            dependencyCount.put(id, 0);
            dependents.put(id, new TreeSet<>());
        }
        for (LifecycleModule module : graph.values()) {
            int count = 0;
            for (String dependency : module.dependsOn()) {
                if (graph.containsKey(dependency)) {
                    count++;
                    dependents.get(dependency).add(module.id());
                }
            }
            dependencyCount.put(module.id(), count);
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        dependencyCount.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        List<String> ordered = new ArrayList<>(graph.size());
        while (!ready.isEmpty()) {
            String id = ready.remove();
            ordered.add(id);
            for (String dependent : dependents.get(id)) {
                int remaining = dependencyCount.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        return ordered;
    }

    private void closeInReverseOrder(Map<String, OwnedModule> entries,
            Set<String> selectedIds, List<Problem> problems) {
        Map<String, LifecycleModule> graph = new TreeMap<>();
        entries.forEach((id, entry) -> graph.put(id, entry.declaration));
        List<String> ordered = topologicalOrder(graph);
        if (ordered.size() != graph.size()) {
            ordered = graph.keySet().stream().sorted().toList();
        }
        for (int i = ordered.size() - 1; i >= 0; i--) {
            String id = ordered.get(i);
            if (selectedIds != null && !selectedIds.contains(id)) {
                continue;
            }
            OwnedModule entry = entries.get(id);
            if (entry != null && entry.handle != null) {
                closeHandle(entry, problems);
            }
        }
    }

    private void closeHandle(OwnedModule module, List<Problem> problems) {
        try {
            module.handle.close();
            module.handle = null;
        } catch (Throwable failure) {
            logFailure(Code.CLOSE_FAILED, module.declaration.id(), failure);
            problems.add(problem(Code.CLOSE_FAILED, module.declaration.id(),
                List.of(module.declaration.id()), "module handle cleanup failed"));
        }
    }

    private void compensate(List<OwnedModule> started, List<Problem> problems) {
        for (int i = started.size() - 1; i >= 0; i--) {
            OwnedModule module = started.get(i);
            List<Problem> cleanupProblems = new ArrayList<>();
            closeHandle(module, cleanupProblems);
            problems.addAll(cleanupProblems);
            if (module.handle != null) {
                orphanHandles.put(module.declaration.id(), module);
            }
        }
    }

    private Set<String> dependentOwnersOutside(Plugin owner, Set<String> removedIds) {
        Set<String> blockers = new TreeSet<>();
        for (OwnedModule candidate : modules.values()) {
            if (candidate.owner != owner
                    && transitivelyDependsOn(candidate.declaration, removedIds, new HashSet<>())) {
                blockers.add(candidate.declaration.id());
            }
        }
        return blockers;
    }

    private boolean transitivelyDependsOn(LifecycleModule candidate, Set<String> targets,
            Set<String> visited) {
        for (String dependency : candidate.dependsOn()) {
            if (targets.contains(dependency)) {
                return true;
            }
            if (!visited.add(dependency)) {
                continue;
            }
            OwnedModule dependencyModule = modules.get(dependency);
            if (dependencyModule != null
                    && transitivelyDependsOn(dependencyModule.declaration, targets, visited)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, LifecycleModule> definitions() {
        Map<String, LifecycleModule> result = new TreeMap<>();
        modules.forEach((id, module) -> result.put(id, module.declaration));
        return result;
    }

    private Map<String, OwnedModule> allHandleEntries() {
        Map<String, OwnedModule> result = new TreeMap<>(modules);
        result.putAll(orphanHandles);
        return result;
    }

    private boolean hasOpenOrphans() {
        return orphanHandles.values().stream().anyMatch(entry -> entry.handle != null);
    }

    private static List<String> moduleIds(List<OwnedModule> modules) {
        return modules.stream().map(module -> module.declaration.id()).sorted().toList();
    }

    private LifecycleResult reject(Code code, String moduleId,
            List<String> relatedIds, String message) {
        return record(new LifecycleResult(Outcome.REJECTED, status,
            List.of(problem(code, moduleId, relatedIds, message)), relatedIds));
    }

    private LifecycleResult record(LifecycleResult result) {
        lastResult = result;
        return result;
    }

    private static LifecycleResult success(Status status, List<String> moduleIds) {
        return new LifecycleResult(Outcome.SUCCESS, status, List.of(), moduleIds);
    }

    private static Problem problem(Code code, String moduleId,
            List<String> relatedIds, String message) {
        return new Problem(code, moduleId, relatedIds, message);
    }

    private void logFailure(Code code, String moduleId, Throwable failure) {
        String id = moduleId == null ? "host" : "module " + moduleId;
        logger.log(Level.SEVERE, "[" + code.code() + "] " + id + " lifecycle operation failed",
            failure);
    }

    private static final class OwnedModule {

        private final Plugin owner;
        private final LifecycleModule declaration;
        private Handle handle;

        private OwnedModule(Plugin owner, LifecycleModule declaration, Handle handle) {
            this.owner = owner;
            this.declaration = declaration;
            this.handle = handle;
        }
    }
}
