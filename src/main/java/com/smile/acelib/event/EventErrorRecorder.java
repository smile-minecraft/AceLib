package com.smile.acelib.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.bukkit.event.Event;

/**
 * 事件錯誤紀錄器。
 *
 * <p>事件錯誤可記錄並定位來源。內部以 {@link Deque}（容量預設 100）保存
 * 最近的錯誤紀錄，當超出容量時自動淘汰最舊的紀錄（FIFO）。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>所有 {@code public} 方法皆為 thread-safe，可於 Folia 多 region 並行環境下
 * 直接使用。底層採用 {@link ConcurrentLinkedDeque} 達成 lock-free 操作。</p>
 *
 * <h2>容量策略</h2>
 * <p>預設容量為 {@value #DEFAULT_CAPACITY} 筆。當 {@link #record(EventErrorRecord)}
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
 * <h2>與 scheduler 模組關係</h2>
 * <p>本類別刻意不依賴 {@code com.smile.acelib.scheduler.TaskErrorRecorder}，
 * 維持 event 模組獨立性。介面與容量策略
 * 對齊 scheduler，方便診斷介面統一呈現。</p>
 *
 * @see EventErrorRecord
 * @see SafeEventRegistry
 * @since 1.0.0
 */
public final class EventErrorRecorder {

    /** 預設保留容量（與 scheduler 模組對齊）。 */
    public static final int DEFAULT_CAPACITY = 100;

    private final int capacity;
    private final Deque<EventErrorRecord> deque = new ConcurrentLinkedDeque<>();

    /**
     * 使用預設容量（{@value #DEFAULT_CAPACITY}）建立實例。
     */
    public EventErrorRecorder() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * 使用指定容量建立實例。
     *
     * @param capacity 保留的最大紀錄數；必須 &gt; 0
     * @throws IllegalArgumentException 當 {@code capacity <= 0}
     */
    public EventErrorRecorder(int capacity) {
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
    public void record(EventErrorRecord record) {
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
    public List<EventErrorRecord> getRecentErrors(int max) {
        if (max <= 0) {
            return Collections.emptyList();
        }
        List<EventErrorRecord> snapshot = new ArrayList<>(deque);
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
     * <p>建議在 {@code SafeEventRegistry.onPluginDisable()} 或 reload 流程結束時呼叫，
     * 避免舊紀錄污染新一輪 plugin 生命週期。</p>
     */
    public void clear() {
        deque.clear();
    }

    /**
     * 判斷是否曾記錄過指定錯誤代碼。
     *
     * <p>主要用於測試（例如「ACELIB-EVT-001 是否曾被記錄過」）。
     * 傳入 {@code null} 一律回傳 false。</p>
     *
     * @param code 錯誤代碼（如 {@code "ACELIB-EVT-001"}）；可為 null
     * @return 若任何一筆紀錄的 code 欄位與傳入值相等（equals）則為 true
     */
    public boolean contains(String code) {
        if (code == null) {
            return false;
        }
        for (EventErrorRecord r : deque) {
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
     * 過濾取得指定事件類型的最近 N 筆紀錄。
     *
     * <p>便利方法：等同於先 {@link #getRecentErrors(int)} 取大池再以
     * {@code equals} 過濾，但避免大量無關紀錄造成的記憶體浪費。
     * 用於診斷時快速聚焦於特定 eventType 的錯誤。</p>
     *
     * @param eventType 目標 Bukkit Event 型別；不可為 null
     * @param max       最多回傳幾筆
     * @return 不可變的「時間由舊到新」紀錄清單
     */
    public List<EventErrorRecord> getRecentErrorsFor(Class<? extends Event> eventType, int max) {
        Objects.requireNonNull(eventType, "eventType");
        if (max <= 0) {
            return Collections.emptyList();
        }
        List<EventErrorRecord> matched = new ArrayList<>();
        // 從最舊掃到最新，保留最後 N 筆匹配
        for (EventErrorRecord r : deque) {
            if (eventType.equals(r.eventType())) {
                matched.add(r);
                if (matched.size() > max) {
                    matched.remove(0);
                }
            }
        }
        return Collections.unmodifiableList(matched);
    }
}