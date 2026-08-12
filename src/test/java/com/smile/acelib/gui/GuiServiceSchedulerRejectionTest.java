package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * Regression tests for scheduler rejection handling：
 * <ol>
 *   <li>{@link PlayerContextExecutor} 派送拒絕（scheduler disabled、player offline、
 *       cancelled no-op task）時，{@link GuiServiceImpl#openInventory} 不得回
 *       {@link GuiState#SUCCESS}、不得殘留 session、不得留下 inventory link。</li>
 *   <li>排程拒絕必須以新的 {@code ACELIB-GUI-013}（{@link GuiErrorCode#SCHEDULER_REJECTED}）
 *       分類代碼記錄，且 {@link GuiResult} 為 {@link GuiState#FAILED}。</li>
 * </ol>
 *
 * <p>本測試不直接覆蓋 reload 與 scheduler 重綁順序（由
 * {@code AceLibPluginGuiServiceIntegrationTest} 涵蓋）；本檔專注於
 * {@link GuiServiceImpl} 對「executor 派送拒絕」的處理。</p>
 */
@DisplayName("GuiService scheduler rejection regression")
class GuiServiceSchedulerRejectionTest {

    private ServerMock server;
    private PlayerMock player;
    private UUID uuid;
    private PluginMock plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("acelib-rejection-test");
        player = server.addPlayer();
        uuid = player.getUniqueId();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * 建立 SafeSchedulerImpl + 立即停用 — 用於測試 GuiService 對 disabled
     * scheduler 的處理。
     */
    private SafeSchedulerImpl disabledScheduler() {
        SafeSchedulerImpl s = new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));
        s.onPluginDisable();
        return s;
    }

    /**
     * 建立正常運行的 SafeSchedulerImpl — 用於測試 SafeSchedulerPlayerContextExecutor
     * 對正常排程的回傳語意。
     */
    private SafeSchedulerImpl runningScheduler() {
        return new SafeSchedulerImpl(
            plugin, Platform.PAPER, PlatformCapability.forPlatform(Platform.PAPER));
    }

    @Test
    @DisplayName("openInventory 對拒絕派送的 executor：回 FAILED + ACELIB-GUI-013、session 與 link 不殘留")
    void openInventory_rejectedExecutor_returnsFailedAndCleansUp() {
        AtomicInteger runnableInvocations = new AtomicInteger();
        PlayerContextExecutor rejecting = (p, r) -> {
            runnableInvocations.incrementAndGet();
            return false;
        };
        GuiServiceImpl service = new GuiServiceImpl(rejecting);

        GuiArgument arg = GuiArgument.of(player, "Reject", 9, List.of());
        GuiResult result = service.openInventory(arg);

        // 主要契約：排程拒絕時 openInventory 必須回 FAILED + ACELIB-GUI-013
        assertEquals(GuiState.FAILED, result.state(),
            "排程拒絕派送時 openInventory 不得回 SUCCESS，必須為 FAILED");
        assertEquals(GuiErrorCode.SCHEDULER_REJECTED, result.errorCode(),
            "排程拒絕派送的錯誤代碼必須為 ACELIB-GUI-013 SCHEDULER_REJECTED");
        assertNull(result.session(),
            "排程拒絕時 result 不可附帶 session（避免呼叫端誤以為成功）");

        // 必須確實呼叫了 executor
        assertEquals(1, runnableInvocations.get(),
            "openInventory 必須嘗試派送一次到 executor");

        // session 不得殘留（已清理）
        GuiResult query = service.getActiveSession(uuid);
        assertEquals(GuiState.REJECTED, query.state(),
            "排程拒絕後 active session 必須為空");
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, query.errorCode());

        // 內部 activeSessionCount 必須為 0
        assertEquals(0, service.activeSessionCount(),
            "排程拒絕後 service.activeSessionCount 必須為 0");

        service.shutdown();
    }

    @Test
    @DisplayName("openInventory 對同一玩家連續被拒絕：不會留下殘留 session（第二輪仍可被拒絕）")
    void openInventory_repeatedRejection_noResidualSession() {
        PlayerContextExecutor rejecting = (p, r) -> false;
        GuiServiceImpl service = new GuiServiceImpl(rejecting);

        GuiArgument arg = GuiArgument.of(player, "Reject", 9, List.of());
        GuiResult first = service.openInventory(arg);
        assertEquals(GuiState.FAILED, first.state());
        assertEquals(GuiErrorCode.SCHEDULER_REJECTED, first.errorCode());

        GuiResult second = service.openInventory(arg);
        assertEquals(GuiState.FAILED, second.state(),
            "第二次呼叫同樣必須回 FAILED，不應殘留 SESSION_EXISTS");
        assertEquals(GuiErrorCode.SCHEDULER_REJECTED, second.errorCode(),
            "第二次被拒絕仍須為 SCHEDULER_REJECTED，不可誤判為 SESSION_EXISTS");

        service.shutdown();
    }

    @Test
    @DisplayName("openInventory 對 disabled SafeScheduler：透過 forProduction 注入 → 回 FAILED + ACELIB-GUI-013")
    void openInventory_disabledScheduler_returnsFailed() {
        SafeSchedulerImpl disabled = disabledScheduler();
        GuiServiceImpl service = GuiServiceImpl.forProduction(disabled);

        GuiArgument arg = GuiArgument.of(player, "Disabled", 9, List.of());
        GuiResult result = service.openInventory(arg);

        // 排程 disabled → SafeScheduler.runForPlayer 會回 NoOpScheduledTask，
        // executor 內部回 false，GuiService 必須回 FAILED + SCHEDULER_REJECTED
        assertEquals(GuiState.FAILED, result.state(),
            "排程 disabled 時 openInventory 不得回 SUCCESS");
        assertEquals(GuiErrorCode.SCHEDULER_REJECTED, result.errorCode(),
            "排程 disabled 必須回 ACELIB-GUI-013 SCHEDULER_REJECTED");
        assertNull(result.session(),
            "排程 disabled 時 result 不可附帶 session");

        // session 不得殘留
        assertEquals(0, service.activeSessionCount(),
            "排程 disabled 後 service.activeSessionCount 必須為 0");
        GuiResult query = service.getActiveSession(uuid);
        assertEquals(GuiState.REJECTED, query.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, query.errorCode());

        service.shutdown();
    }

    @Test
    @DisplayName("PlayerContextExecutor.noop 仍回傳 true（向後相容既有 service-layer 測試契約）")
    void noopExecutor_returnsTrueForBackwardCompatibility() {
        GuiServiceImpl service = new GuiServiceImpl(PlayerContextExecutor.noop());

        GuiArgument arg = GuiArgument.of(player, "Noop", 9, List.of());
        GuiResult result = service.openInventory(arg);

        // 既有契約：noop executor 視為「已派送」，service 仍回 SUCCESS
        assertEquals(GuiState.SUCCESS, result.state(),
            "noop executor 必須視為已派送（向後相容既有 service-layer 測試）");
        assertNotNull(result.session(),
            "noop executor 派送成功時必須附帶 session");

        service.shutdown();
    }

    @Test
    @DisplayName("PlayerContextExecutor.direct 仍回傳 true（向後相容既有 inventory lifecycle 測試）")
    void directExecutor_returnsTrueForBackwardCompatibility() {
        GuiServiceImpl service = new GuiServiceImpl(PlayerContextExecutor.direct());

        GuiArgument arg = GuiArgument.of(player, "Direct", 9, List.of());
        GuiResult result = service.openInventory(arg);

        // 既有契約：direct executor 視為「已派送」
        assertEquals(GuiState.SUCCESS, result.state(),
            "direct executor 必須視為已派送（既有 inventory lifecycle 測試契約）");
        assertNotNull(result.session());

        service.shutdown();
    }

    @Test
    @DisplayName("SafeSchedulerPlayerContextExecutor 對 disabled scheduler 回傳 false（介面契約）")
    void safeSchedulerPlayerContextExecutor_disabledScheduler_returnsFalse() {
        SafeSchedulerImpl disabled = disabledScheduler();
        SafeSchedulerPlayerContextExecutor executor =
            new SafeSchedulerPlayerContextExecutor(disabled);

        boolean dispatched = executor.runOnPlayerRegion(player, () -> { /* no-op */ });
        assertEquals(false, dispatched,
            "SafeSchedulerPlayerContextExecutor 對 disabled scheduler 必須回 false");
    }

    @Test
    @DisplayName("SafeSchedulerPlayerContextExecutor 對正常 scheduler 回傳 true（介面契約）")
    void safeSchedulerPlayerContextExecutor_runningScheduler_returnsTrue() {
        SafeSchedulerImpl running = runningScheduler();
        SafeSchedulerPlayerContextExecutor executor =
            new SafeSchedulerPlayerContextExecutor(running);

        // MockBukkit 的 BukkitScheduler.runTask 為異步排程（不會同步執行 runnable），
        // 但 task.isCancelled() 為 false（task 已 enqueue），因此 executor 必須回 true。
        boolean dispatched = executor.runOnPlayerRegion(player,
            () -> { /* 實際派送會在下一個 server tick 觸發；此測試只驗證 executor 回傳語意 */ });
        assertEquals(true, dispatched,
            "SafeSchedulerPlayerContextExecutor 對正常 scheduler 必須回 true");

        running.onPluginDisable();
    }

    @Test
    @DisplayName("SafeSchedulerPlayerContextExecutor 對 player 離線的 runForPlayer 回傳 false")
    void safeSchedulerPlayerContextExecutor_playerOffline_returnsFalse() {
        SafeSchedulerImpl running = runningScheduler();
        SafeSchedulerPlayerContextExecutor executor =
            new SafeSchedulerPlayerContextExecutor(running);

        // 把 player 設為離線，runForPlayer 內部會回 NoOpScheduledTask（isCancelled=true）
        player.disconnect();

        boolean dispatched = executor.runOnPlayerRegion(player,
            () -> { /* 不應被執行 */ });
        assertEquals(false, dispatched,
            "SafeSchedulerPlayerContextExecutor 對 player offline 必須回 false");

        running.onPluginDisable();
    }
}