package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.bukkit.command.PluginCommand;
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
        // 標記 plugin 為 enabled — PluginCommand.execute 會檢查 isEnabled()，
        // 手動 onEnable 不足以讓 Bukkit 接受 dispatch
        server.getPluginManager().enablePlugin(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---------------------------------------------------------------------
    // 1. plugin.yml 宣告 + bridge attach
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("onEnable 後 plugin.getCommand('acelib') 必須非 null（BukkitCommandBridge 已 attach）")
    void onEnable_acelibCommandIsRegistered() {
        PluginCommand cmd = plugin.getCommand(COMMAND_NAME);
        assertNotNull(cmd,
            "plugin.yml 必須宣告 '" + COMMAND_NAME + "' 指令；onEnable 須透過 "
                + "BukkitCommandBridge.attach 設定 executor");
        assertNotNull(cmd.getExecutor(),
            "BukkitCommandBridge 必須在 onEnable 完成 attach（executor 不可為 null）");
        assertNotNull(cmd.getTabCompleter(),
            "BukkitCommandBridge 必須在 onEnable 完成 attach（tabCompleter 不可為 null）");
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
            Bukkit.dispatchCommand(console, COMMAND_NAME + " status");
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
        Bukkit.dispatchCommand(player, COMMAND_NAME + " status");
        // 玩家訊息走 region backend，下一 tick 送出
        server.getScheduler().performTicks(1L);
        String sent = nextSentMessage(player);
        assertNotNull(sent, "玩家應收到 NO_PERMISSION 訊息");
        // plugin.yml 的 permission-message 是「沒有權限執行此指令」；
        // 此訊息由 Bukkit PluginCommand.testPermission 在 dispatch 進入 executor 之前發出，
        // 證明權限檢查正確運作（Plan §二十五 #11「錯誤訊息」契約）。
        assertTrue(sent.contains("沒有權限") || sent.contains("permission"),
            "玩家訊息應為 NO_PERMISSION（中文/英文任一）；實際: " + sent);
    }

    @Test
    @DisplayName("授予 acelib.admin 權限的玩家 → 收到 status 報告")
    void playerWithPermission_receivesStatus() {
        Player player = server.addPlayer();
        player.addAttachment(plugin, PERMISSION_ADMIN, true);
        CapturingHandler handler = installLogCapture();
        try {
            Bukkit.dispatchCommand(player, COMMAND_NAME + " status");
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
        PluginCommand cmd = plugin.getCommand(COMMAND_NAME);
        assertNotNull(cmd);
        List<String> result = cmd.tabComplete(player, COMMAND_NAME, new String[]{});
        assertNotNull(result, "tab complete 不可回 null");
        assertTrue(result.contains("status"),
            "tab complete 結果必須包含 'status'；實際: " + result);
    }

    // ---------------------------------------------------------------------
    // 5. Disable / Reload 清理
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("onDisable 後 PluginCommand 的 executor 不再是 bridge（bridge 已卸載）")
    void onDisable_clearsBridgeFromPluginCommand() {
        // 先確認 onEnable 後 bridge 確實掛上
        PluginCommand beforeDisable = plugin.getCommand(COMMAND_NAME);
        assertNotNull(beforeDisable);
        org.bukkit.command.CommandExecutor beforeExecutor = beforeDisable.getExecutor();
        assertNotNull(beforeExecutor, "onEnable 後 executor 必須非 null");
        // MockBukkit 4.x PluginCommand 建構子預設 executor = owner plugin，
        // 我們的 attach 必須覆蓋；先確認 attach 確實生效（executor 不是 owner）
        assertFalse(beforeExecutor.equals(plugin),
            "onEnable 後 executor 必須已被 bridge 覆蓋（不可仍是 owner plugin）");
        // 然後 onDisable
        plugin.onDisable();
        // 卸載後 executor 必須改變（不再指向 bridge）— MockBukkit 4.x 的
        // PluginCommand.getExecutor() 在 executor 為 null 時 fallback 回傳 owner，
        // 因此這裡只斷言「不等於 bridge」；真實 Bukkit 環境下會是 null。
        org.bukkit.command.CommandExecutor afterExecutor = beforeDisable.getExecutor();
        assertNotNull(afterExecutor, "after onDisable executor 不可為 null（MockBukkit fallback）");
        assertFalse(afterExecutor.equals(beforeExecutor),
            "onDisable 後 executor 必須不再是 bridge（已被卸載）。"
                + "afterExecutor=" + afterExecutor + ", beforeExecutor=" + beforeExecutor);
    }

    @Test
    @DisplayName("reload 後 /acelib status 仍可 dispatch 且報告仍含 ready")
    void reload_commandStillWorks() {
        assertTrue(plugin.reload(), "reload 應成功");
        CapturingHandler handler = installLogCapture();
        try {
            Bukkit.dispatchCommand(server.getConsoleSender(), COMMAND_NAME + " status");
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
            Bukkit.dispatchCommand(server.getConsoleSender(), COMMAND_NAME + " status");
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
            Bukkit.dispatchCommand(server.getConsoleSender(), COMMAND_NAME);
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
