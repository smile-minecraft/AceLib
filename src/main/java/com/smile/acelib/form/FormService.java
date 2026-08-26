package com.smile.acelib.form;

import com.smile.acelib.scheduler.SafeScheduler;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 表單服務對外 facade（Supported API）。
 *
 * <p>下游插件以 {@link #forProduction(FormSender)} 取得實例（由基岩服務接線
 * 注入發送 seam），以自有 {@link FormSpec} DSL 建立基岩原生表單並經
 * {@link #sendForm(UUID, FormSpec)} 發送。發送結果為明確的
 * {@link FormSendResult}。需要接收玩家回應時，以
 * {@link #sendForm(UUID, FormSpec, Consumer)} 註冊 consumer：回應會被重新派送到
 * 玩家所屬 region context 後才執行（不論 Floodgate 回呼發生在哪個執行緒），
 * 且有效結果最多執行一次；離線、關閉、過期、reload、disable 時執行零次。</p>
 *
 * <h2>生命週期</h2>
 * <ul>
 *   <li>未啟用傳輸層（Floodgate 缺席，綁定 {@link FormSender#absent()}）時，
 *       發送以攜帶 {@code ACELIB-FORM-001} 的 {@link IllegalStateException} 拒絕。</li>
 *   <li>{@link #shutdown()} 後，發送以攜帶 {@code ACELIB-FORM-002} 的
 *       {@link IllegalStateException} 拒絕；所有未完成的回應註冊一併清空，
 *       遲到回呼零執行；shutdown 冪等。正在進行的實體送出不會被中斷：該類
 *       發送可能仍回傳 {@code SENT}（表單已送達），但其回應註冊已一併清空，
 *       遲到回呼同樣零執行。</li>
 * </ul>
 *
 * @since 1.0.0
 */
public interface FormService {

    /**
     * Production factory：建立表單服務實作實例。
     *
     * <p>實作類別 {@code FormServiceImpl} 為 package-private（不暴露為 public API）。
     * 本工廠未接線回應派送排程器——三參數 sendForm 對此實例退回 fire-and-forget
     * 語意；需要接收回應請改用 {@link #forProduction(FormSender, Supplier)}。</p>
     *
     * @param sender 表單發送 seam；不可為 null（Floodgate 缺席時請傳
     *               {@link FormSender#absent()}）
     * @return 新的 {@link FormService} 實作實例；never null
     * @throws NullPointerException sender 為 null
     * @since 1.0.0
     */
    static FormService forProduction(FormSender sender) {
        return new FormServiceImpl(sender);
    }

    /**
     * Production factory：建立表單服務實作實例並接線回應派送排程器。
     *
     * <p>回應派送經由 {@code SafeScheduler.runForPlayer(Player, Runnable)} 送達
     * 玩家所屬 region context（Folia entity scheduler、Paper main thread）；
     * UUID → Player 解析與離線拒絕由實作內部處理。scheduler 以 supplier 包裝
     * （比照發送 seam 的延遲綁定先例）：每次派送才讀取，reload 提交新 scheduler
     * 後自動取到新實例，不捕獲已停用的舊 scheduler。</p>
     *
     * @param sender            表單發送 seam；不可為 null
     * @param schedulerSupplier 排程器供應器；不可為 null（建議包裝 plugin 的
     *                          volatile scheduler 欄位）
     * @return 新的 {@link FormService} 實作實例；never null
     * @throws NullPointerException 任一參數為 null
     * @since 1.0.0
     */
    static FormService forProduction(FormSender sender,
            Supplier<SafeScheduler> schedulerSupplier) {
        Objects.requireNonNull(schedulerSupplier, "schedulerSupplier");
        return new FormServiceImpl(sender,
            SafeSchedulerFormResponseDispatcher.viaSafeScheduler(schedulerSupplier));
    }

    /**
     * 以 AceLib 自有 DSL 發送基岩原生表單給指定玩家。
     *
     * <p>回傳值描述 Floodgate 是否接受遞送：{@link FormSendResult#SENT} 代表已接受
     * （不代表玩家已回應），{@link FormSendResult#REJECTED} 代表被拒絕。
     * Floodgate 內部 boolean 不會以此以外的形式外洩。</p>
     *
     * @param playerId 目標玩家 UUID；不可為 null
     * @param form     表單規格；不可為 null
     * @return 發送結果；never null
     * @throws IllegalArgumentException playerId 或 form 為 null
     * @throws IllegalStateException    服務已 shutdown（攜帶 {@code ACELIB-FORM-002}），
     *                                  或傳輸層未啟用／Floodgate 缺席（攜帶
     *                                  {@code ACELIB-FORM-001}）
     * @since 1.0.0
     */
    FormSendResult sendForm(UUID playerId, FormSpec form);

    /**
     * 以 AceLib 自有 DSL 發送基岩原生表單，並註冊回應 consumer。
     *
     * <p>玩家回應後（提交、關閉或無效輸入），consumer 會收到一個
     * {@link FormResponse}。派送保證：</p>
     * <ul>
     *   <li><strong>thread-agnostic</strong>——不論 Floodgate 回呼發生在哪個
     *       執行緒，consumer 一律先重新派送到玩家所屬 region context 才執行；
     *       絕不在回呼來源執行緒直接執行。</li>
     *   <li><strong>at-most-once</strong>——有效且屬於目前服務生命週期的結果最多
     *       執行一次；重複回呼、查無 token、已 shutdown、生命週期代謝一律丟棄。</li>
     *   <li><strong>失效即清理</strong>——玩家離線、發送被拒、服務 shutdown／
     *       reload／disable 時 consumer 執行零次，且不留 pending 狀態。</li>
     * </ul>
     *
     * <p>{@link FormSendResult#REJECTED} 代表 Floodgate 拒絕遞送：consumer 不會
     * 被呼叫。SENT 只代表已接受遞送，consumer 何時被呼叫取決於玩家何時回應
     * （也可能永不回應——表單沒有 timeout 語意）。</p>
     *
     * @param playerId   目標玩家 UUID；不可為 null
     * @param form       表單規格；不可為 null
     * @param onResponse 回應 consumer；不可為 null（要接收回應請明確傳入；
     *                   執行於玩家 region context，不得在其中做長時間工作）
     * @return 發送結果；never null
     * @throws IllegalArgumentException playerId、form 或 onResponse 為 null
     * @throws IllegalStateException    服務已 shutdown（攜帶 {@code ACELIB-FORM-002}），
     *                                  或傳輸層未啟用／Floodgate 缺席（攜帶
     *                                  {@code ACELIB-FORM-001}）
     * @since 1.0.0
     */
    FormSendResult sendForm(UUID playerId, FormSpec form, Consumer<FormResponse> onResponse);

    /**
     * 取得當前模組狀態（{@code READY} / {@code FAILED}）。
     *
     * <p>用於診斷；不屬於穩定 public API。</p>
     */
    String getModuleStatus();

    /**
     * 停用服務並釋放資源（冪等；shutdown 後所有發送一律拒絕）。
     */
    void shutdown();

    /**
     * 表單發送 seam — 隔離外部表單型別的 package 邊界。
     *
     * <p>比照 {@code BedrockService.PlayerLookup} 先例：seam 介面只含 UUID 與
     * AceLib 自有型別；Cumulus / Floodgate 型別只出現在 external 套件的
     * package-private 實作內。本介面不屬於消費者契約，僅供 plugin 接線使用。</p>
     *
     * @since 1.0.0
     */
    interface FormSender {

        /**
         * 發送已翻譯完成的表單；實作負責翻譯與遞送。
         *
         * @param playerId 目標玩家 UUID；不為 null
         * @param form     表單規格；不為 null
         * @return 發送結果；never null
         * @throws IllegalStateException 傳輸層未啟用（absent seam 攜帶
         *                               {@code ACELIB-FORM-001}）
         */
        FormSendResult sendForm(UUID playerId, FormSpec form);

        /**
         * 發送表單並掛接回應接收端（plugin 接線用，非消費者契約）。
         *
         * <p>預設實作退回 fire-and-forget（{@link #sendForm(UUID, FormSpec)}）：
         * 未覆寫本方法的 seam（absent seam、一般 lambda fake）自然維持既有語意，
         * 三參數 {@code sendForm} 對此類 sender 的 consumer 永不被呼叫。
         * external 套件的 typed 實作負責把 token 與接收端接到 Cumulus handler；
         * 接收端以 {@link Consumer}{@code <}{@link FormResponse}{@code >}
         * 承載（純 JDK 型別＋AceLib 自有型別），由服務層保證後續的重新派送與
         * at-most-once。</p>
         *
         * @param playerId   目標玩家 UUID；不為 null
         * @param form       表單規格；不為 null
         * @param token      本次發送的 request token（pending 註冊表鍵）；不為 null
         * @param onResponse 已映射回應的接收端；不為 null（Floodgate 於未知執行緒觸發）
         * @return 發送結果；never null
         */
        default FormSendResult sendForm(UUID playerId, FormSpec form, UUID token,
                Consumer<FormResponse> onResponse) {
            return sendForm(playerId, form);
        }

        /**
         * Floodgate 缺席時的 fallback seam：任何發送都以攜帶
         * {@code ACELIB-FORM-001} 的 {@link IllegalStateException} 拒絕，
         * 不拋 {@code NoClassDefFoundError}、不依賴任何外部型別。
         *
         * @return absent 發送 seam；never null
         */
        static FormSender absent() {
            return (playerId, form) -> {
                throw new IllegalStateException("["
                    + FormErrorCodes.ACELIB_FORM_SERVICE_NOT_READY
                    + "] form service is unavailable: no bedrock form transport bound");
            };
        }
    }
}
