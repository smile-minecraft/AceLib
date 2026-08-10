package com.smile.acelib.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibVersion;
import com.smile.acelib.context.DebugMode;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.scheduler.TaskErrorRecord;
import com.smile.acelib.scheduler.TaskType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * DiagnosticsService 整合測試。
 *
 * <p>對應 Plan §十九 Phase 14 全部驗收條件。
 * 涵蓋：版本／平台／capability／ready／debug 查詢；缺失模組降級；
 * 排程錯誤摘要；debug on/off 差異；錯誤代碼分類；同類錯誤節流。</p>
 */
@DisplayName("DiagnosticsService — 整合行為")
class DiagnosticsServiceTest {

    @AfterEach
    void cleanup() {
        DebugMode.clearExplicit();
        DebugMode.clearCache();
        MockBukkit.unmock();
    }

    private static Clock fakeClock() {
        AtomicLong millis = new AtomicLong(1_700_000_000_000L);
        return () -> millis.get();
    }

    private static Clock advancingClock(long start) {
        AtomicLong millis = new AtomicLong(start);
        return millis::get;
    }

    @Nested
    @DisplayName("建構與不可變性")
    class ConstructionAndImmutability {

        @Test
        @DisplayName("null clock → IllegalArgumentException")
        void nullClock_throws() {
            assertThrows(NullPointerException.class,
                () -> new DiagnosticsService(null));
        }

        @Test
        @DisplayName("null version 拋例外")
        void nullVersion_throws() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            assertThrows(NullPointerException.class,
                () -> s.bindPlugin(null, Platform.PAPER,
                    PlatformCapability.forPlatform(Platform.PAPER)));
        }

        @Test
        @DisplayName("null platform / capability 拋例外")
        void nullPlatformOrCapability_throws() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            assertThrows(NullPointerException.class,
                () -> s.bindPlugin(AceLibVersion.VERSION, null,
                    PlatformCapability.forPlatform(Platform.PAPER)));
            assertThrows(NullPointerException.class,
                () -> s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER, null));
        }

        @Test
        @DisplayName("bindPlugin 後不可變；重複 bind 拒絕")
        void bind_isIdempotent() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            assertThrows(IllegalStateException.class,
                () -> s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                    PlatformCapability.forPlatform(Platform.PAPER)));
        }
    }

    @Nested
    @DisplayName("核心查詢 API")
    class CoreQueries {

        @Test
        @DisplayName("getVersion / getPlatform / getPlatformCapability / isReady / isDebugEnabled")
        void coreQueries_exposeValues() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            s.setReady(true);
            DebugMode.setEnabled(true);
            try {
                assertEquals(AceLibVersion.VERSION, s.getVersion());
                assertSame(Platform.PAPER, s.getPlatform());
                assertEquals(PlatformCapability.forPlatform(Platform.PAPER),
                    s.getPlatformCapability());
                assertTrue(s.isReady());
                assertTrue(s.isDebugEnabled(),
                    "isDebugEnabled 必須委派給 DebugMode");
            } finally {
                DebugMode.clearExplicit();
            }
        }

        @Test
        @DisplayName("未 bind 時 getVersion 仍回傳 AceLibVersion.VERSION（向後相容）")
        void unbound_getVersion_fallsBackToAceLibVersion() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            assertEquals(AceLibVersion.VERSION, s.getVersion());
        }

        @Test
        @DisplayName("未 bind 時 getPlatform 為 UNKNOWN，isReady 為 false")
        void unbound_defaults() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            assertSame(Platform.UNKNOWN, s.getPlatform());
            assertFalse(s.isReady());
            assertNotNull(s.getPlatformCapability());
        }

        @Test
        @DisplayName("setReady 切換會被 isReady 反映")
        void setReady_reflectedInQuery() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            assertFalse(s.isReady());
            s.setReady(true);
            assertTrue(s.isReady());
            s.setReady(false);
            assertFalse(s.isReady());
        }
    }

    @Nested
    @DisplayName("模組狀態註冊")
    class ModuleStateRegistration {

        @Test
        @DisplayName("registerModule 後 snapshot 內可見")
        void registerModule_visibleInSnapshot() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            s.registerModuleState("scheduler",
                ModuleState.ready("scheduler", "tracked=5"));
            DiagnosticSnapshot snap = s.buildSnapshot();
            ModuleState ms = snap.modules().get("scheduler");
            assertNotNull(ms);
            assertSame(ModuleStatus.READY, ms.status());
            assertTrue(ms.detail().contains("tracked=5"));
        }

        @Test
        @DisplayName("未註冊的模組在 snapshot 內以 NOT_INITIALIZED 安全降級")
        void missingModules_degradeAsNotInitialized() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            DiagnosticSnapshot snap = s.buildSnapshot();
            // 預期模組：scheduler / config / lang / integration / data
            for (String key : new String[]{"scheduler", "config", "lang", "integration", "data"}) {
                ModuleState ms = snap.modules().get(key);
                assertNotNull(ms, "預期模組 " + key + " 必須存在於 snapshot");
                assertSame(ModuleStatus.NOT_INITIALIZED, ms.status(),
                    "未註冊模組 " + key + " 必須為 NOT_INITIALIZED");
            }
        }

        @Test
        @DisplayName("unregisterModule 後模組消失（下次 snapshot 回 NOT_INITIALIZED）")
        void unregister_removesModule() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            s.registerModuleState("scheduler", ModuleState.ready("scheduler", "ok"));
            assertSame(ModuleStatus.READY,
                s.buildSnapshot().modules().get("scheduler").status());
            s.unregisterModuleState("scheduler");
            assertSame(ModuleStatus.NOT_INITIALIZED,
                s.buildSnapshot().modules().get("scheduler").status());
        }

        @Test
        @DisplayName("null module name / state 拋 NullPointerException")
        void nullArgs_throws() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            assertThrows(NullPointerException.class,
                () -> s.registerModuleState(null, ModuleState.ready("x", "x")));
            assertThrows(NullPointerException.class,
                () -> s.registerModuleState("x", null));
        }
    }

    @Nested
    @DisplayName("排程錯誤摘要")
    class SchedulerErrorSummary {

        @Test
        @DisplayName("bindScheduler 後快照包含 scheduler 排程錯誤摘要")
        void bindScheduler_errorSummaryIncluded() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            SafeSchedulerImpl sched = new SafeSchedulerImpl(
                org.mockito.Mockito.mock(org.bukkit.plugin.java.JavaPlugin.class),
                Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER)
            );
            // 直接 record 幾筆錯誤
            sched.getRecorder().record(
                TaskErrorRecord.threw(TaskType.GLOBAL, "ACELIB-SCHED-001",
                    "boom", new RuntimeException("x")));
            sched.getRecorder().record(
                TaskErrorRecord.cancelled(TaskType.PLAYER, "ACELIB-SCHED-002",
                    "player offline"));
            s.bindScheduler(sched);
            DiagnosticSnapshot snap = s.buildSnapshot();
            // 排程錯誤摘要：兩個 code 各一筆
            assertTrue(snap.recentErrors().size() >= 2,
                "snapshot 必須包含至少兩筆排程錯誤摘要");
            assertTrue(snap.recentErrors().stream()
                .anyMatch(e -> "ACELIB-SCHED-001".equals(e.code())));
            assertTrue(snap.recentErrors().stream()
                .anyMatch(e -> "ACELIB-SCHED-002".equals(e.code())));
            assertSame(ModuleStatus.READY,
                snap.modules().get("scheduler").status(),
                "bindScheduler 後 scheduler 模組狀態應為 READY");
        }

        @Test
        @DisplayName("bindScheduler(null) → scheduler 模組標示 NOT_INITIALIZED")
        void unbindScheduler_marksNotInitialized() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            DiagnosticSnapshot snap = s.buildSnapshot();
            assertSame(ModuleStatus.NOT_INITIALIZED,
                snap.modules().get("scheduler").status());
        }

        @Test
        @DisplayName("scheduler 已 disable 時模組狀態標 FAILED")
        void disabledScheduler_marksFailed() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            SafeSchedulerImpl sched = new SafeSchedulerImpl(
                org.mockito.Mockito.mock(org.bukkit.plugin.java.JavaPlugin.class),
                Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER)
            );
            sched.onPluginDisable();
            s.bindScheduler(sched);
            DiagnosticSnapshot snap = s.buildSnapshot();
            assertSame(ModuleStatus.FAILED,
                snap.modules().get("scheduler").status(),
                "disabled scheduler 應標記 FAILED");
        }
    }

    @Nested
    @DisplayName("設定與語言模組")
    class ConfigAndLang {

        @Test
        @DisplayName("bindConfig 後 config 模組標 READY，否則 NOT_INITIALIZED")
        void bindConfig_marksReady() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            assertSame(ModuleStatus.NOT_INITIALIZED,
                s.buildSnapshot().modules().get("config").status());
            s.bindConfig(com.smile.acelib.config.ConfigManager.class,
                true, null);
            assertSame(ModuleStatus.READY,
                s.buildSnapshot().modules().get("config").status());
        }

        @Test
        @DisplayName("設定 reload 失敗時 config 模組標 FAILED 並攜帶錯誤代碼")
        void configReloadFailure_marksFailed() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            s.bindConfig(com.smile.acelib.config.ConfigManager.class,
                false, "ACELIB-CFG-002");
            ModuleState ms = s.buildSnapshot().modules().get("config");
            assertSame(ModuleStatus.FAILED, ms.status());
            assertEquals("ACELIB-CFG-002", ms.errorCode().orElse(null));
        }

        @Test
        @DisplayName("bindLang 對 lang 模組同理")
        void bindLang_marksReady() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            s.bindLang(com.smile.acelib.config.LangManager.class, true, null);
            assertSame(ModuleStatus.READY,
                s.buildSnapshot().modules().get("lang").status());
        }
    }

    @Nested
    @DisplayName("外部整合 / 資料模組")
    class IntegrationAndData {

        @Test
        @DisplayName("Phase 13 未實作 — integration 模組永遠 NOT_INITIALIZED")
        void integration_unavailableUntilPhase13() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            DiagnosticSnapshot snap = s.buildSnapshot();
            assertSame(ModuleStatus.NOT_INITIALIZED,
                snap.modules().get("integration").status());
            assertTrue(snap.modules().get("integration").detail()
                .contains("Phase 13"));
        }

        @Test
        @DisplayName("Phase 8 未實作 — data 模組永遠 NOT_INITIALIZED")
        void data_unavailableUntilPhase8() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            DiagnosticSnapshot snap = s.buildSnapshot();
            assertSame(ModuleStatus.NOT_INITIALIZED,
                snap.modules().get("data").status());
            assertTrue(snap.modules().get("data").detail()
                .contains("Phase 8"));
        }
    }

    @Nested
    @DisplayName("錯誤節流整合")
    class ThrottlingIntegration {

        @Test
        @DisplayName("recordError 在窗口內同 code 第二次 SUPPRESSED（duplicate suppression 政策）")
        void recordError_suppressesDuplicates() {
            Clock clock = advancingClock(1_000_000L);
            DiagnosticsService s = new DiagnosticsService(clock);
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            ThrottleDecision d1 = s.recordError("ACELIB-SCHED-001", "x");
            assertEquals(ThrottleDecision.Kind.ALLOWED, d1.kind());
            ThrottleDecision d2 = s.recordError("ACELIB-SCHED-001", "x");
            assertEquals(ThrottleDecision.Kind.SUPPRESSED, d2.kind(),
                "視窗內重複 code 必須 SUPPRESSED");
        }

        @Test
        @DisplayName("DiagnosticsService 內部 throttler 採 duplicate suppression（max=1），"
            + "不沿用 ErrorThrottler 通用 DEFAULT_MAX_PER_WINDOW=5")
        void recordError_usesDuplicateSuppressionPolicy() {
            // 驗證 DiagnosticsService 對同 code 多次呼叫只放行第 1 次（max=1），
            // 與 ErrorThrottler 通用預設 max=5 行為不同。
            // 此政策由建構子內 `new ErrorThrottler(clock, 1, DEFAULT_WINDOW_MS)` 明確指定。
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            s.recordError("ACELIB-CFG-001", "first");
            s.recordError("ACELIB-CFG-001", "second");
            s.recordError("ACELIB-CFG-001", "third");
            DiagnosticSnapshot snap = s.buildSnapshot();
            ThrottleStats stats = snap.throttleSnapshot().get("ACELIB-CFG-001");
            assertNotNull(stats, "snapshot 必須包含此 code 的節流統計");
            assertEquals(1, stats.allowed(),
                "duplicate suppression（max=1）：三次 recordError 只允許 1 次 ALLOWED");
            assertEquals(2, stats.suppressed(),
                "後續兩次必須 SUPPRESSED");
        }

        @Test
        @DisplayName("節流統計包含在 debug report 內")
        void throttleStats_includedInDebugReport() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            s.recordError("ACELIB-SCHED-001", "x");
            s.recordError("ACELIB-SCHED-001", "x");
            DiagnosticSnapshot snap = s.buildSnapshot();
            Map<String, ThrottleStats> stats = snap.throttleSnapshot();
            assertNotNull(stats);
            assertTrue(stats.containsKey("ACELIB-SCHED-001"));
            assertEquals(1, stats.get("ACELIB-SCHED-001").allowed());
            assertEquals(1, stats.get("ACELIB-SCHED-001").suppressed());
        }

        @Test
        @DisplayName("null code → NullPointerException")
        void recordError_nullCode_throws() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            assertThrows(NullPointerException.class,
                () -> s.recordError(null, "x"));
        }
    }

    @Nested
    @DisplayName("Snapshot 不可變與 Report 整合")
    class SnapshotReportIntegration {

        @Test
        @DisplayName("buildReport 回傳 DiagnosticReport 含 snapshot")
        void buildReport_returnsValidReport() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            s.setReady(true);
            DiagnosticReport report = s.buildReport();
            assertNotNull(report);
            assertNotNull(report.snapshot());
            assertEquals(AceLibVersion.VERSION, report.snapshot().version());
            assertSame(Platform.PAPER, report.snapshot().platform());
        }

        @Test
        @DisplayName("buildSnapshot 多次呼叫回傳內容相同 snapshot（不可變）")
        void buildSnapshot_immutable() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            DiagnosticSnapshot a = s.buildSnapshot();
            DiagnosticSnapshot b = s.buildSnapshot();
            // 兩次應指向不同物件但內容相同 — 因為是 immutable snapshot
            assertEquals(a.version(), b.version());
            assertEquals(a.platform(), b.platform());
            assertEquals(a.isReady(), b.isReady());
            assertEquals(a.modules(), b.modules());
        }

        @Test
        @DisplayName("format(false) 為非 debug 報告；不應洩漏 throttle")
        void format_off_omitsThrottle() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            s.recordError("ACELIB-SCHED-001", "x");
            String text = s.buildReport().format(false);
            assertFalse(text.contains("throttle"),
                "非 debug 模式不應包含 throttle 統計");
        }

        @Test
        @DisplayName("format(true) 包含 throttle 統計")
        void format_on_includesThrottle() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            s.recordError("ACELIB-SCHED-001", "x");
            String text = s.buildReport().format(true);
            assertTrue(text.contains("throttle"),
                "debug 模式必須包含 throttle 統計");
        }
    }

    @Nested
    @DisplayName("排程錯誤摘要：節流與去重")
    class SchedulerErrorSummaryDedup {

        @Test
        @DisplayName("bindScheduler 後 snapshot.recentErrors 按 code 統計（多筆同 code 合併為單行 summary）")
        void errorSummary_dedupsByCode() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            SafeSchedulerImpl sched = new SafeSchedulerImpl(
                org.mockito.Mockito.mock(org.bukkit.plugin.java.JavaPlugin.class),
                Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER)
            );
            sched.getRecorder().record(
                TaskErrorRecord.threw(TaskType.GLOBAL, "ACELIB-SCHED-001",
                    "a", new RuntimeException()));
            sched.getRecorder().record(
                TaskErrorRecord.threw(TaskType.LATER, "ACELIB-SCHED-001",
                    "b", new RuntimeException()));
            sched.getRecorder().record(
                TaskErrorRecord.cancelled(TaskType.PLAYER, "ACELIB-SCHED-002",
                    "c"));
            s.bindScheduler(sched);
            DiagnosticSnapshot snap = s.buildSnapshot();
            // SCHED-001 兩筆 → 一行；SCHED-002 一筆 → 一行
            long sched001Count = snap.recentErrors().stream()
                .filter(e -> "ACELIB-SCHED-001".equals(e.code()))
                .count();
            long sched002Count = snap.recentErrors().stream()
                .filter(e -> "ACELIB-SCHED-002".equals(e.code()))
                .count();
            assertEquals(1, sched001Count,
                "同 code 必須合併為單行 summary");
            assertEquals(1, sched002Count);
            // count 為 2
            ErrorSummaryLine line001 = snap.recentErrors().stream()
                .filter(e -> "ACELIB-SCHED-001".equals(e.code()))
                .findFirst().orElseThrow();
            assertEquals(2, line001.count());
        }
    }

    @Nested
    @DisplayName("Snapshot 內 null 防護")
    class SnapshotNullSafety {

        @Test
        @DisplayName("未 bind plugin 時 buildSnapshot 仍回傳合法不可變 snapshot")
        void unbound_buildSnapshot_returnsValidSnapshot() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            DiagnosticSnapshot snap = s.buildSnapshot();
            assertNotNull(snap);
            assertEquals(AceLibVersion.VERSION, snap.version());
            assertFalse(snap.isReady());
            assertSame(Platform.UNKNOWN, snap.platform());
            assertNotNull(snap.modules());
            assertNotNull(snap.recentErrors());
        }
    }

    // ---------------------------------------------------------------------
    // Phase 14 production wiring + 並行安全
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 14 wiring：scheduler 錯誤自動送入 diagnostics 節流路徑")
    class SchedulerReportWiring {

        @Test
        @DisplayName("bindScheduler 注入 recordSink：scheduler 內部 recorder.record 自動觸發 DiagnosticsService.recordError")
        void bindScheduler_injectsRecordSink() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            SafeSchedulerImpl sched = new SafeSchedulerImpl(
                org.mockito.Mockito.mock(org.bukkit.plugin.java.JavaPlugin.class),
                Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER)
            );
            s.bindScheduler(sched);

            // scheduler 端寫入一筆錯誤 → diagnostics 端 recordError 應自動收到
            sched.getRecorder().record(
                TaskErrorRecord.cancelled(TaskType.GLOBAL, "ACELIB-SCHED-006",
                    "scheduler is disabled"));

            // 等待 sink callback 跑完
            try { Thread.sleep(50L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            DiagnosticSnapshot snap = s.buildSnapshot();
            assertNotNull(snap.throttleSnapshot().get("ACELIB-SCHED-006"),
                "scheduler 注入的錯誤必須進入 diagnostics throttleSnapshot");
            assertEquals(1, snap.throttleSnapshot().get("ACELIB-SCHED-006").allowed(),
                "首次應 ALLOWED");
        }

        @Test
        @DisplayName("同一 code 重複送入：節流只放行一次（但 recorder 內部仍保留全部記錄）")
        void schedulerReport_throttlesDuplicateCode_preservesRecorderCount() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            SafeSchedulerImpl sched = new SafeSchedulerImpl(
                org.mockito.Mockito.mock(org.bukkit.plugin.java.JavaPlugin.class),
                Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER)
            );
            s.bindScheduler(sched);

            // 連續 5 次同 code：diagnostics 端只放行 1 次；recorder 端保留 5 筆
            for (int i = 0; i < 5; i++) {
                sched.getRecorder().record(
                    TaskErrorRecord.cancelled(TaskType.GLOBAL, "ACELIB-SCHED-001",
                        "boom-" + i));
            }
            try { Thread.sleep(80L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            DiagnosticSnapshot snap = s.buildSnapshot();
            // throttle 端：1 allowed, 4 suppressed
            ThrottleStats stats = snap.throttleSnapshot().get("ACELIB-SCHED-001");
            assertNotNull(stats, "throttle 統計必須存在");
            assertEquals(1, stats.allowed(),
                "duplicate suppression 政策：5 次 record 只 ALLOWED 1 次");
            assertEquals(4, stats.suppressed(),
                "剩餘 4 次必須被 SUPPRESSED");

            // recorder 端仍保留 5 筆 → snapshot.recentErrors 合併 count = 5
            long count001 = snap.recentErrors().stream()
                .filter(e -> "ACELIB-SCHED-001".equals(e.code()))
                .findFirst().map(ErrorSummaryLine::count).orElse(0);
            assertEquals(5, count001,
                "原始 recorder summary count 語意必須保留（5 筆合併為 5）");
        }

        @Test
        @DisplayName("不同 code 各自獨立節流（重複只抑制同 code）")
        void schedulerReport_differentCodes_independent() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            SafeSchedulerImpl sched = new SafeSchedulerImpl(
                org.mockito.Mockito.mock(org.bukkit.plugin.java.JavaPlugin.class),
                Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER)
            );
            s.bindScheduler(sched);

            sched.getRecorder().record(
                TaskErrorRecord.cancelled(TaskType.GLOBAL, "ACELIB-SCHED-001", "a"));
            sched.getRecorder().record(
                TaskErrorRecord.cancelled(TaskType.PLAYER, "ACELIB-SCHED-002", "b"));
            sched.getRecorder().record(
                TaskErrorRecord.cancelled(TaskType.ENTITY, "ACELIB-SCHED-003", "c"));
            try { Thread.sleep(80L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            DiagnosticSnapshot snap = s.buildSnapshot();
            assertNotNull(snap.throttleSnapshot().get("ACELIB-SCHED-001"));
            assertNotNull(snap.throttleSnapshot().get("ACELIB-SCHED-002"));
            assertNotNull(snap.throttleSnapshot().get("ACELIB-SCHED-003"));
            for (String code : new String[]{"ACELIB-SCHED-001", "ACELIB-SCHED-002", "ACELIB-SCHED-003"}) {
                assertEquals(1, snap.throttleSnapshot().get(code).allowed(),
                    "首次不同 code 各自 ALLOWED");
                assertEquals(0, snap.throttleSnapshot().get(code).suppressed());
            }
        }

        @Test
        @DisplayName("reportSchedulerError 公開 API：可被外部 caller 呼叫並接受節流")
        void reportSchedulerError_publicApi_throttles() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            ThrottleDecision d1 = s.reportSchedulerError("ACELIB-SCHED-001", "x");
            assertSame(ThrottleDecision.Kind.ALLOWED, d1.kind());
            ThrottleDecision d2 = s.reportSchedulerError("ACELIB-SCHED-001", "y");
            assertSame(ThrottleDecision.Kind.SUPPRESSED, d2.kind(),
                "reportSchedulerError 必須走 duplicate suppression 節流");
        }

        @Test
        @DisplayName("bindScheduler(null) 後 scheduler 注入的 sink 不再被呼叫（unbind 安全）")
        void bindSchedulerNull_clearsSink() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));
            SafeSchedulerImpl sched = new SafeSchedulerImpl(
                org.mockito.Mockito.mock(org.bukkit.plugin.java.JavaPlugin.class),
                Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER)
            );
            s.bindScheduler(sched);
            s.bindScheduler(null); // unbind

            sched.getRecorder().record(
                TaskErrorRecord.cancelled(TaskType.GLOBAL, "ACELIB-SCHED-001", "x"));
            try { Thread.sleep(50L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

            DiagnosticSnapshot snap = s.buildSnapshot();
            assertNull(snap.throttleSnapshot().get("ACELIB-SCHED-001"),
                "unbind 後不應再有 throttle 統計（callback 已解除）");
        }
    }

    @Nested
    @DisplayName("Phase 14 concurrency：buildSnapshot 與 recordError/reset 併發安全")
    class SnapshotConcurrency {

        @Test
        @DisplayName("多執行緒同時 recordError + 偶發 resetThrottler：buildSnapshot 不放入 null ThrottleStats、report 不 NPE")
        void buildSnapshot_concurrentRecordErrorAndReset_isSafe() throws Exception {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));

            int writerCount = 4;
            int snapshotterCount = 2;
            int iterations = 200;
            ExecutorService writers = Executors.newFixedThreadPool(writerCount);
            ExecutorService snapshotters = Executors.newFixedThreadPool(snapshotterCount);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(writerCount + snapshotterCount);
            AtomicInteger nullStatsSeen = new AtomicInteger(0);
            AtomicInteger npeSeen = new AtomicInteger(0);

            try {
                for (int w = 0; w < writerCount; w++) {
                    final int writerId = w;
                    writers.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < iterations; i++) {
                                String code = "ACELIB-SCHED-"
                                    + ((writerId + i) % 6);
                                s.recordError(code, "w" + writerId + "-i" + i);
                                if ((i % 50) == 0) {
                                    s.resetThrottler();
                                }
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                for (int snap = 0; snap < snapshotterCount; snap++) {
                    snapshotters.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < iterations; i++) {
                                try {
                                    DiagnosticSnapshot snapshot = s.buildSnapshot();
                                    DiagnosticReport report = s.buildReport();
                                    // 檢查 snapshot 內 throttleSnapshot 不含 null
                                    for (Map.Entry<String, ThrottleStats> e
                                        : snapshot.throttleSnapshot().entrySet()) {
                                        if (e.getValue() == null) {
                                            nullStatsSeen.incrementAndGet();
                                        }
                                    }
                                    // 檢查 report 不丟 NPE（這次跑得到這行就代表 OK）
                                    if (report.format(true) == null) {
                                        npeSeen.incrementAndGet();
                                    }
                                } catch (NullPointerException npe) {
                                    npeSeen.incrementAndGet();
                                }
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }

                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS),
                    "並發測試應在 30s 內完成");

                assertEquals(0, npeSeen.get(),
                    "buildSnapshot/buildReport 在 record/reset 併發下不應丟 NPE，"
                        + "實際 NPE 次數: " + npeSeen.get());
                assertEquals(0, nullStatsSeen.get(),
                    "buildSnapshot 不應放入 null ThrottleStats，"
                        + "實際 null stats 次數: " + nullStatsSeen.get());

                // 最終一致性：snapshotters 必須完成所有 iteration
                // （沒有 null 結果殘留即可）
            } finally {
                writers.shutdownNow();
                snapshotters.shutdownNow();
            }
        }

        @Test
        @DisplayName("buildSnapshot 在 reset 中迭代 trackedKeys 不應丟 NPE 或留下 null")
        void buildSnapshot_resetRace_isSafe() {
            DiagnosticsService s = new DiagnosticsService(fakeClock());
            s.bindPlugin(AceLibVersion.VERSION, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER));

            // 先預熱一些 tracked keys
            for (int i = 0; i < 50; i++) {
                s.recordError("ACELIB-SCHED-" + (i % 6), "x");
            }

            Thread resetThread = new Thread(() -> {
                for (int i = 0; i < 200; i++) {
                    s.resetThrottler();
                }
            });
            Thread snapThread = new Thread(() -> {
                Set<Integer> nulls = new HashSet<>();
                for (int i = 0; i < 200; i++) {
                    DiagnosticSnapshot snap = s.buildSnapshot();
                    for (Map.Entry<String, ThrottleStats> e
                        : snap.throttleSnapshot().entrySet()) {
                        if (e.getValue() == null) {
                            nulls.add(System.identityHashCode(e.getKey()));
                        }
                    }
                }
                assertTrue(nulls.isEmpty(),
                    "buildSnapshot 在 reset 競態下不應放入 null ThrottleStats，"
                        + "實際 null hash 數: " + nulls.size());
            });

            resetThread.start();
            snapThread.start();
            try {
                resetThread.join(5_000L);
                snapThread.join(5_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            // 最終仍能 buildSnapshot
            assertNotNull(s.buildSnapshot());
        }
    }
}
