package com.smile.acelib.testing;

import com.smile.acelib.diagnostics.Clock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可手動推進的假時鐘（下游單元測試用）。
 *
 * <p>取代各下游自寫的 {@code AtomicLong} 時鐘：直接實作
 * {@link Clock}，可注入 {@code ErrorThrottler} 等時間依賴元件，全程不需 sleep。
 * 起始時間預設為 {@code 0}，由測試顯式推進，跨測試不共享狀態。</p>
 *
 * <p>執行緒安全：內部以 {@link AtomicLong} 承載時間。</p>
 *
 * @since 1.4.0
 */
public final class FakeClock implements Clock {

    private final AtomicLong millis;

    /** 以 {@code 0} 為起始時間建立。 */
    public FakeClock() {
        this(0L);
    }

    /**
     * 以指定起始時間建立。
     *
     * @param startMillis 起始時間（epoch millis）；必須 {@code >= 0}
     * @throws IllegalArgumentException 起始時間為負
     */
    public FakeClock(long startMillis) {
        if (startMillis < 0L) {
            throw new IllegalArgumentException(
                "startMillis must be >= 0, got: " + startMillis);
        }
        this.millis = new AtomicLong(startMillis);
    }

    @Override
    public long currentTimeMillis() {
        return millis.get();
    }

    /**
     * 向前推進時間。
     *
     * @param deltaMillis 推進毫秒數；必須 {@code >= 0} 且不得溢位
     * @throws IllegalArgumentException 推進量為負或會造成 long 溢位
     */
    public void advanceMillis(long deltaMillis) {
        if (deltaMillis < 0L) {
            throw new IllegalArgumentException(
                "deltaMillis must be >= 0, got: " + deltaMillis);
        }
        long current = millis.get();
        if (Long.MAX_VALUE - current < deltaMillis) {
            throw new IllegalArgumentException(
                "advanceMillis would overflow: current=" + current
                    + ", delta=" + deltaMillis);
        }
        millis.addAndGet(deltaMillis);
    }

    /**
     * 直接設定時間（僅允許向前，不允許倒轉，避免視窗語意混亂）。
     *
     * @param newMillis 新時間；必須 {@code >=} 目前時間
     * @throws IllegalArgumentException 新時間早於目前時間
     */
    public void setCurrentTimeMillis(long newMillis) {
        long current = millis.get();
        if (newMillis < current) {
            throw new IllegalArgumentException(
                "refusing to turn clock backwards: current=" + current
                    + ", requested=" + newMillis);
        }
        millis.set(newMillis);
    }
}
