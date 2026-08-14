package com.smile.acelib.diagnostics;

/**
 * 可注入的時鐘抽象。
 *
 * <p>供同類錯誤節流等需要時間判斷的元件注入時間來源；
 * 測試全程使用 {@link java.util.concurrent.atomic.AtomicLong} 實作
 * 來推進時間，<strong>禁止 sleep</strong>。</p>
 *
 * <h2>設計動機</h2>
 * <ul>
 *   <li>讓 {@link ErrorThrottler} 的「視窗內 vs 視窗外」判斷可在
 *       純單元測試內 deterministic 驗證</li>
 *   <li>production 實作會直接委派給 {@link System#currentTimeMillis()}</li>
 *   <li>測試實作可手動推進時間，避免 flaky test</li>
 * </ul>
 *
 * @see ErrorThrottler
 * @since 1.0.0
 */
@FunctionalInterface
public interface Clock {

    /**
     * 取得目前時間（epoch millis）。
     *
     * @return 目前時間（毫秒）
     */
    long currentTimeMillis();

    /**
     * 預設使用系統時鐘的工廠。
     *
     * @return 委派給 {@link System#currentTimeMillis()} 的 {@link Clock}
     */
    static Clock system() {
        return System::currentTimeMillis;
    }
}
