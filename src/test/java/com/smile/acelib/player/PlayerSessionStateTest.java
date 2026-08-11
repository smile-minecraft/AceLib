package com.smile.acelib.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PlayerSessionState} 列舉測試。
 *
 * <p>對應 Plan §十四 Phase 9「session 狀態可觀察」需求：狀態需區分
 * LOADING / READY / UNLOADING / ENDED，使外部代碼能明確處理「資料未就緒」
 * 的情境。</p>
 */
@DisplayName("PlayerSessionState")
class PlayerSessionStateTest {

    @Test
    @DisplayName("列舉包含 LOADING / READY / UNLOADING / ENDED")
    void enumValues() {
        PlayerSessionState[] values = PlayerSessionState.values();
        assertEquals(4, values.length, "Phase 9 session 應有 4 個狀態");
        assertNotNull(PlayerSessionState.valueOf("LOADING"));
        assertNotNull(PlayerSessionState.valueOf("READY"));
        assertNotNull(PlayerSessionState.valueOf("UNLOADING"));
        assertNotNull(PlayerSessionState.valueOf("ENDED"));
    }

    @Test
    @DisplayName("isTerminal：ENDED 為終態；其餘皆非終態")
    void isTerminal() {
        assertTrue(PlayerSessionState.ENDED.isTerminal(),
            "ENDED 必須為終態");
        assertFalse(PlayerSessionState.LOADING.isTerminal(),
            "LOADING 非終態");
        assertFalse(PlayerSessionState.READY.isTerminal(),
            "READY 非終態");
        assertFalse(PlayerSessionState.UNLOADING.isTerminal(),
            "UNLOADING 非終態");
    }

    @Test
    @DisplayName("isReady：只有 READY 視為可操作資料的狀態")
    void isReady() {
        assertTrue(PlayerSessionState.READY.isReady());
        assertFalse(PlayerSessionState.LOADING.isReady());
        assertFalse(PlayerSessionState.UNLOADING.isReady());
        assertFalse(PlayerSessionState.ENDED.isReady());
    }

    @Test
    @DisplayName("stateTransition：LOADING → READY → UNLOADING → ENDED 為合法順序")
    void stateTransitionOrder() {
        assertTrue(PlayerSessionState.LOADING.canTransitionTo(PlayerSessionState.READY),
            "LOADING 應可進入 READY");
        assertTrue(PlayerSessionState.READY.canTransitionTo(PlayerSessionState.UNLOADING),
            "READY 應可進入 UNLOADING");
        assertTrue(PlayerSessionState.UNLOADING.canTransitionTo(PlayerSessionState.ENDED),
            "UNLOADING 應可進入 ENDED");
    }

    @Test
    @DisplayName("stateTransition：ENDED 不可再轉換（終態）")
    void stateTransitionFromTerminal() {
        for (PlayerSessionState target : PlayerSessionState.values()) {
            assertFalse(PlayerSessionState.ENDED.canTransitionTo(target),
                "ENDED 為終態，不得再轉換至 " + target);
        }
    }

    @Test
    @DisplayName("stateTransition：LOADING → ENDED 為合法（load 失敗短路）")
    void stateTransitionLoadFailureShortcut() {
        // LOADING → ENDED 為合法：資料載入失敗時不需走過 READY/UNLOADING。
        assertTrue(PlayerSessionState.LOADING.canTransitionTo(PlayerSessionState.ENDED),
            "LOADING 應可短路至 ENDED（load 失敗情境）");
    }

    @Test
    @DisplayName("stateTransition：跳轉（如 LOADING → UNLOADING）為非法")
    void stateTransitionIllegal() {
        assertFalse(PlayerSessionState.LOADING.canTransitionTo(PlayerSessionState.UNLOADING),
            "LOADING 不可跳至 UNLOADING（必須先 READY 才有資料可保存）");
        assertFalse(PlayerSessionState.READY.canTransitionTo(PlayerSessionState.LOADING),
            "READY 不可回到 LOADING");
    }
}
