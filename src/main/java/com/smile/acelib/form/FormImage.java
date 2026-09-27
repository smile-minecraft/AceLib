package com.smile.acelib.form;

import java.net.URI;

/**
 * Simple 表單按鈕圖示值型別（Supported API）。
 *
 * <p>描述按鈕上可選的一張圖示：基岩資源包路徑（{@link Type#PATH}）或網址
 * （{@link Type#URL}）。圖示型別由 AceLib 自有 enum 承載，Cumulus 型別只在內部
 * 翻譯層出現，不出現在任何公開簽章。</p>
 *
 * <p>驗證規則（建構當下即驗證，含直接呼叫 canonical constructor）：</p>
 * <ul>
 *   <li>PATH：不得為 null／空白、不可以 {@code /} 開頭、不得含 {@code ..}。</li>
 *   <li>URL：必須是具 host 的絕對 http／https URI。</li>
 * </ul>
 *
 * <p>AceLib 不下載、不代理、不驗證圖示內容：客戶端下載 URL 失敗或貼圖不存在
 * 不是伺服器端錯誤。</p>
 *
 * @param type 圖示型別；不可為 null
 * @param data 型別承載資料（PATH 為資源包路徑、URL 為 http/https 網址）；不可為 null
 * @since 1.3.0
 */
public record FormImage(Type type, String data) {

    /** 圖示型別（PATH 資源包路徑／URL 網址）。 */
    public enum Type {
        /** 基岩資源包路徑圖示。 */
        PATH,
        /** 網址圖示。 */
        URL
    }

    /**
     * 正規化建構子：依型別驗證資料。
     *
     * @throws IllegalArgumentException type 為 null 或 data 違反型別驗證規則
     */
    public FormImage {
        if (type == null) {
            throw new IllegalArgumentException("form image type must not be null");
        }
        switch (type) {
            case PATH -> requireValidPath(data);
            case URL -> requireValidUrl(data);
        }
    }

    /**
     * 以資源包路徑建立圖示。
     *
     * @param path 資源包路徑；驗證規則見型別 Javadoc
     * @return PATH 圖示；never null
     * @throws IllegalArgumentException path 不合法
     */
    public static FormImage path(String path) {
        return new FormImage(Type.PATH, path);
    }

    /**
     * 以網址建立圖示。
     *
     * @param url 絕對 http/https 網址；驗證規則見型別 Javadoc
     * @return URL 圖示；never null
     * @throws IllegalArgumentException url 不合法
     */
    public static FormImage url(String url) {
        return new FormImage(Type.URL, url);
    }

    private static void requireValidPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("form image path must not be null or blank");
        }
        if (path.startsWith("/")) {
            throw new IllegalArgumentException(
                "form image path must not start with '/': " + path);
        }
        if (path.contains("..")) {
            throw new IllegalArgumentException(
                "form image path must not contain '..': " + path);
        }
    }

    private static void requireValidUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("form image url must not be null or blank");
        }
        final URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("form image url is malformed: " + url,
                malformed);
        }
        String scheme = parsed.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                "form image url must use http or https scheme: " + url);
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new IllegalArgumentException(
                "form image url must be absolute with a host: " + url);
        }
    }
}
