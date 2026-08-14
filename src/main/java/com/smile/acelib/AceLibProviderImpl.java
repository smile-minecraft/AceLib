package com.smile.acelib;

import java.util.Objects;

/**
 * {@link AceLibApi.AceLibProvider} 的動態實作。
 *
 * <p>以 {@code volatile} 持有目前 {@link AceLibApi}；plugin 在 enable / reload /
 * disable 時呼叫 {@link #updateApi(AceLibApi)} 切換 reference，使已持有 provider
 * 的呼叫端也能讀到最新狀態（reload 後為新 facade，disable 後為 shutdown facade）。
 * 任何 thread 都可安全呼叫 {@link #api()}。</p>
 *
 * <p>本類別為 package-private：provider 實作細節不是消費者契約，
 * 對外只暴露 {@link AceLibApi.AceLibProvider}。</p>
 */
final class AceLibProviderImpl implements AceLibApi.AceLibProvider {

    private volatile AceLibApi api;

    AceLibProviderImpl(AceLibApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public AceLibApi api() {
        return api;
    }

    /**
     * 切換目前 facade reference。
     *
     * @param api 新的目前 facade；不可為 null
     */
    void updateApi(AceLibApi api) {
        this.api = Objects.requireNonNull(api, "api");
    }
}
