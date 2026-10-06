package com.smile.acelib.scheduler;

import java.util.Objects;

/**
 * 排程動作的終態值（Supported，immutable record）。
 *
 * <p>每一個經由 {@link TaskScope} 派送的動作，結束時都會產生恰好一個
 * {@code TaskResult}，透過 {@link TaskTicket} 交給呼叫端。終態只完成一次：
 * 先勝出的完成保留，後續重複完成被忽略。</p>
 *
 * <h2>欄位語意</h2>
 * <ul>
 *   <li>{@code outcome} — 終態分類（永不為 null）；</li>
 *   <li>{@code value} — 完成時攜回的值（可為 null；{@code Void} 流程恆為 null）；</li>
 *   <li>{@code cause} — 失敗時的原始例外；僅 {@code FAILED} 可攜帶，其餘必須為 null；</li>
 *   <li>{@code errorRecord} — 對應的 {@code ACELIB-SCHED-*} 紀錄；
 *       {@code COMPLETED} 必須為 null，{@code FAILED} 與 {@code REJECTED} 必須攜帶，
 *       {@code CANCELLED} 可為 null（呼叫端顯式取消不算錯誤）。</li>
 * </ul>
 *
 * @param outcome     終態分類；不可為 null
 * @param value       完成時的值；可為 null
 * @param cause       失敗時的原始例外；僅失敗時可攜帶
 * @param errorRecord 對應的錯誤紀錄；完成時必須為 null，拒派時必須攜帶
 * @see TaskOutcome
 * @see TaskTicket
 * @see TaskScope
 * @since 1.4.0
 */
public record TaskResult<T>(
    TaskOutcome outcome,
    T value,
    Throwable cause,
    TaskErrorRecord errorRecord
) {

    /**
     * Compact constructor：依終態分類強制欄位組合，避免「完成卻帶例外」之類
     * 自相矛盾的值在系統內流動。
     *
     * @throws NullPointerException 當 {@code outcome} 為 null，或
     *         {@code FAILED} 缺 {@code cause}／{@code errorRecord}，
     *         或 {@code REJECTED} 缺 {@code errorRecord}
     * @throws IllegalArgumentException 當 {@code COMPLETED} 攜帶 {@code cause} 或
     *         {@code errorRecord}，或非失敗終態攜帶 {@code cause}
     */
    public TaskResult {
        Objects.requireNonNull(outcome, "outcome");
        switch (outcome) {
            case COMPLETED -> {
                if (cause != null || errorRecord != null) {
                    throw new IllegalArgumentException(
                        "COMPLETED result must not carry cause or errorRecord");
                }
            }
            case FAILED -> {
                Objects.requireNonNull(cause, "cause (FAILED)");
                Objects.requireNonNull(errorRecord, "errorRecord (FAILED)");
            }
            case CANCELLED -> {
                if (cause != null) {
                    throw new IllegalArgumentException(
                        "CANCELLED result must not carry cause");
                }
            }
            case REJECTED -> {
                if (cause != null) {
                    throw new IllegalArgumentException(
                        "REJECTED result must not carry cause");
                }
                Objects.requireNonNull(errorRecord, "errorRecord (REJECTED)");
            }
        }
    }

    /**
     * 建立完成終態。
     *
     * @param value 完成時的值；可為 null
     * @param <T> 值型別
     * @return 新的完成終態
     */
    public static <T> TaskResult<T> completed(T value) {
        return new TaskResult<>(TaskOutcome.COMPLETED, value, null, null);
    }

    /**
     * 建立失敗終態。
     *
     * @param cause  原始例外；不可為 null
     * @param record 對應的錯誤紀錄；不可為 null
     * @param <T> 值型別
     * @return 新的失敗終態
     */
    public static <T> TaskResult<T> failed(Throwable cause, TaskErrorRecord record) {
        return new TaskResult<>(TaskOutcome.FAILED, null,
            Objects.requireNonNull(cause, "cause"),
            Objects.requireNonNull(record, "record"));
    }

    /**
     * 建立取消終態。
     *
     * @param record 對應的錯誤紀錄；呼叫端顯式取消時可為 null
     * @param <T> 值型別
     * @return 新的取消終態
     */
    public static <T> TaskResult<T> cancelled(TaskErrorRecord record) {
        return new TaskResult<>(TaskOutcome.CANCELLED, null, null, record);
    }

    /**
     * 建立拒派終態。
     *
     * @param record 拒派原因紀錄；不可為 null
     * @param <T> 值型別
     * @return 新的拒派終態
     */
    public static <T> TaskResult<T> rejected(TaskErrorRecord record) {
        return new TaskResult<>(TaskOutcome.REJECTED, null, null,
            Objects.requireNonNull(record, "record"));
    }

    /**
     * @return 是否為完成終態
     */
    public boolean isCompleted() {
        return outcome == TaskOutcome.COMPLETED;
    }

    /**
     * @return 是否為失敗終態
     */
    public boolean isFailed() {
        return outcome == TaskOutcome.FAILED;
    }

    /**
     * @return 是否為取消終態
     */
    public boolean isCancelled() {
        return outcome == TaskOutcome.CANCELLED;
    }

    /**
     * @return 是否為拒派終態
     */
    public boolean isRejected() {
        return outcome == TaskOutcome.REJECTED;
    }
}
