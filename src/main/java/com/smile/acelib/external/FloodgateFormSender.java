package com.smile.acelib.external;

import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.geysermc.cumulus.form.Form;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * Floodgate 表單發送 seam 實作（package-private，Internal）。
 *
 * <p>把 AceLib {@link FormSpec} 經 {@link CumulusFormTranslator} 翻成 Cumulus
 * form 後，以 {@code FloodgateApi.sendForm(UUID, Form)} 遞送，並把上游 boolean
 * 映射為明確的 {@link FormSendResult}（true → SENT、false → REJECTED），
 * boolean 語意不外洩。Cumulus / Floodgate 型別被隔離在本類別與翻譯層內，
 * 對外交付的 {@link FormService.FormSender} 只含 UUID 與 AceLib 自有型別。</p>
 *
 * <p>四參數路徑：token 與回應接收端交給翻譯層掛接 Cumulus handler（掛接與
 * 原始回應映射都在翻譯層完成）；接收端於 Floodgate 回呼執行緒（未知執行緒）
 * 被觸發，後續重新派送由 form 套件承擔。</p>
 *
 * <p>延遲綁定：建構時只包裝 supplier，不呼叫 {@code FloodgateApi.getInstance()}；
 * 每次發送才取 instance，reload 後自動取到新 instance。本類別僅在 adapter
 * 探測確認 marker 存在後才會被載入。</p>
 *
 * @since 1.0.0
 */
final class FloodgateFormSender implements FormService.FormSender {

    /** 延遲綁定的 api 供應器：每次發送重新取得 instance。 */
    private final Supplier<FloodgateApi> apiSupplier;

    FloodgateFormSender(Supplier<FloodgateApi> apiSupplier) {
        this.apiSupplier = Objects.requireNonNull(apiSupplier, "apiSupplier");
    }

    @Override
    public FormSendResult sendForm(UUID playerId, FormSpec form) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(form, "form");
        Form translated = CumulusFormTranslator.toCumulus(form);
        boolean accepted = apiSupplier.get().sendForm(playerId, translated);
        // 上游 boolean 只代表「Floodgate 是否接受遞送」，映射為具名結果，
        // 不得解讀為「玩家已收到」或「玩家已回應」
        return accepted ? FormSendResult.SENT : FormSendResult.REJECTED;
    }

    @Override
    public FormSendResult sendForm(UUID playerId, FormSpec form, UUID token,
            Consumer<FormResponse> onResponse) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(form, "form");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(onResponse, "onResponse");
        // handler 掛接與原始回應映射都在翻譯層完成；token 由 FormServiceImpl
        // 用於 pending 對帳，本類別只負責把接收端接到翻譯層
        Form translated = CumulusFormTranslator.toCumulus(form, onResponse);
        boolean accepted = apiSupplier.get().sendForm(playerId, translated);
        return accepted ? FormSendResult.SENT : FormSendResult.REJECTED;
    }
}
