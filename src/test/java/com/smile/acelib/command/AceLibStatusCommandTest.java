package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.AceLibPlugin;
import com.smile.acelib.platform.PlatformDetector;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 驗證 {@code /acelib status} 經實際 plugin lifecycle 可被 dispatch，並覆蓋
 * 權限、tab completion、disable / reload cleanup、以及「不暴露 mutable
 * 內部」契約（Plan §二十五 #7 / #11）。
 *
 * <p>v1.4.0 起 {@code /acelib} 改經 Brigadier 註冊（不再需要
 * {@code plugin.yml} 的 {@code commands} 宣告）；MockBukkit 不觸發平台
 * 命令同步事件，故本測試經 plugin 內部 {@link CommandRegistry} 直接
 * dispatch（與 Brigadier 執行委派同一入口），結構覆蓋另見
 * {@code TypedCommandTreeTest}。</p>
 *
 * <p>測試沿用 {@code AceLibPluginTest} 的 {@code loadPlugin + 手動 onEnable}
 * 模式，避免 MockBukkit 自動 enable 走 plugin classloader 而撞到
 * {@code PlatformDetector} 的 classpath reflection。</p>
 *
 * <p>每個測試都先 mock / loadPlugin / onEnable，結束 unmock；不共用 plugin
 * 狀態以避免 reload 後 singleton 殘留影響下一個測試。</p>
 */
@DisplayName("AceLib /acelib status command")
class AceLibStatusCommandTest {

    private static final String PERMISSION_ADMIN = "acelib.admin";
    private static final String COMMAND_NAME = "acelib";

    private ServerMock server;
    private AceLibPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        // 手動 onEnable（使用測試 classloader 建構 detector，繞過 plugin
        // classloader 的 MockBukkit "No jar file selected" NPE）
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
        // 標記 plugin 為 enabled
        server.getPluginManager().enablePlugin(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---------------------------------------------------------------------
    // 1. Brigadier 註冊＋內部 registry
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("onEnable 後內部 registry 持有 acelib 根指令（含 alib 別名）")
    void onEnable_acelibCommandIsRegistered() {
        CommandRegistry registry = plugin.getCommandRegistry();
        assertNotNull(registry,
            "onEnable 須建立管理指令內部 registry（BrigadierRegistrar 已註冊）");
        assertNotNull(registry.findCommand(COMMAND_NAME),
            "內部 registry 須持有 '" + COMMAND_NAME + "' 根指令");
        assertNotNull(registry.findCommand("alib"),
            "根指令別名 'alib' 須可查（沿用 plugin.yml 時代的別名）");
        // plugin.yml 不再宣告 commands：Bukkit 端無 PluginCommand，
        // 派送唯一入口為 Brigadier／內部 registry。
        assertNull(plugin.getCommand(COMMAND_NAME),
            "plugin.yml 已移除 commands 宣告；Bukkit.getCommand 應回 null");
    }

    @Test
    @DisplayName("plugin.yml 必須宣告 acelib.admin 權限節點")
    void pluginYmlDeclaresAcelibAdminPermission() {
        PluginDescriptionFile desc = plugin.getDescription();
        assertNotNull(desc, "PluginDescriptionFile 不可為 null");
        boolean hasAdmin = desc.getPermissions().stream()
            .map(Permission::getName)
            .anyMatch(PERMISSION_ADMIN::equals);
        assertTrue(hasAdmin,
            "plugin.yml 必須宣告 '" + PERMISSION_ADMIN + "' 權限節點；目前 permissions: "
                + desc.getPermissions().stream().map(Permission::getName).toList());
    }

    // ---------------------------------------------------------------------
    // 2. console 觸發 → 報告含版本/平台/ready/模組
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("console 觸發 /acelib status → 收到 status 報告（version / platform / ready / modules）")
    void consoleStatus_returnsFullReport() {
        CapturingHandler handler = installLogCapture();
        try {
            ConsoleCommandSender console = server.getConsoleSender();
            dispatch(console, COMMAND_NAME, List.of("status"));
            String out = captureConsoleOutput(handler);
            assertTrue(out.contains("Version:"),
                "status 報告必須含 Version: 行；實際: " + out);
            assertTrue(out.contains("Platform:"),
                "status 報告必須含 Platform: 行；實際: " + out);
            assertTrue(out.contains("Ready: true"),
                "status 報告必須含 Ready: true；實際: " + out);
            assertTrue(out.contains("Modules:"),
                "status 報告必須含 Modules: 區塊；實際: " + out);
            assertTrue(out.contains("scheduler"),
                "status 報告必須列出核心模組（scheduler 等）；實際: " + out);
        } finally {
            handler.close();
        }
    }

    // ---------------------------------------------------------------------
    // 3. 權限檢查
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("無權限玩家觸發 /acelib status → 收到 NO_PERMISSION 訊息")
    void playerNoPermission_rejected() {
        Player player = server.addPlayer();
        // MockBukkit 4.x 預設玩家無 acelib.admin；明確不授予
        dispatch(player, COMMAND_NAME, List.of("status"));
        // 玩家訊息走 region backend，下一 tick 送出
        server.getScheduler().performTicks(1L);
        String sent = nextSentMessage(player);
        assertNotNull(sent, "玩家應收到 NO_PERMISSION 訊息");
        // dispatcher 的 NO_PERMISSION（ACELIB-CMD-003）由 registry 統一發出。
        assertTrue(sent.contains("permission"),
            "玩家訊息應為 NO_PERMISSION；實際: " + sent);
    }

    @Test
    @DisplayName("授予 acelib.admin 權限的玩家 → 收到 status 報告")
    void playerWithPermission_receivesStatus() {
        Player player = server.addPlayer();
        player.addAttachment(plugin, PERMISSION_ADMIN, true);
        CapturingHandler handler = installLogCapture();
        try {
            dispatch(player, COMMAND_NAME, List.of("status"));
            server.getScheduler().performTicks(1L);
            String sent = nextSentMessage(player);
            String logs = captureConsoleOutput(handler);
            assertNotNull(sent,
                "有權限玩家應收到 status 報告。dispatch logs: " + logs);
            assertTrue(sent.contains("Version:"),
                "status 報告必須含 Version: 行；實際: " + sent);
            assertTrue(sent.contains("Modules:"),
                "status 報告必須含 Modules: 區塊；實際: " + sent);
        } finally {
            handler.close();
        }
    }

    // ---------------------------------------------------------------------
    // 4. Tab completion
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("有權限 sender tab complete → 結果包含 'status' 子指令")
    void tabComplete_includesStatus() {
        Player player = server.addPlayer();
        player.addAttachment(plugin, PERMISSION_ADMIN, true);
        CommandRegistry registry = plugin.getCommandRegistry();
        assertNotNull(registry);
        List<String> result = registry.tabComplete(
            new BukkitSender(player), COMMAND_NAME, List.of(""));
        assertNotNull(result, "tab complete 不可回 null");
        assertTrue(result.contains("status"),
            "tab complete 結果必須包含 'status'；實際: " + result);
    }

    // ---------------------------------------------------------------------
    // 5. Disable / Reload 清理
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("onDisable 後內部 registry 已清空並標記 disabled（派送拒絕）")
    void onDisable_registryDisabled() {
        CommandRegistry before = plugin.getCommandRegistry();
        assertNotNull(before, "onEnable 後 registry 必須非 null");
        plugin.onDisable();
        assertNull(plugin.getCommandRegistry(),
            "onDisable 後 getCommandRegistry 應回 null（reference 已解除）");
        CapturingHandler handler = installLogCapture();
        try {
            // 殘留 reference 的 dispatch 必須被 REGISTRY_DISABLED 擋下
            before.dispatch(new BukkitSender(server.getConsoleSender()),
                COMMAND_NAME, List.of("status"));
            String out = captureConsoleOutput(handler);
            assertTrue(out.contains("ACELIB-CMD-009"),
                "disable 後殘留 dispatch 應回 ACELIB-CMD-009；實際: " + out);
        } finally {
            handler.close();
        }
    }

    @Test
    @DisplayName("reload 後 /acelib status 仍可 dispatch 且報告仍含 ready（不重複註冊）")
    void reload_commandStillWorks() {
        assertTrue(plugin.reload(), "reload 應成功");
        CommandRegistry after = plugin.getCommandRegistry();
        assertNotNull(after, "reload 後 registry 仍須存在（不重建、不重複註冊）");
        CapturingHandler handler = installLogCapture();
        try {
            dispatch(server.getConsoleSender(), COMMAND_NAME, List.of("status"));
            String out = captureConsoleOutput(handler);
            assertTrue(out.contains("Version:"),
                "reload 後 status 仍須輸出報告；實際: " + out);
            assertTrue(out.contains("Ready:"),
                "reload 後報告仍須含 Ready: 行；實際: " + out);
        } finally {
            handler.close();
        }
    }

    // ---------------------------------------------------------------------
    // 6. 不暴露 mutable 內部
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("status 報告不可暴露 mutable internals（scheduler 實例 reference / 內部 class hash）")
    void statusOutput_doesNotLeakMutableInternals() {
        CapturingHandler handler = installLogCapture();
        try {
            dispatch(server.getConsoleSender(), COMMAND_NAME, List.of("status"));
            String out = captureConsoleOutput(handler);
            // DiagnosticReport 為 immutable snapshot，格式器不應輸出 Java 物件 reference
            assertFalse(out.contains("SafeSchedulerImpl@"),
                "status 報告不應暴露 SafeSchedulerImpl reference；實際: " + out);
            assertFalse(out.contains("AceLibPlugin@"),
                "status 報告不應暴露 AceLibPlugin reference；實際: " + out);
            assertFalse(out.contains("@" + Integer.toHexString(plugin.hashCode())),
                "status 報告不應暴露 plugin hash code；實際: " + out);
        } finally {
            handler.close();
        }
    }

    // ---------------------------------------------------------------------
    // 7. /acelib (no args) → main help
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("/acelib 無 args → console 收到 help（依權限過濾子指令）")
    void emptyArgs_showsMainHelp() {
        CapturingHandler handler = installLogCapture();
        try {
            dispatch(server.getConsoleSender(), COMMAND_NAME, List.of());
            String out = captureConsoleOutput(handler);
            assertTrue(out.contains("acelib"),
                "help 應包含主指令名；實際: " + out);
            assertTrue(out.contains("status"),
                "help 應列出 status 子指令；實際: " + out);
        } finally {
            handler.close();
        }
    }

    // ---------------------------------------------------------------------
    // 工具（與 CommandRegistryBukkitTest 同形，刻意複製避免跨 class 依賴）
    // ---------------------------------------------------------------------

    private void dispatch(org.bukkit.command.CommandSender sender,
                          String label, List<String> args) {
        CommandRegistry registry = plugin.getCommandRegistry();
        assertNotNull(registry, "dispatch 時 registry 不可為 null");
        registry.dispatch(new BukkitSender(sender), label, args);
    }

    private static String nextSentMessage(Player player) {
        org.mockbukkit.mockbukkit.command.MessageTarget target =
            () -> ((org.mockbukkit.mockbukkit.entity.PlayerMock) player).nextComponentMessage();
        return target.nextMessage();
    }

    private static CapturingHandler installLogCapture() {
        Logger logger = Logger.getLogger("AceLib");
        for (Handler existing : logger.getHandlers()) {
            if (existing instanceof CapturingHandler ch) {
                logger.removeHandler(ch);
            }
        }
        CapturingHandler handler = new CapturingHandler();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        return handler;
    }

    private static String captureConsoleOutput(CapturingHandler handler) {
        StringBuilder sb = new StringBuilder();
        for (LogRecord record : handler.records) {
            String msg = record.getMessage();
            Object[] params = record.getParameters();
            if (params != null && params.length > 0) {
                try {
                    msg = java.text.MessageFormat.format(msg, params);
                } catch (Throwable ignored) {
                    // keep pattern as-is
                }
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(msg);
        }
        return sb.toString();
    }

    static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override public void flush() { }
        @Override public void close() {
            Logger.getLogger("AceLib").removeHandler(this);
        }
    }
}
