package com.smile.acelib.form;

/**
 * 表單回應結果語意（Supported API）。
 *
 * <p>描述玩家對表單的回應落在哪一種狀態；本列舉只定義模型語意，
 * 回應的接收與派送（callback 呼叫、執行緒安全、Folia 派送）由後續
 * 回應派送任務以此語意為基礎實作。</p>
 *
 * <h2>三種狀態</h2>
 * <ul>
 *   <li>{@link #VALID} — 玩家提交了有效回應（按下按鈕、填寫並送出元件等）；
 *       派送端可進一步解析回應內容。</li>
 *   <li>{@link #CLOSED} — 玩家直接關閉表單（按 Esc / 返回鍵），沒有提交任何資料；
 *       這是正常使用者行為，不是錯誤。</li>
 *   <li>{@link #INVALID} — 玩家送出的資料無法解析為有效回應（協定層資料不符）；
 *       派送端不得把此狀態的內容當成玩家意圖。</li>
 * </ul>
 *
 * @since 1.0.0
 */
public enum FormResponseStatus {

    /** 玩家提交了有效回應。 */
    VALID,

    /** 玩家關閉表單，未提交任何資料。 */
    CLOSED,

    /** 玩家送出的資料無法解析為有效回應。 */
    INVALID
}
