package com.smile.acelib.scheduler;

import java.util.Objects;

/**
 * 排程錯誤紀錄（immutable record）。
 *
 * <p>對應 Plan §七 Phase 2「任務錯誤留下可追蹤紀錄」的需求。
 * 任何 {@link SafeScheduler} 派送的任務若發生錯誤、玩家離線、實體失效、
 * chunk 不可用、平台不支援或插件已停用，皆會被包裝為 {@code TaskErrorRecord}
 * 並送入 {@link TaskErrorRecorder}。</p>
 *
 * <h2>欄位語意</h2>
 * <ul>
 *   <li>{@link #type} — 觸發此錯誤的任務類型</li>
 *   <li>{@link #code} — 錯誤分類代碼，遵循 {@code ACELIB-SCHED-xxx} 格式
 *       （參見 CONTRIBUTING.md §6 與 §二十三 DoD）</li>
 *   <li>{@link #detail} — 人類可讀的詳細訊息（含目標、原因）</li>
 *   <li>{@link #cause} — 觸發此錯誤的例外（可為 null，例如「取消」情境）；由
 *       {@link #threw(TaskType, String, String, Throwable)} 工廠方法建立的紀錄
 *       保證非 null</li>
 *   <li>{@link #tick} — 紀錄產生時的伺服器 tick（測試環境若無 {@code Bukkit.getCurrentTick()}
 *       則為 0）</li>
 * </ul>
 *
 * <h2>硬性約束</h2>
 * <ul>
 *   <li>{@code type}、{@code code}、{@code detail} 不可為 null</li>
 *   <li>{@code cause} 在取消類錯誤下可為 null；於執行錯誤下必須帶實際例外</li>
 *   <li>{@code tick} 可為 0（測試環境），不視為錯誤</li>
 * </ul>
 *
 * @see TaskErrorRecorder
 * @see SafeScheduler
 * @since Phase 2 (Plan §七)
 */
public record TaskErrorRecord(
    TaskType type,
    String code,
    String detail,
    Throwable cause,
    long tick
) {

    /**
     * Compact constructor：對不可空欄位做 null 檢查，給予清楚的失敗訊息。
     *
     * @throws NullPointerException 當 {@code type} / {@code code} / {@code detail} 為 null
     */
    public TaskErrorRecord {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        // cause 可為 null（取消類錯誤）；tick 可為任意 long
    }

    /**
     * 建立「取消類」錯誤紀錄。
     *
     * <p>適用情境：玩家離線（{@code ACELIB-SCHED-002}）、實體失效
     * （{@code ACELIB-SCHED-003}）、chunk 不可用（{@code ACELIB-SCHED-004}）、
     * 平台不支援（{@code ACELIB-SCHED-005}）、插件停用（{@code ACELIB-SCHED-006}）。
     * 這些情境下沒有實際例外，因此 {@link #cause} 為 null。</p>
     *
     * @param type   任務類型；不可為 null
     * @param code   錯誤代碼；不可為 null
     * @param detail 詳細訊息；不可為 null
     * @return 新的 {@link TaskErrorRecord} 實例
     */
    public static TaskErrorRecord cancelled(TaskType type, String code, String detail) {
        return new TaskErrorRecord(type, code, detail, null, currentTick());
    }

    /**
     * 建立「例外類」錯誤紀錄。
     *
     * <p>適用情境：任務內部拋錯（{@code ACELIB-SCHED-001}）或 dispatch 階段拋錯。
     * 若傳入的 {@code cause} 為 null，會自動替換為 {@code new Throwable("null-cause")}
     * 以保留例外槽位語意（避免對 {@code cause} 為 null 與「取消」混淆）。</p>
     *
     * @param type   任務類型；不可為 null
     * @param code   錯誤代碼；不可為 null
     * @param detail 詳細訊息；不可為 null
     * @param cause  觸發此錯誤的例外；可為 null（會被替換為 sentinel）
     * @return 新的 {@link TaskErrorRecord} 實例
     */
    public static TaskErrorRecord threw(TaskType type, String code, String detail, Throwable cause) {
        Throwable actual = (cause != null) ? cause : new Throwable("null-cause");
        return new TaskErrorRecord(type, code, detail, actual, currentTick());
    }

    /**
     * 嘗試從 {@code Bukkit.getCurrentTick()} 取得目前 tick；若環境不支援
     * （例如純單元測試未初始化 MockBukkit，或舊版 Bukkit），回傳 0。
     *
     * <p>不丟例外，確保任何環境下都能建立紀錄。</p>
     */
    private static long currentTick() {
        try {
            return org.bukkit.Bukkit.getCurrentTick();
        } catch (Throwable t) {
            return 0L;
        }
    }
}