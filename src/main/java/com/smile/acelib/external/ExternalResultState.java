package com.smile.acelib.external;

/**
 * 外部整合業務操作結果狀態。
 *
 * <p>所有外部整合業務結果（{@link EconomyResult}／{@link PermissionResult}／
 * {@link ExternalOperationResult}／{@link BuildCheckResult}）皆攜帶下列狀態之一：</p>
 *
 * <ul>
 *   <li>{@link #SUCCESS} — 操作成功完成；此時不攜帶錯誤代碼</li>
 *   <li>{@link #UNAVAILABLE} — 提供者缺席／停用／服務未啟用；呼叫端必須視為
 *       「未知」，不得默認允許、不得視為成功</li>
 *   <li>{@link #FAILED} — 提供者存在但操作失敗（回失敗、拋例外、查無對象）</li>
 * </ul>
 *
 * <h2>序列化相容</h2>
 * <p>狀態順序凍結，不得更動。</p>
 *
 * @see EconomyResult
 * @see PermissionResult
 * @see ExternalOperationResult
 * @see BuildCheckResult
 * @since 1.4.0
 */
public enum ExternalResultState {

    /** 操作成功完成。 */
    SUCCESS,

    /** 提供者缺席／停用／服務未啟用。 */
    UNAVAILABLE,

    /** 提供者存在但操作失敗。 */
    FAILED
}
