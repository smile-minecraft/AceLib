package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smile.acelib.lifecycle.LifecycleHost;
import com.smile.acelib.lifecycle.LifecycleModule;
import com.smile.acelib.lifecycle.LifecycleResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 驗證相依圖、handle 所有權、補償與 reload 的可觀察行為。 */
@DisplayName("生命週期宿主")
class LifecycleHostImplTest {

    private static final Logger LOGGER = Logger.getLogger("AceLibLifecycleHostTest");

    private final AtomicReference<AceLibApi> currentApi =
        new AtomicReference<>(AceLibApi.uninitialized());
    private final AceLibApi.AceLibProvider provider = currentApi::get;
    private LifecycleHostImpl host;
    private List<String> events;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        host = new LifecycleHostImpl(() -> provider, LOGGER);
        host.activate();
    }

    @Test
    @DisplayName("整批驗證缺依賴、重名與循環，任何結構錯誤都不呼叫 enable")
    void register_invalidGraphReportsAllProblemsWithoutSideEffects() {
        Plugin owner = owner("ConsumerA");
        LifecycleResult result = host.register(owner, List.of(
            module("consumer:missing", Set.of("missing:module"), context -> {
                events.add("must-not-enable");
                return () -> { };
            }),
            module("consumer:duplicate", Set.of(), context -> () -> { }),
            module("consumer:duplicate", Set.of(), context -> () -> { }),
            module("consumer:cycle-a", Set.of("consumer:cycle-b"), context -> () -> { }),
            module("consumer:cycle-b", Set.of("consumer:cycle-a"), context -> () -> { })));

        assertEquals(LifecycleResult.Outcome.REJECTED, result.outcome());
        assertEquals(LifecycleHost.Status.READY, result.status());
        assertTrue(result.problems().stream().anyMatch(p ->
            p.code() == LifecycleResult.Code.MISSING_DEPENDENCY));
        assertTrue(result.problems().stream().anyMatch(p ->
            p.code() == LifecycleResult.Code.DUPLICATE_ID));
        assertTrue(result.problems().stream().anyMatch(p ->
            p.code() == LifecycleResult.Code.DEPENDENCY_CYCLE));
        assertTrue(events.isEmpty(), "結構拒絕前不得啟用任何模組");
        assertSame(result, host.lastResult());
    }

    @Test
    @DisplayName("依圖啟用、反向停用，並拒絕撤銷仍被其他 plugin 使用的模組")
    void register_ordersModulesAndProtectsCrossOwnerDependencies() {
        Plugin ownerA = owner("ConsumerA");
        Plugin ownerB = owner("ConsumerB");
        LifecycleResult first = host.register(ownerA, List.of(
            module("consumer-a:feature", Set.of("consumer-a:base"), context -> {
                events.add("enable-feature");
                return () -> events.add("close-feature");
            }),
            module("consumer-a:base", Set.of(), context -> {
                events.add("enable-base");
                return () -> events.add("close-base");
            })));
        LifecycleResult second = host.register(ownerB, List.of(
            module("consumer-b:dependent", Set.of("consumer-a:feature"), context -> {
                events.add("enable-dependent");
                return () -> events.add("close-dependent");
            })));

        assertTrue(first.isSuccess());
        assertTrue(second.isSuccess());
        assertEquals(List.of("enable-base", "enable-feature", "enable-dependent"), events);

        LifecycleResult blocked = host.unregister(ownerA);
        assertEquals(LifecycleResult.Outcome.REJECTED, blocked.outcome());
        assertEquals(LifecycleResult.Code.ACTIVE_DEPENDENTS,
            blocked.problems().getFirst().code());
        assertEquals(List.of("consumer-b:dependent"),
            blocked.problems().getFirst().relatedModuleIds());
        assertEquals(3, events.size(), "拒絕撤銷不得先關閉任何 handle");

        assertTrue(host.unregister(ownerB).isSuccess());
        assertTrue(host.unregister(ownerA).isSuccess());
        assertEquals(List.of("enable-base", "enable-feature", "enable-dependent",
            "close-dependent", "close-feature", "close-base"), events);
        assertTrue(host.unregister(ownerA).isSuccess(), "重複 unregister 應安全 no-op");
    }

    @Test
    @DisplayName("批次啟用失敗時反向清理已啟用模組且不提交圖")
    void register_enableFailureCompensatesSuccessfulModules() {
        Plugin owner = owner("ConsumerA");
        LifecycleResult failed = host.register(owner, List.of(
            module("consumer:base", Set.of(), context -> {
                events.add("enable-base");
                return () -> events.add("close-base");
            }),
            module("consumer:broken", Set.of("consumer:base"), context -> {
                events.add("enable-broken");
                throw new IllegalStateException("injected enable failure");
            })));

        assertEquals(LifecycleResult.Outcome.FAILED, failed.outcome());
        assertEquals(LifecycleHost.Status.READY, failed.status());
        assertTrue(failed.problems().stream().anyMatch(p ->
            p.code() == LifecycleResult.Code.ENABLE_FAILED));
        assertEquals(List.of("enable-base", "enable-broken", "close-base"), events);

        LifecycleResult retry = host.register(owner, List.of(
            module("consumer:base", Set.of(), context -> () -> { })));
        assertTrue(retry.isSuccess(), "補償完成後失敗批次不得留在宿主圖中");
    }

    @Test
    @DisplayName("補償清理失敗時保留待清理 handle 並明確將宿主標成 FAILED")
    void register_rollbackFailureMarksHostFailedAndRetainsHandle() {
        Plugin owner = owner("ConsumerA");
        AtomicBoolean failClose = new AtomicBoolean(true);
        LifecycleResult result = host.register(owner, List.of(
            module("consumer:base", Set.of(), context -> () -> {
                events.add("close-base");
                if (failClose.getAndSet(false)) {
                    throw new IllegalStateException("injected close failure");
                }
            }),
            module("consumer:broken", Set.of("consumer:base"), context -> {
                throw new IllegalStateException("injected enable failure");
            })));

        assertEquals(LifecycleResult.Outcome.FAILED, result.outcome());
        assertEquals(LifecycleHost.Status.FAILED, result.status());
        assertTrue(result.problems().stream().anyMatch(p ->
            p.code() == LifecycleResult.Code.CLOSE_FAILED));
        assertEquals(LifecycleHost.Status.FAILED, host.status());
        assertTrue(host.shutdown().isSuccess(), "停用時重試 orphan handle 清理");
        assertEquals(List.of("close-base", "close-base"), events);
    }

    @Test
    @DisplayName("close 擲出 Error 時仍繼續清理其餘 handle 並保留失敗 handle")
    void unregister_closeThrowingErrorContinuesCleanupAndRetainsHandle() {
        Plugin owner = owner("ConsumerA");
        AtomicBoolean failFeature = new AtomicBoolean(true);
        assertTrue(host.register(owner, List.of(
            module("consumer:base", Set.of(), context -> () -> events.add("close-base")),
            module("consumer:feature", Set.of("consumer:base"), context -> () -> {
                events.add("close-feature");
                if (failFeature.getAndSet(false)) {
                    throw new Error("injected close Error");
                }
            }))).isSuccess());

        LifecycleResult result = host.unregister(owner);

        assertEquals(LifecycleResult.Outcome.FAILED, result.outcome());
        assertEquals(LifecycleHost.Status.FAILED, result.status());
        assertTrue(result.problems().stream().anyMatch(problem ->
            problem.code() == LifecycleResult.Code.CLOSE_FAILED
                && "consumer:feature".equals(problem.moduleId())),
            "失敗 handle 的 CLOSE_FAILED 問題必須保留模組 id");
        assertEquals(List.of("close-feature", "close-base"), events,
            "Error 不得中斷其餘 handle 的反向清理");
        assertSame(result, host.lastResult());
        assertEquals(LifecycleHost.Status.FAILED, host.status());

        LifecycleResult retry = host.unregister(owner);
        assertTrue(retry.isSuccess(), "失敗 handle 被保留，重試清理應能完成");
        assertEquals(2, events.stream().filter("close-feature"::equals).count(),
            "失敗 handle 被保留，重試時必須再次清理同一個 handle");
        assertEquals(1, events.stream().filter("close-base"::equals).count(),
            "已成功清理的 handle 不會重複清理");
        assertEquals(LifecycleHost.Status.FAILED, host.status(),
            "清理重試成功也不得把 fail-closed 宿主重新開放");
    }

    @Test
    @DisplayName("FAILED 時仍可撤銷停用 owner 的模組，但宿主保持 fail-closed")
    void failedHostAllowsOwnerCleanupWithoutReopening() {
        Plugin ownerA = owner("ConsumerA");
        Plugin ownerB = owner("ConsumerB");
        assertTrue(host.register(ownerA, List.of(module("consumer-a:base", Set.of(),
            context -> () -> events.add("close-base")))).isSuccess());
        assertTrue(host.register(ownerB, List.of(module("consumer-b:dependent",
            Set.of("consumer-a:base"), context -> () -> events.add("close-dependent"))))
            .isSuccess());
        host.failReload(new IllegalStateException("injected fail-closed reload failure"));

        assertEquals(LifecycleResult.Outcome.REJECTED, host.beginReload().outcome(),
            "fail-closed 宿主仍須拒絕 reload");
        assertTrue(host.unregister(ownerA).isSuccess(),
            "FAILED 時 unregister 必須能撤銷自身模組，即使仍有相依者");
        assertTrue(host.ownerDisabled(ownerB).isSuccess(),
            "FAILED 時 owner disable 必須能撤銷該 owner 自身模組");

        assertEquals(List.of("close-base", "close-dependent"), events);
        assertEquals(LifecycleHost.Status.FAILED, host.status(),
            "owner cleanup 不得把 fail-closed 宿主重新開放");
    }

    @Test
    @DisplayName("reload 先反向關閉，再以同一動態 provider 在新 facade 上拓樸重建")
    void reloadRebuildsModulesAgainstUpdatedProvider() {
        Plugin owner = owner("ConsumerA");
        AceLibApi oldApi = currentApi.get();
        List<AceLibApi> observedApis = new ArrayList<>();
        LifecycleResult registered = host.register(owner, List.of(
            module("consumer:base", Set.of(), context -> {
                observedApis.add(context.apiProvider().api());
                events.add("enable-base");
                return () -> events.add("close-base");
            }),
            module("consumer:feature", Set.of("consumer:base"), context -> {
                observedApis.add(context.apiProvider().api());
                events.add("enable-feature");
                return () -> events.add("close-feature");
            })));
        assertTrue(registered.isSuccess());

        LifecycleResult stopped = host.beginReload();
        assertTrue(stopped.isSuccess());
        assertEquals(LifecycleHost.Status.RELOADING, host.status());
        AceLibApi newApi = AceLibApi.uninitialized();
        currentApi.set(newApi);
        LifecycleResult rebuilt = host.finishReload();

        assertTrue(rebuilt.isSuccess());
        assertEquals(LifecycleHost.Status.READY, host.status());
        assertEquals(List.of(oldApi, oldApi, newApi, newApi), observedApis);
        assertEquals(List.of("enable-base", "enable-feature", "close-feature", "close-base",
            "enable-base", "enable-feature"), events);
    }

    @Test
    @DisplayName("reload 重建失敗時清掉已重建模組並明確保持 FAILED")
    void reloadRebuildFailureCompensatesAndRemainsFailed() {
        Plugin owner = owner("ConsumerA");
        AtomicBoolean failRebuild = new AtomicBoolean(false);
        assertTrue(host.register(owner, List.of(
            module("consumer:base", Set.of(), context -> {
                events.add("enable-base");
                return () -> events.add("close-base");
            }),
            module("consumer:broken", Set.of("consumer:base"), context -> {
                if (failRebuild.get()) {
                    throw new IllegalStateException("injected rebuild failure");
                }
                return () -> events.add("close-broken");
            }))).isSuccess());

        assertTrue(host.beginReload().isSuccess());
        failRebuild.set(true);
        LifecycleResult result = host.finishReload();

        assertEquals(LifecycleResult.Outcome.FAILED, result.outcome());
        assertEquals(LifecycleHost.Status.FAILED, result.status());
        assertTrue(result.problems().stream().anyMatch(p ->
            p.code() == LifecycleResult.Code.RELOAD_FAILED));
        assertEquals(List.of("enable-base", "close-broken", "close-base", "enable-base",
            "close-base"), events);
    }

    @Test
    @DisplayName("reload 狀態只可開始一次，重複停用仍能收斂為 SHUTDOWN")
    void reloadAndShutdownAreStateSafeWhenRepeated() {
        Plugin owner = owner("ConsumerA");
        assertTrue(host.register(owner, List.of(module("consumer:base", Set.of(), context ->
            () -> events.add("close-base")))).isSuccess());

        assertTrue(host.beginReload().isSuccess());
        LifecycleResult duplicateReload = host.beginReload();
        assertEquals(LifecycleResult.Outcome.REJECTED, duplicateReload.outcome());
        assertEquals(LifecycleResult.Code.INVALID_STATE,
            duplicateReload.problems().getFirst().code());
        assertTrue(host.finishReload().isSuccess());
        assertTrue(host.shutdown().isSuccess());
        assertTrue(host.shutdown().isSuccess());
        assertEquals(LifecycleHost.Status.SHUTDOWN, host.status());
        assertFalse(host.register(owner, List.of()).isSuccess());
        assertEquals(2, events.stream().filter("close-base"::equals).count());
    }

    private Plugin owner(String name) {
        Plugin owner = mock(Plugin.class);
        when(owner.getName()).thenReturn(name);
        return owner;
    }

    private static LifecycleModule module(String id, Set<String> dependencies,
            LifecycleModule.Starter starter) {
        return new LifecycleModule(id, dependencies, starter);
    }
}
