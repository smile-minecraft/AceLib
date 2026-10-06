package com.smile.acelib.gui;

import com.smile.acelib.command.CooldownTracker;
import com.smile.acelib.diagnostics.Clock;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 單一 plugin 的 GUI 作用域 handle（Supported API）。
 *
 * <p>持有該 plugin 專屬的導航歷史、按鈕冷卻與檢視狀態；底層 session 登記由
 * 各作用域共用（同一 {@link GuiService} 實例）。實例由 {@link GuiScopes}
 * 統一建立與清理；下游只操作自己持有的 handle，只能操作自己開的 GUI —
 * 跨 plugin 操作回 {@code NOT_OWNER}，新 GUI 取代其他 plugin 的 GUI 時
 * 原擁有者會收到 {@link GuiReplacementListener} 通知。</p>
 *
 * <h2>導航</h2>
 * <p>{@code open}／{@code push}／{@code replace}／{@code back}／{@code close}
 * 五個操作共用同一份歷史：每次切換畫面都開啟新 session（generation 遞增），
 * 舊 generation 的點擊、票券、輸入一律失效。{@code back} 無歷史時回
 * {@code NO_PREVIOUS_VIEW}。</p>
 *
 * <h2>生命週期</h2>
 * <p>{@code onEnable} 時 {@code create}，{@code onDisable} 時 {@link #close()}。
 * {@code close} 會結束本作用域開啟中的 GUI（只清自己，不碰其他 plugin），
 * 具冪等性。有結果通道的操作在關閉後使用回 {@code SCOPE_CLOSED}；
 * 無通道的提示方法（{@code promptChat}／{@code promptAnvil}）則拋
 * {@link IllegalStateException}（比照表單服務生命週期語意）。</p>
 *
 * <h2>執行緒</h2>
 * <p>內部只用 concurrent 集合，不持有內部鎖；按鈕回呼、確認 callback、
 * 重新驗證、輸入 consumer 都不在鎖內執行。Inventory 事件觸發的回呼執行於
 * 玩家 region context — 不得在其中做長時間工作或跨 region 操作。</p>
 *
 * @since 1.4.0
 */
public final class GuiScope {

    /** 導航歷史上限（避免異常迴圈無限加深；超過時丟棄最舊）。 */
    static final int MAX_HISTORY = 64;

    /** 鐵砧結果欄位編號（固定：0、1 輸入，2 結果）。 */
    static final int ANVIL_RESULT_SLOT = 2;

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final JavaPlugin plugin;
    private final String ownerName;
    private final Supplier<GuiService> services;
    private final Clock clock;
    private final FormService forms;
    private final Predicate<UUID> bedrockProbe;
    private final ConcurrentMap<UUID, ScopePlayerState> states =
        new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<GuiReplacementListener> replacementListeners =
        new CopyOnWriteArrayList<>();
    private final CooldownTracker cooldowns;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    GuiScope(JavaPlugin plugin, String ownerName, Supplier<GuiService> services,
            Clock clock, FormService forms, Predicate<UUID> bedrockProbe) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.ownerName = Objects.requireNonNull(ownerName, "ownerName");
        this.services = Objects.requireNonNull(services, "services");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.forms = forms;
        this.bedrockProbe = bedrockProbe;
        this.cooldowns = new CooldownTracker(clock);
    }

    /** 擁有此作用域的 plugin。 */
    public JavaPlugin plugin() {
        return plugin;
    }

    /** 擁有者標記（session 上的 owner 值）。 */
    public String ownerName() {
        return ownerName;
    }

    /** 是否已關閉。 */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * 註冊 GUI 被取代通知（可多個；取代發生時依序觸發）。
     *
     * @param listener 通知回呼；不可為 null
     */
    public void onReplaced(GuiReplacementListener listener) {
        requireArg(listener, "listener");
        requireOpen();
        replacementListeners.add(listener);
    }

    /**
     * 關閉此作用域並從工廠登記移除（具冪等性；只結束自己的 GUI）。
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            GuiScopes.unregister(plugin, this);
            GuiService service = services.get();
            ScopedGuiOperations ops = service instanceof ScopedGuiOperations o ? o : null;
            for (UUID player : states.keySet()) {
                try {
                    if (ops != null) {
                        GuiSession session = ops.currentSession(player);
                        if (session != null && session.owner().equals(ownerName)) {
                            ops.closeOwned(ownerName, player, session.generation());
                        }
                    }
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE,
                        "GuiScope: close owned session failed (ignored): uuid={0}: {1}",
                        new Object[] {player, t.getMessage()});
                }
            }
            states.clear();
            cooldowns.clearAll();
            replacementListeners.clear();
        }
    }

    // -----------------------------------------------------------------
    // 導航
    // -----------------------------------------------------------------

    /**
     * 開啟視圖（全新開始：清空自身歷史；取代其他 plugin 的 GUI 時通知對方）。
     */
    public GuiResult openView(UUID playerUuid, GuiView view) {
        requireArg(playerUuid, "playerUuid");
        requireArg(view, "view");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        OwnedOpenOutcome outcome = ops.openOwned(ownerName, playerUuid, view.title(),
            view.kind(), view.size(), allSlots(view), true);
        if (!outcome.result().isSuccess()) {
            states.remove(playerUuid);
            return outcome.result();
        }
        notifyIfForeign(outcome.replaced(), playerUuid, outcome.result().session());
        ScopePlayerState state = new ScopePlayerState();
        state.generation = outcome.result().session().generation();
        pushEntry(state, new ViewEntry(view, null, null));
        states.put(playerUuid, state);
        return outcome.result();
    }

    /**
     * 推入新視圖（保留歷史，可 {@code back}）。
     */
    public GuiResult pushView(UUID playerUuid, GuiView view) {
        requireArg(playerUuid, "playerUuid");
        requireArg(view, "view");
        return navigate(playerUuid, view, null, null, NavMode.PUSH);
    }

    /**
     * 取代目前視圖（歷史頂換成新視圖，不增加返回層數）。
     */
    public GuiResult replaceView(UUID playerUuid, GuiView view) {
        requireArg(playerUuid, "playerUuid");
        requireArg(view, "view");
        return navigate(playerUuid, view, null, null, NavMode.REPLACE);
    }

    /**
     * 回到上一頁（無歷史時回 {@code NO_PREVIOUS_VIEW}）。
     */
    public GuiResult back(UUID playerUuid) {
        requireArg(playerUuid, "playerUuid");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        ScopePlayerState state = states.get(playerUuid);
        if (state == null || state.history.size() < 2) {
            return GuiResult.rejected(GuiErrorCode.NO_PREVIOUS_VIEW,
                "no previous view for uuid=" + playerUuid);
        }
        GuiSession live = checkedSession(ops, playerUuid);
        if (live == null) {
            states.remove(playerUuid);
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (!live.owner().equals(ownerName)) {
            return GuiResult.rejected(GuiErrorCode.NOT_OWNER,
                "session owned by " + live.owner() + ", not " + ownerName);
        }
        ViewEntry discarded = state.history.pollLast();
        ViewEntry previous = state.history.peekLast();
        if (previous == null) {
            if (discarded != null) {
                state.history.addLast(discarded);
            }
            return GuiResult.rejected(GuiErrorCode.NO_PREVIOUS_VIEW,
                "no previous view for uuid=" + playerUuid);
        }
        GuiResult reopened = renderEntry(ops, playerUuid, state, previous, true);
        if (!reopened.isSuccess()) {
            state.history.addLast(discarded);
            return reopened;
        }
        return reopened;
    }

    /**
     * 關閉目前 GUI（只關自己的；他人擁有回 {@code NOT_OWNER}）。
     */
    public GuiResult close(UUID playerUuid) {
        requireArg(playerUuid, "playerUuid");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        GuiSession session = ops.currentSession(playerUuid);
        if (session == null) {
            states.remove(playerUuid);
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (!session.owner().equals(ownerName)) {
            return GuiResult.rejected(GuiErrorCode.NOT_OWNER,
                "session owned by " + session.owner() + ", not " + ownerName);
        }
        GuiResult result = ops.closeOwned(ownerName, playerUuid, session.generation());
        states.remove(playerUuid);
        return result;
    }

    /**
     * 以目前視圖重開（reload 後恢復用：底層服務替換導致舊 session 全失時，
     * 以歷史頂重建 session）。
     */
    public GuiResult reopen(UUID playerUuid) {
        requireArg(playerUuid, "playerUuid");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        ScopePlayerState state = states.get(playerUuid);
        ViewEntry top = state == null ? null : state.history.peekLast();
        if (top == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no view to reopen for uuid=" + playerUuid);
        }
        return renderEntry(ops, playerUuid, state, top, false);
    }

    /**
     * 讀取目前視圖（渲染用）。
     *
     * @return 目前視圖；無視圖／session 已失效／他人擁有時為 empty
     */
    public Optional<GuiView> viewOf(UUID playerUuid) {
        requireArg(playerUuid, "playerUuid");
        requireOpen();
        GuiService service = services.get();
        if (!(service instanceof ScopedGuiOperations ops)) {
            return Optional.empty();
        }
        ScopePlayerState state = states.get(playerUuid);
        ViewEntry top = state == null ? null : state.history.peekLast();
        if (top == null) {
            return Optional.empty();
        }
        GuiSession session = ops.currentSession(playerUuid);
        if (session == null || !session.owner().equals(ownerName)
                || session.generation() != state.generation) {
            return Optional.empty();
        }
        return Optional.of(top.view());
    }

    // -----------------------------------------------------------------
    // 共用流程
    // -----------------------------------------------------------------

    /**
     * 開啟共用流程（Java 玩家走 inventory 視圖，基岩玩家走原生表單）。
     */
    public GuiResult openFlow(UUID playerUuid, GuiFlow flow) {
        requireArg(playerUuid, "playerUuid");
        requireArg(flow, "flow");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        GuiFlowStep start = flow.step(flow.startStepId());
        states.remove(playerUuid);
        ScopePlayerState state = new ScopePlayerState();
        state.flow = flow;
        state.stepId = start.id();
        if (isBedrock(playerUuid) && start.form() != null) {
            return sendStepForm(ops, playerUuid, state, flow, start, true);
        }
        GuiResult opened = navigateFresh(ops, playerUuid, state, start.view(),
            start.id(), start.form());
        return opened;
    }

    /**
     * 前進到流程指定步驟（未知步驟為程式設計錯誤，拋例外）。
     */
    public GuiResult goTo(UUID playerUuid, String stepId) {
        requireArg(playerUuid, "playerUuid");
        requireArg(stepId, "stepId");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        ScopePlayerState state = states.get(playerUuid);
        if (state == null || state.flow == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active flow for uuid=" + playerUuid);
        }
        GuiFlowStep step = state.flow.step(stepId);
        if (step == null) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 未知的流程步驟: " + stepId);
        }
        GuiSession live = checkedSession(ops, playerUuid);
        if (live == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (isBedrock(playerUuid) && step.form() != null) {
            return sendStepForm(ops, playerUuid, state, state.flow, step, true);
        }
        if (state.history.isEmpty()) {
            return navigateFresh(ops, playerUuid, state, step.view(), step.id(),
                step.form());
        }
        return openViewSession(ops, playerUuid, state, step.view(), step.id(),
            step.form(), NavMode.PUSH);
    }

    // -----------------------------------------------------------------
    // 點擊
    // -----------------------------------------------------------------

    /**
     * 處理點擊（供服務 listener 與自訂 inventory 處理）。
     *
     * <p>按鈕欄位執行專屬回呼（回 {@code SUCCESS}），放行欄位回
     * {@code ALLOWED}（呼叫端不取消事件），其餘回 {@code SLOT_PROTECTED}。
     * 按鈕路徑與欄位保護相互獨立 — 不得以保護拒絕冒充按鈕事件。</p>
     */
    public GuiResult handleClick(UUID playerUuid, long generation, int slot) {
        return dispatchClick(playerUuid, generation, slot, Optional.empty());
    }

    /**
     * 處理點擊（含鐵砧更名文字）。
     *
     * @param anvilText 鐵砧視圖結果欄位當下的更名文字；非鐵砧視圖忽略
     */
    public GuiResult handleClick(UUID playerUuid, long generation, int slot,
            Optional<String> anvilText) {
        requireArg(playerUuid, "playerUuid");
        requireArg(anvilText, "anvilText");
        return dispatchClick(playerUuid, generation, slot, anvilText);
    }

    /**
     * 點擊分派實作（服務 listener 入口）。
     */
    public GuiResult dispatchClick(UUID playerUuid, long generation, int slot,
            Optional<String> anvilText) {
        requireArg(playerUuid, "playerUuid");
        requireArg(anvilText, "anvilText");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        if (slot < 0) {
            return GuiResult.rejected(GuiErrorCode.INVALID_INPUT,
                "slot out of range: " + slot);
        }
        GuiSession session = ops.currentSession(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (!session.owner().equals(ownerName)) {
            return GuiResult.rejected(GuiErrorCode.NOT_OWNER,
                "session owned by " + session.owner() + ", not " + ownerName);
        }
        if (session.generation() != generation) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + session.generation()
                    + " but got " + generation);
        }
        ScopePlayerState state = states.get(playerUuid);
        ViewEntry top = state == null ? null : state.history.peekLast();
        if (top == null || state.generation != generation) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active view for uuid=" + playerUuid);
        }
        GuiView view = top.view();
        if (slot >= view.size()) {
            return GuiResult.rejected(GuiErrorCode.INVALID_INPUT,
                "slot out of range: " + slot + " (size=" + view.size() + ")");
        }
        // 鐵砧結果欄位＋待處理鐵砧輸入 → 自動送出（優先於按鈕）。
        if (view.kind() == GuiView.Kind.ANVIL && slot == ANVIL_RESULT_SLOT
                && state.anvilToken != null) {
            return ops.submitInputOwned(ownerName, state.anvilToken,
                anvilText.orElse(""));
        }
        GuiButton button = view.buttons().get(slot);
        if (button != null) {
            if (button.cooldownMillis() > 0L
                    && !cooldowns.tryAcquire(playerUuid, "gui-button:" + button.id(),
                        button.cooldownMillis())) {
                return GuiResult.rejected(GuiErrorCode.COOLDOWN_ACTIVE,
                    "button " + button.id() + " is cooling down for uuid="
                        + playerUuid);
            }
            Consumer<GuiButtonClick> handler = view.handlers().get(button.id());
            if (handler == null) {
                return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                    "button " + button.id() + " has no handler");
            }
            Optional<String> text = view.kind() == GuiView.Kind.ANVIL
                ? anvilText : Optional.empty();
            GuiButtonClick click = new GuiButtonClick(playerUuid, generation, slot,
                button.id(), text);
            try {
                handler.accept(click);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING,
                    "GuiScope: button handler failed for button={0}, uuid={1}: {2}",
                    new Object[] {button.id(), playerUuid, t.getMessage()});
                return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                    "button handler failed for " + button.id() + ": "
                        + t.getMessage());
            }
            return GuiResult.success(session, "button " + button.id() + " handled");
        }
        if (view.allowedSlots().contains(slot)) {
            return GuiResult.allowed(session);
        }
        return GuiResult.rejected(GuiErrorCode.SLOT_PROTECTED,
            "slot " + slot + " is protected");
    }

    // -----------------------------------------------------------------
    // 確認票券
    // -----------------------------------------------------------------

    /** 建立一次性確認票券（只限自己的 session）。 */
    public GuiResult createConfirmation(UUID playerUuid, long generation,
            String actionId, Runnable callback) {
        requireArg(playerUuid, "playerUuid");
        requireArg(actionId, "actionId");
        requireArg(callback, "callback");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        GuiService service = services.get();
        GuiResult ownership = checkOwnership(ops, playerUuid, generation);
        if (ownership != null) {
            return ownership;
        }
        return service.createConfirmation(playerUuid, generation, actionId, callback);
    }

    /** 確認並執行 domain action（一次性；只限自己的票券）。 */
    public GuiResult confirm(UUID playerUuid, long generation, String actionToken) {
        requireArg(playerUuid, "playerUuid");
        requireArg(actionToken, "actionToken");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        GuiService service = services.get();
        GuiResult ownership = checkOwnership(ops, playerUuid, generation);
        if (ownership != null) {
            return ownership;
        }
        return service.confirm(playerUuid, generation, actionToken);
    }

    /** 取消確認（不執行 callback；只限自己的票券）。 */
    public GuiResult cancel(UUID playerUuid, long generation, String actionToken) {
        requireArg(playerUuid, "playerUuid");
        requireArg(actionToken, "actionToken");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        GuiService service = services.get();
        GuiResult ownership = checkOwnership(ops, playerUuid, generation);
        if (ownership != null) {
            return ownership;
        }
        return service.cancel(playerUuid, generation, actionToken);
    }

    /**
     * 送出前重新驗證再確認。
     *
     * <p>先執行 {@code revalidation}（不在任何內部鎖內，可安全呼叫作用域查詢）：
     * 只有回傳 {@code SUCCESS} 才執行 domain action；否則自动取消該票券
     * （一次性失效）並回傳驗證結果，callback 不執行。驗證器抛例外視為失敗
     * （fail-closed）。</p>
     */
    public GuiResult confirmWithRevalidation(UUID playerUuid, long generation,
            String actionToken, GuiRevalidation revalidation) {
        requireArg(playerUuid, "playerUuid");
        requireArg(actionToken, "actionToken");
        requireArg(revalidation, "revalidation");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        GuiService service = services.get();
        GuiResult ownership = checkOwnership(ops, playerUuid, generation);
        if (ownership != null) {
            return ownership;
        }
        GuiResult validated;
        try {
            validated = revalidation.revalidate(playerUuid, generation);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING,
                "GuiScope: revalidation failed for uuid={0}: {1}",
                new Object[] {playerUuid, t.getMessage()});
            service.cancel(playerUuid, generation, actionToken);
            return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "revalidation failed: " + t.getMessage());
        }
        if (validated == null) {
            service.cancel(playerUuid, generation, actionToken);
            return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "revalidation returned null");
        }
        if (!validated.isSuccess()) {
            service.cancel(playerUuid, generation, actionToken);
            return validated;
        }
        return service.confirm(playerUuid, generation, actionToken);
    }

    // -----------------------------------------------------------------
    // 非同步更新（擁有者檢查＋委派）
    // -----------------------------------------------------------------

    /** 建立非同步更新請求（只限自己的 session）。 */
    public GuiResult beginAsyncUpdate(UUID playerUuid, long sessionGeneration,
            int pageIndex) {
        requireArg(playerUuid, "playerUuid");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        GuiService service = services.get();
        GuiResult ownership = checkOwnership(ops, playerUuid, sessionGeneration);
        if (ownership != null) {
            return ownership;
        }
        return service.beginAsyncUpdate(playerUuid, sessionGeneration, pageIndex);
    }

    /** 套用非同步更新（只限自己的請求）。 */
    public <T> GuiResult applyAsyncUpdate(GuiAsyncRequest request, GuiPage<T> page,
            Runnable renderer) {
        requireArg(request, "request");
        requireArg(page, "page");
        requireArg(renderer, "renderer");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        GuiService service = services.get();
        GuiResult ownership = checkOwnership(ops, request.playerUuid(),
            request.sessionGeneration());
        if (ownership != null) {
            return ownership;
        }
        return service.applyAsyncUpdate(request, page, renderer);
    }

    // -----------------------------------------------------------------
    // 輸入提示
    // -----------------------------------------------------------------

    /**
     * 建立聊天輸入提示。
     *
     * @return 輸入票券；永不為 null
     * @throws IllegalStateException 作用域已關閉／服務未啟用／已停用／
     *     無 session／generation 不符／他人擁有（訊息攜帶對應錯誤碼）
     */
    public GuiInputTicket promptChat(UUID playerUuid, long generation,
            GuiInputPrompt prompt, Consumer<GuiInputResult> consumer) {
        requireArg(playerUuid, "playerUuid");
        requireArg(prompt, "prompt");
        requireArg(consumer, "consumer");
        requireOpen();
        if (prompt.kind() != GuiInputKind.CHAT) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] promptChat 只接受 CHAT 提示");
        }
        ScopedGuiOperations ops = bridgeOrThrow();
        InputPromptOutcome outcome =
            ops.promptInputOwned(ownerName, playerUuid, generation, prompt, consumer);
        if (outcome.ticket() == null) {
            throw new IllegalStateException("[" + outcome.result().errorCode() + "] "
                + outcome.result().detail());
        }
        if (!prompt.hint().isEmpty()) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null) {
                try {
                    player.sendMessage(prompt.hint());
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE,
                        "GuiScope: failed to send chat hint (ignored): {0}",
                        t.getMessage());
                }
            }
        }
        return outcome.ticket();
    }

    /**
     * 建立鐵砧輸入提示（開啟鐵砧視圖取代目前畫面）。
     *
     * @return 輸入票券；永不為 null
     * @throws IllegalStateException 作用域已關閉／服務未啟用／已停用等
     *     （訊息攜帶對應錯誤碼）
     */
    public GuiInputTicket promptAnvil(UUID playerUuid, GuiInputPrompt prompt,
            Consumer<GuiInputResult> consumer) {
        requireArg(playerUuid, "playerUuid");
        requireArg(prompt, "prompt");
        requireArg(consumer, "consumer");
        requireOpen();
        if (prompt.kind() != GuiInputKind.ANVIL) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] promptAnvil 只接受 ANVIL 提示");
        }
        ScopedGuiOperations ops = bridgeOrThrow();
        GuiSession anchor = ops.currentSession(playerUuid);
        long anchorGeneration = anchor == null ? -1L : anchor.generation();
        InputPromptOutcome outcome = ops.promptInputOwned(ownerName, playerUuid,
            anchor == null ? 1L : anchorGeneration, prompt, consumer);
        if (outcome.ticket() == null) {
            // 無 session 時以 generation 1 嘗試會回 SESSION_NOT_FOUND；
            // 此處直接透出結果碼。
            throw new IllegalStateException("[" + outcome.result().errorCode() + "] "
                + outcome.result().detail());
        }
        notifyIfForeign(outcome.replaced(), playerUuid, outcome.result().session());
        GuiView anvilView = GuiView.anvil(prompt.title()).allow(0, 1).build();
        ScopePlayerState state = new ScopePlayerState();
        state.generation = outcome.result().session().generation();
        state.anvilToken = outcome.ticket().token();
        pushEntry(state, new ViewEntry(anvilView, null, null));
        states.put(playerUuid, state);
        return outcome.ticket();
    }

    /**
     * 以票券送出輸入文字。
     */
    public GuiResult submitInput(UUID token, String text) {
        requireArg(token, "token");
        requireArg(text, "text");
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        return ops.submitInputOwned(ownerName, token, text);
    }

    // -----------------------------------------------------------------
    // 退服
    // -----------------------------------------------------------------

    /**
     * 玩家退服清理（結束 session、失效票券與輸入、丟棄檢視狀態）。
     */
    public void handlePlayerQuit(UUID playerUuid) {
        requireArg(playerUuid, "playerUuid");
        if (closed.get()) {
            return;
        }
        GuiService service = services.get();
        if (service instanceof ScopedGuiOperations ops) {
            ops.handleQuit(playerUuid);
        } else {
            states.remove(playerUuid);
        }
        cooldowns.clear(playerUuid);
    }

    // -----------------------------------------------------------------
    // 內部（同套件服務接線用）
    // -----------------------------------------------------------------

    /**
     * 是否持有該玩家該 generation 的視圖狀態（服務 listener 分派用）。
     */
    boolean hasViewState(UUID playerUuid, long generation) {
        ScopePlayerState state = states.get(playerUuid);
        return state != null && state.generation == generation
            && !state.history.isEmpty();
    }

    /**
     * 被取代時的內部處理：丟棄該玩家狀態並觸發通知（同套件接線用）。
     */
    void onReplacedInternal(UUID playerUuid, GuiSession oldSession,
            GuiSession newSession) {
        states.remove(playerUuid);
        for (GuiReplacementListener listener : replacementListeners) {
            try {
                listener.onReplaced(playerUuid, oldSession, newSession);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING,
                    "GuiScope: replacement listener failed for uuid={0}: {1}",
                    new Object[] {playerUuid, t.getMessage()});
            }
        }
    }

    /**
     * 丟棄該玩家全部狀態（退服／取代接線用）。
     */
    void dropPlayerState(UUID playerUuid) {
        states.remove(playerUuid);
        cooldowns.clear(playerUuid);
    }

    /**
     * 關閉事件通知：僅當關閉的 generation 即為目前視圖時丟棄狀態
     * （取代導航產生的舊視窗關閉事件會被忽略）。
     */
    void dropViewState(UUID playerUuid, Long closingGeneration) {
        if (closingGeneration == null) {
            return;
        }
        ScopePlayerState state = states.get(playerUuid);
        if (state != null && state.generation == closingGeneration.longValue()) {
            states.remove(playerUuid, state);
        }
    }

    // -----------------------------------------------------------------
    // 內部輔助
    // -----------------------------------------------------------------

    private enum NavMode {
        PUSH,
        REPLACE
    }

    private GuiResult navigate(UUID playerUuid, GuiView view, String stepId,
            FormSpec form, NavMode mode) {
        ScopedGuiOperations ops = usableOrNull();
        if (ops == null) {
            return closedResult();
        }
        GuiSession live = checkedSession(ops, playerUuid);
        if (live == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (!live.owner().equals(ownerName)) {
            return GuiResult.rejected(GuiErrorCode.NOT_OWNER,
                "session owned by " + live.owner() + ", not " + ownerName);
        }
        ScopePlayerState state = states.get(playerUuid);
        if (state == null) {
            state = new ScopePlayerState();
            states.put(playerUuid, state);
        }
        if (isBedrock(playerUuid) && form != null) {
            GuiFlow flow = state.flow;
            if (flow == null) {
                return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                    "no active flow for uuid=" + playerUuid);
            }
            GuiFlowStep step = stepId == null ? null : flow.step(stepId);
            if (step == null) {
                return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                    "no active flow step for uuid=" + playerUuid);
            }
            return sendStepForm(ops, playerUuid, state, flow, step, mode == NavMode.PUSH);
        }
        return openViewSession(ops, playerUuid, state, view, stepId, form, mode);
    }

    private GuiResult navigateFresh(ScopedGuiOperations ops, UUID playerUuid,
            ScopePlayerState state, GuiView view, String stepId, FormSpec form) {
        OwnedOpenOutcome outcome = ops.openOwned(ownerName, playerUuid, view.title(),
            view.kind(), view.size(), allSlots(view), true);
        if (!outcome.result().isSuccess()) {
            states.remove(playerUuid);
            return outcome.result();
        }
        notifyIfForeign(outcome.replaced(), playerUuid, outcome.result().session());
        state.generation = outcome.result().session().generation();
        state.anvilToken = null;
        state.stepId = stepId;
        state.history.clear();
        pushEntry(state, new ViewEntry(view, stepId, form));
        states.put(playerUuid, state);
        return outcome.result();
    }

    private GuiResult openViewSession(ScopedGuiOperations ops, UUID playerUuid,
            ScopePlayerState state, GuiView view, String stepId, FormSpec form,
            NavMode mode) {
        OwnedOpenOutcome outcome = ops.openOwned(ownerName, playerUuid, view.title(),
            view.kind(), view.size(), allSlots(view), true);
        if (!outcome.result().isSuccess()) {
            states.remove(playerUuid);
            return outcome.result();
        }
        notifyIfForeign(outcome.replaced(), playerUuid, outcome.result().session());
        state.generation = outcome.result().session().generation();
        state.anvilToken = null;
        state.stepId = stepId;
        if (mode == NavMode.REPLACE) {
            state.history.pollLast();
        }
        pushEntry(state, new ViewEntry(view, stepId, form));
        return outcome.result();
    }

    private GuiResult renderEntry(ScopedGuiOperations ops, UUID playerUuid,
            ScopePlayerState state, ViewEntry entry, boolean fromBack) {
        if (isBedrock(playerUuid) && entry.form() != null && state.flow != null
                && entry.stepId() != null) {
            GuiFlowStep step = state.flow.step(entry.stepId());
            if (step != null) {
                return sendStepForm(ops, playerUuid, state, state.flow, step, false);
            }
        }
        OwnedOpenOutcome outcome = ops.openOwned(ownerName, playerUuid,
            entry.view().title(), entry.view().kind(), entry.view().size(),
            allSlots(entry.view()), true);
        if (!outcome.result().isSuccess()) {
            return outcome.result();
        }
        notifyIfForeign(outcome.replaced(), playerUuid, outcome.result().session());
        state.generation = outcome.result().session().generation();
        state.anvilToken = null;
        if (fromBack) {
            state.stepId = entry.stepId();
            if (entry.stepId() == null) {
                state.flow = null;
            }
        }
        // 注意：reopen（fromBack=false）不 push — 頂部項目已在歷史中，
        // 重複 push 會造成返回歷史出現重複。
        return outcome.result();
    }

    private GuiResult sendStepForm(ScopedGuiOperations ops, UUID playerUuid,
            ScopePlayerState state, GuiFlow flow, GuiFlowStep step, boolean pushHistory) {
        OwnedOpenOutcome formSession = ops.openFormSessionOwned(ownerName, playerUuid,
            step.form().title());
        if (!formSession.result().isSuccess()) {
            return formSession.result();
        }
        notifyIfForeign(formSession.replaced(), playerUuid,
            formSession.result().session());
        long flowGen = state.flowGeneration + 1;
        state.flow = flow;
        state.stepId = step.id();
        state.flowGeneration = flowGen;
        state.generation = formSession.result().session().generation();
        state.anvilToken = null;
        if (pushHistory) {
            pushEntry(state, new ViewEntry(step.view(), step.id(), step.form()));
        } else if (!hasEntry(state, step.id())) {
            pushEntry(state, new ViewEntry(step.view(), step.id(), step.form()));
        }
        states.put(playerUuid, state);
        FormSendResult sent;
        try {
            sent = forms.sendForm(playerUuid, step.form(),
                response -> onFormResponse(playerUuid, flow, flowGen, step.id(), response));
        } catch (RuntimeException | Error sendFailure) {
            states.remove(playerUuid);
            try {
                ops.closeOwned(ownerName, playerUuid,
                    formSession.result().session().generation());
            } catch (Throwable ignored) {
                LOGGER.log(Level.FINE,
                    "GuiScope: failed form send cleanup failed (ignored): {0}",
                    ignored.getMessage());
            }
            return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "bedrock form send failed: " + sendFailure.getMessage());
        }
        if (sent == FormSendResult.REJECTED) {
            states.remove(playerUuid);
            try {
                ops.closeOwned(ownerName, playerUuid,
                    formSession.result().session().generation());
            } catch (Throwable ignored) {
                LOGGER.log(Level.FINE,
                    "GuiScope: rejected form send cleanup failed (ignored): {0}",
                    ignored.getMessage());
            }
            return GuiResult.rejected(GuiErrorCode.OPERATION_FAILED,
                "bedrock form rejected for uuid=" + playerUuid);
        }
        return formSession.result();
    }

    private void onFormResponse(UUID playerUuid, GuiFlow flow, long flowGen,
            String stepId, com.smile.acelib.form.FormResponse response) {
        if (closed.get()) {
            return;
        }
        ScopePlayerState state = states.get(playerUuid);
        if (state == null || state.flow != flow || state.flowGeneration != flowGen
                || !stepId.equals(state.stepId)) {
            return; // 過時回應：推進後才回來，忽略
        }
        switch (response.status()) {
            case CLOSED -> endFlow(playerUuid, state, false);
            case INVALID -> {
                // 停留：不推進也不關閉
            }
            case VALID -> {
                GuiFlowStep current = flow.step(stepId);
                if (current == null) {
                    return;
                }
                Integer button = response.clickedButton().orElse(null);
                String nextId = button == null ? null
                    : current.transitions().get(button);
                GuiFlowStep next = nextId != null ? flow.step(nextId)
                    : flow.linearNext(stepId);
                if (next == null) {
                    endFlow(playerUuid, state, true);
                } else {
                    advanceFlow(playerUuid, state, flow, next);
                }
            }
        }
    }

    private void advanceFlow(UUID playerUuid, ScopePlayerState state, GuiFlow flow,
            GuiFlowStep next) {
        GuiService service = services.get();
        if (!(service instanceof ScopedGuiOperations ops)) {
            return;
        }
        if (next.form() != null) {
            sendStepForm(ops, playerUuid, state, flow, next, true);
        } else {
            // 無基岩呈現的步驟：退回開啟 Java 視圖（Geyser 會轉譯顯示）
            openViewSession(ops, playerUuid, state, next.view(), next.id(), null,
                NavMode.PUSH);
        }
    }

    private void endFlow(UUID playerUuid, ScopePlayerState state, boolean completed) {
        states.remove(playerUuid, state);
        GuiService service = services.get();
        if (service instanceof ScopedGuiOperations ops) {
            try {
                GuiSession session = ops.currentSession(playerUuid);
                if (session != null && session.owner().equals(ownerName)) {
                    ops.closeOwned(ownerName, playerUuid, session.generation());
                }
            } catch (Throwable t) {
                LOGGER.log(Level.FINE,
                    "GuiScope: end flow cleanup failed (ignored): {0}", t.getMessage());
            }
        }
        if (completed) {
            Consumer<UUID> onComplete = state.flow == null ? null
                : state.flow.onComplete();
            if (onComplete != null) {
                try {
                    onComplete.accept(playerUuid);
                } catch (Throwable t) {
                    LOGGER.log(Level.WARNING,
                        "GuiScope: flow onComplete failed for uuid={0}: {1}",
                        new Object[] {playerUuid, t.getMessage()});
                }
            }
        }
    }

    private void notifyIfForeign(GuiSession replaced, UUID playerUuid,
            GuiSession current) {
        if (replaced != null && !replaced.owner().equals(ownerName)) {
            GuiScopes.notifyReplaced(replaced.owner(), playerUuid, replaced, current);
        }
    }

    private boolean isBedrock(UUID playerUuid) {
        return forms != null && bedrockProbe != null && bedrockProbe.test(playerUuid);
    }

    private ScopedGuiOperations usableOrNull() {
        if (closed.get()) {
            return null;
        }
        GuiService service = services.get();
        return service instanceof ScopedGuiOperations ops ? ops : null;
    }

    private GuiResult closedResult() {
        GuiService service = services.get();
        if (service == null) {
            return GuiResult.rejected(GuiErrorCode.NOT_READY,
                "gui service is unavailable");
        }
        if (closed.get()) {
            return GuiResult.rejected(GuiErrorCode.SCOPE_CLOSED,
                "gui scope for plugin " + ownerName + " is closed");
        }
        return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
            "unsupported GuiService implementation for scoped operations");
    }

    private ScopedGuiOperations bridgeOrThrow() {
        if (closed.get()) {
            throw new IllegalStateException("[" + GuiErrorCode.SCOPE_CLOSED
                + "] gui scope for plugin " + ownerName + " is closed");
        }
        GuiService service = services.get();
        if (service == null) {
            throw new IllegalStateException("[" + GuiErrorCode.NOT_READY
                + "] gui service is unavailable");
        }
        if (!(service instanceof ScopedGuiOperations ops)) {
            throw new IllegalStateException("[" + GuiErrorCode.OPERATION_FAILED
                + "] unsupported GuiService implementation for scoped operations");
        }
        return ops;
    }

    private GuiResult checkOwnership(ScopedGuiOperations ops, UUID playerUuid,
            long generation) {
        GuiSession session = ops.currentSession(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (!session.owner().equals(ownerName)) {
            return GuiResult.rejected(GuiErrorCode.NOT_OWNER,
                "session owned by " + session.owner() + ", not " + ownerName);
        }
        if (session.generation() != generation) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + session.generation()
                    + " but got " + generation);
        }
        return null;
    }

    private GuiSession checkedSession(ScopedGuiOperations ops, UUID playerUuid) {
        try {
            return ops.currentSession(playerUuid);
        } catch (Throwable t) {
            return null;
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("[" + GuiErrorCode.SCOPE_CLOSED
                + "] gui scope for plugin " + ownerName + " is closed");
        }
    }

    private static void requireArg(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] " + name + " must not be null");
        }
    }

    private static Set<Integer> allSlots(GuiView view) {
        HashSet<Integer> slots = new HashSet<>();
        for (int i = 0; i < view.size(); i++) {
            slots.add(i);
        }
        return slots;
    }

    private static void pushEntry(ScopePlayerState state, ViewEntry entry) {
        state.history.addLast(entry);
        while (state.history.size() > MAX_HISTORY) {
            state.history.pollFirst();
        }
    }

    private static boolean hasEntry(ScopePlayerState state, String stepId) {
        for (ViewEntry entry : state.history) {
            if (stepId.equals(entry.stepId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 歷史項目：視圖＋所屬流程步驟（直接導航時步驟為 null）。
     */
    private record ViewEntry(GuiView view, String stepId,
                             com.smile.acelib.form.FormSpec form) {
    }

    /**
     * 單一玩家的作用域狀態（內部可變；以 session generation 為 truth，
     * 本狀態為快取 — 操作前一律以 live session 重新驗證）。
     */
    private static final class ScopePlayerState {
        final Deque<ViewEntry> history = new ConcurrentLinkedDeque<>();
        volatile long generation;
        volatile GuiFlow flow;
        volatile String stepId;
        volatile long flowGeneration;
        volatile UUID anvilToken;
    }
}
