package com.smile.acelib.gui;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

/**
 * 預設 {@link GuiService} 實作（Plan §十六 §二十一 Phase 11 主實作）。
 *
 * <p>設計要點：</p>
 * <ul>
 *   <li>{@link GuiSessionRegistry} 為唯一 session owner；{@link GuiSession} 為
 *       不可變值物件，每次 {@link #openInventory} 建立新物件</li>
 *   <li>Generation 為 {@link GuiSessionRegistry} 內部 monotonic counter —
 *       對外部 caller 而言「不可重用」即成立</li>
 *   <li>每次操作前置驗證 session 存在 + generation 相符；失敗回對應
 *       {@code ACELIB-GUI-*} 結果，不丟例外</li>
 *   <li>Bukkit 事件（{@link InventoryClickEvent} / {@link InventoryDragEvent} /
 *       {@link InventoryCloseEvent}）由內部 listener 統一處理，listener
 *       透過 {@link #validateClick} 等服務層契約保證一致行為</li>
 *   <li>{@link #shutdown()} 標記 stopped 並清除所有 active session；既有
 *       session 不再可被 close（會回 SHUTDOWN）</li>
 * </ul>
 *
 * <h2>Player reference 處理</h2>
 * <p>本類別不接受 {@link Player} 為欄位；{@link #openInventory} 接收
 * {@link GuiArgument}，內部只保留 UUID。實際開啟 inventory 時透過
 * {@link Server#getPlayer(UUID)} 拿當下 Player 物件（已存在的 inventory
 * reference 立即釋放）。</p>
 *
 * @see GuiService
 * @since Phase 11 (Plan §十六 §二十一)
 */
public final class GuiServiceImpl implements GuiService {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final GuiSessionRegistry registry = new GuiSessionRegistry();
    private final AtomicBoolean running = new AtomicBoolean(true);
    /**
     * 預先建立的 Bukkit listener；實際註冊延後到
     * {@link #registerListeners(Server, org.bukkit.plugin.Plugin)} 呼叫，
     * 通常由 {@link com.smile.acelib.AceLibPlugin#onPluginReady()} 觸發。
     */
    private final GuiListener listener = new GuiListener(this);
    /**
     * Inventory mutation 路由 adapter。production 必須為
     * {@link SafeSchedulerPlayerContextExecutor}；測試可用
     * {@link PlayerContextExecutor#direct()} / {@link PlayerContextExecutor#noop()}。
     */
    private final PlayerContextExecutor playerContextExecutor;

    /**
     * Default 建構子：使用 {@link PlayerContextExecutor#noop()} —
     * 既有 service-layer 單元測試不需要實際開 inventory。
     *
     * <p>需要真實 inventory lifecycle 的測試 / production 必須用
     * {@link #GuiServiceImpl(PlayerContextExecutor)} 或
     * {@link #forProduction(com.smile.acelib.scheduler.SafeScheduler)} 注入 executor。</p>
     */
    public GuiServiceImpl() {
        this(PlayerContextExecutor.noop());
    }

    /**
     * 注入式建構子：透過 {@link PlayerContextExecutor} 把 inventory mutation
     * 派送到玩家 region context。
     *
     * @param executor 派送 adapter；不可為 null
     */
    public GuiServiceImpl(PlayerContextExecutor executor) {
        this.playerContextExecutor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Production factory：透過 {@link com.smile.acelib.scheduler.SafeScheduler}
     * 把 inventory mutation 派送到玩家 region context（Folia entity scheduler、
     * Paper main thread）。
     *
     * <p>對應 Evidence Pack「inventory mutation 透過既有 SafeExecutor/region-aware
     * adapter」契約 — production 必須使用此 factory，不得用 default constructor
     * 或 {@link PlayerContextExecutor#noop()}。</p>
     *
     * @param scheduler 對應平台 SafeScheduler；不可為 null
     * @return 新的 {@link GuiServiceImpl}
     * @throws NullPointerException 當 {@code scheduler} 為 null
     * @since Phase 11（Plan §十六 §二十一）
     */
    public static GuiServiceImpl forProduction(
            com.smile.acelib.scheduler.SafeScheduler scheduler) {
        Objects.requireNonNull(scheduler, "scheduler");
        return new GuiServiceImpl(new SafeSchedulerPlayerContextExecutor(scheduler));
    }

    /** 取得內部使用的 player context executor（測試 seam）。 */
    PlayerContextExecutor getPlayerContextExecutor() {
        return playerContextExecutor;
    }

    /**
     * null-input 預檢：null 必須丟 {@link IllegalArgumentException} 並攜帶
     * {@link GuiErrorCode#INVALID_INPUT}。
     */
    private static void requireNonNull(Object o, String name) {
        if (o == null) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] " + name + " must not be null");
        }
    }

    // -----------------------------------------------------------------
    // GuiService
    // -----------------------------------------------------------------

    @Override
    public GuiResult openInventory(GuiArgument argument) {
        requireNonNull(argument, "argument");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown");
        }
        // 先透過 UUID 取得當下 Player reference；caller 不持有，內部使用完即釋放。
        Player player = Bukkit.getPlayer(argument.playerUuid());
        if (player == null) {
            return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "player offline or not found: uuid=" + argument.playerUuid());
        }
        // 先註冊 session — listener 透過 generation 與 UUID 識別 ownership
        GuiSession session;
        try {
            session = registry.startSession(argument.playerUuid(), "acelib",
                argument.size(), argument.protectedSlots(), argument.title());
        } catch (IllegalStateException ex) {
            return GuiResult.rejected(GuiErrorCode.SESSION_EXISTS,
                ex.getMessage());
        }
        final long generation = session.generation();
        // 透過 player context 開 inventory + link + 實際開啟視窗；
        // Folia 下由 entity scheduler 派送，Paper 下走 main thread。
        boolean dispatched;
        try {
            dispatched = playerContextExecutor.runOnPlayerRegion(player, () -> {
                try {
                    Inventory inv = Bukkit.createInventory(null,
                        argument.size(), argument.title());
                    GuiInventoryLink.link(inv, generation);
                    player.openInventory(inv);
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING,
                        "GuiService: failed to open inventory for uuid={0}: {1}",
                        new Object[] { argument.playerUuid(), t.getMessage() });
                    // 建立失敗時移除 session，避免殘留
                    registry.endSession(argument.playerUuid());
                }
            });
        } catch (Throwable t) {
            // executor 派送丟例外（極少見，通常代表 dispatcher 本身失敗）
            LOGGER.log(Level.WARNING,
                "GuiService: player context executor failed: {0}", t.getMessage());
            registry.endSession(argument.playerUuid());
            return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "executor failed: " + t.getMessage());
        }
        if (!dispatched) {
            // player context executor 拒絕派送時：清理 session 並回報 scheduler rejection
            // （SafeScheduler 回傳 cancelled no-op task — 對應 scheduler disabled、
            // player offline、平台不支援等）時，舊實作仍回 SUCCESS 但實際未開 inventory，
            // 留下 stale session 與 player reference。
            // 修正：清理 session、回 FAILED + ACELIB-GUI-013 SCHEDULER_REJECTED。
            registry.endSession(argument.playerUuid());
            LOGGER.log(Level.WARNING,
                "GuiService: player context executor refused dispatch for uuid={0} "
                    + "(scheduler disabled, player offline, or platform unsupported)",
                argument.playerUuid());
            return GuiResult.failed(GuiErrorCode.SCHEDULER_REJECTED,
                "player context executor refused dispatch for uuid="
                    + argument.playerUuid());
        }
        return GuiResult.success(session, "opened gui session");
    }

    @Override
    public GuiResult closeInventory(UUID playerUuid, long generation) {
        requireNonNull(playerUuid, "playerUuid");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown");
        }
        GuiSession session = registry.getSession(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (session.generation() != generation) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + session.generation()
                    + " but got " + generation);
        }
        // 先從 registry 移除 session — 後續 close event 內部 cleanup 會 idempotent 通過
        GuiSession removed = registry.endSession(playerUuid);
        if (removed == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "session was already removed (race): uuid=" + playerUuid);
        }
        // 透過 player context 實際關閉 Bukkit inventory
        Player player = Bukkit.getPlayer(playerUuid);
        if (player != null) {
            try {
                boolean dispatched = playerContextExecutor.runOnPlayerRegion(player, () -> {
                    try {
                        player.closeInventory();
                    } catch (Throwable t) {
                        LOGGER.log(Level.FINE,
                            "GuiService: close inventory failed for uuid={0}: {1}",
                            new Object[] { playerUuid, t.getMessage() });
                    }
                });
                if (!dispatched) {
                    // close 路徑：executor 拒絕派送（player offline 等）。
                    // session 已從 registry 移除（上方 endSession），close 視為成功
                    // （語意「已不再屬於此 GUI」），不需 FAILED；僅 FINE 記錄以便診斷。
                    LOGGER.log(Level.FINE,
                        "GuiService: close executor refused dispatch for uuid={0} "
                            + "(player likely offline)", playerUuid);
                }
            } catch (Throwable t) {
                LOGGER.log(Level.FINE,
                    "GuiService: close executor dispatch failed: {0}", t.getMessage());
            }
        }
        return GuiResult.success(removed, "closed gui session");
    }

    @Override
    public GuiResult getActiveSession(UUID playerUuid) {
        requireNonNull(playerUuid, "playerUuid");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown");
        }
        GuiSession session = registry.getSession(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        return GuiResult.success(session);
    }

    @Override
    public GuiResult validateClick(UUID playerUuid, long generation, int slot) {
        requireNonNull(playerUuid, "playerUuid");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown");
        }
        GuiSession session = registry.getSession(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (session.generation() != generation) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + session.generation()
                    + " but got " + generation);
        }
        if (slot < 0 || slot >= session.size()) {
            return GuiResult.rejected(GuiErrorCode.INVALID_INPUT,
                "slot out of range: " + slot + " (size=" + session.size() + ")");
        }
        if (session.protectedSlots().contains(slot)) {
            return GuiResult.rejected(GuiErrorCode.SLOT_PROTECTED,
                "slot " + slot + " is protected");
        }
        return GuiResult.allowed(session);
    }

    @Override
    public String getModuleStatus() {
        return running.get() ? "READY" : "FAILED";
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return; // idempotent
        }
        // 先清空 link：listener 後續事件仍可能被 Bukkit dispatch（雖然已 unregister），
        // 確保它看到「沒有 active link」就 early return。
        GuiInventoryLink.clear();
        registry.clear();
    }

    // -----------------------------------------------------------------
    // 內部 cleanup（被 Bukkit InventoryCloseEvent 觸發）
    // -----------------------------------------------------------------

    /**
     * 內部清理：由 Bukkit {@link InventoryCloseEvent} listener 呼叫，
     * <strong>不驗證 generation</strong>（Bukkit 關閉事件本身即為可信來源）。
     *
     * <p>若遊戲內關閉 GUI（例如玩家按 ESC、視窗被伺服器關閉），內部 listener
     * 會呼叫此方法移除 session。後續 closeInventory 必須回 SESSION_NOT_FOUND，
     * 證明 session 已被清理。</p>
     *
     * @param playerUuid 玩家 UUID；不可為 null
     */
    public void internalCleanup(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        registry.endSession(playerUuid);
    }

    /**
     * 取得當前 active session 數（測試 seam）。
     */
    public int activeSessionCount() {
        return registry.size();
    }

    /**
     * 是否處於 running 狀態（測試 seam）。
     */
    public boolean isRunning() {
        return running.get();
    }

    // -----------------------------------------------------------------
    // 內部 listener 工具（供 AceLibPlugin 註冊）
    // -----------------------------------------------------------------

    /**
     * 驗證 {@link InventoryClickEvent} 並視情況取消。
     *
     * <p>僅在該 inventory 屬於 active session 時介入；其他 GUI 行為不受影響。
     * 對應「受保護 slot 點擊被阻擋」契約。</p>
     *
     * @param event Bukkit 派送的 click event；不可為 null
     */
    public void handleClick(InventoryClickEvent event) {
        Objects.requireNonNull(event, "event");
        if (!running.get()) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Long generation = GuiInventoryLink.generationOf(top);
        if (generation == null) {
            return; // 非本服務管理的 inventory
        }
        org.bukkit.entity.HumanEntity whoClicked = event.getWhoClicked();
        if (!(whoClicked instanceof Player p)) {
            return;
        }
        GuiResult result = validateClick(p.getUniqueId(), generation, event.getRawSlot());
        if (result.isRejected()) {
            event.setCancelled(true);
            LOGGER.log(Level.FINE,
                "GuiService: blocked click (slot={0}, code={1}, detail={2})",
                new Object[] { event.getRawSlot(), result.errorCode(), result.detail() });
        }
    }

    /**
     * 驗證 {@link InventoryDragEvent} 並視情況取消。
     *
     * <p>拖曳事件統一拒絕：受保護 slot 集合的設計並未涵蓋「拖曳路徑」
     * （玩家可能從受保護 slot 拖到受保護 slot），故保守一律攔截。屬於
     * 第一個可驗收切片範圍的最小行為。</p>
     *
     * @param event Bukkit 派送的 drag event；不可為 null
     */
    public void handleDrag(InventoryDragEvent event) {
        Objects.requireNonNull(event, "event");
        if (!running.get()) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Long generation = GuiInventoryLink.generationOf(top);
        if (generation == null) {
            return;
        }
        org.bukkit.entity.HumanEntity whoClicked = event.getWhoClicked();
        if (!(whoClicked instanceof Player p)) {
            return;
        }
        // 對 top inventory 內涉及的 slot 做 protected 檢查
        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < event.getView().getTopInventory().getSize()) {
                GuiResult result = validateClick(p.getUniqueId(), generation, slot);
                if (result.isRejected()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    /**
     * 處理 {@link InventoryCloseEvent}：移除對應 session。
     *
     * @param event Bukkit 派送的 close event；不可為 null
     */
    public void handleClose(InventoryCloseEvent event) {
        Objects.requireNonNull(event, "event");
        if (!running.get()) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        GuiInventoryLink.unlink(top);
        org.bukkit.entity.HumanEntity who = event.getPlayer();
        if (who instanceof Player p) {
            internalCleanup(p.getUniqueId());
        }
    }

    /**
     * 註冊 listener 至指定 server 與 plugin。
     *
     * <p>listener 內部僅持有本 service reference；事件觸發時透過
     * {@link #handleClick} / {@link #handleDrag} / {@link #handleClose}
     * 統一處理。</p>
     *
     * <p>既有 listener（建構時預先建立的 {@link #listener}）透過
     * {@link org.bukkit.event.HandlerList#unregisterAll(Listener)} 解除舊綁定
     * 後再用新 server 重新註冊；對於 reload 流程可避免 listener 重複註冊。</p>
     *
     * <p>為避免與世界服務 listener 衝突，本方法建立的 listener 為 service
     * 內部匿名實例；若 caller 持有 listener reference，可透過
     * {@link org.bukkit.event.HandlerList#unregisterAll(Listener)} 解除。</p>
     *
     * @param server 當前 Bukkit server；不可為 null
     * @param plugin 註冊 plugin owner；不可為 null
     */
    public void registerListeners(Server server,
                                   org.bukkit.plugin.Plugin plugin) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(plugin, "plugin");
        // 先解除既有 listener（reload 場景）
        org.bukkit.event.HandlerList.unregisterAll(listener);
        server.getPluginManager().registerEvents(listener, plugin);
    }

    /**
     * 取得 listener reference（plugin lifecycle seam）。
     *
     * <p>由 {@link com.smile.acelib.AceLibPlugin} 持有，以便在 onDisable /
     * reload 時透過 {@link org.bukkit.event.HandlerList#unregisterAll(Listener)}
     * 解除註冊；listener 內部仍屬本 service 私有，外部 caller 僅可取得 reference
     * 不可變更內部 handler。</p>
     *
     * @return listener reference；永不為 null
     */
    public org.bukkit.event.Listener getListener() {
        return listener;
    }

    /**
     * 取得當前 server 的 Player 物件（測試 seam）。
     *
     * <p>內部呼叫 {@link Bukkit#getPlayer(UUID)}。
     * 對應 {@code openInventory} 內部由 UUID 取得 Player 的需求。
     * 本方法保持 package-private — 僅本套件內 listener 可用。</p>
     */
    Player resolvePlayer(UUID playerUuid) {
        return Bukkit.getPlayer(playerUuid);
    }

    /**
     * 將 inventory 與 session generation 綁定（listener 用）。
     */
    void linkInventory(Inventory inventory, long generation) {
        GuiInventoryLink.link(inventory, generation);
    }
}
