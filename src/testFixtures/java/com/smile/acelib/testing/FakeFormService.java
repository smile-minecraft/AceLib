package com.smile.acelib.testing;

import com.smile.acelib.form.FormErrorCodes;
import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 標準表單假實作（下游單元測試用）。
 *
 * <p>取代各下游自寫的表單 stub：發送結果可編排、回應由測試顯式投遞、
 * at-most-once 由內部標記保證，語意與 production {@code FormServiceImpl}
 * 對齊。不碰 Floodgate／Cumulus，適用純單元測試。</p>
 *
 * <h2>可模擬的三種失敗</h2>
 * <ul>
 *   <li>過時回應：對未知／已過期 token 呼叫 {@link #deliverResponse} →
 *       consumer 不執行（回 false），{@link #droppedCount()} 累計</li>
 *   <li>重複回應：同一 token 投遞第二次 → consumer 只執行一次，
 *       第二次計為丟棄</li>
 *   <li>關閉失敗：玩家關閉表單即 {@code CLOSED} 回應——以
 *       {@code new FormResponse(FormResponseStatus.CLOSED, null, List.of())}
 *       投遞即可重現；{@link #rejectNextSend()} 模擬遞送被拒（consumer 永不呼叫）；
 *       {@link #setTransportAbsent} 模擬 Floodgate 缺席（FORM-001）</li>
 * </ul>
 *
 * <p>非執行緒安全：僅供單執行緒單元測試使用。</p>
 *
 * @since 1.4.0
 */
public final class FakeFormService implements FormService {

    private final Map<UUID, PendingEntry> pending = new LinkedHashMap<>();
    private boolean transportAbsent;
    private FormSendResult nextResult = FormSendResult.SENT;
    private boolean stopped;
    private int deliveredCount;
    private int droppedCount;

    /**
     * 設定傳輸層缺席（模擬 Floodgate 不在）：後續發送以攜帶
     * {@code ACELIB-FORM-001} 的 {@link IllegalStateException} 拒絕。
     */
    public void setTransportAbsent(boolean absent) {
        this.transportAbsent = absent;
    }

    /** 讓下一次發送回 {@code REJECTED}（consumer 永不呼叫，不留 pending）。 */
    public void rejectNextSend() {
        this.nextResult = FormSendResult.REJECTED;
    }

    /**
     * 向指定 token 投遞回應（模擬 Floodgate 回呼）。
     *
     * @param token    發送時註冊的 request token
     * @param response 玩家回應；不可為 null
     * @return 投遞並執行 consumer 為 true；未知／已處理 token（過時或重複）
     *         為 false（consumer 不執行，計為丟棄）
     */
    public boolean deliverResponse(UUID token, FormResponse response) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(response, "response");
        PendingEntry entry = pending.get(token);
        if (entry == null || !entry.handled.compareAndSet(false, true)) {
            droppedCount++;
            return false;
        }
        pending.remove(token);
        try {
            entry.consumer.accept(response);
        } finally {
            deliveredCount++;
        }
        return true;
    }

    /**
     * 向唯一的待回應投遞（pending 恰好一個時使用；否則丟 IllegalStateException）。
     *
     * @param response 玩家回應；不可為 null
     * @return 同 {@link #deliverResponse}
     */
    public boolean deliverFirst(FormResponse response) {
        if (pending.size() != 1) {
            throw new IllegalStateException(
                "expected exactly one pending form response, got: " + pending.size());
        }
        return deliverResponse(pending.keySet().iterator().next(), response);
    }

    /** @return 目前待回應 token（不可變快照） */
    public List<UUID> pendingTokens() {
        return List.copyOf(new ArrayList<>(pending.keySet()));
    }

    /** @return 目前待回應數量 */
    public int pendingCount() {
        return pending.size();
    }

    /** @return 已交付並執行的 consumer 次數 */
    public int deliveredCount() {
        return deliveredCount;
    }

    /** @return 被丟棄的投遞次數（過時或重複） */
    public int droppedCount() {
        return droppedCount;
    }

    @Override
    public FormSendResult sendForm(UUID playerId, FormSpec form) {
        requireValidSendInput(playerId, form);
        requireRunning();
        requireTransport();
        return consumeNextResult();
    }

    @Override
    public FormSendResult sendForm(UUID playerId, FormSpec form,
            Consumer<FormResponse> onResponse) {
        if (onResponse == null) {
            throw new IllegalArgumentException(
                "sendForm requires non-null playerId, form and onResponse consumer");
        }
        requireValidSendInput(playerId, form);
        requireRunning();
        requireTransport();
        UUID token = UUID.randomUUID();
        PendingEntry entry = new PendingEntry(playerId, onResponse);
        pending.put(token, entry);
        FormSendResult result = consumeNextResult();
        if (result == FormSendResult.REJECTED) {
            pending.remove(token);
        }
        return result;
    }

    @Override
    public String getModuleStatus() {
        return stopped ? "FAILED" : "READY";
    }

    @Override
    public void shutdown() {
        stopped = true;
        pending.clear();
    }

    private FormSendResult consumeNextResult() {
        FormSendResult result = nextResult;
        nextResult = FormSendResult.SENT;
        return result;
    }

    private void requireRunning() {
        if (stopped) {
            throw new IllegalStateException("["
                + FormErrorCodes.ACELIB_FORM_SERVICE_SHUTDOWN
                + "] form service is unavailable: ACELIB-FORM-002");
        }
    }

    private void requireTransport() {
        if (transportAbsent) {
            throw new IllegalStateException("["
                + FormErrorCodes.ACELIB_FORM_SERVICE_NOT_READY
                + "] form service is unavailable: no bedrock form transport bound");
        }
    }

    private static void requireValidSendInput(UUID playerId, FormSpec form) {
        if (playerId == null || form == null) {
            throw new IllegalArgumentException(
                "sendForm requires non-null playerId and form");
        }
    }

    private static final class PendingEntry {
        final UUID playerId;
        final Consumer<FormResponse> consumer;
        final AtomicBoolean handled = new AtomicBoolean(false);

        PendingEntry(UUID playerId, Consumer<FormResponse> consumer) {
            this.playerId = playerId;
            this.consumer = consumer;
        }
    }
}
