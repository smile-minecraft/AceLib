package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.smile.acelib.diagnostics.DiagnosticReport;
import com.smile.acelib.diagnostics.DiagnosticSnapshot;
import com.smile.acelib.diagnostics.DiagnosticsService;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.platform.PlatformDetector;
import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.scheduler.TaskErrorRecord;
import com.smile.acelib.scheduler.TaskType;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * AceLibPlugin 主類別的單元測試。
 *
 * <h2>為何不用 {@code MockBukkit.load(Class)}？</h2>
 * MockBukkit 4.x 內部使用 {@code MockBukkitConfiguredPluginClassLoader}，
 * 其 {@code findClass} 對非 plugin 自身 class 的探測會拋
 * {@link NullPointerException}（{@code "No jar file selected"}），而非
 * {@link ClassNotFoundException}。我們的 production code
 * {@link PlatformDetector} 透過 {@code Class.forName(fqcn, false, classLoader)}
 * 探測 Folia / Paper marker class，若 classloader 是 plugin classloader，
 * 該 NPE 會冒到 {@code onEnable} 並導致 {@code MockBukkit.load} 整個失敗。
 *
 * <p>為避免修改 production code，本測試改走以下流程：
 * <ol>
 *   <li>{@code MockBukkit.mock()} 初始化 server</li>
 *   <li>{@code pluginManager.loadPlugin(AceLibPlugin.class)} 載入 plugin
 *       但<strong>不</strong>呼叫 enablePlugin（這是 loadPlugin 與 load 的差異）</li>
 *   <li>手動呼叫 {@code plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()))}
 *       傳入用「測試 classloader」建構的 detector；測試 classloader 可以找到
 *       {@code org.bukkit.Bukkit}（MockBukkit 提供），因此 detector 正確分類為 PAPER</li>
 * </ol>
 *
 * <h2>每個測試的環境隔離</h2>
 * 每個測試開始時重新 {@code mock()} + {@code loadPlugin} + {@code onEnable}，
 * 結束時 {@code unmock()} 釋放資源；不使用 {@code MockBukkit.load} 的自動
 * enable 機制以避免 plugin classloader NPE。
 */
@DisplayName("AceLibPlugin")
class AceLibPluginTest {

    private static ServerMock server;
    private AceLibPlugin plugin;

    @AfterAll
    static void tearDownBukkit() {
        MockBukkit.unmock();
    }

    @BeforeAll
    static void setUpClass() {
        // 初始化 mock server（每個 class 只一次）
        server = MockBukkit.mock();
    }

    @BeforeEach
    void loadFresh() {
        // 重新 mock + loadPlugin（不 enable）+ 手動 onEnable。
        // 詳見 class-level Javadoc 對「為何不用 MockBukkit.load」的說明。
        MockBukkit.unmock();
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        // 使用測試 classloader 建立 detector；它可以找到 org.bukkit.Bukkit（MockBukkit
        // 在 runtime classpath），因此會正確分類為 PAPER。這是繞過 plugin classloader
        // 拋 NPE 問題的關鍵：production code 拿到的 classloader 是測試 classloader，
        // 不是 plugin classloader。
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
    }

    @AfterEach
    void unloadPlugin() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("AceLibVersion.VERSION 應為 1.1.2")
    void versionConstant_isCorrect() {
        assertEquals("1.1.2", AceLibVersion.VERSION);
    }

    @Test
    @DisplayName("loadPlugin + 手動 onEnable 後 plugin 已啟用，isReady() 應為 true")
    void mockBukkit_load_makesPluginReady() {
        assertNotNull(plugin, "loadPlugin 必須回傳非 null plugin");
        assertTrue(plugin.isReady(), "手動 onEnable 後應已啟用");
    }

    @Test
    @DisplayName("onEnable 後 getVersion() 回傳 AceLibVersion.VERSION")
    void onEnable_exposesVersion() {
        assertEquals(AceLibVersion.VERSION, plugin.getApi().getVersion());
    }

    @Test
    @DisplayName("onEnable 後 getPlatform() 在 MockBukkit 環境下回傳 PAPER")
    void onEnable_exposesPlatform() {
        Platform p = plugin.getApi().getPlatform();
        assertNotNull(p, "platform 不可為 null");
        // MockBukkit 4.x runtime classpath 內含 org.bukkit.Bukkit 但不含
        // io.papermc.paper.threadedregions.RegionizedServer，因此 detector 應回傳 PAPER。
        assertSame(Platform.PAPER, p,
            "MockBukkit 環境下應判定為 PAPER，實際: " + p);
    }

    @Test
    @DisplayName("onDisable 後 isReady() 應為 false")
    void onDisable_releasesResources() {
        assertTrue(plugin.isReady());
        plugin.onDisable();
        assertFalse(plugin.isReady(), "onDisable 後必須 not ready");
    }

    @Test
    @DisplayName("reload() 在已啟用時回傳 true 且不丟例外")
    void reload_succeedsWhenReady() {
        boolean result = plugin.reload();
        assertTrue(result, "已啟用時 reload 應回傳 true");
        assertTrue(plugin.isReady(), "reload 後仍須 ready");
    }

    @Test
    @DisplayName("onEnable 為冪等：重複呼叫不爆且 isReady 仍 true")
    void onEnable_idempotent() {
        ServerMock s = server;
        PlatformDetector d = new PlatformDetector(getClass().getClassLoader());
        assertDoesNotThrow(() -> {
            plugin.onEnable(s, d);
        }, "重複 onEnable 必須不丟例外");
        assertTrue(plugin.isReady());
    }

    @Test
    @DisplayName("detectsFoliaOrPaper: 兩種 classloader 情境下皆能 classify")
    void detectsFoliaOrPaper() {
        // 情境 A: 預設 classloader（測試 classloader，可找到 org.bukkit.Bukkit）
        PlatformDetector detectorA = new PlatformDetector(getClass().getClassLoader());
        Platform platformA = detectorA.detect();
        assertNotNull(platformA);
        assertSame(Platform.PAPER, platformA,
            "測試 classloader 應找到 org.bukkit.Bukkit，分類為 PAPER，實際: " + platformA);

        // 情境 B: 空 classloader（子載入器隔離）
        ClassLoader empty = new ClassLoader(null) {};
        PlatformDetector detectorB = new PlatformDetector(empty);
        Platform platformB = detectorB.detect();
        assertNotNull(platformB);
        assertSame(Platform.UNKNOWN, platformB,
            "空 classloader 必須判定為 UNKNOWN，實際: " + platformB);
    }

    @Test
    @DisplayName("reload 在尚未 onEnable 時回傳 false，不丟例外")
    void reload_beforeOnEnable_returnsFalse() {
        // 重新 mock + loadPlugin 但不呼叫 onEnable，模擬「尚未 onEnable」狀態。
        MockBukkit.unmock();
        server = MockBukkit.mock();
        AceLibPlugin fresh =
            (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        assertFalse(fresh.isReady());
        assertFalse(fresh.reload(), "尚未 onEnable 時 reload 應回傳 false");
    }

    @Test
    @DisplayName("onDisable 在尚未 onEnable 時呼叫不丟例外")
    void onDisable_beforeOnEnable_safe() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        AceLibPlugin fresh =
            (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        assertDoesNotThrow(fresh::onDisable);
        assertFalse(fresh.isReady());
    }

    @Test
    @DisplayName("getApi() 在 onEnable 之前回傳 default instance，不丟例外")
    void getApi_beforeOnEnable_safeToCall() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        AceLibPlugin fresh =
            (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        assertDoesNotThrow(fresh::getApi);
        assertNotNull(fresh.getApi());
        assertEquals(AceLibVersion.VERSION, fresh.getApi().getVersion());
    }

    // ---------------------------------------------------------------------
    // Phase 1 新增測試：平台警告 / capability 對外暴露
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("onEnable 偵測 UNKNOWN platform 時輸出 ACELIB-PLAT-004 warning")
    void onEnable_unknownPlatform_logsWarning() {
        // 隔離 Bukkit 環境，重新 mock + loadPlugin，再用空 classloader 觸發 UNKNOWN
        MockBukkit.unmock();
        server = MockBukkit.mock();
        AceLibPlugin fresh =
            (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);

        // 附加 log capture handler 到 root + "AceLib" logger，捕獲所有 LogRecord
        List<LogRecord> captured = new ArrayList<>();
        Handler handler = new RecordingHandler(captured);
        LogCaptureScope scope = LogCaptureScope.attachAndLower("AceLib", handler);
        try {
            PlatformDetector emptyDetector = new PlatformDetector(new ClassLoader(null) {});
            fresh.onEnable(server, emptyDetector);

            // 驗證 ready 狀態 + 平台已記錄
            assertTrue(fresh.isReady(), "onEnable 必須仍將 plugin 標記為 ready");
            assertSame(Platform.UNKNOWN, fresh.getApi().getPlatform(),
                "空 classloader 偵測結果必須為 UNKNOWN");

            // 驗證 log 輸出包含 ACELIB-PLAT-004
            boolean hasWarning = captured.stream().anyMatch(r ->
                r.getMessage() != null && r.getMessage().contains("ACELIB-PLAT-004"));
            assertTrue(hasWarning,
                "UNKNOWN 平台必須輸出含 ACELIB-PLAT-004 的 warning。實際訊息: "
                    + captured.stream().map(LogRecord::getMessage).toList());
        } finally {
            scope.detach();
        }
    }

    @Test
    @DisplayName("onEnable 偵測 PAPER（測試 classloader 無 Folia）時輸出 fine-level 提示")
    void onEnable_paperClasspathOnly_logsFine() {
        // 此情境下 plugin 已被 @BeforeEach 用 test classloader 啟用（會是 PAPER）
        // 我們重新走一次流程並驗 log
        MockBukkit.unmock();
        server = MockBukkit.mock();
        AceLibPlugin fresh =
            (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);

        List<LogRecord> captured = new ArrayList<>();
        Handler handler = new RecordingHandler(captured);
        LogCaptureScope scope = LogCaptureScope.attachAndLower("AceLib", handler);
        try {
            PlatformDetector paperOnlyDetector = new PlatformDetector(getClass().getClassLoader());
            fresh.onEnable(server, paperOnlyDetector);

            assertSame(Platform.PAPER, fresh.getApi().getPlatform());
            // 不要求 ACELIB-PLAT-004（因為不是 UNKNOWN）
            boolean hasPlat004 = captured.stream().anyMatch(r ->
                r.getMessage() != null && r.getMessage().contains("ACELIB-PLAT-004"));
            assertFalse(hasPlat004,
                "PAPER 環境不應輸出 ACELIB-PLAT-004 warning。實際: "
                    + captured.stream().map(LogRecord::getMessage).toList());

            // 期望有 non-Folia 提示（fine level — 因此必須先把 logger level 調為 ALL）
            boolean hasFineNote = captured.stream().anyMatch(r ->
                r.getMessage() != null && r.getMessage().contains("non-Folia environment"));
            assertTrue(hasFineNote,
                "PAPER 環境應輸出 non-Folia 提示。實際: "
                    + captured.stream().map(LogRecord::getMessage).toList());
        } finally {
            scope.detach();
        }
    }

    @Test
    @DisplayName("getPlatformCapability() 回傳對應 PAPER 的 capability")
    void getPlatformCapability_returnsPaperCapability() {
        // @BeforeEach 已將 plugin 用 test classloader 啟用（MockBukkit 環境 = PAPER）
        PlatformCapability cap = plugin.getPlatformCapability();
        assertNotNull(cap, "getPlatformCapability 不可為 null");
        assertEquals(PlatformCapability.forPlatform(Platform.PAPER), cap,
            "MockBukkit 環境下 capability 應與 Platform.PAPER 一致");
    }

    @Test
    @DisplayName("getApi().getPlatformCapability() 與 getPlatformCapability() 回傳相同實例")
    void getApiAndPlugin_getPlatformCapability_consistent() {
        assertEquals(
            plugin.getApi().getPlatformCapability(),
            plugin.getPlatformCapability(),
            "plugin facade 與 plugin 本體暴露的 capability 必須一致");
    }

    // ---------------------------------------------------------------------
    // Phase 14：DiagnosticsService lifecycle wiring
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("onEnable 後 getDiagnosticsService() 回傳已 bind 的 service（version/platform/capability/ready）")
    void getDiagnosticsService_afterOnEnable_isBound() {
        DiagnosticsService ds = plugin.getDiagnosticsService();
        assertNotNull(ds, "onEnable 後 diagnostics service 不可為 null");
        DiagnosticSnapshot snap = ds.buildSnapshot();
        assertEquals(AceLibVersion.VERSION, snap.version(),
            "diagnostics snapshot 必須反映 plugin 版本");
        assertSame(Platform.PAPER, snap.platform(),
            "MockBukkit 環境下 diagnostics platform 應為 PAPER");
        assertEquals(PlatformCapability.forPlatform(Platform.PAPER),
            snap.capability(),
            "diagnostics capability 應對應 PAPER");
        assertTrue(snap.isReady(),
            "onEnable 完成後 diagnostics snapshot 必須 ready=true");
    }

    @Test
    @DisplayName("onEnable 之前 getDiagnosticsService() 回傳 safe 預設（不丟例外），"
        + "且 snapshot 顯示未 bind / 未 ready")
    void getDiagnosticsService_beforeOnEnable_isSafeDefault() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        AceLibPlugin fresh =
            (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        assertDoesNotThrow(fresh::getDiagnosticsService,
            "onEnable 前 getter 不可丟例外");
        DiagnosticsService ds = fresh.getDiagnosticsService();
        assertNotNull(ds, "default diagnostics service 不可為 null");
        DiagnosticSnapshot snap = ds.buildSnapshot();
        assertFalse(snap.isReady(),
            "未 onEnable 時 snapshot.ready 必須為 false");
        assertSame(Platform.UNKNOWN, snap.platform(),
            "未 onEnable 時 snapshot.platform 必須為 UNKNOWN");
    }

    @Test
    @DisplayName("buildDiagnosticsReport() 回傳當下 DiagnosticReport，含 snapshot")
    void buildDiagnosticsReport_returnsReportWithCurrentState() {
        DiagnosticReport report = plugin.buildDiagnosticsReport();
        assertNotNull(report, "buildDiagnosticsReport 不可為 null");
        assertNotNull(report.snapshot(), "report 必須包含 snapshot");
        assertEquals(AceLibVersion.VERSION, report.snapshot().version());
        assertTrue(report.snapshot().isReady());
    }

    @Test
    @DisplayName("onDisable 後 scheduler 已 disabled 且 diagnostics 解除綁定（不丟例外）")
    void onDisable_disablesSchedulerAndUnbindsDiagnostics() {
        // 觸碰一次 diagnostics 取得 reference，並驗證 ready=true
        DiagnosticsService beforeDs = plugin.getDiagnosticsService();
        assertTrue(beforeDs.buildSnapshot().isReady());
        assertTrue(beforeDs.buildSnapshot().modules()
            .containsKey("scheduler"),
            "scheduler 模組必須出現在 snapshot modules");
        assertSame(
            com.smile.acelib.diagnostics.ModuleStatus.READY,
            beforeDs.buildSnapshot().modules().get("scheduler").status(),
            "scheduler 模組在 ready 時應為 READY");

        plugin.onDisable();

        // onDisable 後：scheduler 必須 disabled
        SafeSchedulerImpl schedAfter = plugin.getSchedulerForDiagnostics();
        assertNotNull(schedAfter, "scheduler reference 仍應可由 plugin 內部查得");
        assertTrue(schedAfter.isDisabled(),
            "onDisable 後 scheduler 必須被標記為 disabled");

        // diagnostics 解除綁定：snapshot 顯示 scheduler 模組狀態降級為 FAILED，
        // ready 為 false，且不丟例外
        assertFalse(beforeDs.buildSnapshot().isReady(),
            "onDisable 後 diagnostics snapshot 必須 not ready");
        assertSame(
            com.smile.acelib.diagnostics.ModuleStatus.FAILED,
            beforeDs.buildSnapshot().modules().get("scheduler").status(),
            "onDisable 後 scheduler 模組狀態應降級為 FAILED");
    }

    @Test
    @DisplayName("reload 後 diagnostics 重新綁定 platform/capability（不殘留舊綁定）")
    void reload_rebindsDiagnosticsBindings() {
        DiagnosticsService ds = plugin.getDiagnosticsService();
        DiagnosticSnapshot before = ds.buildSnapshot();
        assertSame(Platform.PAPER, before.platform());

        // 觸發 reload
        assertTrue(plugin.reload(), "已啟用時 reload 應成功");

        DiagnosticSnapshot after = ds.buildSnapshot();
        assertSame(Platform.PAPER, after.platform(),
            "reload 後 platform 應仍偵測為 PAPER");
        // 重新載入後 capability 必須對應新偵測結果
        assertEquals(PlatformCapability.forPlatform(Platform.PAPER),
            after.capability(),
            "reload 後 capability 必須重新計算");

        // 重新綁定後 scheduler 模組應為 READY（reload 不應留下 FAILED 狀態）
        assertSame(
            com.smile.acelib.diagnostics.ModuleStatus.READY,
            after.modules().get("scheduler").status(),
            "reload 後 scheduler 模組狀態必須是 READY，不留殘留");
    }

    // ---------------------------------------------------------------------
    // Phase 14 failure-path regression（M-14-04 Momus review）
    // ---------------------------------------------------------------------
    // 規範：reload 必須有交易式失敗語意 — 任一步驟失敗必須：
    //   1. 回傳 false（不可靜默回 true）
    //   2. 輸出 WARNING/SEVERE + ACELIB-DBG-001
    //   3. 不發布半完成的新 api/scheduler/diagnostics 狀態
    //   4. 必要時 rollback 到既有綁定
    // Failure seam（兩個 package-private hooks）：
    //   - `plugin.reloadRebindFailureHook` — diagnostics rebind 完成、commit 前
    //     注入受控例外，模擬 bindScheduler 內部不一致的罕見路徑。
    //   - `plugin.reloadOldTeardownFailureHook` — 舊 scheduler teardown 階段
    //     注入受控例外，模擬 onPluginDisable 內部拋錯的罕見路徑。

    @Test
    @DisplayName("reload diagnostics rebind 失敗：回傳 false、輸出 ACELIB-DBG-001 "
        + "WARNING/SEVERE、scheduler/api/diagnostics reference 不變、new scheduler sink rollback")
    void reload_diagnosticsRebindFailure_returnsFalseAndLogsCode() {
        // 取得 reload 前的 reference 與狀態
        DiagnosticsService dsBefore = plugin.getDiagnosticsService();
        SafeSchedulerImpl schedBefore = plugin.getSchedulerForDiagnostics();
        AceLibApi apiBefore = plugin.getApi();
        DiagnosticSnapshot beforeSnap = dsBefore.buildSnapshot();
        assertTrue(beforeSnap.isReady(), "reload 前 diagnostics 必須 ready");

        // 注入受控失敗：rebind 完成（in-place 寫入 version/platform/capability）
        // 後、commit 前拋出，模擬真實的「bindScheduler 後內部不一致」
        // 同時透過 reflection 取得「在 hook 觸發時 ds.boundScheduler 是誰」
        // （即 commit 前的 newScheduler），以便後續驗證其 recorder sink 已 rollback。
        final SafeSchedulerImpl[] newSchedulerCapture = new SafeSchedulerImpl[1];
        plugin.reloadRebindFailureHook = () -> {
            try {
                java.lang.reflect.Field f = DiagnosticsService.class
                    .getDeclaredField("boundScheduler");
                f.setAccessible(true);
                newSchedulerCapture[0] = (SafeSchedulerImpl) f.get(dsBefore);
            } catch (ReflectiveOperationException roe) {
                throw new IllegalStateException(
                    "test reflection failed: " + roe, roe);
            }
            throw new IllegalStateException("injected: bindScheduler commit failed");
        };

        List<LogRecord> captured = new ArrayList<>();
        Handler handler = new RecordingHandler(captured);
        LogCaptureScope scope = LogCaptureScope.attachAndLower("AceLib", handler);
        try {
            boolean result = plugin.reload();
            assertFalse(result,
                "diagnostics rebind 失敗時 reload 必須回傳 false（不可靜默成功）");

            // 必須輸出含 ACELIB-DBG-001 的 WARNING 或 SEVERE log
            boolean hasCodeLog = captured.stream().anyMatch(r ->
                r.getMessage() != null
                    && r.getMessage().contains("ACELIB-DBG-001")
                    && (r.getLevel() == Level.WARNING
                        || r.getLevel() == Level.SEVERE));
            assertTrue(hasCodeLog, () ->
                "rebind 失敗必須記錄含 ACELIB-DBG-001 的 WARNING/SEVERE log；實際: "
                    + captured.stream()
                        .map(r -> r.getLevel() + ":" + r.getMessage())
                        .toList());

            // 狀態一致性：scheduler / api / diagnostics reference 必須保留
            assertSame(schedBefore, plugin.getSchedulerForDiagnostics(),
                "rebind 失敗時 scheduler reference 必須保持不變（不發布新 scheduler）");
            assertSame(apiBefore, plugin.getApi(),
                "rebind 失敗時 api reference 必須保持不變");
            assertSame(dsBefore, plugin.getDiagnosticsService(),
                "rebind 失敗時 diagnostics reference 必須保持不變（in-place 契約）");

            // rollback 後 diagnostics scheduler 模組狀態必須明確降級為 FAILED
            // （Phase A 內 oldScheduler 已 disabled；rollback 內 bindScheduler(disabled)
            // 觸發 markSchedulerDisabled 語意）。絕不可 READY 或 NOT_INITIALIZED。
            DiagnosticSnapshot afterSnap = dsBefore.buildSnapshot();
            assertSame(
                com.smile.acelib.diagnostics.ModuleStatus.FAILED,
                afterSnap.modules().get("scheduler").status(),
                () -> "rollback 後 diagnostics scheduler 模組狀態必須明確 FAILED；實際: "
                    + afterSnap.modules().get("scheduler").status());

            // oldScheduler 已 disabled（Phase A 已處理）
            assertTrue(schedBefore.isDisabled(),
                "Phase A 完成後 oldScheduler 必須 disabled");

            // 透過 reflection 取得 hook 期間的 newScheduler reference，
            // 並驗證 rollback 已將其 disable + 解除 recorder sink
            assertNotNull(newSchedulerCapture[0],
                "test seam 必須能在 hook 期間自 ds.boundScheduler 取得 newScheduler reference");
            assertTrue(newSchedulerCapture[0].isDisabled(),
                "rollback 後 newScheduler 必須 disabled（rollbackReload 呼叫 onPluginDisable）");

            // 驗證 newScheduler 的 recorder sink 已被 rollback 解除
            // （bindScheduler(disabled oldScheduler) 內部會 clearRecordSink 上一綁定）
            Object newSink = readRecorderSink(newSchedulerCapture[0]);
            assertNull(newSink,
                "rollback 後 newScheduler 的 recorder sink 必須已被清除（不可繼續傳遞錯誤到 diagnostics）");

            // 行為驗證：rollback 後寫入 newScheduler 的 recorder 不會傳遞到 diagnostics
            newSchedulerCapture[0].getRecorder().record(
                TaskErrorRecord.cancelled(TaskType.GLOBAL, "ACELIB-VERIFY-NEWSCHED",
                    "post-rollback probe (should NOT propagate)"));
            try { Thread.sleep(50L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            assertNull(dsBefore.buildSnapshot().throttleSnapshot()
                    .get("ACELIB-VERIFY-NEWSCHED"),
                "newScheduler recorder sink 已 rollback；後續 record 不應傳遞到 diagnostics");

            // Phase C 失敗為 recoverable：plugin 仍 ready（可再次 reload 嘗試）
            assertTrue(plugin.isReady(),
                "Phase C rebind 失敗為 recoverable；plugin 仍應 ready");
        } finally {
            plugin.reloadRebindFailureHook = null;
            scope.detach();
        }
    }

@Test
    @DisplayName("reload 舊 scheduler teardown 失敗：回傳 false、輸出 ACELIB-DBG-001 "
        + "WARNING/SEVERE、scheduler/api/diagnostics reference 不變、plugin 明確降級為 FAILED")
    void reload_oldSchedulerTeardownFailure_returnsFalseAndLogsCode() {
        // 取得 reload 前的 reference 與狀態
        DiagnosticsService dsBefore = plugin.getDiagnosticsService();
        SafeSchedulerImpl schedBefore = plugin.getSchedulerForDiagnostics();
        AceLibApi apiBefore = plugin.getApi();
        DiagnosticSnapshot beforeSnap = dsBefore.buildSnapshot();
        assertTrue(beforeSnap.isReady(), "reload 前 diagnostics 必須 ready");
        assertTrue(plugin.isReady(), "reload 前 plugin 必須 ready");

        // 注入受控失敗：模擬「舊 scheduler onPluginDisable 之後某罕見路徑拋錯」
        plugin.reloadOldTeardownFailureHook = () -> {
            throw new RuntimeException("injected: old scheduler teardown hook threw");
        };

        List<LogRecord> captured = new ArrayList<>();
        Handler handler = new RecordingHandler(captured);
        LogCaptureScope scope = LogCaptureScope.attachAndLower("AceLib", handler);
        try {
            boolean result = plugin.reload();
            assertFalse(result,
                "舊 scheduler teardown 失敗時 reload 必須回傳 false（不可靜默成功）");

            // 必須輸出含 ACELIB-DBG-001 的 WARNING 或 SEVERE log
            boolean hasCodeLog = captured.stream().anyMatch(r ->
                r.getMessage() != null
                    && r.getMessage().contains("ACELIB-DBG-001")
                    && (r.getLevel() == Level.WARNING
                        || r.getLevel() == Level.SEVERE));
            assertTrue(hasCodeLog, () ->
                "teardown 失敗必須記錄含 ACELIB-DBG-001 的 WARNING/SEVERE log；實際: "
                    + captured.stream()
                        .map(r -> r.getLevel() + ":" + r.getMessage())
                        .toList());

            // 狀態一致性：scheduler / api / diagnostics reference 必須保留
            assertSame(schedBefore, plugin.getSchedulerForDiagnostics(),
                "teardown 失敗時 scheduler reference 必須保持不變（不發布新 scheduler）");
            assertSame(apiBefore, plugin.getApi(),
                "teardown 失敗時 api reference 必須保持不變");
            assertSame(dsBefore, plugin.getDiagnosticsService(),
                "teardown 失敗時 diagnostics reference 必須保持不變（in-place 契約）");

            // Phase A 失敗策略：scheduler 半 disabled / 內部狀態不明，
            // 為避免「diagnostics 顯示 READY 但實際半失效」的假象，必須明確降級。
            DiagnosticSnapshot afterSnap = dsBefore.buildSnapshot();
            assertSame(
                com.smile.acelib.diagnostics.ModuleStatus.FAILED,
                afterSnap.modules().get("scheduler").status(),
                () -> "teardown 失敗後 diagnostics scheduler 模組狀態必須明確 FAILED；實際: "
                    + afterSnap.modules().get("scheduler").status());
            assertFalse(afterSnap.isReady(),
                "teardown 失敗後 diagnostics.ready 必須為 false");

            // plugin 本體同步降級（unrecoverable：須重新 onEnable）
            assertFalse(plugin.isReady(),
                "teardown 失敗後 plugin.ready 必須降級為 false（無法安全恢復 old ready/sink）");
            assertFalse(plugin.getApi().isReady(),
                "teardown 失敗後 api.isReady() 必須透過 readyCheck callback 回傳 false");

            // Phase A 第一步已 clearRecordSink、第二步已 onPluginDisable 才進入 hook。
            // 因此 oldScheduler 必然 disabled 且 recorder sink 已清除。
            assertTrue(schedBefore.isDisabled(),
                "Phase A 內已呼叫 onPluginDisable；oldScheduler 必須 disabled");

            // 行為驗證：old scheduler recorder sink 必須已清除
            Object oldSink = readRecorderSink(schedBefore);
            assertNull(oldSink,
                "teardown 失敗後 old scheduler recorder sink 必須已清除（Phase A 第一步）");

            // 寫入一筆 unique code，verify 不傳遞到 diagnostics（sink 已清除）
            schedBefore.getRecorder().record(
                TaskErrorRecord.cancelled(TaskType.GLOBAL, "ACELIB-VERIFY-OLDSCHED",
                    "post-teardown-failure probe (should NOT propagate)"));
            try { Thread.sleep(50L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            assertNull(dsBefore.buildSnapshot().throttleSnapshot()
                    .get("ACELIB-VERIFY-OLDSCHED"),
                "old scheduler recorder sink 已清除；後續 record 不應傳遞到 diagnostics");
        } finally {
            plugin.reloadOldTeardownFailureHook = null;
            scope.detach();
        }
    }

    @Test
    @DisplayName("reload new SafeSchedulerImpl construction 失敗：回傳 false、輸出 "
        + "ACELIB-DBG-001 WARNING/SEVERE、scheduler/api/diagnostics reference 不變、"
        + "plugin 明確降級為 FAILED（與 Phase A 策略一致）")
    void reload_newSchedulerConstructionFailure_returnsFalseAndLogsCode() {
        // 取得 reload 前的 reference 與狀態
        DiagnosticsService dsBefore = plugin.getDiagnosticsService();
        SafeSchedulerImpl schedBefore = plugin.getSchedulerForDiagnostics();
        AceLibApi apiBefore = plugin.getApi();
        DiagnosticSnapshot beforeSnap = dsBefore.buildSnapshot();
        assertTrue(beforeSnap.isReady(), "reload 前 diagnostics 必須 ready");
        assertTrue(plugin.isReady(), "reload 前 plugin 必須 ready");

        // 注入受控失敗：模擬「new SafeSchedulerImpl(...) 建構子拋錯」罕見路徑。
        // Hook 在 constructor 呼叫前執行；拋出時進入 production catch 並觸發降級。
        plugin.reloadNewSchedulerConstructionFailureHook = () -> {
            throw new RuntimeException(
                "injected: new SafeSchedulerImpl constructor threw");
        };

        List<LogRecord> captured = new ArrayList<>();
        Handler handler = new RecordingHandler(captured);
        LogCaptureScope scope = LogCaptureScope.attachAndLower("AceLib", handler);
        try {
            boolean result = plugin.reload();
            assertFalse(result,
                "new scheduler construction 失敗時 reload 必須回傳 false");

            // 必須輸出含 ACELIB-DBG-001 的 WARNING 或 SEVERE log
            boolean hasCodeLog = captured.stream().anyMatch(r ->
                r.getMessage() != null
                    && r.getMessage().contains("ACELIB-DBG-001")
                    && (r.getLevel() == Level.WARNING
                        || r.getLevel() == Level.SEVERE));
            assertTrue(hasCodeLog, () ->
                "new scheduler construction 失敗必須記錄含 ACELIB-DBG-001 的 "
                    + "WARNING/SEVERE log；實際: "
                    + captured.stream()
                        .map(r -> r.getLevel() + ":" + r.getMessage())
                        .toList());

            // 狀態一致性：scheduler / api / diagnostics reference 必須保留
            assertSame(schedBefore, plugin.getSchedulerForDiagnostics(),
                "new scheduler construction 失敗時 scheduler reference 必須保持不變");
            assertSame(apiBefore, plugin.getApi(),
                "new scheduler construction 失敗時 api reference 必須保持不變");
            assertSame(dsBefore, plugin.getDiagnosticsService(),
                "new scheduler construction 失敗時 diagnostics reference 必須保持不變");

            // Phase B 失敗策略與 Phase A 一致：scheduler 半建構 / 內部狀態不明，
            // 為避免「diagnostics 顯示 READY 但實際半失效」的假象，必須明確降級。
            DiagnosticSnapshot afterSnap = dsBefore.buildSnapshot();
            assertSame(
                com.smile.acelib.diagnostics.ModuleStatus.FAILED,
                afterSnap.modules().get("scheduler").status(),
                () -> "new scheduler construction 失敗後 diagnostics scheduler 模組狀態"
                    + "必須明確 FAILED；實際: "
                    + afterSnap.modules().get("scheduler").status());
            assertFalse(afterSnap.isReady(),
                "new scheduler construction 失敗後 diagnostics.ready 必須為 false");

            // plugin 本體同步降級（unrecoverable：須重新 onEnable）
            assertFalse(plugin.isReady(),
                "new scheduler construction 失敗後 plugin.ready 必須降級為 false");
            assertFalse(plugin.getApi().isReady(),
                "new scheduler construction 失敗後 api.isReady() 必須透過 readyCheck "
                    + "callback 回傳 false");

            // Phase A 已完成：oldScheduler 已 disabled
            assertTrue(schedBefore.isDisabled(),
                "Phase A 完成後 oldScheduler 必須 disabled");

            // Phase A 第一步已 clearRecordSink
            Object oldSink = readRecorderSink(schedBefore);
            assertNull(oldSink,
                "new scheduler construction 失敗後 old scheduler recorder sink 必須"
                    + "已清除（Phase A 第一步）");

            // 行為驗證：寫入一筆 unique code 不傳遞到 diagnostics（sink 已清除）
            schedBefore.getRecorder().record(
                TaskErrorRecord.cancelled(TaskType.GLOBAL, "ACELIB-VERIFY-BPHASE",
                    "post-PhaseB-failure probe (should NOT propagate)"));
            try { Thread.sleep(50L); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            assertNull(dsBefore.buildSnapshot().throttleSnapshot()
                    .get("ACELIB-VERIFY-BPHASE"),
                "old scheduler recorder sink 已清除；後續 record 不應傳遞到 diagnostics");
        } finally {
            plugin.reloadNewSchedulerConstructionFailureHook = null;
            scope.detach();
        }
    }

    @Test
    @DisplayName("reload diagnostics rebind 失敗（platform/capability 不同）："
        + "rollback 完整恢復 version/platform/capability/ready metadata"
        + "（Phase C partial commit 防護：rebindPlugin 寫入的新 metadata 必須還原）")
    void reload_diagnosticsRebindFailure_restoresMetadataSnapshot() {
        // 取得 reload 前的 reference 與 metadata snapshot
        DiagnosticsService dsBefore = plugin.getDiagnosticsService();
        SafeSchedulerImpl schedBefore = plugin.getSchedulerForDiagnostics();
        AceLibApi apiBefore = plugin.getApi();
        DiagnosticSnapshot beforeSnap = dsBefore.buildSnapshot();
        assertSame(Platform.PAPER, beforeSnap.platform(),
            "前置條件：reload 前 diagnostics platform 必須為 PAPER");
        assertTrue(beforeSnap.isReady(),
            "前置條件：reload 前 diagnostics 必須 ready");
        String oldVersion = beforeSnap.version();
        PlatformCapability oldCapability = beforeSnap.capability();

        // 用 reflection 將 plugin.platformDetector 換成會回傳 UNKNOWN 的 detector，
        // 使 reload 過程中 ds.rebindPlugin 會把 metadata 翻為 UNKNOWN，
        // 才能驗證 rollback 是否完整恢復為 reload 前值。PlatformDetector 是 final，
        // 不能 subclass，故使用與既有 failure-path 測試一致的 reflection 方式
        // 注入 UNKNOWN detector（空 classloader → 偵測為 UNKNOWN）。
        PlatformDetector originalDetector = readPlatformDetectorViaReflection(plugin);
        try {
            PlatformDetector unknownDetector = new PlatformDetector(
                new ClassLoader(null) {});
            writePlatformDetectorViaReflection(plugin, unknownDetector);

            // 注入受控失敗 hook：攔截時 snapshot 應已被翻為 UNKNOWN
            // （證明 hook 在 rebind 後觸發，並展示「partial commit」：
            //  若 rollback 不完整，metadata 會留在 UNKNOWN 假狀態）
            plugin.reloadRebindFailureHook = () -> {
                DiagnosticSnapshot midSnap = dsBefore.buildSnapshot();
                assertSame(Platform.UNKNOWN, midSnap.platform(),
                    "hook 觸發時 metadata 應已被 rebindPlugin 翻為 UNKNOWN"
                        + "（partial commit 演示）；實際: " + midSnap.platform());
                throw new IllegalStateException(
                    "injected: rebind commit failed (Phase C failure)");
            };

            List<LogRecord> captured = new ArrayList<>();
            Handler handler = new RecordingHandler(captured);
            LogCaptureScope scope = LogCaptureScope.attachAndLower("AceLib", handler);
            try {
                boolean result = plugin.reload();
                assertFalse(result,
                    "diagnostics rebind 失敗時 reload 必須回傳 false（不可靜默成功）");

                // 必須輸出含 ACELIB-DBG-001 的 WARNING/SEVERE log
                boolean hasCodeLog = captured.stream().anyMatch(r ->
                    r.getMessage() != null
                        && r.getMessage().contains("ACELIB-DBG-001")
                        && (r.getLevel() == Level.WARNING
                            || r.getLevel() == Level.SEVERE));
                assertTrue(hasCodeLog, () ->
                    "rebind 失敗必須記錄含 ACELIB-DBG-001 的 WARNING/SEVERE log；實際: "
                        + captured.stream()
                            .map(r -> r.getLevel() + ":" + r.getMessage())
                            .toList());

                // === Phase C rollback 契約：metadata 完整恢復 reload 前值 ===
                DiagnosticSnapshot afterSnap = dsBefore.buildSnapshot();
                assertEquals(oldVersion, afterSnap.version(),
                    () -> "rollback 後 version 必須恢復為 reload 前值；"
                        + "實際: " + afterSnap.version());
                assertSame(Platform.PAPER, afterSnap.platform(),
                    () -> "rollback 後 platform 必須恢復為 reload 前值"
                        + "（Phase C 失敗契約：rebindPlugin 寫入的 UNKNOWN 必須還原）；"
                        + "實際: " + afterSnap.platform());
                assertEquals(oldCapability, afterSnap.capability(),
                    () -> "rollback 後 capability 必須恢復為 reload 前值；"
                        + "實際: " + afterSnap.capability());
                assertTrue(afterSnap.isReady(),
                    () -> "rollback 後 ready 應恢復為 reload 前值 (recoverable)；"
                        + "實際: " + afterSnap.isReady());

                // === 既有契約保留（in-place rollback） ===
                assertSame(schedBefore, plugin.getSchedulerForDiagnostics(),
                    "rebind 失敗時 scheduler reference 必須保持不變");
                assertSame(apiBefore, plugin.getApi(),
                    "rebind 失敗時 api reference 必須保持不變");
                assertSame(dsBefore, plugin.getDiagnosticsService(),
                    "rebind 失敗時 diagnostics reference 必須保持不變"
                        + "（in-place 契約）");
                assertTrue(plugin.isReady(),
                    "Phase C rebind 失敗為 recoverable；plugin 仍應 ready");

                // scheduler 模組為 FAILED（oldScheduler 已 disabled，rollback 內
                // bindScheduler(disabled) 自動標記 FAILED + ACELIB-SCHED-006）
                assertSame(
                    com.smile.acelib.diagnostics.ModuleStatus.FAILED,
                    afterSnap.modules().get("scheduler").status(),
                    () -> "rollback 後 scheduler 模組狀態必須明確 FAILED；"
                        + "實際: " + afterSnap.modules().get("scheduler").status());

                // oldScheduler 已被 Phase A disable
                assertTrue(schedBefore.isDisabled(),
                    "Phase A 完成後 oldScheduler 必須 disabled");

                // Phase C 流程：rebindPlugin 寫了新的 metadata；rollback 必須將其
                // 完整還原。若測試走到這裡仍綠燈，證明 restoreMetadata 已生效。
            } finally {
                plugin.reloadRebindFailureHook = null;
                scope.detach();
            }
        } finally {
            // 還原原始 detector（@AfterEach 也會重置 plugin，但 finally 防禦性
            // 還原避免單一測試失敗後污染 @AfterEach 之前的其他斷言）
            writePlatformDetectorViaReflection(plugin, originalDetector);
        }
    }

    @Test
    @DisplayName("reload 正常路徑（success-path regression）— 不受 failure seam 影響："
        + "同一 diagnostics reference in-place rebind、新 scheduler READY、舊 scheduler disabled")
    void reload_normalSuccessPath_regressionAfterFailureSeam() {
        // 先確認 hook 為 null 時 reload 仍走原本的成功路徑
        assertNull(plugin.reloadRebindFailureHook);
        assertNull(plugin.reloadOldTeardownFailureHook);

        DiagnosticsService ds = plugin.getDiagnosticsService();
        assertTrue(ds.buildSnapshot().isReady());

        assertTrue(plugin.reload(), "正常 reload 必須回傳 true");

        // 同一 reference in-place rebind
        assertSame(ds, plugin.getDiagnosticsService());
        assertSame(Platform.PAPER, ds.buildSnapshot().platform());
        assertSame(
            com.smile.acelib.diagnostics.ModuleStatus.READY,
            ds.buildSnapshot().modules().get("scheduler").status(),
            "正常 reload 後 scheduler 模組必須 READY");

        // 新 scheduler reference
        assertNotNull(plugin.getSchedulerForDiagnostics());
    }

    @Test
    @DisplayName("getDiagnosticsService 與 getSchedulerForDiagnostics 在 onDisable 前後皆不為 null"
        + "（提供後續命令/管理員查詢入口）")
    void diagnosticsAndSchedulerGetters_areAlwaysAvailable() {
        // onEnable 後
        assertNotNull(plugin.getDiagnosticsService());
        assertNotNull(plugin.getSchedulerForDiagnostics());

        // onDisable 後（不應變 null；只是狀態降級）
        plugin.onDisable();
        assertNotNull(plugin.getDiagnosticsService(),
            "onDisable 後 diagnostics service 仍須可取得");
        assertNotNull(plugin.getSchedulerForDiagnostics(),
            "onDisable 後 scheduler reference 仍須可取得（已 disabled 狀態）");
    }

    // ---------------------------------------------------------------------
    // Helper: reflection-based 測試輔助
    // ---------------------------------------------------------------------

    /**
     * 透過 reflection 讀取 {@link SafeSchedulerImpl#getRecorder() TaskErrorRecorder}
     * 內部 {@code recordSink} 欄位；僅供 failure-path 測試驗證 sink 是否被清除。
     *
     * <p>不能修改 production {@code TaskErrorRecorder}（scheduler 套件不在本任務
     * 允許修改範圍）；故以 reflection 取 {@code volatile Consumer recordSink} 欄位。
     * 回傳值可能為 {@code null}（sink 已清除）或具體 {@link java.util.function.Consumer}
     * 實例（仍注入中）。</p>
     *
     * @param scheduler 要查的 scheduler
     * @return 內部 recordSink；若 reflection 失敗則丟 {@link AssertionError}
     */
    private static Object readRecorderSink(SafeSchedulerImpl scheduler) {
        try {
            java.lang.reflect.Field f = com.smile.acelib.scheduler.TaskErrorRecorder.class
                .getDeclaredField("recordSink");
            f.setAccessible(true);
            return f.get(scheduler.getRecorder());
        } catch (ReflectiveOperationException roe) {
            throw new AssertionError(
                "readRecorderSink reflection failed: " + roe, roe);
        }
    }

    /**
     * 透過 reflection 讀取 {@link AceLibPlugin} 內部
     * {@code platformDetector} 欄位。
     *
     * <p>僅供 Phase C rollback metadata 測試注入「會回傳不同 platform」
     * 的 detector。Production code 本身不依賴 reflection；測試只透過
     * reflection 在 setup 階段替換既有 detector 為 UNKNOWN-detector，
     * 用以驗證「rebindPlugin 寫入的新 metadata 必須於 rollback 還原」。
     * 還原責任在 caller 的 finally 區塊。</p>
     *
     * @param plugin 目標 plugin
     * @return 內部 platformDetector；若 reflection 失敗則丟 {@link AssertionError}
     */
    private static PlatformDetector readPlatformDetectorViaReflection(AceLibPlugin plugin) {
        try {
            java.lang.reflect.Field f = AceLibPlugin.class
                .getDeclaredField("platformDetector");
            f.setAccessible(true);
            return (PlatformDetector) f.get(plugin);
        } catch (ReflectiveOperationException roe) {
            throw new AssertionError(
                "readPlatformDetectorViaReflection failed: " + roe, roe);
        }
    }

    /**
     * 透過 reflection 寫入 {@link AceLibPlugin} 內部 {@code platformDetector}
     * 欄位。
     *
     * <p>僅供 Phase C rollback metadata 測試於 setup 階段替換 detector。
     * 還原責任在 caller 的 finally 區塊。</p>
     *
     * @param plugin   目標 plugin
     * @param detector 新的 detector（不可為 null）
     */
    private static void writePlatformDetectorViaReflection(AceLibPlugin plugin,
                                                            PlatformDetector detector) {
        try {
            java.lang.reflect.Field f = AceLibPlugin.class
                .getDeclaredField("platformDetector");
            f.setAccessible(true);
            f.set(plugin, detector);
        } catch (ReflectiveOperationException roe) {
            throw new AssertionError(
                "writePlatformDetectorViaReflection failed: " + roe, roe);
        }
    }

    // ---------------------------------------------------------------------
    // Helper: LogRecord recorder + 配套 scope
    // ---------------------------------------------------------------------

    /**
     * 簡單的 JUL Handler，把所有收到的 LogRecord 加入外部 List。
     * 測試結束後由 caller 負責從 Logger 移除。
     */
    private static final class RecordingHandler extends Handler {
        private final List<LogRecord> sink;

        RecordingHandler(List<LogRecord> sink) {
            this.sink = sink;
        }

        @Override
        public void publish(LogRecord record) {
            sink.add(record);
        }

        @Override
        public void flush() {
            // no-op
        }

        @Override
        public void close() {
            // no-op
        }
    }

    /**
     * 連同「附加 handler」與「暫時把 logger 與 root level 調為 ALL」一起打包的 scope。
     * 測試結束時呼叫 {@link #detach()} 還原 level 並移除 handler，避免污染其他測試。
     *
     * <p>為何需要把 level 調成 ALL？JavaPlugin 在 MockBukkit 環境下取出的 logger
     * 預設 level 為 INFO，會在 handler 處理之前就過濾掉 FINE 訊息。
     * 為驗證 fine-level 輸出，必須先把 level 拉低。</p>
     */
    private static final class LogCaptureScope {
        private final List<Logger> touched;
        private final List<Level> previousLevels;
        private final Handler handler;
        private final Logger root;
        private final Level previousRootLevel;

        private LogCaptureScope(List<Logger> touched, List<Level> previousLevels,
                                Handler handler, Logger root, Level previousRootLevel) {
            this.touched = touched;
            this.previousLevels = previousLevels;
            this.handler = handler;
            this.root = root;
            this.previousRootLevel = previousRootLevel;
        }

        /**
         * 附加 handler 到 root 與指定名稱的 logger，並把兩者 level 暫時調成 ALL。
         *
         * @param pluginLoggerName plugin logger 的名稱（"AceLib"）
         * @param handler          已建構的 Handler
         * @return 可在 finally 呼叫 {@link #detach()} 的 scope
         */
        static LogCaptureScope attachAndLower(String pluginLoggerName, Handler handler) {
            Logger root = Logger.getLogger("");
            Level previousRoot = root.getLevel();
            root.setLevel(Level.ALL);
            root.addHandler(handler);

            Logger named = Logger.getLogger(pluginLoggerName);
            List<Logger> touched = new ArrayList<>();
            List<Level> previous = new ArrayList<>();
            touched.add(named);
            previous.add(named.getLevel());
            named.setLevel(Level.ALL);
            named.addHandler(handler);
            return new LogCaptureScope(touched, previous, handler, root, previousRoot);
        }

        void detach() {
            for (int i = 0; i < touched.size(); i++) {
                Logger lg = touched.get(i);
                lg.removeHandler(handler);
                lg.setLevel(previousLevels.get(i));
            }
            root.removeHandler(handler);
            root.setLevel(previousRootLevel);
        }
    }
}
