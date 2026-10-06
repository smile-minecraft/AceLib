package com.example.acelibconsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smile.acelib.testing.FakeClock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 下游視角的 test-fixtures 引用證明：consumer 只需 classifier 依賴
 * {@code com.smile:acelib:1.4.0-SNAPSHOT:test-fixtures} 即可使用
 * {@code FakeClock} 等測試輔助（本機 mavenLocal 路徑；公開 JitPack 路徑見
 * docs/reference/release-artifacts.md）。
 */
class FixturesSmokeTest {

    @Test
    void fakeClock_advancesDeterministically() {
        FakeClock clock = new FakeClock(1_000L);
        AtomicInteger runs = new AtomicInteger();
        long before = clock.currentTimeMillis();
        clock.advanceMillis(500L);
        runs.incrementAndGet();
        assertEquals(before + 500L, clock.currentTimeMillis());
        assertEquals(1, runs.get());
    }
}
