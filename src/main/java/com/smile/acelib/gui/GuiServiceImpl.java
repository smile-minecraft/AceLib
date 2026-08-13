package com.smile.acelib.gui;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
final class GuiServiceImpl implements GuiService {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final GuiSessionRegistry registry = new GuiSessionRegistry();
    private final AtomicBoolean running = new AtomicBoolean(true);
    /**
     * 待確認 action 表（Phase 11 延伸第二切片：confirmation/cancellation）。
     * key 為服務產生的不透明 action token；value 為對應 {@link PendingAction}。
     * 不持有 {@code Player} reference — 僅保存 UUID 與 domain callback。
     */
    private final ConcurrentMap<String, PendingAction> pendingActions
        = new ConcurrentHashMap<>();
    /**
     * 每個玩家目前的 request generation（Phase 11 延伸第三切片：非同步更新 stale 防護）。
     * key 為玩家 UUID；value 為該玩家目前有效（最大）的 request generation。
     * {@link #beginAsyncUpdate} 每次呼叫遞增並取得新值；{@link #applyAsyncUpdate}
     * 比對請求的 requestGeneration 是否仍等於此值，否則視為過時（被取代）。
     * 不持有 {@code Player} reference — 僅保存 UUID 與 long 計數。
     */
    private final ConcurrentMap<UUID, AtomicLong> requestGenerations
        = new ConcurrentHashMap<>();
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
    GuiServiceImpl() {
        this(PlayerContextExecutor.noop());
    }

    /**
     * 注入式建構子：透過 {@link PlayerContextExecutor} 把 inventory mutation
     * 派送到玩家 region context。
     *
     * @param executor 派送 adapter；不可為 null
     */
    GuiServiceImpl(PlayerContextExecutor executor) {
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
    static GuiServiceImpl forProduction(
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
        // session 結束時使綁定的待確認 action 失效，避免關閉後 confirm 仍執行 callback
        invalidatePendingActions(playerUuid);
        // 同時失效該玩家的非同步更新請求序號，避免關閉後舊請求仍被視為有效
        requestGenerations.remove(playerUuid);
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

    // -----------------------------------------------------------------
    // 非同步更新請求合約（Phase 11 延伸第三切片）
    // -----------------------------------------------------------------

    @Override
    public GuiResult beginAsyncUpdate(UUID playerUuid, long sessionGeneration,
                                      int pageIndex) {
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
        if (session.generation() != sessionGeneration) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + session.generation()
                    + " but got " + sessionGeneration);
        }
        // 單調遞增該玩家的 request generation；後發請求會取得更大值，
        // 使先前的請求在 apply 時因序號不符被拒絕（stale 防護）。
        long requestGeneration =
            requestGenerations.computeIfAbsent(playerUuid, k -> new AtomicLong(0L))
                .incrementAndGet();
        GuiAsyncRequest request = new GuiAsyncRequest(playerUuid, sessionGeneration,
            pageIndex, requestGeneration);
        return GuiResult.success(session, request);
    }

    @Override
    public <T> GuiResult applyAsyncUpdate(GuiAsyncRequest request, GuiPage<T> page,
                                          Runnable renderer) {
        requireNonNull(request, "request");
        requireNonNull(page, "page");
        requireNonNull(renderer, "renderer");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown");
        }
        UUID playerUuid = request.playerUuid();
        GuiSession session = registry.getSession(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (session.generation() != request.sessionGeneration()) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "session generation changed: expected " + request.sessionGeneration()
                    + " but current " + session.generation());
        }
        // request generation 必須仍為目前有效值；否則視為過時（被同一 session 的
        // 後發請求取代），舊結果不得覆寫目前 GUI。
        AtomicLong current = requestGenerations.get(playerUuid);
        if (current == null || current.get() != request.requestGeneration()) {
            return GuiResult.rejected(GuiErrorCode.STALE_REQUEST,
                "async request is stale (superseded by a newer request): "
                    + "requestGeneration=" + request.requestGeneration());
        }
        // 玩家必須仍在線，否則不得對離線玩家執行 inventory mutation。
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return GuiResult.rejected(GuiErrorCode.PLAYER_OFFLINE,
                "player offline when async result returned: uuid=" + playerUuid);
        }
        // 在玩家 region context 內「執行前」重新驗證所有必要條件（deferred race 防護）：
        // 從 enqueue 到真正執行 renderer 之間，service / session / request generation /
        // 在線狀態 / inventory link 都可能改變，必須以執行當下的狀態為準。
        // 任一條件失效時 renderer 不得執行，且 request 不殘留（不修改任何 inventory / link）。
        AtomicReference<GuiResult> outcome = new AtomicReference<>();
        boolean dispatched;
        try {
            dispatched = playerContextExecutor.runOnPlayerRegion(player, () -> {
                try {
                    // 1) service 仍 running
                    if (!running.get()) {
                        outcome.set(GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                            "gui service is shutdown before renderer executed: uuid="
                                + playerUuid));
                        return;
                    }
                    // 2) UUID 對應 active session 且 generation 相符
                    GuiSession currentSession = registry.getSession(playerUuid);
                    if (currentSession == null) {
                        outcome.set(GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                            "no active session for uuid=" + playerUuid
                                + " before renderer executed"));
                        return;
                    }
                    if (currentSession.generation() != request.sessionGeneration()) {
                        outcome.set(GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                            "session generation changed: expected "
                                + request.sessionGeneration() + " but current "
                                + currentSession.generation()));
                        return;
                    }
                    // 3) request generation 仍為目前有效值（未被後發請求取代）
                    AtomicLong liveGen = requestGenerations.get(playerUuid);
                    if (liveGen == null
                            || liveGen.get() != request.requestGeneration()) {
                        outcome.set(GuiResult.rejected(GuiErrorCode.STALE_REQUEST,
                            "async request is stale (superseded by a newer request): "
                                + "requestGeneration=" + request.requestGeneration()));
                        return;
                    }
                    // 4) 玩家在線有效
                    Player livePlayer = Bukkit.getPlayer(playerUuid);
                    if (livePlayer == null) {
                        outcome.set(GuiResult.rejected(GuiErrorCode.PLAYER_OFFLINE,
                            "player offline before renderer executed: uuid="
                                + playerUuid));
                        return;
                    }
                    // 5) inventory link generation 一致
                    Inventory top = livePlayer.getOpenInventory().getTopInventory();
                    Long linkedGen = top == null ? null
                        : GuiInventoryLink.generationOf(top);
                    if (linkedGen == null
                            || linkedGen != currentSession.generation()) {
                        outcome.set(GuiResult.rejected(GuiErrorCode.INVENTORY_MISMATCH,
                            "open inventory is no longer bound to this session "
                                + "(generation=" + currentSession.generation()
                                + ", linked=" + linkedGen
                                + "); refusing to overwrite"));
                        return;
                    }
                    // 所有條件通過：在 player region context 內執行 renderer 恰好一次
                    renderer.run();
                    outcome.set(GuiResult.success(currentSession,
                        "applied async update; page=" + page.kind()
                            + (page.isError() ? ", code=" + page.errorCode() : "")));
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING,
                        "GuiService: async update renderer failed for uuid={0}: {1}",
                        new Object[] { playerUuid, t.getMessage() });
                    outcome.set(GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                        "async update renderer failed: " + t.getMessage()));
                }
            });
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING,
                "GuiService: async update executor dispatch failed: {0}", t.getMessage());
            return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "executor failed: " + t.getMessage());
        }
        if (!dispatched) {
            // executor 拒絕派送（scheduler disabled、player offline、平台不支援）：
            // renderer 不得執行，回 SCHEDULER_REJECTED。
            return GuiResult.failed(GuiErrorCode.SCHEDULER_REJECTED,
                "player context executor refused dispatch for uuid=" + playerUuid);
        }
        GuiResult result = outcome.get();
        if (result != null) {
            // 同步 executor（direct / noop 實際執行）已完成 renderer，回傳真實結果。
            return result;
        }
        // 延遲 executor：派送已接受（enqueue 成功），但 renderer 尚未執行。
        // 不得冒充 renderer 已完成 — 回 ACCEPTED，最終結果於執行時重新驗證後決定。
        return GuiResult.accepted(session,
            "async update dispatched; renderer will run on player region after revalidation");
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

    // -----------------------------------------------------------------
    // 確認 / 取消 action contract（Phase 11 延伸第二切片）
    // -----------------------------------------------------------------

    @Override
    public GuiResult createConfirmation(UUID playerUuid, long generation,
                                       String actionId, Runnable callback) {
        requireNonNull(playerUuid, "playerUuid");
        requireNonNull(actionId, "actionId");
        requireNonNull(callback, "callback");
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
        String token = UUID.randomUUID().toString();
        PendingAction action = new PendingAction(playerUuid, generation,
            actionId, token, callback, session);
        pendingActions.put(token, action);
        GuiConfirmation confirmation = new GuiConfirmation(playerUuid, generation,
            actionId, token, GuiConfirmation.State.PENDING);
        return GuiResult.success(session, confirmation);
    }

    @Override
    public GuiResult confirm(UUID playerUuid, long generation, String actionToken) {
        requireNonNull(playerUuid, "playerUuid");
        requireNonNull(actionToken, "actionToken");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown");
        }
        PendingAction action = pendingActions.get(actionToken);
        if (action == null || !action.playerUuid.equals(playerUuid)) {
            return GuiResult.rejected(GuiErrorCode.UNKNOWN_ACTION,
                "unknown or expired action token=" + actionToken);
        }
        if (action.generation != generation) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + action.generation
                    + " but got " + generation);
        }
        synchronized (action) {
            if (action.state != GuiConfirmation.State.PENDING) {
                return GuiResult.rejected(GuiErrorCode.ACTION_ALREADY_RESOLVED,
                    "action already resolved (state=" + action.state
                        + "), token=" + actionToken);
            }
            action.state = GuiConfirmation.State.CONFIRMED;
            try {
                action.callback.run();
            } catch (Throwable t) {
                action.failureDetail = t.getMessage();
                LOGGER.log(Level.WARNING,
                    "GuiService: confirmation callback failed for actionId={0}, "
                        + "token={1}: {2}",
                    new Object[] { action.actionId, actionToken, t.getMessage() });
                return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                    "confirmation callback failed for actionId=" + action.actionId
                        + ": " + t.getMessage());
            }
        }
        // 解析後保留 entry（state 已非 PENDING）以便重複 confirm/cancel 回
        // ACTION_ALREADY_RESOLVED；session 結束或 shutdown 時統一清理。
        return GuiResult.success(action.session);
    }

    @Override
    public GuiResult cancel(UUID playerUuid, long generation, String actionToken) {
        requireNonNull(playerUuid, "playerUuid");
        requireNonNull(actionToken, "actionToken");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown");
        }
        PendingAction action = pendingActions.get(actionToken);
        if (action == null || !action.playerUuid.equals(playerUuid)) {
            return GuiResult.rejected(GuiErrorCode.UNKNOWN_ACTION,
                "unknown or expired action token=" + actionToken);
        }
        if (action.generation != generation) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + action.generation
                    + " but got " + generation);
        }
        synchronized (action) {
            if (action.state != GuiConfirmation.State.PENDING) {
                return GuiResult.rejected(GuiErrorCode.ACTION_ALREADY_RESOLVED,
                    "action already resolved (state=" + action.state
                        + "), token=" + actionToken);
            }
            action.state = GuiConfirmation.State.CANCELLED;
            // callback 不執行；保留 entry 以便重複 cancel 回 ACTION_ALREADY_RESOLVED
        }
        return GuiResult.success(action.session);
    }

    /**
     * 使指定玩家的所有待確認 action 失效（session 結束時呼叫）。
     *
     * <p>失效後 confirm/cancel 會因 token 不存在而回 {@code UNKNOWN_ACTION}，
     * 不會執行 callback。</p>
     */
    private void invalidatePendingActions(UUID playerUuid) {
        pendingActions.entrySet().removeIf(e -> e.getValue().playerUuid.equals(playerUuid));
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
        pendingActions.clear();
        requestGenerations.clear();
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
        invalidatePendingActions(playerUuid);
        requestGenerations.remove(playerUuid);
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

    /**
     * 待確認 action 內部狀態（Phase 11 延伸第二切片）。
     *
     * <p>不可變欄位記錄綁定資訊；{@link #state} 為唯一可變欄位，僅在
     * {@code synchronized(action)} 區塊內由 confirm/cancel 轉換，確保 callback
     * 恰好執行一次。本物件不持有 {@code Player} reference。</p>
     */
    private static final class PendingAction {
        final UUID playerUuid;
        final long generation;
        final String actionId;
        final String actionToken;
        final Runnable callback;
        final GuiSession session;
        GuiConfirmation.State state = GuiConfirmation.State.PENDING;
        volatile String failureDetail;

        PendingAction(UUID playerUuid, long generation, String actionId,
                      String actionToken, Runnable callback, GuiSession session) {
            this.playerUuid = playerUuid;
            this.generation = generation;
            this.actionId = actionId;
            this.actionToken = actionToken;
            this.callback = callback;
            this.session = session;
        }
    }
}
