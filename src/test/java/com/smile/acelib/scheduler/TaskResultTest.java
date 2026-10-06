package com.smile.acelib.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 任務完成語意值型別測試（不依賴 MockBukkit 的純單元測試）。
 *
 * <p>鎖定「已接受排程 vs 動作完成」的終態分類：完成攜值、失敗攜因、
 * 取消與拒派攜紀錄；終態只可建立一次的不可變語意由呼叫端
 * {@code TaskTicket} 保證，本類別驗證值本身的約束。</p>
 */
@DisplayName("排程完成語意 — TaskResult / TaskOutcome")
class TaskResultTest {

    @Test
    @DisplayName("completed 攜值且無因無紀錄")
    void completed_carriesValue() {
        TaskResult<String> result = TaskResult.completed("ok");
        assertEquals(TaskOutcome.COMPLETED, result.outcome());
        assertEquals("ok", result.value());
        assertNull(result.cause());
        assertNull(result.errorRecord());
        assertTrue(result.isCompleted());
        assertFalse(result.isFailed());
        assertFalse(result.isCancelled());
        assertFalse(result.isRejected());
    }

    @Test
    @DisplayName("completed 允許 null 值（Void 流程）")
    void completed_allowsNullValue() {
        TaskResult<Void> result = TaskResult.completed(null);
        assertEquals(TaskOutcome.COMPLETED, result.outcome());
        assertNull(result.value());
        assertTrue(result.isCompleted());
    }

    @Test
    @DisplayName("failed 攜因與 SCHED-001 紀錄")
    void failed_carriesCauseAndRecord() {
        IllegalStateException failure = new IllegalStateException("boom");
        TaskErrorRecord record = TaskErrorRecord.threw(
            TaskType.ASYNC, "ACELIB-SCHED-001", "user task threw exception: boom", failure);
        TaskResult<String> result = TaskResult.failed(failure, record);
        assertEquals(TaskOutcome.FAILED, result.outcome());
        assertSame(failure, result.cause());
        assertSame(record, result.errorRecord());
        assertNull(result.value());
        assertTrue(result.isFailed());
        assertFalse(result.isCompleted());
    }

    @Test
    @DisplayName("cancelled 攜 SCHED-002 紀錄")
    void cancelled_carriesRecord() {
        TaskErrorRecord record = TaskErrorRecord.cancelled(
            TaskType.PLAYER, "ACELIB-SCHED-002", "player is offline (uuid=?)");
        TaskResult<Void> result = TaskResult.cancelled(record);
        assertEquals(TaskOutcome.CANCELLED, result.outcome());
        assertSame(record, result.errorRecord());
        assertNull(result.cause());
        assertTrue(result.isCancelled());
        assertFalse(result.isCompleted());
    }

    @Test
    @DisplayName("cancelled 允許無紀錄（呼叫端顯式取消不算錯誤）")
    void cancelled_allowsNullRecord() {
        TaskResult<Void> result = TaskResult.cancelled(null);
        assertEquals(TaskOutcome.CANCELLED, result.outcome());
        assertNull(result.errorRecord());
        assertTrue(result.isCancelled());
    }

    @Test
    @DisplayName("rejected 必須攜紀錄（拒派一定有原因代碼）")
    void rejected_carriesRecord() {
        TaskErrorRecord record = TaskErrorRecord.cancelled(
            TaskType.PLAYER, "ACELIB-SCHED-002", "player is offline (uuid=?)");
        TaskResult<Void> result = TaskResult.rejected(record);
        assertEquals(TaskOutcome.REJECTED, result.outcome());
        assertSame(record, result.errorRecord());
        assertTrue(result.isRejected());
        assertFalse(result.isCancelled());
    }

    @Test
    @DisplayName("rejected 拒絕 null 紀錄")
    void rejected_nullRecord_throws() {
        assertThrows(NullPointerException.class, () -> TaskResult.rejected(null));
    }

    @Test
    @DisplayName("failed 拒絕 null 因與 null 紀錄")
    void failed_nullCauseOrRecord_throws() {
        TaskErrorRecord record = TaskErrorRecord.cancelled(
            TaskType.ASYNC, "ACELIB-SCHED-001", "x");
        assertThrows(NullPointerException.class, () -> TaskResult.failed(null, record));
        assertThrows(NullPointerException.class,
            () -> TaskResult.failed(new IllegalStateException("x"), null));
    }

    @Test
    @DisplayName("completed 不可攜因或紀錄；cancelled 不可攜因")
    void mismatchedFields_throw() {
        TaskErrorRecord record = TaskErrorRecord.cancelled(
            TaskType.GLOBAL, "ACELIB-SCHED-006", "scheduler is disabled");
        IllegalStateException failure = new IllegalStateException("x");
        assertThrows(IllegalArgumentException.class,
            () -> new TaskResult<>(TaskOutcome.COMPLETED, "v", failure, null));
        assertThrows(IllegalArgumentException.class,
            () -> new TaskResult<>(TaskOutcome.COMPLETED, "v", null, record));
        assertThrows(IllegalArgumentException.class,
            () -> new TaskResult<>(TaskOutcome.CANCELLED, null, failure, null));
        assertThrows(IllegalArgumentException.class,
            () -> new TaskResult<>(TaskOutcome.REJECTED, null, failure, record));
    }

    @Test
    @DisplayName("outcome 不可為 null")
    void nullOutcome_throws() {
        assertThrows(NullPointerException.class,
            () -> new TaskResult<>(null, "v", null, null));
    }

    @Test
    @DisplayName("四種終態互斥")
    void outcomes_areMutuallyExclusive() {
        assertEquals(4, TaskOutcome.values().length);
        assertTrue(TaskOutcome.COMPLETED != TaskOutcome.FAILED
            && TaskOutcome.FAILED != TaskOutcome.CANCELLED
            && TaskOutcome.CANCELLED != TaskOutcome.REJECTED);
    }
}
