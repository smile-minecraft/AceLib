package com.smile.acelib.data;

/**
 * 玩家資料與 record 資料模型之間的編解碼器（SPI）。
 *
 * <p>AceLib 內建 {@link RecordPlayerDataCodec} 以 record component 名對映頂層欄位。
 * 下游若要改用其他映射規則（例如別名、加密欄位），可自行實作本介面注入
 * {@code PlayerDataService}。</p>
 *
 * <h2>方向</h2>
 * <ul>
 *   <li>{@link #encode(Object)}：record → {@link Record}（欄位即 component 名）</li>
 *   <li>{@link #decode(Record)}：{@link Record} → record</li>
 * </ul>
 *
 * <h2>錯誤</h2>
 * <p>資料與資料模型不符（型別錯誤、巢狀節點缺形狀）時，
 * {@link #decode(Record)} 拋 {@link DataStoreException}
 * （{@code ACELIB-DATA-002}，訊息帶出問題欄位）；<strong>不猜、不填預設值</strong>。
 * 單純「欄位不存在」不算錯誤：依 component 型別取預設值（primitive 為型別零值、
 * 參考型別為 {@code null}）。</p>
 *
 * @param <T> record 資料模型型別
 * @see RecordPlayerDataCodec
 * @since 1.4.0
 */
public interface PlayerDataCodec<T> {

    /**
     * 把資料模型編碼成頂層欄位集合。
     *
     * @param value 資料模型實例；不可為 null
     * @return 欄位名 → 值 的 record；不可為 null
     * @throws NullPointerException     當 {@code value} 為 null
     * @throws DataStoreException       當欄位值型別無法序列化（{@code ACELIB-DATA-006}）
     */
    Record encode(T value);

    /**
     * 把頂層欄位集合解碼回資料模型。
     *
     * @param record 欄位視圖；不可為 null
     * @return 資料模型實例；不可為 null
     * @throws NullPointerException 當 {@code record} 為 null
     * @throws DataStoreException    當欄位型別與資料模型不符（{@code ACELIB-DATA-002}）
     */
    T decode(Record record);
}