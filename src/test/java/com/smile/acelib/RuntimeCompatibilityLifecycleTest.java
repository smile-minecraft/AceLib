package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.diagnostics.ModuleState;
import com.smile.acelib.diagnostics.ModuleStatus;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformDetector;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.event.HandlerList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 相容性 gate 在 plugin 生命週期（enable / reload / disable）的行為測試。
 *
 * <p>使用 {@code compatibilityOverride} 測試 seam 強制回傳指定 {@link CompatibilityStatus}，
 * 跳過真實 classpath 探測（避免 MockBukkit plugin classloader 的 NPE 干擾），專注驗證
 * fail-closed 路徑與 diagnostics 模組狀態的一致性。</p>
 */
@DisplayName("Runtime compatibility lifecycle")
class RuntimeCompatibilityLifecycleTest {

    private static ServerMock server;

    @BeforeAll
    static void setUpClass() {
        server = MockBukkit.mock();
    }

    @AfterAll
    static void tearDownClass() {
        MockBukkit.unmock();
    }

    @BeforeEach
    void setUp() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
    }

    /**
     * 建立一個 fresh plugin，並以指定相容性狀態強制啟用。
     *
     * <p>MockBukkit 4.x 的 {@code loadPlugin} 不會標記 plugin enabled，且
     * {@code PluginManager.enablePlugin} 在本 repo 的 plugin classloader 下會 NPE
     * （見 {@code AceLibPluginTest} 說明）；而 {@code isEnabled()} 為 final，故
     * {@link AceLibPlugin#onPluginReady()} 的 {@code registerEvents} 路徑在測試中永遠早退。
     * 為讓「listener 已解除」斷言成為非 vacuous，這裡呼叫 package-private 測試 seam
     * {@code registerListenersForTest()}，經由 plugin loader 直接註冊 player / gui listener
     * （繞過 enabled 守門），使 {@link HandlerList#getRegisteredListeners} 真正反映已註冊的
     * listener，再斷言 teardown 確實解除。</p>
     */
    private AceLibPlugin freshEnabledWith(CompatibilityStatus forced) {
        AceLibPlugin p = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        p.compatibilityOverride = ignored -> forced;
        p.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        p.registerListenersForTest();
        return p;
    }

    /** 在 AceLib plugin logger 與 root logger 上掛 handler 捕捉 log，執行 action 後移除。 */
    private List<LogRecord> capture(Runnable action) {
        List<LogRecord> captured = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger acelib = Logger.getLogger("AceLib");
        Logger root = Logger.getLogger("");
        Level prevAcelib = acelib.getLevel();
        Level prevRoot = root.getLevel();
        acelib.addHandler(handler);
        acelib.setLevel(Level.ALL);
        root.addHandler(handler);
        root.setLevel(Level.ALL);
        try {
            action.run();
        } finally {
            acelib.removeHandler(handler);
            acelib.setLevel(prevAcelib);
            root.removeHandler(handler);
            root.setLevel(prevRoot);
        }
        return captured;
    }

    private static ModuleState compatibilityModule(AceLibPlugin p) {
        return p.getDiagnosticsService().buildSnapshot().modules().get("compatibility");
    }

    // ---------------------------------------------------------------------
    // INCOMPATIBLE enable → not ready + diagnostics FAILED
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("incompatible enable：plugin not ready 且 diagnostics 顯示 INCOMPATIBLE / PLAT-009")
    void incompatibleEnable_notReadyAndDiagnosticsFailed() {
        AceLibPlugin p = freshEnabledWith(
            CompatibilityStatus.incompatible("forced incompatible", "fp-summary"));
        assertFalse(p.isReady(), "INCOMPATIBLE 必須 not ready");
        ModuleState ms = compatibilityModule(p);
        assertNotNull(ms, "compatibility 模組必須存在");
        assertEquals(ModuleStatus.FAILED, ms.status());
        assertTrue(ms.errorCode().isPresent());
        assertEquals("ACELIB-PLAT-009", ms.errorCode().get());
        assertTrue(ms.detail().contains("INCOMPATIBLE"));
    }

    // ---------------------------------------------------------------------
    // UNVERIFIED enable → ready + diagnostics READY + warning
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("unverified enable：plugin ready 且 diagnostics READY，並輸出 PLAT-009 warning")
    void unverifiedEnable_readyWithWarning() {
        List<LogRecord> logs = capture(() -> {
            AceLibPlugin p = freshEnabledWith(
                CompatibilityStatus.unverified("forced unverified", "fp-summary"));
            assertTrue(p.isReady(), "UNVERIFIED 仍應 ready");
            ModuleState ms = compatibilityModule(p);
            assertNotNull(ms);
            assertEquals(ModuleStatus.READY, ms.status());
            assertTrue(ms.detail().contains("UNVERIFIED"));
        });
        boolean warned = logs.stream().anyMatch(r ->
            r.getLevel() == Level.WARNING && r.getMessage() != null && r.getMessage().contains("ACELIB-PLAT-009"));
        assertTrue(warned, "UNVERIFIED 必須輸出含 ACELIB-PLAT-009 的 warning");
    }

    // ---------------------------------------------------------------------
    // reload 後 profile 改變 → 重新發佈 compatibility 模組
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("reload：override 由 SUPPORTED 改為 UNVERIFIED 後，diagnostics 反映新 profile 且仍 ready")
    void reload_republishesCompatibilityProfile() {
        AceLibPlugin p = freshEnabledWith(CompatibilityStatus.supported("fp-1"));
        assertTrue(p.isReady());
        assertEquals(ModuleStatus.READY, compatibilityModule(p).status());
        assertTrue(compatibilityModule(p).detail().contains("SUPPORTED"));

        p.compatibilityOverride = ignored -> CompatibilityStatus.unverified("now unverified", "fp-2");
        boolean ok = p.reload();
        assertTrue(ok, "reload 在 UNVERIFIED 時應成功");
        assertTrue(p.isReady());
        ModuleState ms = compatibilityModule(p);
        assertEquals(ModuleStatus.READY, ms.status());
        assertTrue(ms.detail().contains("UNVERIFIED"));
    }

    // ---------------------------------------------------------------------
    // reload probe 失敗（INCOMPATIBLE）→ 回傳 false + 降級 FAILED
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("reload：override 改為 INCOMPATIBLE → reload 回傳 false、runtime 資源停用、plugin 降級 FAILED")
    void reload_incompatible_downgradesToFailed() {
        AceLibPlugin p = freshEnabledWith(CompatibilityStatus.supported("fp-1"));
        assertTrue(p.isReady());

        // 快照「本 plugin 在 reload 前註冊的 listener 實例」。Bukkit HandlerList 為靜態結構，
        // 跨測試可能殘留其他 plugin 實例的 listener；只記錄 getPlugin() == p 的實例，
        // 使後續斷言對殘留污染免疫，同時仍能抓到本 plugin listener 未解除的回歸。
        java.util.Set<Object> beforeListeners = new java.util.HashSet<>();
        for (org.bukkit.plugin.RegisteredListener rl : HandlerList.getRegisteredListeners(p)) {
            if (rl.getPlugin() == p) {
                beforeListeners.add(rl.getListener());
            }
        }
        assertFalse(beforeListeners.isEmpty(),
            "reload 前本 plugin 必須已註冊 listener（registerListenersForTest seam 必須生效）");

        // 先取得 provider reference，模擬「已持有 provider 的呼叫端」
        AceLibApi.AceLibProvider held = server.getServicesManager().load(AceLibApi.AceLibProvider.class);
        assertNotNull(held, "enable 後必須可取得 provider");
        assertTrue(held.api().isReady(), "reload 前 provider 必須是 ready facade");

        p.compatibilityOverride = ignored -> CompatibilityStatus.incompatible("now incompatible", "fp-2");
        boolean ok = p.reload();
        assertFalse(ok, "reload 在 INCOMPATIBLE 時應回傳 false");
        assertFalse(p.isReady(), "reload 後 plugin 應 not ready");
        ModuleState ms = compatibilityModule(p);
        assertEquals(ModuleStatus.FAILED, ms.status());
        assertEquals("ACELIB-PLAT-009", ms.errorCode().orElse(""));
        // 對外 provider registration 必須已解除（新呼叫端取得 null）
        assertNull(server.getServicesManager().getRegistration(AceLibApi.AceLibProvider.class),
            "INCOMPATIBLE reload 後 ServicesManager 不得有 provider registration");
        // 已持有 provider 的呼叫端必須讀到 shutdown 語意
        assertFalse(held.api().isReady(), "已持有 provider 必須讀到 shutdown 語意");
        assertSame(held.api(), p.getApi(), "已持有 provider 與 plugin.getApi() 必須同為 shutdown facade");
        // runtime 資源必須已停用：舊 scheduler 已 onPluginDisable()
        assertTrue(p.getSchedulerForDiagnostics().isDisabled(),
            "INCOMPATIBLE reload 後舊 scheduler 必須已停用（onPluginDisable）");
        // 管理指令框架必須已解除：Bukkit 的 PluginCommand.setExecutor(null) 會把 executor
        // 回退為 owning plugin（即 AceLib 本身），因此「已解除」的正確可觀察狀態是 executor
        // 不再是 BukkitCommandBridge（dispatch 不再進入 AceLib dispatcher）。
        org.bukkit.command.CommandExecutor exec = p.getCommand("acelib").getExecutor();
        assertFalse(exec instanceof com.smile.acelib.command.BukkitCommandBridge,
            "INCOMPATIBLE reload 後 /acelib 指令 executor 必須不再是 BukkitCommandBridge（已解除派送）");
        // 既有 listener 必須已解除（player lifecycle + gui listener 皆 unregister）。
        // 只檢查 reload 前快照中的本 plugin listener 實例是否仍存在；對跨測試的靜態
        // HandlerList 殘留（屬其他 plugin 實例）免疫，且仍能抓到本 plugin listener 未解除的回歸。
        java.util.Set<Object> afterListeners = new java.util.HashSet<>();
        for (org.bukkit.plugin.RegisteredListener rl : HandlerList.getRegisteredListeners(p)) {
            if (rl.getPlugin() == p) {
                afterListeners.add(rl.getListener());
            }
        }
        for (Object l : beforeListeners) {
            assertFalse(afterListeners.contains(l),
                "INCOMPATIBLE reload 後本 plugin 註冊的 listener 必須已全部解除；仍殘留: " + l);
        }
    }

    @Test
    @DisplayName("reload：INCOMPATIBLE 且 teardown 拋錯 → 仍降級 FAILED 且不拋未捕捉例外")
    void reload_incompatible_teardownFailure_stillDowngradesWithoutThrowing() {
        AceLibPlugin p = freshEnabledWith(CompatibilityStatus.supported("fp-1"));
        assertTrue(p.isReady());

        // 注入受控失敗：舊 scheduler teardown hook 拋錯（與 Phase A 共用同一 seam）
        p.reloadOldTeardownFailureHook = () -> {
            throw new RuntimeException("simulated teardown failure");
        };
        p.compatibilityOverride = ignored -> CompatibilityStatus.incompatible("now incompatible", "fp-2");

        boolean ok = p.reload();
        assertFalse(ok, "teardown 失敗仍應回傳 false");
        assertFalse(p.isReady(), "teardown 失敗仍應 not ready");
        ModuleState ms = compatibilityModule(p);
        assertEquals(ModuleStatus.FAILED, ms.status());
        assertEquals("ACELIB-PLAT-009", ms.errorCode().orElse(""));
        // teardown 即便 hook 失敗，scheduler 仍應盡力停用（onPluginDisable 在 hook 前已執行）
        assertTrue(p.getSchedulerForDiagnostics().isDisabled());
    }

    // ---------------------------------------------------------------------
    // disable 後 profile 清除
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("disable：compatibility 模組應被解除註冊")
    void disable_clearsCompatibilityProfile() {
        AceLibPlugin p = freshEnabledWith(CompatibilityStatus.supported("fp-1"));
        assertNotNull(compatibilityModule(p), "enable 後 compatibility 模組應存在");

        p.onDisable();
        assertNull(compatibilityModule(p),
            "disable 後 compatibility 模組應被解除註冊（避免殘留過期 profile）");
    }

    // ---------------------------------------------------------------------
    // INCOMPATIBLE enable → onDisable 仍須清除 compatibility 模組狀態
    // ---------------------------------------------------------------------
    @Test
    @DisplayName("disable：INCOMPATIBLE enable 後 onDisable 仍清除 compatibility 模組狀態（不殘留 FAILED 假象）")
    void disable_afterIncompatibleEnable_clearsCompatibilityState() {
        AceLibPlugin p = freshEnabledWith(
            CompatibilityStatus.incompatible("forced incompatible", "fp-summary"));
        assertFalse(p.isReady(), "INCOMPATIBLE 必須 not ready");
        assertNotNull(compatibilityModule(p), "enable 後 compatibility 模組應存在（FAILED）");

        p.onDisable();
        assertNull(compatibilityModule(p),
            "INCOMPATIBLE enable 後 onDisable 仍應解除 compatibility 模組註冊，"
                + "避免 diagnostics 殘留 READY/FAILED 假象");
    }
}
