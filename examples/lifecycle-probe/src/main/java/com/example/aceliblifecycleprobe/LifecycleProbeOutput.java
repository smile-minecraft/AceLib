package com.example.aceliblifecycleprobe;

import com.smile.acelib.lifecycle.LifecycleHost;
import com.smile.acelib.lifecycle.LifecycleResult;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

final class LifecycleProbeOutput {

    static final String MODULE_ENABLED = "lprobe module enabled";
    static final String MODULE_CLOSED = "lprobe module closed";

    private LifecycleProbeOutput() {
    }

    static String status(boolean apiReady, LifecycleHost.Status lifecycleStatus,
                         LifecycleResult lastResult) {
        return "[lprobe] status apiReady=" + apiReady
            + " lifecycleStatus=" + Objects.requireNonNull(lifecycleStatus, "lifecycleStatus")
            + " lastResult=" + describe(Objects.requireNonNull(lastResult, "lastResult"));
    }

    static String reload(long sequence, boolean apiReady,
                         LifecycleHost.Status lifecycleStatus, LifecycleResult lastResult) {
        return "[lprobe] reload #" + sequence + " return=void apiReady=" + apiReady
            + " lifecycleStatus=" + Objects.requireNonNull(lifecycleStatus, "lifecycleStatus")
            + " lastResult=" + describe(Objects.requireNonNull(lastResult, "lastResult"));
    }

    static String operation(String name, LifecycleResult result) {
        return "[lprobe] " + Objects.requireNonNull(name, "name")
            + " result=" + describe(Objects.requireNonNull(result, "result"));
    }

    private static String describe(LifecycleResult result) {
        String modules = join(result.moduleIds());
        String problems = result.problems().stream()
            .map(problem -> problem.code().code()
                + (problem.moduleId() == null ? "" : "@" + problem.moduleId())
                + "=" + problem.message())
            .collect(Collectors.joining(";"));
        if (problems.isEmpty()) {
            problems = "none";
        }
        return result.outcome() + "/status=" + result.status()
            + "/modules=" + modules + "/problems=" + problems;
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? "none" : String.join(",", values);
    }
}
