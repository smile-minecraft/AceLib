package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.context.OperationType;
import com.smile.acelib.context.SafeExecutor;
import com.smile.acelib.gui.GuiArgument;
import com.smile.acelib.gui.GuiErrorCode;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import com.smile.acelib.player.PlayerDataService;
import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.scheduler.ScheduledTask;
import com.smile.acelib.world.LocationSnapshot;
import com.smile.acelib.world.WorldErrorCode;
import com.smile.acelib.world.WorldService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * reload 成功路徑的服務釋放與在線玩家重接測試。
 *
 * <p>根因：成功 reload 只覆寫 {@code worldService}/{@code guiService} 欄位，
 * 未 unregister 舊 GUI listener、未 shutdown 舊 impl；player 服務重建後 registry
 * 為空且在線玩家未重接；自建 player io executor 每次新建永不關閉。</p>
 */
@DisplayName("reload 服務釋放與在線玩家重接")
class ReloadServiceReleaseTest {

    private ServerMock server;
    private AceLibPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new com.smile.acelib.platform.PlatformDetector(
            getClass().getClassLoader()));
        // 經由真實 enable 路徑註冊 listener（production 行為：onPluginReady
        // 呼叫 registerEvents）。onEnable 冪等，第二次進入直接 skip，
        // 只完成標記 enabled + listener 註冊（與 PlayerLifecycleTest 同一模式）。
        server.getPluginManager().enablePlugin(plugin);
    }

    @AfterEach
    void tearDown() {
        if (plugin != null && plugin.isReady()) {
            plugin.onDisable();
        }
        MockBukkit.unmock();
    }

    // -----------------------------------------------------------------
    // 小工具
    // -----------------------------------------------------------------

    /** 本 plugin 註冊的 listener 實例集合（對跨測試的靜態 HandlerList 殘留免疫）。 */
    private static Set<Object> pluginListeners(AceLibPlugin p) {
        Set<Object> found = new HashSet<>();
        for (org.bukkit.plugin.RegisteredListener rl : HandlerList.getRegisteredListeners(p)) {
            if (rl.getPlugin() == p) {
                found.add(rl.getListener());
            }
        }
        return found;
    }

    private static void waitForReady(PlayerDataService svc, UUID uuid) {
        long deadline = System.currentTimeMillis() + 5000L;
        while ((svc.getSession(uuid).isEmpty()
                || !svc.getSession(uuid).get().isReady())
            && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(svc.getSession(uuid).isPresent()
            && svc.getSession(uuid).get().isReady(), "session 必須在時限內 READY");
    }

    /** 指定 listener 實例在本 plugin 名下的註冊條目數（同一實例可註冊多個事件）。 */
    private static long countRegistrations(AceLibPlugin p, Object listener) {
        long count = 0;
        for (org.bukkit.plugin.RegisteredListener rl : HandlerList.getRegisteredListeners(p)) {
            if (rl.getPlugin() == p && rl.getListener() == listener) {
                count++;
            }
        }
        return count;
    }

    private static int livePlayerIoThreads() {
        Thread[] all = new Thread[Thread.activeCount() * 2 + 16];
        int n = Thread.enumerate(all);
        int live = 0;
        for (int i = 0; i < n; i++) {
            Thread t = all[i];
            if (t != null && t.isAlive() && t.getName().startsWith("acelib-player-io-")) {
                live++;
            }
        }
        return live;
    }

    private static int settlePlayerIoThreads(int expectAtMost, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int live;
        do {
            live = livePlayerIoThreads();
            if (live <= expectAtMost) {
                break;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (System.currentTimeMillis() < deadline);
        return livePlayerIoThreads();
    }

    // -----------------------------------------------------------------
    // (1) GUI listener 不得殘留
    // -----------------------------------------------------------------

    @Test
    @DisplayName("成功 reload 後 GUI listener 只保留新的一個（舊的已解除）")
    void reload_oldGuiListenerUnregistered_onlyNewRemains() {
        GuiService before = plugin.getApi().getGuiService();
        Object oldGuiListener = before.getListener();
        Set<Object> beforeSet = pluginListeners(plugin);
        assertTrue(beforeSet.contains(oldGuiListener), "前置：舊 GUI listener 必須已註冊");

        assertTrue(plugin.reload(), "reload 必須成功");
        // production onPluginReady 已為新 service 重新註冊 listener（enable 路徑）。

        GuiService after = plugin.getApi().getGuiService();
        Object newGuiListener = after.getListener();
        Set<Object> afterSet = pluginListeners(plugin);
        assertEquals(beforeSet.size(), afterSet.size(),
            "reload 前後 listener 實例數必須一致（舊 GUI listener 未解除會多一個）");
        assertFalse(afterSet.contains(oldGuiListener), "舊 GUI listener 必須已解除註冊");
        assertTrue(afterSet.contains(newGuiListener), "新 GUI listener 必須已註冊");
    }

    // -----------------------------------------------------------------
    // (2) 舊 GUI service 不可用、新 READY
    // -----------------------------------------------------------------

    @Test
    @DisplayName("成功 reload 後舊 GuiService 不可用、新為 READY")
    void reload_oldGuiServiceShutdown_newReady() {
        GuiService before = plugin.getApi().getGuiService();
        assertEquals("READY", before.getModuleStatus(), "前置：舊 GUI service 必須 READY");

        assertTrue(plugin.reload(), "reload 必須成功");

        assertEquals("FAILED", before.getModuleStatus(),
            "舊 GuiService reload 後必須已 shutdown");
        assertEquals(GuiErrorCode.SHUTDOWN,
            before.openInventory(GuiArgument.of(UUID.randomUUID(), "Old", 9, List.of()))
                .errorCode(),
            "舊 GuiService 的 openInventory 必須回 SHUTDOWN");
        GuiService after = plugin.getApi().getGuiService();
        assertNotSame(before, after, "reload 必須重建 GUI service");
        assertEquals("READY", after.getModuleStatus(), "新 GUI service 必須 READY");
    }

    // -----------------------------------------------------------------
    // (3) 舊 World service 不可用、新 READY
    // -----------------------------------------------------------------

    @Test
    @DisplayName("成功 reload 後舊 WorldService 不可用、新為 READY")
    void reload_oldWorldServiceShutdown_newReady() {
        WorldService before = plugin.getApi().getWorldService();
        assertEquals("READY", before.getModuleStatus(), "前置：舊 world service 必須 READY");

        assertTrue(plugin.reload(), "reload 必須成功");

        assertEquals("FAILED", before.getModuleStatus(),
            "舊 WorldService reload 後必須已 shutdown");
        assertEquals(WorldErrorCode.SHUTDOWN,
            before.readBlock(LocationSnapshot.of(UUID.randomUUID(), 0, 0, 0)).errorCode(),
            "舊 WorldService 的 readBlock 必須回 SHUTDOWN");
        WorldService after = plugin.getApi().getWorldService();
        assertNotSame(before, after, "reload 必須重建 world service");
        assertEquals("READY", after.getModuleStatus(), "新 world service 必須 READY");
    }

    // -----------------------------------------------------------------
    // (4) 在線玩家重接
    // -----------------------------------------------------------------

    @Test
    @DisplayName("成功 reload 後在線玩家已重接：getData/markDirty/quit 皆可用")
    void reload_onlinePlayerRejoined_dataQuitUsable() {
        PlayerDataService old = plugin.getPlayerDataService();
        PlayerMock player = server.addPlayer();
        UUID uuid = player.getUniqueId();
        waitForReady(old, uuid);
        old.getData(uuid).ifPresent(rec -> {
            rec.set("reload-persisted", "must-survive");
            old.markDirty(uuid);
        });

        assertTrue(plugin.reload(), "reload 必須成功");
        assertTrue(old.isShutdown(), "舊 player service 必須 shutdown");

        PlayerDataService current = plugin.getPlayerDataService();
        assertNotSame(old, current, "reload 必須重建 player service");

        // getData 可取得（含 shutdown 前 flush 的持久化資料）
        Optional<com.smile.acelib.data.Record> data = Optional.empty();
        long deadline = System.currentTimeMillis() + 5000L;
        while (data.isEmpty() && System.currentTimeMillis() < deadline) {
            data = current.getData(uuid);
            if (data.isEmpty()) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        assertTrue(data.isPresent(), "在線玩家 reload 後 getData 必須可取得（已重接）");
        assertEquals("must-survive", data.get().get("reload-persisted"),
            "重接後資料必須含 shutdown 前 flush 的內容");

        // markDirty 不得拋 PLAYER-005
        current.markDirty(uuid);

        // quit 可用：經事件離線後 session 移除，且不拋非同步事件例外
        server.getPluginManager().callEvent(new PlayerQuitEvent(player, Component.empty(),
            PlayerQuitEvent.QuitReason.DISCONNECTED));
        long quitDeadline = System.currentTimeMillis() + 5000L;
        while (current.getSession(uuid).isPresent()
            && System.currentTimeMillis() < quitDeadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertFalse(current.getSession(uuid).isPresent(), "quit 事件後 session 必須移除");
    }

    // -----------------------------------------------------------------
    // (4b) 重接全卡住：共用總時限有界 + 逐一警告 + reload 照常成功
    // -----------------------------------------------------------------

    @Test
    @DisplayName("重接全卡住時等待以共用總時限為界：逾時各記警告且 reload 成功")
    void reload_rejoinAllStuck_boundedBySharedDeadline() {
        final int stuckPlayers = 4;
        final long totalBudgetMs = 500L;
        List<PlayerMock> mocks = new ArrayList<>();
        for (int i = 0; i < stuckPlayers; i++) {
            mocks.add(server.addPlayer());
        }
        List<UUID> uuids = mocks.stream().map(PlayerMock::getUniqueId).toList();

        // 全卡住的載入 future：永不完成，模擬所有 join 同時等不到載入。
        List<CompletableFuture<Void>> stuck = new ArrayList<>();
        for (int i = 0; i < stuckPlayers; i++) {
            stuck.add(new CompletableFuture<>());
        }
        AtomicInteger next = new AtomicInteger(0);
        plugin.reloadRejoinJoinOverride = (uuid, name) ->
            stuck.get(next.getAndIncrement() % stuck.size());
        plugin.reloadRejoinTimeoutMsOverride = totalBudgetMs;

        List<LogRecord> warnings = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record);
            }

            @Override
            public void flush() {
                // 測試不需要 flush
            }

            @Override
            public void close() {
                // 測試不需要 close
            }
        };
        Logger pluginLogger = plugin.getLogger();
        Logger fallbackLogger = Logger.getLogger("AceLib");
        pluginLogger.addHandler(handler);
        fallbackLogger.addHandler(handler);
        long startNanos = System.nanoTime();
        boolean reloaded;
        try {
            reloaded = plugin.reload();
        } finally {
            plugin.reloadRejoinJoinOverride = null;
            plugin.reloadRejoinTimeoutMsOverride = -1L;
            pluginLogger.removeHandler(handler);
            fallbackLogger.removeHandler(handler);
            for (CompletableFuture<Void> future : stuck) {
                future.completeExceptionally(
                    new IllegalStateException("test stuck rejoin released"));
            }
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(reloaded, "重接全卡住時 reload 仍須成功（fail-open）");
        // 逐一等待的最壞情況是人數 × 總時限；共用總時限必須遠小於它。
        assertTrue(elapsedMs < stuckPlayers * totalBudgetMs,
            "重接等待必須以共用總時限為界：上限=" + totalBudgetMs + "ms，"
                + stuckPlayers + " 人逐一等待最壞 "
                + (stuckPlayers * totalBudgetMs) + "ms，實際=" + elapsedMs + "ms");
        // 每位逾時玩家各一則 ACELIB-PLAYER 警告，且警告可對應到該玩家。
        for (UUID uuid : uuids) {
            long matching = warnings.stream()
                .filter(r -> Level.WARNING.equals(r.getLevel()))
                .map(r -> String.valueOf(r.getMessage()))
                .filter(msg -> msg.contains("ACELIB-PLAYER") && msg.contains(uuid.toString()))
                .count();
            assertTrue(matching >= 1,
                "逾時玩家 " + uuid + " 必須各記一則 ACELIB-PLAYER 警告");
        }
    }

    // -----------------------------------------------------------------
    // (5) 自建 io executor 收斂
    // -----------------------------------------------------------------

    @Test
    @DisplayName("成功 reload 後舊自建 io executor 已關閉")
    void reload_oldPlayerIoExecutorShutdown() {
        PlayerMock player = server.addPlayer();
        waitForReady(plugin.getPlayerDataService(), player.getUniqueId());
        ExecutorService before = plugin.getPlayerIoExecutor();
        assertNotEquals(null, before, "前置：自建 io executor 必須存在");

        assertTrue(plugin.reload(), "reload 必須成功");

        assertTrue(before.isShutdown(), "舊自建 io executor reload 後必須已關閉");
        assertNotSame(before, plugin.getPlayerIoExecutor(), "reload 必須重建 io executor");
    }

    @Test
    @DisplayName("連續 reload 後自建 io pool 的 live threads 不成長")
    void reload_consecutiveReloads_ioThreadsDoNotGrow() {
        // 每一代 pool 都強制做一次 join/load，使洩漏（若有）表現為常駐 thread；
        // 以增量斷言對跨測試類別的執行緒污染免疫。
        PlayerMock first = server.addPlayer();
        waitForReady(plugin.getPlayerDataService(), first.getUniqueId());
        int baseline = livePlayerIoThreads();

        for (int i = 0; i < 3; i++) {
            assertTrue(plugin.reload(), "第 " + (i + 1) + " 次 reload 必須成功");
            PlayerMock worker = server.addPlayer();
            waitForReady(plugin.getPlayerDataService(), worker.getUniqueId());
            server.getPluginManager().callEvent(new PlayerQuitEvent(worker, Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED));
            // 真正離線（否則 online 清單無限成長，後續重接併發數跟著膨脹）。
            worker.disconnect();
        }

        int live = settlePlayerIoThreads(baseline + 1, 10_000L);
        assertTrue(live <= baseline + 1,
            "連續 reload 後自建 io pool threads 不得成長：基準=" + baseline + "，實際=" + live);
    }

    // -----------------------------------------------------------------
    // (6) SafeExecutor 跟隨新 canonical scheduler + 空窗期 fail-closed
    // -----------------------------------------------------------------

    @Test
    @DisplayName("成功 reload 後派送走新 canonical scheduler（舊已停用）")
    void reload_dispatchFollowsNewCanonicalScheduler() {
        SafeSchedulerImpl oldScheduler = plugin.getSchedulerForDiagnostics();

        assertTrue(plugin.reload(), "reload 必須成功");

        SafeSchedulerImpl current = plugin.getSchedulerForDiagnostics();
        assertNotSame(oldScheduler, current, "reload 必須換綁 canonical scheduler");
        assertTrue(oldScheduler.isDisabled(), "舊 scheduler 必須已停用");

        AtomicBoolean ran = new AtomicBoolean(false);
        ScheduledTask task = SafeExecutor.executeAsync(plugin, Platform.PAPER,
            PlatformCapability.forPlatform(Platform.PAPER), OperationType.READ_ONLY,
            () -> ran.set(true));
        assertFalse(task.isCancelled(), "新 scheduler 上的派送不得為 cancelled");
        long deadline = System.currentTimeMillis() + 5000L;
        while (!ran.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(ran.get(), "新 scheduler 必須實際執行派送");
    }

    @Test
    @DisplayName("reload 空窗期派送為 SCHED-006 fail-closed（Phase A 至 commit 之間）")
    void reload_windowDispatch_failClosedSched006() throws Exception {
        SafeSchedulerImpl oldScheduler = plugin.getSchedulerForDiagnostics();
        WorldService oldWorld = plugin.getApi().getWorldService();
        GuiService oldGui = plugin.getApi().getGuiService();
        PlayerDataService oldPlayer = plugin.getPlayerDataService();
        Object oldGuiListener = oldGui.getListener();
        Set<Object> beforeSet = pluginListeners(plugin);

        AtomicReference<SafeSchedulerImpl> windowScheduler = new AtomicReference<>();
        AtomicBoolean windowCancelled = new AtomicBoolean(false);
        AtomicBoolean windowRan = new AtomicBoolean(false);
        plugin.reloadRebindFailureHook = () -> {
            // 此 hook 執行於 Phase C commit 前：this.scheduler 仍為舊（已 disabled）。
            windowScheduler.set(plugin.getSchedulerForDiagnostics());
            ScheduledTask task = SafeExecutor.executeAsync(plugin, Platform.PAPER,
                PlatformCapability.forPlatform(Platform.PAPER), OperationType.READ_ONLY,
                () -> windowRan.set(true));
            windowCancelled.set(task.isCancelled());
            throw new IllegalStateException("injected: reload failure after window probe");
        };
        try {
            assertFalse(plugin.reload(), "注入失敗時 reload 必須回傳 false");
        } finally {
            plugin.reloadRebindFailureHook = null;
        }

        assertSame(oldScheduler, windowScheduler.get(), "空窗期解析必須仍為舊 scheduler");
        assertTrue(oldScheduler.isDisabled(), "空窗期舊 scheduler 必須已停用");
        assertTrue(windowCancelled.get(), "空窗期派送必須為 cancelled no-op");
        Thread.sleep(300L);
        assertFalse(windowRan.get(), "空窗期 runnable 不可執行");
        assertTrue(oldScheduler.getRecorder().contains("ACELIB-SCHED-006"),
            "空窗期派送必須記 ACELIB-SCHED-006");

        // rollback 後舊服務狀態可判斷：world/gui 仍 READY、player 同一 reference 可用。
        assertEquals("READY", oldWorld.getModuleStatus(), "rollback 後舊 world 仍 READY");
        assertEquals("READY", oldGui.getModuleStatus(), "rollback 後舊 GUI 仍 READY");
        assertSame(oldPlayer, plugin.getPlayerDataService(), "rollback 不得替換 player service");
        assertFalse(oldPlayer.isShutdown(), "rollback 後舊 player service 仍可用");
        // 不留雙 listener。
        assertEquals(beforeSet, pluginListeners(plugin), "rollback 前後 listener 集合必須一致");
        assertTrue(pluginListeners(plugin).contains(oldGuiListener), "舊 GUI listener 必須仍在");
    }

    // -----------------------------------------------------------------
    // 邊界：player shutdown 失敗 rollback
    // -----------------------------------------------------------------

    @Test
    @DisplayName("player shutdown 失敗 rollback：舊服務狀態可判斷且不留雙 listener")
    void reload_playerShutdownFailure_oldServicesIntactAndSingleListener() {
        WorldService oldWorld = plugin.getApi().getWorldService();
        GuiService oldGui = plugin.getApi().getGuiService();
        PlayerDataService oldPlayer = plugin.getPlayerDataService();
        Object oldGuiListener = oldGui.getListener();
        Set<Object> beforeSet = pluginListeners(plugin);
        long guiRegsBefore = countRegistrations(plugin, oldGuiListener);
        assertTrue(guiRegsBefore > 0, "前置：舊 GUI listener 必須已註冊");

        plugin.reloadPlayerShutdownFailureHook = () -> {
            throw new com.smile.acelib.player.PlayerStateException(
                "ACELIB-PLAYER-008", "injected player shutdown failure");
        };
        try {
            assertFalse(plugin.reload(), "player shutdown 失敗時 reload 必須回傳 false");
        } finally {
            plugin.reloadPlayerShutdownFailureHook = null;
        }

        assertFalse(plugin.isReady(), "partial reload 必須降級");
        assertSame(oldPlayer, plugin.getPlayerDataService(), "不得替換 player service");
        assertFalse(oldPlayer.isShutdown(), "舊 player service 仍可用");
        assertEquals("READY", oldWorld.getModuleStatus(), "舊 world 仍 READY");
        assertEquals("READY", oldGui.getModuleStatus(), "舊 GUI 仍 READY");
        // 此失敗路徑依既有降級語意解除 player listener（ready=false 不再派送），
        // 但 GUI listener 不得受影響：舊實例仍在且註冊數不變（不雙 listener）。
        assertTrue(pluginListeners(plugin).contains(oldGuiListener), "舊 GUI listener 必須仍在");
        assertEquals(guiRegsBefore, countRegistrations(plugin, oldGuiListener),
            "舊 GUI listener 註冊數必須不變（不雙 listener）");
        assertEquals(beforeSet.size() - 1, pluginListeners(plugin).size(),
            "僅 player listener 依降級語意解除，其餘 listener 集合必須一致");
        // 測試收尾：降級路徑不走 onDisable（tearDown 跳過），手動關閉追蹤中的 pool，
        // 避免 daemon threads 污染後續測試的執行緒計數。
        ExecutorService io = plugin.getPlayerIoExecutor();
        if (io != null) {
            io.shutdown();
        }
    }

    // -----------------------------------------------------------------
    // 邊界：reload 前已 quit 的玩家不重建、reload 後新 join 可用
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reload 前已 quit 的玩家不重建；reload 後新 join 經新 listener 建立 session")
    void reload_quitBeforeReload_noResurrection_joinAfterReloadWorks() {
        PlayerDataService old = plugin.getPlayerDataService();
        PlayerMock leaver = server.addPlayer();
        UUID leaverUuid = leaver.getUniqueId();
        waitForReady(old, leaverUuid);
        server.getPluginManager().callEvent(new PlayerQuitEvent(leaver, Component.empty(),
            PlayerQuitEvent.QuitReason.DISCONNECTED));
        long quitDeadline = System.currentTimeMillis() + 5000L;
        while (old.getSession(leaverUuid).isPresent()
            && System.currentTimeMillis() < quitDeadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(old.getSession(leaverUuid).isEmpty(), "前置：quit 後 session 必須移除");
        // MockBukkit 的 callEvent(quit) 不會把玩家移出 online 清單；
        // disconnect 使其真正離線，否則重接（正確地）視其仍在線而重建。
        leaver.disconnect();

        assertTrue(plugin.reload(), "reload 必須成功");

        PlayerDataService current = plugin.getPlayerDataService();
        assertTrue(current.getSession(leaverUuid).isEmpty(),
            "已 quit 的玩家 reload 後不得復活 session");

        PlayerMock joiner = server.addPlayer();
        waitForReady(current, joiner.getUniqueId());
        assertTrue(current.getData(joiner.getUniqueId()).isPresent(),
            "reload 後新 join 的玩家必須經新 listener 建立可用 session");
    }
}
