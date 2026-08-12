package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GuiState ordinal 凍結合約 regression test（對應 Momus blocking 修正）。
 *
 * <p>GuiState 的序列化相容依賴 enum 常數順序凍結：既有五個狀態
 * SUCCESS / ALLOWED / REJECTED / FAILED / CLOSED 的 ordinal 不得因新增
 * ACCEPTED 而位移。本測試鎖定其 ordinal 與 values() 順序，確保未來任何
 * 新增狀態都只能追加到末尾，不得插入既有常數之間。</p>
 */
@DisplayName("GuiState ordinal 凍結合約")
class GuiStateOrdinalTest {

    @Test
    @DisplayName("既有五個狀態 ordinal 不變，ACCEPTED 追加末尾")
    void originalOrdinalsFrozen_acceptedAppendedAtEnd() {
        assertEquals(0, GuiState.SUCCESS.ordinal(), "SUCCESS 必須保持 ordinal 0");
        assertEquals(1, GuiState.ALLOWED.ordinal(), "ALLOWED 必須保持 ordinal 1");
        assertEquals(2, GuiState.REJECTED.ordinal(), "REJECTED 必須保持 ordinal 2");
        assertEquals(3, GuiState.FAILED.ordinal(), "FAILED 必須保持 ordinal 3");
        assertEquals(4, GuiState.CLOSED.ordinal(), "CLOSED 必須保持 ordinal 4");
        assertEquals(5, GuiState.ACCEPTED.ordinal(),
            "ACCEPTED 必須追加到末尾 ordinal 5，不得插入既有常數之間");
    }

    @Test
    @DisplayName("values() 順序為 SUCCESS, ALLOWED, REJECTED, FAILED, CLOSED, ACCEPTED")
    void valuesOrder_frozenThenAccepted() {
        assertArrayEquals(
            new GuiState[] {
                GuiState.SUCCESS, GuiState.ALLOWED, GuiState.REJECTED,
                GuiState.FAILED, GuiState.CLOSED, GuiState.ACCEPTED
            },
            GuiState.values(),
            "values() 順序必須凍結，ACCEPTED 只能追加末尾");
    }
}
