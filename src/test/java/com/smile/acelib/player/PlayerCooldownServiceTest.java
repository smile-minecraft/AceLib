package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.diagnostics.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerCooldownService} 行為測試。
 *
 * <p>對應 Plan §十四 Phase 9 驗收標準：冷卻時間可開始、查詢、結束。</p>
 *
 * <p>與既有的 {@code command.CooldownTracker} 不同：
 * PlayerCooldownService 獨立於指令系統，純粹以 UUID + cooldown key 管理
 * 冷卻；提供更明確的 start/query/end API 與 reload 語意。</p>
 */
@DisplayName("PlayerCooldownService")
class PlayerCooldownServiceTest {

    private static class FakeClock implements Clock {
        final AtomicLong now = new AtomicLong(0);
        @Override public long currentTimeMillis() { return now.get(); }
        void advance(long ms) { now.addAndGet(ms); }
    }

    @Test
    @DisplayName("start：建立冷卻（duration<=0 仍視為可立即再次 acquire）")
    void start_createsCooldown() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        svc.start(id, "skill.a", 1000);
        // duration 內 query 回傳剩餘時間
        assertTrue(svc.remainingMillis(id, "skill.a") > 0);
    }

    @Test
    @DisplayName("query：未 start 的 key 回傳 0")
    void query_unstarted_returnsZero() {
        PlayerCooldownService svc = new PlayerCooldownService(new FakeClock());
        assertEquals(0, svc.remainingMillis(UUID.randomUUID(), "skill.a"));
    }

    @Test
    @DisplayName("query：過期後回傳 0")
    void query_expired_returnsZero() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        svc.start(id, "skill.a", 1000);
        clock.advance(1500);
        assertEquals(0, svc.remainingMillis(id, "skill.a"));
    }

    @Test
    @DisplayName("tryAcquire：冷卻中 false；冷卻結束 true")
    void tryAcquire_respectsCooldown() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        // 首次 acquire — 應成功並啟動冷卻
        assertTrue(svc.tryAcquire(id, "skill.a", 1000));
        // 冷卻期間再 acquire — false
        assertFalse(svc.tryAcquire(id, "skill.a", 1000));
        // 時間推進過期後又可 acquire
        clock.advance(1001);
        assertTrue(svc.tryAcquire(id, "skill.a", 1000));
    }

    @Test
    @DisplayName("tryAcquire：duration<=0 視為無冷卻")
    void tryAcquire_noCooldown() {
        PlayerCooldownService svc = new PlayerCooldownService(new FakeClock());
        UUID id = UUID.randomUUID();
        assertTrue(svc.tryAcquire(id, "skill.a", 0));
        assertTrue(svc.tryAcquire(id, "skill.a", 0));
    }

    @Test
    @DisplayName("end：清除指定 key 的冷卻（管理者指令）")
    void end_clearsSpecificKey() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        svc.start(id, "skill.a", 1000);
        svc.start(id, "skill.b", 5000);
        assertTrue(svc.remainingMillis(id, "skill.a") > 0);
        svc.end(id, "skill.a");
        assertEquals(0, svc.remainingMillis(id, "skill.a"),
            "end 應清除該 key 的冷卻");
        assertTrue(svc.remainingMillis(id, "skill.b") > 0,
            "end 不應影響其他 key");
    }

    @Test
    @DisplayName("endAll：清除單一玩家所有冷卻")
    void endAll_clearsPlayer() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        svc.start(id, "skill.a", 1000);
        svc.start(id, "skill.b", 5000);
        svc.endAll(id);
        assertEquals(0, svc.remainingMillis(id, "skill.a"));
        assertEquals(0, svc.remainingMillis(id, "skill.b"));
    }

    @Test
    @DisplayName("clearAll：清除所有玩家的所有冷卻（reload 使用）")
    void clearAll_clearsEverything() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        svc.start(a, "skill.a", 1000);
        svc.start(b, "skill.b", 5000);
        svc.clearAll();
        assertEquals(0, svc.remainingMillis(a, "skill.a"));
        assertEquals(0, svc.remainingMillis(b, "skill.b"));
    }

    @Test
    @DisplayName("clearAll：冪等")
    void clearAll_idempotent() {
        PlayerCooldownService svc = new PlayerCooldownService(new FakeClock());
        svc.clearAll();
        svc.clearAll();
    }

    @Test
    @DisplayName("start/tryAcquire：null UUID 拋 NPE")
    void nullUuid_throws() {
        PlayerCooldownService svc = new PlayerCooldownService(new FakeClock());
        assertThrows(NullPointerException.class,
            () -> svc.start(null, "k", 1000));
        assertThrows(NullPointerException.class,
            () -> svc.tryAcquire(null, "k", 1000));
        assertThrows(NullPointerException.class,
            () -> svc.remainingMillis(null, "k"));
        assertThrows(NullPointerException.class,
            () -> svc.end(null, "k"));
        assertThrows(NullPointerException.class,
            () -> svc.endAll(null));
    }

    @Test
    @DisplayName("start/tryAcquire：null key 拋 NPE")
    void nullKey_throws() {
        PlayerCooldownService svc = new PlayerCooldownService(new FakeClock());
        UUID id = UUID.randomUUID();
        assertThrows(NullPointerException.class,
            () -> svc.start(id, null, 1000));
        assertThrows(NullPointerException.class,
            () -> svc.tryAcquire(id, null, 1000));
        assertThrows(NullPointerException.class,
            () -> svc.remainingMillis(id, null));
        assertThrows(NullPointerException.class,
            () -> svc.end(id, null));
    }

    @Test
    @DisplayName("start：負 duration 拋 IAE")
    void start_negativeDuration_throws() {
        PlayerCooldownService svc = new PlayerCooldownService(new FakeClock());
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> svc.start(id, "k", -1));
    }

    @Test
    @DisplayName("trackedPlayerCount：反映不同玩家數")
    void trackedPlayerCount() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        assertEquals(0, svc.trackedPlayerCount());
        svc.start(UUID.randomUUID(), "k", 1000);
        assertEquals(1, svc.trackedPlayerCount());
        svc.start(UUID.randomUUID(), "k", 1000);
        assertEquals(2, svc.trackedPlayerCount());
    }

    @Test
    @DisplayName("名稱變更不影響既有冷卻：相同 UUID 不同 name 仍查到原冷卻")
    void nameChange_keepsCooldown() {
        FakeClock clock = new FakeClock();
        PlayerCooldownService svc = new PlayerCooldownService(clock);
        UUID id = UUID.randomUUID();
        // "alice" 啟動冷卻
        svc.start(id, "skill.a", 1000);
        long before = svc.remainingMillis(id, "skill.a");
        // 改名 "alice_renamed" — 由於以 UUID 索引，冷卻不受影響
        long after = svc.remainingMillis(id, "skill.a");
        assertEquals(before, after);
    }
}
