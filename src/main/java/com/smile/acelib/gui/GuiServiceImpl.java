package com.smile.acelib.gui;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.smile.acelib.diagnostics.Clock;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * 預設 {@link GuiService} 實作（Internal）。
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
 *   <li>{@link GuiServiceControl#shutdownService()} 標記 stopped 並清除所有
 *       active session；既有的 session 不再可被 close（會回 SHUTDOWN）</li>
 * </ul>
 *
 * <h2>Player reference 處理</h2>
 * <p>本類別不接受 {@link Player} 為欄位；{@link #openInventory} 接收
 * {@link GuiArgument}，內部只保留 UUID。實際開啟 inventory 時透過
 * {@link Server#getPlayer(UUID)} 拿當下 Player 物件（已存在的 inventory
 * reference 立即釋放）。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴；透過
 * {@link GuiService#forProduction(com.smile.acelib.scheduler.SafeScheduler)}
 * 或 {@link com.smile.acelib.AceLibApi} 取得 {@link GuiService} 介面。
 * 插件隔離的作用域操作（開啟取代、輸入提示）走同套件
 * {@link ScopedGuiOperations} 橋接，不經公開介面。</p>
 *
 * @see GuiService
 * @since 1.0.0
 */
final class GuiServiceImpl
        implements GuiService, GuiServiceControl, ScopedGuiOperations {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final GuiSessionRegistry registry = new GuiSessionRegistry();
    private final AtomicBoolean running = new AtomicBoolean(true);
    /**
     * 待確認 action 表（confirmation/cancellation）。
     * key 為服務產生的不透明 action token；value 為對應 {@link PendingAction}。
     * 不持有 {@code Player} reference — 僅保存 UUID 與 domain callback。
     */
    private final ConcurrentMap<String, PendingAction> pendingActions
        = new ConcurrentHashMap<>();
    /**
     * 每個玩家目前的 request generation（非同步更新 stale 防護）。
     * key 為玩家 UUID；value 為該玩家目前有效（最大）的 request generation。
     * {@link #beginAsyncUpdate} 每次呼叫遞增並取得新值；{@link #applyAsyncUpdate}
     * 比對請求的 requestGeneration 是否仍等於此值，否則視為過時（被取代）。
     * 不持有 {@code Player} reference — 僅保存 UUID 與 long 計數。
     */
    private final ConcurrentMap<UUID, AtomicLong> requestGenerations
        = new ConcurrentHashMap<>();
    /**
     * 待輸入提示表（聊天／鐵砧）。
     * key 為服務產生的不透明票券；value 為對應 {@link PendingInput}。
     * 不持有 {@code Player} reference — 僅保存 UUID 與回呼。
     */
    private final ConcurrentMap<UUID, PendingInput> pendingInputs
        = new ConcurrentHashMap<>();
    /**
     * 時間來源（輸入逾時判斷用）。production 與預設建構走系統時鐘；
     * 測試可經注入式建構子傳入可手動推進的時鐘，全程不需 sleep。
     */
    private final Clock clock;
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
        this(PlayerContextExecutor.noop(), Clock.system());
    }

    /**
     * 注入式建構子：透過 {@link PlayerContextExecutor} 把 inventory mutation
     * 派送到玩家 region context。
     *
     * @param executor 派送 adapter；不可為 null
     */
    GuiServiceImpl(PlayerContextExecutor executor) {
        this(executor, Clock.system());
    }

    /**
     * 注入式建構子：同時注入派送 adapter 與時間來源（輸入逾時測試 seam）。
     *
     * @param executor 派送 adapter；不可為 null
     * @param clock 時間來源；不可為 null
     */
    GuiServiceImpl(PlayerContextExecutor executor, Clock clock) {
        this.playerContextExecutor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
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
     * @since 1.0.0
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
        // Legacy 路徑：擁有者固定為內部標記，既有 session 時拒絕（SESSION_EXISTS），
        // 行為與 1.3.x 一致；作用域導航改走 openOwned（取代語意）。
        OwnedOpenOutcome outcome = openInternal("acelib", argument.playerUuid(),
            argument.title(), GuiView.Kind.CHEST, argument.size(),
            argument.protectedSlots(), false);
        return outcome.result();
    }

    /**
     * 統一開啟入口（legacy 與作用域共用）。
     *
     * @param owner 擁有者標記；不可為 null
     * @param playerUuid 目標玩家；不可為 null
     * @param title 視圖標題；不可為 null
     * @param kind inventory 種類；不可為 null
     * @param size 總格數（CHEST 用；ANVIL 忽略，固定 3 格）
     * @param protectedSlots 受保護集合；可為 null（視為空集合）
     * @param replaceExisting 已有 session 時取代或拒絕
     * @return 開啟結果＋被取代的舊 session（無取代時為 null）
     */
    private OwnedOpenOutcome openInternal(String owner, UUID playerUuid, String title,
            GuiView.Kind kind, int size, Set<Integer> protectedSlots,
            boolean replaceExisting) {
        return openInternal(owner, playerUuid, title, kind, size, protectedSlots,
            replaceExisting, null);
    }

    /**
     * 統一開啟入口（含按鈕物品）。
     *
     * @param buttonIcons 按鈕物品表（欄位 → 物品快照）；可為 null（視為無物品）。
     *     開啟時於玩家 region context 內放入與按鈕相同的欄位；
     *     單一欄位放置失敗只記錄，不影響開啟結果與點擊語意
     */
    private OwnedOpenOutcome openInternal(String owner, UUID playerUuid, String title,
            GuiView.Kind kind, int size, Set<Integer> protectedSlots,
            boolean replaceExisting,
            Map<Integer, ItemStack> buttonIcons) {
        if (!running.get()) {
            return new OwnedOpenOutcome(GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown"), null);
        }
        // 先透過 UUID 取得當下 Player reference；caller 不持有，內部使用完即釋放。
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return new OwnedOpenOutcome(GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "player offline or not found: uuid=" + playerUuid), null);
        }
        int slots = kind == GuiView.Kind.ANVIL ? 3 : size;
        // 先註冊 session — listener 透過 generation 與 UUID 識別 ownership
        GuiSession session;
        GuiSession replaced = null;
        try {
            session = registry.startSession(playerUuid, owner, slots,
                protectedSlots, title);
        } catch (IllegalStateException exists) {
            if (!replaceExisting) {
                return new OwnedOpenOutcome(GuiResult.rejected(GuiErrorCode.SESSION_EXISTS,
                    exists.getMessage()), null);
            }
            // 取代：先結束舊 session（含票券／非同步序號／輸入提示），再建新 session。
            // 取代具破壞性 — 開啟失敗時舊 session 無法復原，呼叫端（作用域）負責通知。
            replaced = registry.endSession(playerUuid);
            invalidatePendingActions(playerUuid);
            requestGenerations.remove(playerUuid);
            invalidatePlayerInputs(playerUuid);
            try {
                session = registry.startSession(playerUuid, owner, slots,
                    protectedSlots, title);
            } catch (IllegalStateException raced) {
                return new OwnedOpenOutcome(GuiResult.rejected(GuiErrorCode.SESSION_EXISTS,
                    raced.getMessage()), replaced);
            }
        }
        final long generation = session.generation();
        final int openSize = slots;
        final Map<Integer, ItemStack> icons =
            buttonIcons == null || buttonIcons.isEmpty()
                ? Map.of()
                : Map.copyOf(buttonIcons);
        // 透過 player context 開 inventory + 放按鈕物品 + link + 實際開啟視窗；
        // Folia 下由 entity scheduler 派送，Paper 下走 main thread。
        boolean dispatched;
        try {
            dispatched = playerContextExecutor.runOnPlayerRegion(player, () -> {
                try {
                    Inventory inv = kind == GuiView.Kind.ANVIL
                        ? Bukkit.createInventory(null, InventoryType.ANVIL, title)
                        : Bukkit.createInventory(null, openSize, title);
                    placeButtonIcons(inv, icons);
                    GuiInventoryLink.link(inv, generation);
                    player.openInventory(inv);
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING,
                        "GuiService: failed to open inventory for uuid={0}: {1}",
                        new Object[] { playerUuid, t.getMessage() });
                    // 建立失敗時移除 session，避免殘留
                    registry.endSession(playerUuid);
                }
            });
        } catch (Throwable t) {
            // executor 派送丟例外（極少見，通常代表 dispatcher 本身失敗）
            LOGGER.log(Level.WARNING,
                "GuiService: player context executor failed: {0}", t.getMessage());
            registry.endSession(playerUuid);
            return new OwnedOpenOutcome(GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "executor failed: " + t.getMessage()), replaced);
        }
        if (!dispatched) {
            // player context executor 拒絕派送時：清理 session 並回報 scheduler rejection
            // （SafeScheduler 回傳 cancelled no-op task — 對應 scheduler disabled、
            // player offline、平台不支援等）時，舊實作仍回 SUCCESS 但實際未開 inventory，
            // 留下 stale session 與 player reference。
            // 修正：清理 session、回 FAILED + ACELIB-GUI-013 SCHEDULER_REJECTED。
            registry.endSession(playerUuid);
            LOGGER.log(Level.WARNING,
                "GuiService: player context executor refused dispatch for uuid={0} "
                    + "(scheduler disabled, player offline, or platform unsupported)",
                playerUuid);
            return new OwnedOpenOutcome(GuiResult.failed(GuiErrorCode.SCHEDULER_REJECTED,
                "player context executor refused dispatch for uuid="
                    + playerUuid), replaced);
        }
        return new OwnedOpenOutcome(GuiResult.success(session, "opened gui session"),
            replaced);
    }

    @Override
    public OwnedOpenOutcome openOwned(String owner, UUID playerUuid, String title,
            GuiView.Kind kind, int size, Set<Integer> protectedSlots,
            boolean replaceExisting) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(kind, "kind");
        return openInternal(owner, playerUuid, title, kind, size,
            protectedSlots, replaceExisting);
    }

    @Override
    public OwnedOpenOutcome openOwned(String owner, UUID playerUuid, String title,
            GuiView.Kind kind, int size, Set<Integer> protectedSlots,
            boolean replaceExisting,
            Map<Integer, ItemStack> buttonIcons) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(kind, "kind");
        return openInternal(owner, playerUuid, title, kind, size,
            protectedSlots, replaceExisting, buttonIcons);
    }

    /**
     * 於玩家 region context 內把按鈕物品放入與按鈕相同的欄位。
     *
     * <p>呼叫端保證已在 region context 內（見 {@link #openInternal} 的派送 lambda）。
     * 本方法是唯一的複製點：每次放置前即時 {@code clone}，inventory 持有的物品
     * 與宣告物品互不共享（同一宣告給多位玩家開啟時各自獨立）。
     * 每欄獨立 try/catch：單一欄位失敗（越界、物品異常）只記錄
     * （{@link GuiErrorCode#OPERATION_FAILED}），不影響開啟結果與後續點擊語意。</p>
     */
    private static void placeButtonIcons(Inventory inventory,
            Map<Integer, ItemStack> icons) {
        if (icons.isEmpty()) {
            return;
        }
        for (Map.Entry<Integer, ItemStack> entry
                : icons.entrySet()) {
            int slot = entry.getKey();
            if (slot < 0 || slot >= inventory.getSize()) {
                LOGGER.log(Level.FINE,
                    "GuiService: button icon slot out of range (skipped): slot={0}",
                    slot);
                continue;
            }
            try {
                ItemStack icon = entry.getValue();
                if (icon != null) {
                    inventory.setItem(slot, icon.clone());
                }
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING,
                    "[" + GuiErrorCode.OPERATION_FAILED
                        + "] GuiService: button icon placement failed (ignored): slot="
                        + slot,
                    t);
            }
        }
    }

    @Override
    public GuiResult closeOwned(String owner, UUID playerUuid, long generation) {        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(playerUuid, "playerUuid");
        GuiSession session = registry.getSession(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (!session.owner().equals(owner)) {
            return GuiResult.rejected(GuiErrorCode.NOT_OWNER,
                "session owned by " + session.owner() + ", not " + owner);
        }
        return closeInventory(playerUuid, generation);
    }

    @Override
    public OwnedOpenOutcome openFormSessionOwned(String owner, UUID playerUuid,
            String title) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(title, "title");
        if (!running.get()) {
            return new OwnedOpenOutcome(GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown"), null);
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return new OwnedOpenOutcome(GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "player offline or not found: uuid=" + playerUuid), null);
        }
        GuiSession replaced = registry.endSession(playerUuid);
        if (replaced != null) {
            invalidatePendingActions(playerUuid);
            requestGenerations.remove(playerUuid);
            invalidatePlayerInputs(playerUuid);
        }
        GuiSession session;
        try {
            session = registry.startSession(playerUuid, owner, 9,
                Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8), title);
        } catch (IllegalStateException | IllegalArgumentException raced) {
            return new OwnedOpenOutcome(GuiResult.rejected(GuiErrorCode.SESSION_EXISTS,
                raced.getMessage()), replaced);
        }
        return new OwnedOpenOutcome(GuiResult.success(session, "opened form session"),
            replaced);
    }

    @Override
    public GuiSession currentSession(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        return registry.getSession(playerUuid);
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
        // 同時失效該玩家的待輸入提示，避免關閉後送出仍執行 consumer
        invalidatePlayerInputs(playerUuid);
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
    // 非同步更新請求合約
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
    // 確認 / 取消 action contract
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
        final Runnable callback;
        final GuiSession confirmedSession;
        synchronized (action) {
            if (action.state != GuiConfirmation.State.PENDING) {
                return GuiResult.rejected(GuiErrorCode.ACTION_ALREADY_RESOLVED,
                    "action already resolved (state=" + action.state
                        + "), token=" + actionToken);
            }
            action.state = GuiConfirmation.State.CONFIRMED;
            callback = action.callback;
            confirmedSession = action.session;
        }
        // 狀態轉換完成、離開鎖之後才執行 callback：callback 進行中不得
        // 持有 action 的監視器，否則並行 confirm/cancel 會被 callback 阻塞。
        // 恰好執行一次仍由鎖內的狀態轉換保證 — 只有搶到 PENDING→CONFIRMED
        // 的執行緒會走到這裡。
        try {
            callback.run();
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
        // 解析後保留 entry（state 已非 PENDING）以便重複 confirm/cancel 回
        // ACTION_ALREADY_RESOLVED；session 結束或 shutdown 時統一清理。
        return GuiResult.success(confirmedSession);
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

    // -----------------------------------------------------------------
    // 輸入提示（聊天／鐵砧）
    // -----------------------------------------------------------------

    @Override
    public InputPromptOutcome promptInputOwned(String owner, UUID playerUuid,
            long generation, GuiInputPrompt prompt, Consumer<GuiInputResult> consumer) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(consumer, "consumer");
        if (!running.get()) {
            return new InputPromptOutcome(GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown"), null, null);
        }
        GuiSession session = registry.getSession(playerUuid);
        if (session == null) {
            return new InputPromptOutcome(GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid), null, null);
        }
        if (!session.owner().equals(owner)) {
            return new InputPromptOutcome(GuiResult.rejected(GuiErrorCode.NOT_OWNER,
                "session owned by " + session.owner() + ", not " + owner), null, null);
        }
        if (session.generation() != generation) {
            return new InputPromptOutcome(GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + session.generation()
                    + " but got " + generation), null, null);
        }
        pruneExpiredInputs(playerUuid);
        if (prompt.kind() == GuiInputKind.CHAT) {
            UUID token = UUID.randomUUID();
            PendingInput entry = new PendingInput(token, owner, playerUuid, generation,
                prompt, consumer, clock.currentTimeMillis());
            pendingInputs.put(token, entry);
            GuiInputTicket ticket = new GuiInputTicket(token, playerUuid, generation,
                prompt.kind());
            return new InputPromptOutcome(GuiResult.success(session), ticket, null);
        }
        // ANVIL：開啟鐵砧視圖（取代語意），再登記輸入提示。
        OwnedOpenOutcome opened = openInternal(owner, playerUuid, prompt.title(),
            GuiView.Kind.ANVIL, 3, Set.of(0, 1, 2), true);
        if (!opened.result().isSuccess()) {
            return new InputPromptOutcome(opened.result(), null, opened.replaced());
        }
        GuiSession anvilSession = opened.result().session();
        UUID token = UUID.randomUUID();
        PendingInput entry = new PendingInput(token, owner, playerUuid,
            anvilSession.generation(), prompt, consumer, clock.currentTimeMillis());
        pendingInputs.put(token, entry);
        GuiInputTicket ticket = new GuiInputTicket(token, playerUuid,
            anvilSession.generation(), prompt.kind());
        return new InputPromptOutcome(GuiResult.success(anvilSession), ticket,
            opened.replaced());
    }

    @Override
    public GuiResult submitInputOwned(String owner, UUID token, String text) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(text, "text");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown");
        }
        PendingInput entry = pendingInputs.get(token);
        if (entry == null) {
            return GuiResult.rejected(GuiErrorCode.INPUT_EXPIRED,
                "unknown or expired input token=" + token);
        }
        if (!entry.owner.equals(owner)) {
            return GuiResult.rejected(GuiErrorCode.NOT_OWNER,
                "input owned by " + entry.owner + ", not " + owner);
        }
        return deliverInput(entry, text);
    }

    @Override
    public GuiResult routeChatInput(UUID playerUuid, String text) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(text, "text");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown");
        }
        GuiSession session = registry.getSession(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.INPUT_EXPIRED,
                "no active session for uuid=" + playerUuid);
        }
        // 聊天路由給目前 session 擁有者的最新待處理聊天提示；
        // 多個 plugin 同時提示同一玩家時，以畫面上 GUI 的擁有者為準。
        PendingInput latest = null;
        for (PendingInput candidate : pendingInputs.values()) {
            if (candidate.kind != GuiInputKind.CHAT
                    || !candidate.playerUuid.equals(playerUuid)
                    || !candidate.owner.equals(session.owner())
                    || candidate.generation != session.generation()) {
                continue;
            }
            if (latest == null || candidate.createdAtMillis > latest.createdAtMillis) {
                latest = candidate;
            }
        }
        if (latest == null) {
            return GuiResult.rejected(GuiErrorCode.INPUT_EXPIRED,
                "no pending chat prompt for uuid=" + playerUuid);
        }
        return deliverInput(latest, text);
    }

    /**
     * 派送輸入文字到 consumer（一次性）。
     *
     * <p>先做同步預檢（session／generation／逾時／長度／在線），再經 player context
     * executor 派送並於執行前重新驗證（deferred race 防護，比照
     * {@link #applyAsyncUpdate}）。超長文字被拒時票券保留，可重試。</p>
     */
    private GuiResult deliverInput(PendingInput entry, String text) {
        UUID playerUuid = entry.playerUuid;
        GuiSession session = registry.getSession(playerUuid);
        if (session == null) {
            pendingInputs.remove(entry.token, entry);
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (session.generation() != entry.generation) {
            pendingInputs.remove(entry.token, entry);
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "session generation changed: expected " + entry.generation
                    + " but current " + session.generation());
        }
        if (entry.prompt.timeoutMillis() > 0L
                && clock.currentTimeMillis() - entry.createdAtMillis
                    > entry.prompt.timeoutMillis()) {
            pendingInputs.remove(entry.token, entry);
            return GuiResult.rejected(GuiErrorCode.INPUT_EXPIRED,
                "input prompt expired for uuid=" + playerUuid);
        }
        if (text.length() > entry.prompt.maxLength()) {
            return GuiResult.rejected(GuiErrorCode.INVALID_INPUT,
                "input text too long: " + text.length()
                    + " (max=" + entry.prompt.maxLength() + ")");
        }
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return GuiResult.rejected(GuiErrorCode.PLAYER_OFFLINE,
                "player offline when input submitted: uuid=" + playerUuid);
        }
        if (!entry.handled.compareAndSet(false, true)) {
            return GuiResult.rejected(GuiErrorCode.INPUT_EXPIRED,
                "input already submitted for uuid=" + playerUuid);
        }
        GuiInputResult delivered = new GuiInputResult(playerUuid, entry.generation,
            entry.kind, text);
        AtomicReference<GuiResult> outcome = new AtomicReference<>();
        boolean dispatched;
        try {
            dispatched = playerContextExecutor.runOnPlayerRegion(player, () -> {
                try {
                    if (!running.get()) {
                        outcome.set(GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                            "gui service is shutdown before input delivered: uuid="
                                + playerUuid));
                        return;
                    }
                    GuiSession current = registry.getSession(playerUuid);
                    if (current == null) {
                        outcome.set(GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                            "no active session for uuid=" + playerUuid
                                + " before input delivered"));
                        return;
                    }
                    if (current.generation() != entry.generation) {
                        outcome.set(GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                            "session generation changed before input delivered"));
                        return;
                    }
                    if (pendingInputs.get(entry.token) != entry) {
                        outcome.set(GuiResult.rejected(GuiErrorCode.INPUT_EXPIRED,
                            "input no longer pending before delivery: uuid="
                                + playerUuid));
                        return;
                    }
                    if (Bukkit.getPlayer(playerUuid) == null) {
                        outcome.set(GuiResult.rejected(GuiErrorCode.PLAYER_OFFLINE,
                            "player offline before input delivered: uuid="
                                + playerUuid));
                        return;
                    }
                    entry.consumer.accept(delivered);
                    outcome.set(GuiResult.success(current, "input delivered"));
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING,
                        "GuiService: input consumer failed for uuid={0}: {1}",
                        new Object[] { playerUuid, t.getMessage() });
                    outcome.set(GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                        "input consumer failed: " + t.getMessage()));
                } finally {
                    pendingInputs.remove(entry.token, entry);
                }
            });
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING,
                "GuiService: input executor dispatch failed: {0}", t.getMessage());
            pendingInputs.remove(entry.token, entry);
            return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "executor failed: " + t.getMessage());
        }
        if (!dispatched) {
            pendingInputs.remove(entry.token, entry);
            return GuiResult.failed(GuiErrorCode.SCHEDULER_REJECTED,
                "player context executor refused dispatch for uuid=" + playerUuid);
        }
        GuiResult result = outcome.get();
        if (result != null) {
            return result;
        }
        return GuiResult.accepted(session,
            "input dispatched; consumer will run on player region after revalidation");
    }

    /**
     * 使指定玩家的所有待輸入提示失效（session 結束時呼叫）。
     *
     * <p>失效後送出會因票券不存在而回 {@code INPUT_EXPIRED}，
     * consumer 永不執行。</p>
     */
    private void invalidatePlayerInputs(UUID playerUuid) {
        pendingInputs.entrySet().removeIf(e -> e.getValue().playerUuid.equals(playerUuid));
    }

    /**
     * 清除該玩家已逾時的輸入提示（提示建立與送出時的順手清理，避免長期殘留）。
     */
    private void pruneExpiredInputs(UUID playerUuid) {
        long now = clock.currentTimeMillis();
        pendingInputs.entrySet().removeIf(e -> {
            PendingInput entry = e.getValue();
            return entry.playerUuid.equals(playerUuid)
                && entry.prompt.timeoutMillis() > 0L
                && now - entry.createdAtMillis > entry.prompt.timeoutMillis();
        });
    }

    @Override
    public String getModuleStatus() {
        return running.get() ? "READY" : "FAILED";
    }

    @Override
    public void shutdownService() {
        if (!running.compareAndSet(true, false)) {
            return; // idempotent
        }
        // 先清空 link：listener 後續事件仍可能被 Bukkit dispatch（雖然已 unregister），
        // 確保它看到「沒有 active link」就 early return。
        GuiInventoryLink.clear();
        registry.clear();
        pendingActions.clear();
        requestGenerations.clear();
        pendingInputs.clear();
    }

    // -----------------------------------------------------------------
    // 內部 cleanup（被 Bukkit InventoryCloseEvent 觸發）
    // -----------------------------------------------------------------

    /**
     * 內部清理：移除玩家目前 session 與待處理狀態，<strong>不驗證 generation</strong>。
     * generation 與關閉視窗的比對由 {@link #handleClose(InventoryCloseEvent)} 在呼叫前完成。
     *
     * <p>若遊戲內關閉目前 GUI（例如玩家按 ESC、視窗被伺服器關閉），內部 listener
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
        invalidatePlayerInputs(playerUuid);
    }

    @Override
    public void handleQuit(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        internalCleanup(playerUuid);
        GuiScopes.dropPlayer(playerUuid);
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
     * <p>作用域視圖（經 {@link GuiScope} 開啟）的點擊改走視圖規則
     * （按鈕回呼／放行欄位／預設全擋），不再經 legacy
     * {@link #validateClick}；legacy session 維持原行為。</p>
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
        GuiSession session = registry.getSession(p.getUniqueId());
        GuiScope scope = session == null ? null
            : GuiScopes.findByOwner(session.owner());
        if (scope != null && scope.hasViewState(p.getUniqueId(), generation)) {
            GuiResult result = scope.dispatchClick(p.getUniqueId(), generation,
                event.getRawSlot(), readAnvilText(top));
            // 只有 ALLOWED（放行欄位、由遊戲邏輯繼續處理）不取消；
            // 按鈕已處理／被拒一律取消，避免物品被拿走或放入。
            if (!result.isAllowed()) {
                event.setCancelled(true);
            }
            if (result.isRejected()) {
                LOGGER.log(Level.FINE,
                    "GuiService: blocked scoped click (slot={0}, code={1}, detail={2})",
                    new Object[] { event.getRawSlot(), result.errorCode(),
                        result.detail() });
            }
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
     * 讀取鐵砧視圖當下的更名文字（供作用域按鈕回呼）。
     *
     * @return 更名文字；非鐵砧視圖或讀取失敗時為 empty
     */
    private static Optional<String> readAnvilText(Inventory top) {
        if (top instanceof AnvilInventory anvil) {
            try {
                return Optional.ofNullable(anvil.getRenameText());
            } catch (Throwable t) {
                LOGGER.log(Level.FINE,
                    "GuiService: failed to read anvil rename text (ignored): {0}",
                    t.getMessage());
            }
        }
        return Optional.empty();
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
     * 處理 {@link InventoryCloseEvent}：解除視窗綁定，並清理非過時的關閉事件。
     *
     * <p>僅在目前不存在 session，或關閉視窗仍有 linked generation 且其 generation
     * 與目前 session 相同時，才清理目前 session。generation 為 null 的未識別視窗，
     * 以及 generation 與目前 session 不同的舊視窗，都只解除 inventory link 並保留
     * 目前 session。</p>
     *
     * @param event Bukkit 派送的 close event；不可為 null
     */
    public void handleClose(InventoryCloseEvent event) {
        Objects.requireNonNull(event, "event");
        if (!running.get()) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Long closingGeneration = GuiInventoryLink.generationOf(top);
        GuiInventoryLink.unlink(top);
        org.bukkit.entity.HumanEntity who = event.getPlayer();
        if (who instanceof Player p) {
            GuiSession current = registry.getSession(p.getUniqueId());
            boolean closingOwnedByCurrent = closingGeneration != null
                && current != null
                && current.generation() == closingGeneration.longValue();
            if (current == null || closingOwnedByCurrent) {
                internalCleanup(p.getUniqueId());
            }
            // 通知各作用域：該 generation 的視圖狀態失效（手動關閉）。
            // 取代導航產生的舊視窗關閉事件 generation 與目前不同，會被忽略。
            GuiScopes.notifyInventoryClosed(p.getUniqueId(), closingGeneration);
        }
    }

    /**
     * 路由玩家聊天訊息到待處理的聊天輸入提示（供聊天 listener）。
     *
     * <p>成功（SUCCESS／ACCEPTED）表示訊息已被某個提示消耗，
     * 呼叫端應取消事件；其餘狀態表示無命中，放行。</p>
     *
     * @param playerUuid 發訊玩家；不可為 null
     * @param message 聊天訊息；不可為 null
     * @return 路由結果
     */
    public GuiResult handleChatInput(UUID playerUuid, String message) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(message, "message");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown");
        }
        return routeChatInput(playerUuid, message);
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
     * 待確認 action 內部狀態。
     *
     * <p>不可變欄位記錄綁定資訊；{@link #state} 為唯一可變欄位，僅在
     * {@code synchronized(action)} 區塊內由 confirm/cancel 轉換，確保 callback
     * 恰好執行一次。callback 本身在狀態轉換完成、離開鎖之後才執行，
     * 不得在持有監視器的狀態下運行。本物件不持有 {@code Player} reference。</p>
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

    /**
     * 待輸入提示內部狀態。
     *
     * <p>不可變欄位記錄綁定資訊；{@link #handled} 以 CAS 保證 consumer
     * 恰好執行一次（聊天路由與票券送出的競爭由該旗標線性化）。
     * consumer 本身在玩家 region context 內、經重新驗證後才執行。
     * 本物件不持有 {@code Player} reference。</p>
     */
    private static final class PendingInput {
        final UUID token;
        final String owner;
        final UUID playerUuid;
        final long generation;
        final GuiInputKind kind;
        final GuiInputPrompt prompt;
        final Consumer<GuiInputResult> consumer;
        final long createdAtMillis;
        final AtomicBoolean handled = new AtomicBoolean(false);

        PendingInput(UUID token, String owner, UUID playerUuid, long generation,
                GuiInputPrompt prompt, Consumer<GuiInputResult> consumer,
                long createdAtMillis) {
            this.token = token;
            this.owner = owner;
            this.playerUuid = playerUuid;
            this.generation = generation;
            this.kind = prompt.kind();
            this.prompt = prompt;
            this.consumer = consumer;
            this.createdAtMillis = createdAtMillis;
        }
    }
}
