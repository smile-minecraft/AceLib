package com.smile.acelib.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 排程錯誤紀錄器。
 *
 * <p>對應 Plan §七 Phase 2「任務錯誤留下可追蹤紀錄」的需求。
 * 內部以 {@link Deque}（容量預設 100）保存最近的錯誤紀錄，
 * 當超出容量時自動淘汰最舊的紀錄（FIFO）。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>所有 {@code public} 方法皆為 thread-safe，可於 Folia 多 region 並行環境下
 * 直接使用。底層採用 {@link ConcurrentLinkedDeque} 達成 lock-free 操作。</p>
 *
 * <h2>容量策略</h2>
 * <p>預設容量為 {@value #DEFAULT_CAPACITY} 筆。當 {@link #record(TaskErrorRecord)}
 * 寫入第 101 筆時，最舊的 1 筆會被淘汰。容量可在建構時指定，
 * 但不可 ≤ 0。</p>
 *
 * <h2>查詢語意</h2>
 * <ul>
 *   <li>{@link #getRecentErrors(int)} — 取得最近 N 筆（依時間順序，最舊在前）</li>
 *   <li>{@link #getErrorCount()} — 目前保留的紀錄總數</li>
 *   <li>{@link #contains(String)} — 是否曾記錄過指定錯誤代碼（用於測試）</li>
 *   <li>{@link #clear()} — 清空所有紀錄（reload / disable 時使用）</li>
 * </ul>
 *
 * @see TaskErrorRecord
 * @since Phase 2 (Plan §七)
 */
public final class TaskErrorRecorder {

    /** 預設保留容量（Plan §七 Phase 2 規格：100 筆）。 */
    public static final int DEFAULT_CAPACITY = 100;

    private final int capacity;
    private final Deque<TaskErrorRecord> deque = new ConcurrentLinkedDeque<>();

    /**
     * 使用預設容量（{@value #DEFAULT_CAPACITY}）建立實例。
     */
    public TaskErrorRecorder() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 使用指定容量建立實例。
     *
     * @param capacity 保留的最大紀錄數；必須 &gt; 0
     * @throws IllegalArgumentException 當 {@code capacity <= 0}
     */
    public TaskErrorRecorder(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0, got: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * 寫入一筆錯誤紀錄。
     *
     * <p>若寫入後總筆數超過容量，最舊的一筆會被淘汰。
     * 若傳入 {@code null} 則為 no-op（避免上游 NPE）。</p>
     *
     * @param record 要寫入的紀錄；可為 null（no-op）
     */
    public void record(TaskErrorRecord record) {
        if (record == null) {
            return;
        }
        deque.addLast(record);
        // 淘汰超過容量的最舊紀錄（FIFO）
        while (deque.size() > capacity) {
            deque.pollFirst();
        }
    }

    /**
     * 取得最近的 N 筆紀錄。
     *
     * <p>回傳的清單為「時間由舊到新」的排序（與 {@link #record} 寫入順序一致）。
     * 傳入 {@code max <= 0} 回傳空清單；{@code max} 大於目前總數則回傳全部。</p>
     *
     * @param max 最多回傳幾筆；&lt;=0 回傳空清單
     * @return 不可變的「最近 N 筆」清單（永遠不為 null）
     */
    public List<TaskErrorRecord> getRecentErrors(int max) {
        if (max <= 0) {
            return Collections.emptyList();
        }
        List<TaskErrorRecord> snapshot = new ArrayList<>(deque);
        int size = snapshot.size();
        if (max >= size) {
            return Collections.unmodifiableList(snapshot);
        }
        return Collections.unmodifiableList(
            new ArrayList<>(snapshot.subList(size - max, size))
        );
    }

    /**
     * 取得目前保留的紀錄總數。
     *
     * @return 紀錄筆數（0 ~ 容量）
     */
    public int getErrorCount() {
        return deque.size();
    }

    /**
     * 清空所有紀錄。
     *
     * <p>建議在 {@code AceLibPlugin.onDisable()} 後或 reload 流程結束時呼叫，
     * 避免舊紀錄污染新一輪 plugin 生命週期。</p>
     */
    public void clear() {
        deque.clear();
    }

    /**
     * 判斷是否曾記錄過指定錯誤代碼。
     *
     * <p>主要用於測試（例如「ACELIB-SCHED-002 是否曾被記錄過」）。
     * 傳入 {@code null} 一律回傳 false。</p>
     *
     * @param code 錯誤代碼（如 {@code "ACELIB-SCHED-002"}）；可為 null
     * @return 若任何一筆紀錄的 code 欄位與傳入值相等（equals）則為 true
     */
    public boolean contains(String code) {
        if (code == null) {
            return false;
        }
        for (TaskErrorRecord r : deque) {
            if (code.equals(r.code())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取得當前容量。
     *
     * @return 容量上限（&gt;0）
     */
    public int getCapacity() {
        return capacity;
    }

    /**
     * 兩個 recorder「內容相同」的語意：容量相同且目前所有紀錄 equals。
     *
     * <p>僅供測試與診斷使用；不應作為高頻操作依據。</p>
     *
     * @param other 對照組
     * @return 內容相同時為 true
     */
    public boolean contentEquals(TaskErrorRecorder other) {
        if (other == null) {
            return false;
        }
        if (this.capacity != other.capacity) {
            return false;
        }
        if (this.deque.size() != other.deque.size()) {
            return false;
        }
        return Objects.equals(new ArrayList<>(this.deque), new ArrayList<>(other.deque));
    }
}