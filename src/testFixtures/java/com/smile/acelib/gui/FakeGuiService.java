package com.smile.acelib.gui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import com.smile.acelib.diagnostics.Clock;

/**
 * 標準 GUI 假實作（下游單元測試用）。
 *
 * <p>取代各下游自寫的 GUI 假實作：記憶體 session 登記、單調遞增 generation、
 * 一次性確認票券，語意與 production {@code GuiServiceImpl} 對齊。
 * 不碰 Bukkit，適用純單元測試。</p>
 *
 * <h2>可模擬的三種失敗</h2>
 * <ul>
 *   <li>過時回應：以舊 generation 呼叫 {@code closeInventory}／{@code validateClick}／
 *       {@code confirm} → {@code GENERATION_MISMATCH}；以未知 token 呼叫
 *       {@code confirm}／{@code cancel} → {@code UNKNOWN_ACTION}</li>
 *   <li>重複回應：同一票券第二次 {@code confirm}／{@code cancel} →
 *       {@code ACTION_ALREADY_RESOLVED}，callback 不重複執行</li>
 *   <li>關閉失敗：{@link #failNextClose()} 讓下一次 {@code closeInventory} 回
 *       {@code FAILED + OPERATION_FAILED} 且 session 保留（可觀察、可重試）。
 *       這是假實作獨有的測試注入——production 的 {@code closeInventory} 沒有
 *       關閉失敗路徑（session 移除後一律回成功），下游不得把此行為當成
 *       production 語意。</li>
 * </ul>
 *
 * <p>離線模擬：{@link #markOffline(UUID)} 後 {@code openInventory} 比照 production
 * （玩家不在線）回 {@code FAILED + OPERATION_FAILED}；{@code closeInventory}
 * 仍可成功（語意「已不再屬於此 GUI」）。</p>
 *
 * <p>非執行緒安全：僅供單執行緒單元測試使用。</p>
 *
 * <p>同時實作 {@link ScopedGuiOperations} 橋接：作用域導航、輸入提示與
 * 聊天路由與 production 語意對齊（派送為同步直接執行，回 {@code SUCCESS}）。</p>
 *
 * @since 1.4.0
 */
public final class FakeGuiService
        implements GuiService, GuiServiceControl, ScopedGuiOperations {

    private final Map<UUID, GuiSession> sessions = new HashMap<>();
    private final Map<String, PendingAction> actions = new HashMap<>();
    private final Map<UUID, AtomicLong> requestGenerations = new HashMap<>();
    private final Map<UUID, PendingInput> pendingInputs = new HashMap<>();
    private final Set<UUID> offline = new HashSet<>();
    private final AtomicLong generationSequence = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Clock clock;
    private boolean failNextClose;

    /** 主要建構子（系統時鐘）。 */
    public FakeGuiService() {
        this(Clock.system());
    }

    /**
     * 注入式建構子（輸入逾時測試 seam：傳入可手動推進的時鐘）。
     *
     * @param clock 時間來源；不可為 null
     */
    public FakeGuiService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 讓下一次 {@link #closeInventory} 失敗（回 {@code FAILED + OPERATION_FAILED}，
     * session 保留）。旗標為一次性，觸發後自動清除。
     */
    public void failNextClose() {
        failNextClose = true;
    }

    /** 標記玩家離線（後續 {@code openInventory} 比照玩家不在線回失敗）。 */
    public void markOffline(UUID playerUuid) {
        offline.add(Objects.requireNonNull(playerUuid, "playerUuid"));
    }

    /** 解除玩家離線標記。 */
    public void markOnline(UUID playerUuid) {
        offline.remove(Objects.requireNonNull(playerUuid, "playerUuid"));
    }

    /** @return 目前 active session 數量（測試觀察用） */
    public int activeSessionCount() {
        return sessions.size();
    }

    /** @return 目前待確認 action 數量（測試觀察用） */
    public int pendingActionCount() {
        int count = 0;
        for (PendingAction action : actions.values()) {
            if (action.state == GuiConfirmation.State.PENDING) {
                count++;
            }
        }
        return count;
    }

    @Override
    public GuiResult openInventory(GuiArgument argument) {
        requireNonNull(argument, "argument");
        OwnedOpenOutcome outcome = openInternal("fake", argument.playerUuid(),
            argument.title(), GuiView.Kind.CHEST, argument.size(),
            argument.protectedSlots(), false);
        return outcome.result();
    }

    private OwnedOpenOutcome openInternal(String owner, UUID playerUuid, String title,
            GuiView.Kind kind, int size, Set<Integer> protectedSlots,
            boolean replaceExisting) {
        if (!running.get()) {
            return new OwnedOpenOutcome(GuiResult.rejected(GuiErrorCode.SHUTDOWN,
                "gui service is shutdown"), null);
        }
        if (offline.contains(playerUuid)) {
            return new OwnedOpenOutcome(GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "player offline or not found: uuid=" + playerUuid), null);
        }
        int slots = kind == GuiView.Kind.ANVIL ? 3 : size;
        GuiSession replaced = null;
        if (sessions.containsKey(playerUuid)) {
            if (!replaceExisting) {
                return new OwnedOpenOutcome(GuiResult.rejected(GuiErrorCode.SESSION_EXISTS,
                    "session already exists for uuid=" + playerUuid), null);
            }
            replaced = sessions.remove(playerUuid);
            invalidatePendingActions(playerUuid);
            requestGenerations.remove(playerUuid);
            invalidatePlayerInputs(playerUuid);
        }
        Set<Integer> normalized = protectedSlots == null || protectedSlots.isEmpty()
            ? Set.of()
            : Set.copyOf(new HashSet<>(protectedSlots));
        GuiSession session = new GuiSession(playerUuid,
            generationSequence.incrementAndGet(), owner, title, slots, normalized);
        sessions.put(playerUuid, session);
        return new OwnedOpenOutcome(
            GuiResult.success(session, "opened fake gui session"), replaced);
    }

    @Override
    public OwnedOpenOutcome openOwned(String owner, UUID playerUuid, String title,
            GuiView.Kind kind, int size, Set<Integer> protectedSlots,
            boolean replaceExisting) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(kind, "kind");
        return openInternal(owner, playerUuid, title, kind, size, protectedSlots,
            replaceExisting);
    }

    @Override
    public GuiResult closeOwned(String owner, UUID playerUuid, long generation) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(playerUuid, "playerUuid");
        GuiSession session = sessions.get(playerUuid);
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
        if (offline.contains(playerUuid)) {
            return new OwnedOpenOutcome(GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "player offline or not found: uuid=" + playerUuid), null);
        }
        GuiSession replaced = sessions.remove(playerUuid);
        if (replaced != null) {
            invalidatePendingActions(playerUuid);
            requestGenerations.remove(playerUuid);
            invalidatePlayerInputs(playerUuid);
        }
        GuiSession session = new GuiSession(playerUuid,
            generationSequence.incrementAndGet(), owner, title, 9,
            Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8));
        sessions.put(playerUuid, session);
        return new OwnedOpenOutcome(
            GuiResult.success(session, "opened fake form session"), replaced);
    }

    @Override
    public GuiSession currentSession(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        return sessions.get(playerUuid);
    }

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
        GuiSession session = sessions.get(playerUuid);
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
            pendingInputs.put(token, new PendingInput(token, owner, playerUuid,
                generation, prompt, consumer, clock.currentTimeMillis()));
            return new InputPromptOutcome(GuiResult.success(session),
                new GuiInputTicket(token, playerUuid, generation, prompt.kind()), null);
        }
        OwnedOpenOutcome opened = openInternal(owner, playerUuid, prompt.title(),
            GuiView.Kind.ANVIL, 3, Set.of(0, 1, 2), true);
        if (!opened.result().isSuccess()) {
            return new InputPromptOutcome(opened.result(), null, opened.replaced());
        }
        GuiSession anvilSession = opened.result().session();
        UUID token = UUID.randomUUID();
        pendingInputs.put(token, new PendingInput(token, owner, playerUuid,
            anvilSession.generation(), prompt, consumer, clock.currentTimeMillis()));
        return new InputPromptOutcome(GuiResult.success(anvilSession),
            new GuiInputTicket(token, playerUuid, anvilSession.generation(),
                prompt.kind()),
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
        GuiSession session = sessions.get(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.INPUT_EXPIRED,
                "no active session for uuid=" + playerUuid);
        }
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

    private GuiResult deliverInput(PendingInput entry, String text) {
        UUID playerUuid = entry.playerUuid;
        GuiSession session = sessions.get(playerUuid);
        if (session == null) {
            pendingInputs.remove(entry.token);
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (session.generation() != entry.generation) {
            pendingInputs.remove(entry.token);
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "session generation changed: expected " + entry.generation
                    + " but current " + session.generation());
        }
        if (entry.prompt.timeoutMillis() > 0L
                && clock.currentTimeMillis() - entry.createdAtMillis
                    > entry.prompt.timeoutMillis()) {
            pendingInputs.remove(entry.token);
            return GuiResult.rejected(GuiErrorCode.INPUT_EXPIRED,
                "input prompt expired for uuid=" + playerUuid);
        }
        if (text.length() > entry.prompt.maxLength()) {
            return GuiResult.rejected(GuiErrorCode.INVALID_INPUT,
                "input text too long: " + text.length()
                    + " (max=" + entry.prompt.maxLength() + ")");
        }
        if (offline.contains(playerUuid)) {
            return GuiResult.rejected(GuiErrorCode.PLAYER_OFFLINE,
                "player offline when input submitted: uuid=" + playerUuid);
        }
        if (!entry.handled.compareAndSet(false, true)) {
            return GuiResult.rejected(GuiErrorCode.INPUT_EXPIRED,
                "input already submitted for uuid=" + playerUuid);
        }
        try {
            entry.consumer.accept(new GuiInputResult(playerUuid, entry.generation,
                entry.kind, text));
        } catch (RuntimeException | Error consumerFailure) {
            return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "input consumer failed: " + consumerFailure.getMessage());
        } finally {
            pendingInputs.remove(entry.token);
        }
        return GuiResult.success(session, "input delivered");
    }

    private void invalidatePlayerInputs(UUID playerUuid) {
        pendingInputs.entrySet().removeIf(e -> e.getValue().playerUuid.equals(playerUuid));
    }

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
    public void handleQuit(UUID playerUuid) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        sessions.remove(playerUuid);
        actions.entrySet().removeIf(e -> e.getValue().playerUuid.equals(playerUuid));
        requestGenerations.remove(playerUuid);
        invalidatePlayerInputs(playerUuid);
        GuiScopes.dropPlayer(playerUuid);
    }

    @Override
    public GuiResult closeInventory(UUID playerUuid, long generation) {
        requireNonNull(playerUuid, "playerUuid");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN, "gui service is shutdown");
        }
        GuiSession session = sessions.get(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (session.generation() != generation) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + session.generation() + " but got " + generation);
        }
        if (failNextClose) {
            failNextClose = false;
            return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "fake close failure injected for uuid=" + playerUuid);
        }
        sessions.remove(playerUuid);
        invalidatePendingActions(playerUuid);
        requestGenerations.remove(playerUuid);
        invalidatePlayerInputs(playerUuid);
        return GuiResult.success(session, "closed fake gui session");
    }

    @Override
    public GuiResult getActiveSession(UUID playerUuid) {
        requireNonNull(playerUuid, "playerUuid");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN, "gui service is shutdown");
        }
        GuiSession session = sessions.get(playerUuid);
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
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN, "gui service is shutdown");
        }
        GuiSession session = sessions.get(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (session.generation() != generation) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + session.generation() + " but got " + generation);
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
    public GuiResult createConfirmation(UUID playerUuid, long generation,
            String actionId, Runnable callback) {
        requireNonNull(playerUuid, "playerUuid");
        requireNonNull(actionId, "actionId");
        requireNonNull(callback, "callback");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN, "gui service is shutdown");
        }
        GuiSession session = sessions.get(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (session.generation() != generation) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + session.generation() + " but got " + generation);
        }
        String token = UUID.randomUUID().toString();
        actions.put(token, new PendingAction(playerUuid, generation, actionId, callback, session));
        GuiConfirmation confirmation = new GuiConfirmation(playerUuid, generation,
            actionId, token, GuiConfirmation.State.PENDING);
        return GuiResult.success(session, confirmation);
    }

    @Override
    public GuiResult confirm(UUID playerUuid, long generation, String actionToken) {
        requireNonNull(playerUuid, "playerUuid");
        requireNonNull(actionToken, "actionToken");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN, "gui service is shutdown");
        }
        PendingAction action = actions.get(actionToken);
        if (action == null || !action.playerUuid.equals(playerUuid)) {
            return GuiResult.rejected(GuiErrorCode.UNKNOWN_ACTION,
                "unknown or expired action token=" + actionToken);
        }
        if (action.generation != generation) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + action.generation + " but got " + generation);
        }
        if (action.state != GuiConfirmation.State.PENDING) {
            return GuiResult.rejected(GuiErrorCode.ACTION_ALREADY_RESOLVED,
                "action already resolved (state=" + action.state + "), token=" + actionToken);
        }
        action.state = GuiConfirmation.State.CONFIRMED;
        try {
            action.callback.run();
        } catch (RuntimeException | Error callbackFailure) {
            return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "confirmation callback failed for actionId=" + action.actionId
                    + ": " + callbackFailure.getMessage());
        }
        return GuiResult.success(action.session);
    }

    @Override
    public GuiResult cancel(UUID playerUuid, long generation, String actionToken) {
        requireNonNull(playerUuid, "playerUuid");
        requireNonNull(actionToken, "actionToken");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN, "gui service is shutdown");
        }
        PendingAction action = actions.get(actionToken);
        if (action == null || !action.playerUuid.equals(playerUuid)) {
            return GuiResult.rejected(GuiErrorCode.UNKNOWN_ACTION,
                "unknown or expired action token=" + actionToken);
        }
        if (action.generation != generation) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + action.generation + " but got " + generation);
        }
        if (action.state != GuiConfirmation.State.PENDING) {
            return GuiResult.rejected(GuiErrorCode.ACTION_ALREADY_RESOLVED,
                "action already resolved (state=" + action.state + "), token=" + actionToken);
        }
        action.state = GuiConfirmation.State.CANCELLED;
        return GuiResult.success(action.session);
    }

    @Override
    public GuiResult beginAsyncUpdate(UUID playerUuid, long sessionGeneration, int pageIndex) {
        requireNonNull(playerUuid, "playerUuid");
        if (!running.get()) {
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN, "gui service is shutdown");
        }
        GuiSession session = sessions.get(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (session.generation() != sessionGeneration) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "expected generation=" + session.generation() + " but got " + sessionGeneration);
        }
        long requestGeneration = requestGenerations
            .computeIfAbsent(playerUuid, k -> new AtomicLong(0L))
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
            return GuiResult.rejected(GuiErrorCode.SHUTDOWN, "gui service is shutdown");
        }
        UUID playerUuid = request.playerUuid();
        GuiSession session = sessions.get(playerUuid);
        if (session == null) {
            return GuiResult.rejected(GuiErrorCode.SESSION_NOT_FOUND,
                "no active session for uuid=" + playerUuid);
        }
        if (session.generation() != request.sessionGeneration()) {
            return GuiResult.rejected(GuiErrorCode.GENERATION_MISMATCH,
                "session generation changed: expected " + request.sessionGeneration()
                    + " but current " + session.generation());
        }
        AtomicLong current = requestGenerations.get(playerUuid);
        if (current == null || current.get() != request.requestGeneration()) {
            return GuiResult.rejected(GuiErrorCode.STALE_REQUEST,
                "async request is stale (superseded by a newer request): "
                    + "requestGeneration=" + request.requestGeneration());
        }
        if (offline.contains(playerUuid)) {
            return GuiResult.rejected(GuiErrorCode.PLAYER_OFFLINE,
                "player offline when async result returned: uuid=" + playerUuid);
        }
        try {
            renderer.run();
        } catch (RuntimeException | Error rendererFailure) {
            return GuiResult.failed(GuiErrorCode.OPERATION_FAILED,
                "async update renderer failed: " + rendererFailure.getMessage());
        }
        return GuiResult.success(session,
            "applied fake async update; page=" + page.kind());
    }

    @Override
    public String getModuleStatus() {
        return running.get() ? "READY" : "FAILED";
    }

    @Override
    public void shutdownService() {
        running.set(false);
        sessions.clear();
        actions.clear();
        requestGenerations.clear();
        pendingInputs.clear();
    }

    /**
     * 停用假服務（測試便利方法，等同 {@link #shutdownService()}）。
     */
    public void shutdown() {
        shutdownService();
    }

    private void invalidatePendingActions(UUID playerUuid) {
        actions.entrySet().removeIf(e -> e.getValue().playerUuid.equals(playerUuid));
    }

    private static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] " + name + " must not be null");
        }
    }

    private static final class PendingAction {
        final UUID playerUuid;
        final long generation;
        final String actionId;
        final Runnable callback;
        final GuiSession session;
        GuiConfirmation.State state = GuiConfirmation.State.PENDING;

        PendingAction(UUID playerUuid, long generation, String actionId,
                Runnable callback, GuiSession session) {
            this.playerUuid = playerUuid;
            this.generation = generation;
            this.actionId = actionId;
            this.callback = callback;
            this.session = session;
        }
    }

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
