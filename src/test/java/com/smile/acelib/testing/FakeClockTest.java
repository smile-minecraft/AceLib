package com.smile.acelib.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.diagnostics.Clock;
import com.smile.acelib.diagnostics.ErrorThrottler;
import com.smile.acelib.diagnostics.ThrottleDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 可控時鐘 Red 錨點：下游以單一假實作推進時間，不再各自手寫 AtomicLong 時鐘。
 *
 * <p>在 {@code FakeClock} 交付前，本檔連編譯都過不了（缺失型別即缺陷本身）；
 * Green 後則鎖定：推進／回撥拒絕／與 {@link ErrorThrottler} 共用推進視窗。</p>
 */
@DisplayName("FakeClock — 可控時鐘")
class FakeClockTest {

    @Test
    @DisplayName("推進時間：advanceMillis 讓 ErrorThrottler 跨越視窗，全程無 sleep")
    void advanceMillis_movesThrottlerWindow() {
        FakeClock clock = new FakeClock(1_000L);
        ErrorThrottler throttler = new ErrorThrottler(clock, 1, 1_000L);

        assertEquals(ThrottleDecision.Kind.ALLOWED,
            throttler.tryRecord("ACELIB-TST-001", "first").kind());
        assertEquals(ThrottleDecision.Kind.SUPPRESSED,
            throttler.tryRecord("ACELIB-TST-001", "second").kind());

        clock.advanceMillis(1_000L);

        assertEquals(ThrottleDecision.Kind.ALLOWED,
            throttler.tryRecord("ACELIB-TST-001", "third").kind());
        assertEquals(2_000L, clock.currentTimeMillis());
    }

    @Test
    @DisplayName("時鐘實作 Clock 介面：可直接注入既有時間依賴元件")
    void implementsClockInterface() {
        Clock clock = new FakeClock();
        assertTrue(clock.currentTimeMillis() >= 0L);
    }

    @Test
    @DisplayName("邊界：負推進與溢位推進都被拒絕，不污染當前時間")
    void rejectsNegativeAndOverflowingAdvance() {
        FakeClock clock = new FakeClock(500L);
        assertThrows(IllegalArgumentException.class, () -> clock.advanceMillis(-1L));
        assertThrows(IllegalArgumentException.class, () -> clock.advanceMillis(Long.MAX_VALUE));
        assertEquals(500L, clock.currentTimeMillis());
    }
}
