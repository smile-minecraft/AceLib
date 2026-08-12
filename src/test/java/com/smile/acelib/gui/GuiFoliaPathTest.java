package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.scheduler.SafeSchedulerImpl;
import com.smile.acelib.platform.Platform;
import com.smile.acelib.platform.PlatformCapability;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * Folia capability regression tests for {@link SafeSchedulerPlayerContextExecutor}
 * 與 {@link GuiServiceImpl#forProduction(com.smile.acelib.scheduler.SafeScheduler)}。
 *
 * <h2>MockBukkit Folia 限制（必須如實記錄，不得宣稱真 runtime）</h2>
 * <p>本測試運行於 MockBukkit v26.1.2 + paper-api 26.1.2。該環境的
 * {@code org.bukkit.entity.Entity} 介面已宣告 {@code getScheduler()}，且 MockBukkit
 * 提供 {@code FoliaEntityScheduler} mock 實作。因此 Folia capability 下
 * {@code SafeSchedulerImpl} 的 {@code dispatchFolia} 路徑<strong>不會</strong>像
 * 舊版假設那樣因 classpath 缺 Folia API 而落入 {@code ACELIB-SCHED-005} 拒絕；
 * 相反地，它會把任務 enqueue 到 MockBukkit 的 scheduler（回傳非 cancelled task）。</p>
 *
 * <p>這代表：</p>
 * <ul>
 *   <li><strong>online Folia</strong>：executor 回 {@code true}（reflection dispatch
 *       未拋錯，adapter 回報 accepted/deferred），但 runnable <em>不會同步執行</em>
 *       （MockBukkit 排程於 tick，測試不 performTick 即不執行）。這是「seam 證據」——
 *       僅證明 dispatch 路徑未被拒絕，<em>不代表</em>真 Folia region 執行，也不宣稱
 *       已驗證真實 scheduler task 狀態（例如 {@code isCancelled()} 等）。</li>
 *   <li><strong>offline / disabled Folia</strong>：仍走真實拒絕路徑（player offline →
 *       {@code ACELIB-SCHED-002}；scheduler disabled → {@code ACELIB-SCHED-006}），
 *       executor 回 {@code false}，runnable 不執行。這是可在 MockBukkit 驗證的
 *       拒絕/降級語意。</li>
 * </ul>
 *
 * <p>本測試據此證明：</p>
 * <ol>
 *   <li>Folia offline / disabled 時，{@code openInventory} 回 {@code FAILED} +
 *       {@code ACELIB-GUI-013}（{@link GuiErrorCode#SCHEDULER_REJECTED}），
 *       且<strong>不殘留</strong> active session / inventory link（無 stale session）。</li>
 *   <li>Folia online 時，executor 回 {@code true}（reflection dispatch accepted/deferred），但 renderer /
 *       inventory mutation <strong>不會同步執行</strong>——證明不會「錯誤回成功並
 *       立即執行 renderer」。</li>
 *   <li>任何路徑下 runnable 都不會在 executor 回傳前被錯誤執行。</li>
 * </ol>
 *
 * <p><strong>未驗證</strong>：真 Folia runtime 的 region 內同步執行語意（MockBukkit
 * 非真 region 環境）；本測試所有「成功 enqueue」皆標註為 seam 證據。</p>
 */
@DisplayName("GuiService / SafeScheduler Folia capability path")
class GuiFoliaPathTest {

    private ServerMock server;
    private PlayerMock player;
    private UUID uuid;
    private PluginMock plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin("acelib-folia-test");
        player = server.addPlayer();
        uuid = player.getUniqueId();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * 建立 FOLIA capability 的 SafeSchedulerImpl（MockBukkit 環境）。
     */
    private SafeSchedulerImpl foliaScheduler() {
        return new SafeSchedulerImpl(
            plugin, Platform.FOLIA, PlatformCapability.forPlatform(Platform.FOLIA));
    }

    // -----------------------------------------------------------------
    // 拒絕路徑（MockBukkit 可驗證的真實降級語意）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("FOLIA + player offline：SafeSchedulerPlayerContextExecutor 回 false，runnable 不執行")
    void foliaExecutor_offlinePlayer_returnsFalseAndDoesNotExecute() {
        SafeSchedulerImpl folia = foliaScheduler();
        SafeSchedulerPlayerContextExecutor executor =
            new SafeSchedulerPlayerContextExecutor(folia);

        player.disconnect();
        AtomicBoolean executed = new AtomicBoolean(false);

        boolean dispatched = executor.runOnPlayerRegion(player, () -> executed.set(true));

        assertEquals(false, dispatched,
            "FOLIA + player offline 必須回 false（runForPlayer 內部回 NoOpScheduledTask）");
        assertFalse(executed.get(),
            "FOLIA + player offline 不得執行 runnable");
        // 明確記錄拒絕原因：玩家離線 ACELIB-SCHED-002
        boolean recordedOffline = folia.getRecorderErrors(10).stream()
            .anyMatch(r -> "ACELIB-SCHED-002".equals(r.code()));
        assertTrue(recordedOffline, "FOLIA + player offline 必須記錄 ACELIB-SCHED-002");

        folia.onPluginDisable();
    }

    @Test
    @DisplayName("FOLIA + scheduler disabled：SafeSchedulerPlayerContextExecutor 回 false，runnable 不執行")
    void foliaExecutor_disabledScheduler_returnsFalseAndDoesNotExecute() {
        SafeSchedulerImpl folia = foliaScheduler();
        folia.onPluginDisable();
        SafeSchedulerPlayerContextExecutor executor =
            new SafeSchedulerPlayerContextExecutor(folia);

        AtomicBoolean executed = new AtomicBoolean(false);
        boolean dispatched = executor.runOnPlayerRegion(player, () -> executed.set(true));

        assertEquals(false, dispatched,
            "FOLIA + scheduler disabled 必須回 false（dispatch 回 NoOpScheduledTask）");
        assertFalse(executed.get(),
            "FOLIA + scheduler disabled 不得執行 runnable");
        // 明確記錄拒絕原因：插件停用 ACELIB-SCHED-006
        boolean recordedDisabled = folia.getRecorderErrors(10).stream()
            .anyMatch(r -> "ACELIB-SCHED-006".equals(r.code()));
        assertTrue(recordedDisabled, "FOLIA + scheduler disabled 必須記錄 ACELIB-SCHED-006");
    }

    @Test
    @DisplayName("FOLIA + player offline：GuiServiceImpl.forProduction openInventory 在 Bukkit.getPlayer 層即回 FAILED（無 stale session）")
    void openInventory_foliaOffline_returnsFailedAndCleansUp() {
        SafeSchedulerImpl folia = foliaScheduler();
        GuiServiceImpl service = GuiServiceImpl.forProduction(folia);

        player.disconnect();
        GuiArgument arg = GuiArgument.of(player, "FoliaOffline", 9, List.of());
        GuiResult result = service.openInventory(arg);

        // MockBukkit 下 disconnect() 後 Bukkit.getPlayer(uuid) 回 null，
        // openInventory 在解析 player 階段即回 FAILED + ACELIB-GUI-012（player offline），
        // executor 根本未被呼叫——這同樣證明「不會錯誤回成功、不殘留 session」。
        // （SCHEDULER_REJECTED / ACELIB-GUI-013 由「scheduler disabled」路徑證明，
        //  見 openInventory_foliaDisabled_returnsFailedAndCleansUp。）
        assertEquals(GuiState.FAILED, result.state(),
            "FOLIA + player offline 時 openInventory 不得回 SUCCESS");
        assertEquals(GuiErrorCode.OPERATION_FAILED, result.errorCode(),
            "FOLIA + player offline（Bukkit.getPlayer 回 null）必須回 ACELIB-GUI-012 OPERATION_FAILED");
        assertNull(result.session(),
            "FOLIA + player offline 時 result 不可附帶 session");

        // 不得殘留 active session / link
        assertEquals(0, service.activeSessionCount(),
            "FOLIA + player offline 後 activeSessionCount 必須為 0");
        GuiResult query = service.getActiveSession(uuid);
        assertEquals(GuiState.REJECTED, query.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, query.errorCode());

        service.shutdown();
    }

    @Test
    @DisplayName("FOLIA + scheduler disabled：GuiServiceImpl.forProduction openInventory 回 FAILED + ACELIB-GUI-013，無 stale session")
    void openInventory_foliaDisabled_returnsFailedAndCleansUp() {
        SafeSchedulerImpl folia = foliaScheduler();
        folia.onPluginDisable();
        GuiServiceImpl service = GuiServiceImpl.forProduction(folia);

        GuiArgument arg = GuiArgument.of(player, "FoliaDisabled", 9, List.of());
        GuiResult result = service.openInventory(arg);

        assertEquals(GuiState.FAILED, result.state(),
            "FOLIA + scheduler disabled 時 openInventory 不得回 SUCCESS");
        assertEquals(GuiErrorCode.SCHEDULER_REJECTED, result.errorCode(),
            "FOLIA + scheduler disabled 必須回 ACELIB-GUI-013 SCHEDULER_REJECTED");
        assertNull(result.session(),
            "FOLIA + scheduler disabled 時 result 不可附帶 session");

        assertEquals(0, service.activeSessionCount(),
            "FOLIA + scheduler disabled 後 activeSessionCount 必須為 0");
        GuiResult query = service.getActiveSession(uuid);
        assertEquals(GuiState.REJECTED, query.state());
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, query.errorCode());

        service.shutdown();
    }

    // -----------------------------------------------------------------
    // online Folia：seam 證據（reflection dispatch accepted/deferred，非真 region 執行）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("FOLIA + online player：executor 回 true（reflection dispatch accepted/deferred seam 證據），但 runnable 不會同步執行")
    void foliaExecutor_onlinePlayer_enqueuesButDoesNotExecuteSynchronously() {
        SafeSchedulerImpl folia = foliaScheduler();
        SafeSchedulerPlayerContextExecutor executor =
            new SafeSchedulerPlayerContextExecutor(folia);

        AtomicBoolean executed = new AtomicBoolean(false);
        boolean dispatched = executor.runOnPlayerRegion(player, () -> executed.set(true));

        // seam 證據：MockBukkit 提供 Folia entity scheduler mock，reflection dispatch
        // 未拋錯，adapter 回報 accepted/deferred（executor 回 true）。這不代表真 region
        // 執行，也不代表已驗證真實 scheduler task 狀態（例如 isCancelled 等）。
        assertEquals(true, dispatched,
            "FOLIA + online player 在 MockBukkit 下 reflection dispatch 未拋錯，"
                + "adapter 回報 accepted/deferred（seam 證據）");
        assertFalse(executed.get(),
            "FOLIA + online player 的 runnable 不得同步執行（MockBukkit 排程於 tick，"
                + "測試未 performTick 即不執行；證明不會錯誤回成功並立即執行 renderer）");

        folia.onPluginDisable();
    }

    @Test
    @DisplayName("FOLIA + online player：openInventory 回 SUCCESS（deferred/accepted 語意），renderer 不會同步執行")
    void openInventory_foliaOnline_returnsSuccessDeferred() {
        SafeSchedulerImpl folia = foliaScheduler();
        GuiServiceImpl service = GuiServiceImpl.forProduction(folia);

        GuiArgument arg = GuiArgument.of(player, "FoliaOnline", 9, List.of());
        GuiResult result = service.openInventory(arg);

        // MockBukkit Folia 路徑 reflection dispatch 未拋錯，adapter 回報 accepted/deferred
        // （executor 回 true）→ openInventory 回 SUCCESS（session 已建立，renderer 排程
        // 但尚未執行）。這是 deferred/accepted 語意，非「錯誤回成功並立即執行 renderer」，
        // 亦不代表已驗證真實 Folia region 執行或真實 scheduler task 狀態。
        assertEquals(GuiState.SUCCESS, result.state(),
            "FOLIA + online player 在 MockBukkit 下 openInventory 回 SUCCESS"
                + "（adapter 回報 accepted/deferred，seam 證據）");
        assertTrue(result.session() != null,
            "FOLIA + online player 的 openInventory 必須附帶 session（deferred 語意）");
        assertEquals(1, service.activeSessionCount(),
            "FOLIA + online player 的 openInventory 建立了一個 active session");

        // renderer（實際開 inventory + link）尚未同步執行：玩家視窗未開啟
        assertFalse(player.isOnline() && player.getOpenInventory() != null
                && player.getOpenInventory().getTopInventory() != null
                && player.getOpenInventory().getTopInventory().getSize() == 9,
            "FOLIA + online player 的 renderer 不應在 executor 回傳前同步執行（無 false renderer execution）");

        service.shutdown();
    }
}
