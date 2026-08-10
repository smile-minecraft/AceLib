package com.smile.acelib.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibVersion;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * DiagnosticSnapshot 與 DiagnosticReport 的單元測試。
 *
 * <p>對應 Plan §十九 Phase 14「狀態查詢」與「診斷報告」需求。
 * 純邏輯測試，不依賴 Bukkit / MockBukkit。</p>
 */
@DisplayName("DiagnosticSnapshot / Report — 不可變診斷快照")
class DiagnosticSnapshotTest {

    private static Clock fixed(long millis) {
        return () -> millis;
    }

    private static Map<String, ModuleState> sampleModules() {
        Map<String, ModuleState> m = new LinkedHashMap<>();
        m.put("scheduler", ModuleState.ready("scheduler", "tracked=0"));
        m.put("config", ModuleState.notInitialized("config", "尚未綁定 AceLibConfig"));
        m.put("lang", ModuleState.notInitialized("lang", "尚未綁定 AceLibConfig"));
        m.put("integration", ModuleState.notInitialized("integration", "Phase 13 未實作"));
        m.put("data", ModuleState.notInitialized("data", "Phase 8 未實作"));
        return m;
    }

    @Nested
    @DisplayName("Snapshot 不可變性與欄位")
    class SnapshotImmutability {

        @Test
        @DisplayName("Snapshot 必須包含 version/platform/capability/ready/debug")
        void snapshot_containsAllRequiredFields() {
            DiagnosticSnapshot s = DiagnosticSnapshot.builder()
                .timestampMillis(1_700_000_000_000L)
                .version(AceLibVersion.VERSION)
                .platform(Platform.PAPER)
                .capability(PlatformCapability.forPlatform(Platform.PAPER))
                .ready(true)
                .debugEnabled(false)
                .modules(sampleModules())
                .recentErrors(List.of())
                .build();

            assertEquals(AceLibVersion.VERSION, s.version());
            assertSame(Platform.PAPER, s.platform());
            assertEquals(PlatformCapability.forPlatform(Platform.PAPER), s.capability());
            assertTrue(s.isReady());
            assertFalse(s.isDebugEnabled());
            assertEquals(1_700_000_000_000L, s.timestampMillis());
        }

        @Test
        @DisplayName("Snapshot 模組清單為不可變")
        void snapshot_modulesIsImmutable() {
            DiagnosticSnapshot s = DiagnosticSnapshot.builder()
                .timestampMillis(1L)
                .version(AceLibVersion.VERSION)
                .platform(Platform.PAPER)
                .capability(PlatformCapability.forPlatform(Platform.PAPER))
                .ready(true)
                .debugEnabled(false)
                .modules(sampleModules())
                .recentErrors(List.of())
                .build();

            Map<String, ModuleState> modules = s.modules();
            assertThrows(UnsupportedOperationException.class,
                () -> modules.put("rogue", ModuleState.ready("x", "x")));
        }

        @Test
        @DisplayName("Snapshot 必要欄位 null → NullPointerException")
        void snapshot_requiredFieldsNotNull() {
            DiagnosticSnapshot.Builder b = DiagnosticSnapshot.builder()
                .timestampMillis(1L)
                .platform(Platform.PAPER)
                .capability(PlatformCapability.forPlatform(Platform.PAPER))
                .ready(true)
                .debugEnabled(false);
            // 缺 version
            assertThrows(NullPointerException.class,
                () -> b.modules(sampleModules()).recentErrors(List.of()).build());
        }

        @Test
        @DisplayName("未啟用時 ready=false 仍可建立 Snapshot")
        void snapshot_uninitializedReady_false() {
            DiagnosticSnapshot s = DiagnosticSnapshot.builder()
                .timestampMillis(1L)
                .version(AceLibVersion.VERSION)
                .platform(Platform.UNKNOWN)
                .capability(PlatformCapability.forPlatform(Platform.UNKNOWN))
                .ready(false)
                .debugEnabled(false)
                .modules(sampleModules())
                .recentErrors(List.of())
                .build();

            assertFalse(s.isReady());
            assertSame(Platform.UNKNOWN, s.platform());
        }

        @Test
        @DisplayName("Snapshot timestamp 可轉為 Instant")
        void snapshot_timestampIsConvertible() {
            long millis = 1_700_000_000_000L;
            DiagnosticSnapshot s = DiagnosticSnapshot.builder()
                .timestampMillis(millis)
                .version(AceLibVersion.VERSION)
                .platform(Platform.PAPER)
                .capability(PlatformCapability.forPlatform(Platform.PAPER))
                .ready(true)
                .debugEnabled(false)
                .modules(sampleModules())
                .recentErrors(List.of())
                .build();

            Instant instant = s.timestamp();
            assertNotNull(instant);
            assertEquals(millis, instant.toEpochMilli());
        }
    }

    @Nested
    @DisplayName("ModuleStatus 與 ModuleState")
    class ModuleStatusSemantics {

        @Test
        @DisplayName("ModuleStatus 至少含 READY / NOT_INITIALIZED / UNAVAILABLE / FAILED / DEGRADED")
        void moduleStatus_hasAllRequiredValues() {
            assertSame(ModuleStatus.READY, ModuleStatus.valueOf("READY"));
            assertSame(ModuleStatus.NOT_INITIALIZED, ModuleStatus.valueOf("NOT_INITIALIZED"));
            assertSame(ModuleStatus.UNAVAILABLE, ModuleStatus.valueOf("UNAVAILABLE"));
            assertSame(ModuleStatus.FAILED, ModuleStatus.valueOf("FAILED"));
            assertSame(ModuleStatus.DEGRADED, ModuleStatus.valueOf("DEGRADED"));
        }

        @Test
        @DisplayName("ModuleState.ready / notInitialized / unavailable / failed / degraded 工廠")
        void moduleState_factories() {
            ModuleState r = ModuleState.ready("x", "ok");
            assertSame(ModuleStatus.READY, r.status());
            assertEquals("x", r.name());

            ModuleState ni = ModuleState.notInitialized("x", "waiting");
            assertSame(ModuleStatus.NOT_INITIALIZED, ni.status());
            assertTrue(ni.detail().contains("waiting"));

            ModuleState ua = ModuleState.unavailable("x", "missing dep");
            assertSame(ModuleStatus.UNAVAILABLE, ua.status());

            ModuleState f = ModuleState.failed("x", "boom", "ACELIB-CFG-001");
            assertSame(ModuleStatus.FAILED, f.status());
            assertEquals("ACELIB-CFG-001", f.errorCode().orElse(null));

            ModuleState d = ModuleState.degraded("x", "using fallback");
            assertSame(ModuleStatus.DEGRADED, d.status());
        }

        @Test
        @DisplayName("ModuleState 不可變且 errorCode 可為 null")
        void moduleState_immutableAndErrorCodeOptional() {
            ModuleState s = ModuleState.ready("a", "ok");
            assertNull(s.errorCode().orElse(null));
            // 不可變 — 沒有 setter
            assertThrows(NoSuchMethodException.class,
                () -> ModuleState.class.getMethod("setStatus", ModuleStatus.class));
        }
    }

    @Nested
    @DisplayName("Report formatter")
    class ReportFormatting {

        @Test
        @DisplayName("Report 必須包含 version / platform / capability / ready / debug 區塊")
        void report_containsBasicSections() {
            DiagnosticSnapshot s = DiagnosticSnapshot.builder()
                .timestampMillis(1_700_000_000_000L)
                .version(AceLibVersion.VERSION)
                .platform(Platform.PAPER)
                .capability(PlatformCapability.forPlatform(Platform.PAPER))
                .ready(true)
                .debugEnabled(false)
                .modules(sampleModules())
                .recentErrors(List.of())
                .build();

            DiagnosticReport report = DiagnosticReport.from(s);
            String text = report.format(false);
            assertTrue(text.contains(AceLibVersion.VERSION),
                "report 必須包含版本字串");
            assertTrue(text.contains("Paper"),
                "report 必須包含平台顯示名稱");
            assertTrue(text.contains("READY"),
                "report 必須標示 ready 狀態");
            assertTrue(text.contains("debug"),
                "report 必須標示 debug 區塊");
        }

        @Test
        @DisplayName("Report 包含 scheduler / config / lang / integration / data 區段")
        void report_containsAllModuleSections() {
            DiagnosticSnapshot s = DiagnosticSnapshot.builder()
                .timestampMillis(1L)
                .version(AceLibVersion.VERSION)
                .platform(Platform.PAPER)
                .capability(PlatformCapability.forPlatform(Platform.PAPER))
                .ready(true)
                .debugEnabled(false)
                .modules(sampleModules())
                .recentErrors(List.of())
                .build();

            String text = DiagnosticReport.from(s).format(false);
            for (String key : new String[]{"scheduler", "config", "lang", "integration", "data"}) {
                assertTrue(text.contains(key),
                    "report 必須包含 " + key + " 區段");
            }
        }

        @Test
        @DisplayName("Report 不可用模組顯示 UNAVAILABLE / NOT_INITIALIZED 安全降級")
        void report_unavailableModules_degradeSafely() {
            Map<String, ModuleState> m = new LinkedHashMap<>();
            m.put("config", ModuleState.notInitialized("config", "尚未 bind"));
            m.put("lang", ModuleState.notInitialized("lang", "尚未 bind"));
            m.put("data", ModuleState.notInitialized("data", "Phase 8 未實作"));
            m.put("integration", ModuleState.notInitialized("integration", "Phase 13 未實作"));
            m.put("scheduler", ModuleState.unavailable("scheduler", "scheduler not installed"));

            DiagnosticSnapshot s = DiagnosticSnapshot.builder()
                .timestampMillis(1L)
                .version(AceLibVersion.VERSION)
                .platform(Platform.PAPER)
                .capability(PlatformCapability.forPlatform(Platform.PAPER))
                .ready(true)
                .debugEnabled(false)
                .modules(m)
                .recentErrors(List.of())
                .build();

            String text = DiagnosticReport.from(s).format(false);
            assertTrue(text.contains("NOT_INITIALIZED"),
                "未初始化模組必須顯示 NOT_INITIALIZED");
            assertTrue(text.contains("UNAVAILABLE"),
                "不可用模組必須顯示 UNAVAILABLE");
        }

        @Test
        @DisplayName("Report 包含錯誤摘要區塊，且列出錯誤代碼與分類")
        void report_errorSummary_present() {
            DiagnosticSnapshot s = DiagnosticSnapshot.builder()
                .timestampMillis(1L)
                .version(AceLibVersion.VERSION)
                .platform(Platform.PAPER)
                .capability(PlatformCapability.forPlatform(Platform.PAPER))
                .ready(true)
                .debugEnabled(false)
                .modules(sampleModules())
                .recentErrors(List.of(
                    new ErrorSummaryLine("ACELIB-SCHED-001", "task threw", 3, ErrorCategory.SCHEDULER),
                    new ErrorSummaryLine("ACELIB-CFG-003", "config reload failed", 1, ErrorCategory.CONFIG)
                ))
                .build();

            String text = DiagnosticReport.from(s).format(false);
            assertTrue(text.contains("ACELIB-SCHED-001"));
            assertTrue(text.contains("ACELIB-CFG-003"));
            assertTrue(text.contains("SCHEDULER"),
                "錯誤摘要必須顯示 category");
            assertTrue(text.contains("CONFIG"));
        }

        @Test
        @DisplayName("debug on 增加額外資訊；off 不顯示")
        void report_debugMode_addsExtraInfo() {
            DiagnosticSnapshot s = DiagnosticSnapshot.builder()
                .timestampMillis(1L)
                .version(AceLibVersion.VERSION)
                .platform(Platform.PAPER)
                .capability(PlatformCapability.forPlatform(Platform.PAPER))
                .ready(true)
                .debugEnabled(true)
                .modules(sampleModules())
                .recentErrors(List.of())
                .throttleSnapshot(Map.of(
                    "ACELIB-SCHED-001", new ThrottleStats(5, 3, 1_000L)
                ))
                .build();

            String on = DiagnosticReport.from(s).format(true);
            String off = DiagnosticReport.from(s).format(false);
            // debug on 應包含 throttle / scheduler tracked / cap details 等資訊
            assertTrue(on.contains("throttle"),
                "debug on 必須包含 throttle 統計區塊");
            // debug off 不應包含 throttle 區塊
            assertFalse(off.contains("throttle"),
                "debug off 不可包含 throttle 區塊");
        }

        @Test
        @DisplayName("Report 不可變 — 多次 format 結果一致")
        void report_isImmutable() {
            DiagnosticSnapshot s = DiagnosticSnapshot.builder()
                .timestampMillis(1L)
                .version(AceLibVersion.VERSION)
                .platform(Platform.PAPER)
                .capability(PlatformCapability.forPlatform(Platform.PAPER))
                .ready(true)
                .debugEnabled(false)
                .modules(sampleModules())
                .recentErrors(List.of())
                .build();
            DiagnosticReport r = DiagnosticReport.from(s);
            String first = r.format(true);
            String second = r.format(true);
            assertEquals(first, second, "同一 Report 多次 format 必須一致");
        }

        @Test
        @DisplayName("Report 暴露 snapshot 來源供外部查詢")
        void report_exposesSourceSnapshot() {
            DiagnosticSnapshot s = DiagnosticSnapshot.builder()
                .timestampMillis(1L)
                .version(AceLibVersion.VERSION)
                .platform(Platform.PAPER)
                .capability(PlatformCapability.forPlatform(Platform.PAPER))
                .ready(true)
                .debugEnabled(false)
                .modules(sampleModules())
                .recentErrors(List.of())
                .build();
            DiagnosticReport r = DiagnosticReport.from(s);
            assertSame(s, r.snapshot());
        }
    }
}
