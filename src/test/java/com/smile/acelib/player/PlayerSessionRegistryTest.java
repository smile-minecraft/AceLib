package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerSessionRegistry} 行為測試。
 *
 * <p>對應 Plan §十四 Phase 9 驗收標準：session 與冷卻可 start/query/end，
 * 名稱變化不影響既有 session。</p>
 */
@DisplayName("PlayerSessionRegistry")
class PlayerSessionRegistryTest {

    @Test
    @DisplayName("startSession：建立新 session 並回傳；後續 get 可查到")
    void startSession_createsNewSession() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        UUID id = UUID.randomUUID();
        PlayerSession s = reg.startSession(id, "alice");
        assertNotNull(s);
        assertSame(id, s.getUniqueId());
        assertEquals("alice", s.getName());
        assertSame(PlayerSessionState.LOADING, s.getState());

        Optional<PlayerSession> found = reg.getSession(id);
        assertTrue(found.isPresent());
        assertSame(s, found.get());
    }

    @Test
    @DisplayName("startSession：同一 UUID 重複呼叫拋 PLAYER-004")
    void startSession_duplicateUuid_throws() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        UUID id = UUID.randomUUID();
        reg.startSession(id, "alice");
        PlayerStateException ex = assertThrows(PlayerStateException.class,
            () -> reg.startSession(id, "alice2"));
        assertEquals("ACELIB-PLAYER-004", ex.getCode());
    }

    @Test
    @DisplayName("startSession：null UUID 拋 NPE")
    void startSession_nullUuid_throws() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        assertThrows(NullPointerException.class, () -> reg.startSession(null, "alice"));
    }

    @Test
    @DisplayName("startSession：null name 拋 NPE")
    void startSession_nullName_throws() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        assertThrows(NullPointerException.class,
            () -> reg.startSession(UUID.randomUUID(), null));
    }

    @Test
    @DisplayName("getSession：未註冊 UUID 回傳 empty Optional")
    void getSession_unknownUuid_empty() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        assertFalse(reg.getSession(UUID.randomUUID()).isPresent());
    }

    @Test
    @DisplayName("getSession：null UUID 拋 NPE")
    void getSession_nullUuid_throws() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        assertThrows(NullPointerException.class, () -> reg.getSession(null));
    }

    @Test
    @DisplayName("endSession：移除 session 並回傳；後續 get 回傳 empty")
    void endSession_removesSession() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        UUID id = UUID.randomUUID();
        reg.startSession(id, "alice");
        PlayerSession ended = reg.endSession(id);
        assertNotNull(ended);
        assertFalse(reg.getSession(id).isPresent());
    }

    @Test
    @DisplayName("endSession：未註冊 UUID 回傳 null")
    void endSession_unknownUuid_null() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        assertEquals(null, reg.endSession(UUID.randomUUID()));
    }

    @Test
    @DisplayName("endSession：null UUID 拋 NPE")
    void endSession_nullUuid_throws() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        assertThrows(NullPointerException.class, () -> reg.endSession(null));
    }

    @Test
    @DisplayName("size：反映目前 active session 數")
    void size_reflectsActiveSessionCount() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        assertEquals(0, reg.size());
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        reg.startSession(aliceId, "alice");
        assertEquals(1, reg.size());
        reg.startSession(bobId, "bob");
        assertEquals(2, reg.size());
        reg.endSession(aliceId);
        assertEquals(1, reg.size());
    }

    @Test
    @DisplayName("clear：移除所有 session（reload / disable 使用）")
    void clear_removesAllSessions() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        reg.startSession(UUID.randomUUID(), "alice");
        reg.startSession(UUID.randomUUID(), "bob");
        reg.clear();
        assertEquals(0, reg.size());
    }

    @Test
    @DisplayName("clearAll：冪等，重複呼叫不丟例外")
    void clear_idempotent() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        reg.clear();
        reg.clear();
        assertEquals(0, reg.size());
    }

    @Test
    @DisplayName("name-change 場景：同名玩家重登（不同 UUID）不會汙染既有 session")
    void nameChangeDifferentUuid_doesNotPollute() {
        PlayerSessionRegistry reg = new PlayerSessionRegistry();
        UUID aliceId = UUID.randomUUID();
        UUID aliceRenamedId = UUID.randomUUID();
        PlayerSession s1 = reg.startSession(aliceId, "alice");
        // 模擬 "alice" 改名後以新 UUID 登入 — 兩者並存
        PlayerSession s2 = reg.startSession(aliceRenamedId, "alice_renamed");
        assertEquals(2, reg.size());
        // 同時查得到
        assertSame(s1, reg.getSession(aliceId).get());
        assertSame(s2, reg.getSession(aliceRenamedId).get());
    }
}
