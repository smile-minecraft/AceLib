package com.smile.acelib.data;

import java.util.Map;

/**
 * JSON 編解碼器（用於 {@link JsonFileDataStore} 的底層序列化）。
 *
 * <p>介面刻意抽象，預設實作見 {@code JsonCodecImpl}（內建極簡 JSON 處理器）；
 * 後續插件若想換成 Gson / Jackson，可實作此介面注入。</p>
 *
 * <h2>序列化範圍</h2>
 * <ul>
 *   <li>基本型別：{@link String} / {@link Integer} / {@link Long} / {@link Double} / {@link Boolean}</li>
 *   <li>{@code null}</li>
 *   <li>巢狀 {@code Map<String, Object>} 與 {@code List<Object>}</li>
 *   <li>本版本不支援自訂 POJO 序列化（避免引入反射／依賴；改用 {@code Map} 顯式建模）</li>
 * </ul>
 *
 * <h2>錯誤代碼</h2>
 * <ul>
 *   <li>{@code ACELIB-DATA-002}：解析失敗（內容不是合法 JSON）</li>
 *   <li>{@code ACELIB-DATA-006}：不支援型別（round-trip 型別白名單外）</li>
 * </ul>
 *
 * @since 1.0.0
 */
public interface JsonCodec {

    /**
     * 將 {@link SchemaVersion} 編碼為 {@code "major.minor"} 字串。
     *
     * @param version 不可為 null
     * @return 不可為 null
     */
    String encodeVersion(SchemaVersion version);

    /**
     * 將 {@code "major.minor"} 字串解碼為 {@link SchemaVersion}。
     *
     * @param text 不可為 null
     * @return 不可為 null
     * @throws DataStoreException 格式錯誤（{@code ACELIB-DATA-002}）
     */
    SchemaVersion decodeVersion(String text);

    /**
     * 將物件序列化為 JSON 字串。
     *
     * @param value 不可為 null
     * @return 不可為 null
     * @throws DataStoreException 不支援型別（{@code ACELIB-DATA-006}）
     */
    String encode(Object value);

    /**
     * 將 JSON 字串反序列化為 {@code Map<String, Object>}（根節點）。
     *
     * @param text 不可為 null
     * @return 不可為 null；空輸入回傳空 map
     * @throws DataStoreException 解析失敗（{@code ACELIB-DATA-002}）
     */
    Map<String, Object> decode(String text);
}