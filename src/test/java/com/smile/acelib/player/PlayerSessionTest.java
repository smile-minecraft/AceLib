package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerSession} 行為測試。
 *
 * <p>對應 Plan §十四 Phase 9 驗收標準：
 * 玩家名稱變化不影響 UUID 識別、session 狀態轉換可觀察、
 * 不持有已失效 Player 物件。</p>
 */
@DisplayName("PlayerSession")
class PlayerSessionTest {

    @Test
    @DisplayName("constructor：null UUID 拋 NPE；state 預設 LOADING")
    void constructor_nullUuid_throws() {
        assertThrows(NullPointerException.class,
            () -> new PlayerSession(null, "alice", PlayerSessionState.LOADING));
    }

    @Test
    @DisplayName("constructor：null name 拋 NPE")
    void constructor_nullName_throws() {
        assertThrows(NullPointerException.class,
            () -> new PlayerSession(UUID.randomUUID(), null, PlayerSessionState.LOADING));
    }

    @Test
    @DisplayName("constructor：null state 拋 NPE")
    void constructor_nullState_throws() {
        assertThrows(NullPointerException.class,
            () -> new PlayerSession(UUID.randomUUID(), "alice", null));
    }

    @Test
    @DisplayName("getUniqueId 回傳建構時的 UUID")
    void getUniqueId_returnsConstructorValue() {
        UUID id = UUID.randomUUID();
        PlayerSession s = new PlayerSession(id, "alice", PlayerSessionState.LOADING);
        assertSame(id, s.getUniqueId());
    }

    @Test
    @DisplayName("getName 回傳建構時快照；後續 rename 不影響 session 記錄")
    void getName_returnsSnapshot() {
        UUID id = UUID.randomUUID();
        PlayerSession s = new PlayerSession(id, "alice", PlayerSessionState.LOADING);
        assertEquals("alice", s.getName());
        // 注意：PlayerSession 持有的是「建構時快照」而非 Player 物件 —
        // 後續即使玩家改名，這個 snapshot 不變。
        // UUID 仍為同一個，資料 key 不受影響。
        assertSame(id, s.getUniqueId());
    }

    @Test
    @DisplayName("getState 回傳當前狀態")
    void getState_returnsConstructorValue() {
        PlayerSession s = new PlayerSession(UUID.randomUUID(), "alice",
            PlayerSessionState.READY);
        assertSame(PlayerSessionState.READY, s.getState());
    }

    @Test
    @DisplayName("transitionTo：合法轉換時更新 state；非法時維持原值並拋例外")
    void transitionTo_updatesState() {
        UUID id = UUID.randomUUID();
        PlayerSession s = new PlayerSession(id, "alice", PlayerSessionState.LOADING);
        s.transitionTo(PlayerSessionState.READY);
        assertSame(PlayerSessionState.READY, s.getState());
    }

    @Test
    @DisplayName("transitionTo：END 不可再轉換")
    void transitionTo_terminalRejects() {
        PlayerSession s = new PlayerSession(UUID.randomUUID(), "alice",
            PlayerSessionState.ENDED);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> s.transitionTo(PlayerSessionState.LOADING));
        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("ENDED"),
            "錯誤訊息應提示當前為 ENDED，實際: " + ex.getMessage());
    }

    @Test
    @DisplayName("transitionTo：非法跳轉拋 IllegalStateException")
    void transitionTo_illegalTransition_throws() {
        PlayerSession s = new PlayerSession(UUID.randomUUID(), "alice",
            PlayerSessionState.LOADING);
        // LOADING → UNLOADING 為非法（必須先 READY 才有資料可保存）
        assertThrows(IllegalStateException.class,
            () -> s.transitionTo(PlayerSessionState.UNLOADING));
        // 狀態不變
        assertSame(PlayerSessionState.LOADING, s.getState());
    }

    @Test
    @DisplayName("transitionTo：LOADING → ENDED 為合法（load 失敗短路）")
    void transitionTo_loadingToEnded_isLegal() {
        PlayerSession s = new PlayerSession(UUID.randomUUID(), "alice",
            PlayerSessionState.LOADING);
        s.transitionTo(PlayerSessionState.ENDED);
        assertSame(PlayerSessionState.ENDED, s.getState());
    }

    @Test
    @DisplayName("isReady：state=READY 時為 true；其他狀態為 false")
    void isReady() {
        PlayerSession loading = new PlayerSession(UUID.randomUUID(), "alice",
            PlayerSessionState.LOADING);
        PlayerSession ready = new PlayerSession(UUID.randomUUID(), "bob",
            PlayerSessionState.READY);
        PlayerSession unloading = new PlayerSession(UUID.randomUUID(), "carol",
            PlayerSessionState.UNLOADING);
        PlayerSession ended = new PlayerSession(UUID.randomUUID(), "dave",
            PlayerSessionState.ENDED);
        assertFalse(loading.isReady());
        assertTrue(ready.isReady());
        assertFalse(unloading.isReady());
        assertFalse(ended.isReady());
    }

    @Test
    @DisplayName("equals/hashCode：以 UUID 為唯一識別（同 UUID 不同 name 仍相等）")
    void equals_hashCode_byUuidOnly() {
        UUID id = UUID.randomUUID();
        PlayerSession a = new PlayerSession(id, "alice", PlayerSessionState.LOADING);
        PlayerSession b = new PlayerSession(id, "ALICE_RENAMED", PlayerSessionState.READY);
        // 即使名稱改變、state 不同，UUID 相同仍應視為同一 session
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        // 不同 UUID 應不相等
        PlayerSession c = new PlayerSession(UUID.randomUUID(), "alice",
            PlayerSessionState.LOADING);
        assertNotEquals(a, c);
    }

    @Test
    @DisplayName("toString 包含 UUID 與 name，方便 log 辨識")
    void toString_containsUuidAndName() {
        UUID id = UUID.randomUUID();
        PlayerSession s = new PlayerSession(id, "alice", PlayerSessionState.LOADING);
        String str = s.toString();
        assertTrue(str.contains(id.toString()), "toString 應包含 UUID");
        assertTrue(str.contains("alice"), "toString 應包含 name");
        assertTrue(str.contains("LOADING"), "toString 應包含 state");
    }
}
