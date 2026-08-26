package com.smile.acelib.form;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * {@link FormService} production 實作（Internal）。
 *
 * <p>承載生命週期語意（READY / shutdown 後 FAILED）、發送委派與回應派送管線：
 * null 輸入以 {@link IllegalArgumentException} 拒絕（優先於生命週期檢查，比照
 * bedrock 模組順序），shutdown 後以攜帶 {@code ACELIB-FORM-002} 的
 * {@link IllegalStateException} 拒絕；傳輸層未啟用（absent seam）的
 * {@code ACELIB-FORM-001} 拒絕由 seam 本身承載。下游不得直接依賴本類別。</p>
 *
 * <h2>回應派送管線</h2>
 * <p>三參數 {@code sendForm} 先註冊 pending 項目（token → 消費者＋生命週期代數），
 * 再經四參數 seam 送出；Floodgate 於未知執行緒回呼時，先 CAS 標記 handled
 * （at-most-once），再經 {@link FormResponseDispatcher} 重新派送到玩家 region
 * context；runnable 內執行五項重檢（服務未停、玩家在線、token 有效、結果未處理、
 * generation 未變）全過才呼叫消費者。五項重檢與消費者執行位於同一監視器臨界區，
 * 對 shutdown 線性化：shutdown 先完成則 callback 零次執行；callback 已開始執行
 * 則 shutdown 等待其完成後才返回。離線／拒絕／shutdown／reload／disable 一律
 * 執行零次且不留 pending 殘留。</p>
 *
 * <h2>發送端原子性與 SENT-after-shutdown</h2>
 * <p>三參數 {@code sendForm} 的生命週期檢查、token 產生與 pending 註冊位於
 * 同一監視器臨界區，與 {@link #shutdown()} 線性化：shutdown() 返回後 pending
 * 保證為空。實體送出（{@code sender.sendForm}）在鎖外執行——若 shutdown 發生
 * 在實體送出進行中，{@code sendForm} 可能仍回傳 {@code SENT}（表單已物理送達
 * Floodgate）。這是既定語意而非缺陷：該次註冊已被 shutdown 清空，遲到回呼會被
 * 五項重檢擋下零次執行。</p>
 *
 * @since 1.0.0
 */
final class FormServiceImpl implements FormService {

    private final FormService.FormSender sender;
    private final FormResponseDispatcher dispatcher;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * 生命週期監視器：線性化「五項重檢＋callback 執行」與 {@link #shutdown()}。
     * 監視器天生可重入，consumer 於 callback 內呼叫 shutdown 不會自死鎖。
     */
    private final Object lifecycleLock = new Object();

    /** 生命週期代數：每次 shutdown 遞增；reload 建新實例，代數天然隔離。 */
    private final AtomicLong generation = new AtomicLong();

    /** pending 註冊表：token → 待交付回應項目。 */
    private final ConcurrentHashMap<UUID, PendingResponse> pending = new ConcurrentHashMap<>();

    FormServiceImpl(FormService.FormSender sender) {
        this(sender, FormResponseDispatcher.noop());
    }

    FormServiceImpl(FormService.FormSender sender, FormResponseDispatcher dispatcher) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    @Override
    public FormSendResult sendForm(UUID playerId, FormSpec form) {
        requireValidSendInput(playerId, form);
        requireRunning();
        return sender.sendForm(playerId, form);
    }

    @Override
    public FormSendResult sendForm(UUID playerId, FormSpec form,
            Consumer<FormResponse> onResponse) {
        if (onResponse == null) {
            // 要接收回應必須明確傳入 consumer；null consumer 屬程式設計錯誤，
            // 不佔用服務錯誤代碼（比照雙參數版 null 輸入慣例）
            throw new IllegalArgumentException(
                "sendForm requires non-null playerId, form and onResponse consumer"
                    + " (playerId=" + playerId + ", form="
                    + (form == null ? "null" : form.getClass().getSimpleName())
                    + ", onResponse=null)");
        }
        requireValidSendInput(playerId, form);

        UUID token;
        PendingResponse entry;
        // 生命週期檢查＋token 產生＋pending 註冊與 shutdown 共用同一監視器，
        // 兩個臨界區線性化：註冊先完成則 shutdown 的 clear 必定移除該項目；
        // shutdown 先完成則 requireRunning 以 FORM-002 拒絕、不會註冊。
        // 因此 shutdown() 返回後 pending 保證為空，「檢查通過後、註冊前插入
        // shutdown」的殘留窗口不存在。鎖內只有純記憶體操作（volatile 讀取、
        // UUID 產生、物件配置、ConcurrentHashMap 寫入），不呼叫會回呼本服務
        // 或取得其他鎖的外部程式碼；sender 呼叫留在鎖外，不持鎖做外部 I/O。
        synchronized (lifecycleLock) {
            requireRunning();
            token = UUID.randomUUID();
            entry = new PendingResponse(playerId, onResponse, generation.get());
            // 先註冊再發送：handler 只可能在表單遞送後觸發，註冊先行可覆蓋該 race
            pending.put(token, entry);
        }
        FormSendResult result;
        try {
            result = sender.sendForm(playerId, form, token,
                response -> onMappedResponse(token, entry, response));
        } catch (RuntimeException | Error sendFailure) {
            pending.remove(token, entry);
            throw sendFailure;
        }
        if (result == FormSendResult.REJECTED && pending.remove(token, entry)) {
            // 發送被拒：不會有任何回呼，立即清理避免「已註冊但永不交付」殘留
        }
        return result;
    }

    @Override
    public String getModuleStatus() {
        return stopped.get() ? "FAILED" : "READY";
    }

    @Override
    public void shutdown() {
        // 與 deliverInPlayerContext 共用同一監視器：shutdown 取得鎖前，已在
        // 飛行中的交付（可能正在執行 consumer）會先完整結束；shutdown 取得鎖
        // 後啟動的交付必然看到 stopped=true 與新代數，零執行。遞增 generation、
        // 標記 stopped 與清空 pending 在同一臨界區內完成，不會被交付路徑觀察到
        // 半套狀態。
        synchronized (lifecycleLock) {
            generation.incrementAndGet();
            stopped.set(true);
            pending.clear();
        }
    }

    /**
     * 回應 sink 入口（Floodgate 於未知執行緒觸發）。
     *
     * <p>CAS 標記 handled 實現 at-most-once：重複回呼、CAS 失敗一律丟棄。
     * 派送被拒（離線／scheduler disabled）或 dispatcher 拋例外時清理 pending，
     * 零執行；例外原樣上拋不吞錯。</p>
     */
    private void onMappedResponse(UUID token, PendingResponse entry, FormResponse response) {
        if (!entry.handled.compareAndSet(false, true)) {
            return;
        }
        boolean dispatched;
        try {
            dispatched = dispatcher.dispatch(entry.playerId,
                () -> deliverInPlayerContext(token, entry, response));
        } catch (RuntimeException | Error dispatchFailure) {
            // custom dispatcher 拋例外時同樣不留殘留；比照 sendForm 的
            // sendFailure 清理模式，清理後原樣重拋
            pending.remove(token, entry);
            throw dispatchFailure;
        }
        if (!dispatched) {
            pending.remove(token, entry);
        }
    }

    /**
     * 在玩家 region context 內交付回應；五項重檢全過才呼叫消費者。
     *
     * <ol>
     *   <li>服務未停</li>
     *   <li>玩家仍在線</li>
     *   <li>token 仍有效（pending 中仍是同一項目）</li>
     *   <li>結果未處理（handled 所有權仍在本次派送）</li>
     *   <li>生命週期 generation 未變</li>
     * </ol>
     */
    private void deliverInPlayerContext(UUID token, PendingResponse entry,
            FormResponse response) {
        // 五項重檢＋consumer.accept 對 shutdown 是單一臨界區：檢查與執行之間
        // 不再存在可插入 shutdown 的窗口——shutdown 先取得鎖則本次交付零執行；
        // 交付先取得鎖則 shutdown 等它完整結束（該次執行屬服務生命週期內）。
        // 表單回應低頻，跨 token 的 callback 序列化是可接受的取捨。臨界區內
        // 呼叫 dispatcher.isPlayerOnline 屬唯讀查詢，production 實作
        // （SafeSchedulerFormResponseDispatcher）只查 Bukkit 玩家狀態、不回呼
        // 本服務也不取得其他鎖，無鎖序風險。
        synchronized (lifecycleLock) {
            if (stopped.get()) {
                pending.remove(token, entry);
                return;
            }
            if (!dispatcher.isPlayerOnline(entry.playerId)) {
                pending.remove(token, entry);
                return;
            }
            if (pending.get(token) != entry) {
                return;
            }
            if (!entry.handled.get()) {
                return;
            }
            if (generation.get() != entry.generation) {
                pending.remove(token, entry);
                return;
            }
            try {
                entry.consumer.accept(response);
            } finally {
                pending.remove(token, entry);
            }
        }
    }

    /** 共用輸入驗證：null 輸入屬程式設計錯誤，不佔用服務錯誤代碼。 */
    private static void requireValidSendInput(UUID playerId, FormSpec form) {
        if (playerId == null || form == null) {
            // 比照 FormSpec.requireNonBlank 先例；ACELIB-FORM-* 只標記生命週期
            // 狀態（002）與缺席 seam（001）
            throw new IllegalArgumentException(
                "sendForm requires non-null playerId and form"
                    + " (playerId=" + playerId + ", form="
                    + (form == null ? "null" : form.getClass().getSimpleName()) + ")");
        }
    }

    /** 共用生命週期檢查：shutdown 後以 ACELIB-FORM-002 拒絕。 */
    private void requireRunning() {
        if (stopped.get()) {
            throw new IllegalStateException("["
                + FormErrorCodes.ACELIB_FORM_SERVICE_SHUTDOWN
                + "] form service is unavailable: ACELIB-FORM-002");
        }
    }

    /** 目前 pending 註冊數（測試觀察用；package-private test seam）。 */
    int pendingCountForTesting() {
        return pending.size();
    }

    /**
     * 只遞增生命週期代數，不改動 stopped 與 pending（測試注入代數失配用；
     * package-private test seam，比照 {@link #pendingCountForTesting()} 慣例）。
     * 正式路徑的代數遞增只發生在 {@link #shutdown()}。
     */
    void bumpGenerationForTesting() {
        generation.incrementAndGet();
    }

    /** pending 項目：消費者、目標玩家、註冊時的生命週期代數與 at-most-once 標記。 */
    private static final class PendingResponse {

        private final UUID playerId;
        private final Consumer<FormResponse> consumer;
        private final long generation;
        private final AtomicBoolean handled = new AtomicBoolean(false);

        private PendingResponse(UUID playerId, Consumer<FormResponse> consumer,
                long generation) {
            this.playerId = Objects.requireNonNull(playerId, "playerId");
            this.consumer = Objects.requireNonNull(consumer, "consumer");
            this.generation = generation;
        }
    }
}
