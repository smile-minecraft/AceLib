package com.smile.acelib.gui;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GUI session registry（Plan §十六 Phase 11 共同契約）。
 *
 * <p>以 {@link UUID} 為唯一識別 key 維護 active session 清單；提供
 * start / get / end / clear 等 lifecycle 方法。Session 一旦 end 即從
 * registry 移除，不會保留失效 session。</p>
 *
 * <h2>Generation 單調遞增</h2>
 * <ul>
 *   <li>每個新 session 取得一個「registry 內部 generation」 — 此值一旦給出即
 *       不可重用；即使 session 被 end，下次 start 會拿到更大值</li>
 *   <li>generation 對單一 registry 而言是 monotonic；不同 UUID 的 session 也
 *       會拿到單調遞增的值（避免 caller 透過 generation 反推時混淆）</li>
 *   <li>generation 不可作為 Api 的唯一識別 — {@link GuiSession} 仍以
 *       {@link UUID} + generation 複合識別</li>
 * </ul>
 *
 * <h2>不變量</h2>
 * <ul>
 *   <li>同一 UUID 不可同時存在多個 session（{@link #startSession} 會拒絕重複）</li>
 *   <li>Session 一旦 {@link #endSession} 即從 registry 移除</li>
 *   <li>所有方法 thread-safe（使用 {@link ConcurrentHashMap} 與 {@link AtomicLong}）</li>
 * </ul>
 *
 * @see GuiSession
 * @since Phase 11 (Plan §十六)
 */
public final class GuiSessionRegistry {

    private final ConcurrentMap<UUID, GuiSession> sessions = new ConcurrentHashMap<>();
    private final AtomicLong generationCounter = new AtomicLong(0L);

    /**
     * 為指定 UUID 建立新 session，並分配一個 monotonic generation。
     *
     * <p>若該 UUID 已有 active session 則拋 {@link IllegalStateException}
     * 帶 {@link GuiErrorCode#SESSION_EXISTS}，由 {@link GuiServiceImpl}
     * 翻譯為對應結果。</p>
     *
     * @param uuid           玩家 UUID；不可為 null
     * @param owner          plugin owner 標記；不可為 null
     * @param size           GUI 總 slot 數（必須 &gt; 0）
     * @param protectedSlots 受保護 slot 集合；可為 null（normalize 為空集合）
     * @return 不可變的新 session（title 為空字串）
     * @throws NullPointerException 當 {@code uuid} 或 {@code owner} 為 null
     * @throws IllegalArgumentException 當 {@code size} <= 0
     * @throws IllegalStateException 當 {@code uuid} 已有 active session
     */
    public GuiSession startSession(UUID uuid, String owner, int size,
                                   Set<Integer> protectedSlots) {
        return startSession(uuid, owner, size, protectedSlots, "");
    }

    /**
     * 為指定 UUID 建立新 session，並分配一個 monotonic generation（攜帶 title）。
     *
     * @param uuid           玩家 UUID；不可為 null
     * @param owner          plugin owner 標記；不可為 null
     * @param size           GUI 總 slot 數（必須 &gt; 0）
     * @param protectedSlots 受保護 slot 集合；可為 null（normalize 為空集合）
     * @param title          GUI 顯示標題；可為 null（normalize 為空字串）
     * @return 不可變的新 session
     * @throws NullPointerException 當 {@code uuid} 或 {@code owner} 為 null
     * @throws IllegalArgumentException 當 {@code size} <= 0
     * @throws IllegalStateException 當 {@code uuid} 已有 active session
     * @since Phase 11（Plan §十六 §二十一）
     */
    public GuiSession startSession(UUID uuid, String owner, int size,
                                   Set<Integer> protectedSlots, String title) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(owner, "owner");
        if (size <= 0) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] size 必須 > 0；實際: " + size);
        }
        Set<Integer> normalized = protectedSlots == null || protectedSlots.isEmpty()
            ? Set.of()
            : Set.copyOf(new HashSet<>(protectedSlots));
        long generation = generationCounter.incrementAndGet();
        GuiSession session = new GuiSession(uuid, generation, owner,
            title == null ? "" : title, size, normalized);
        GuiSession existing = sessions.putIfAbsent(uuid, session);
        if (existing != null) {
            throw new IllegalStateException(
                "[" + GuiErrorCode.SESSION_EXISTS + "] session 已存在於 uuid="
                    + uuid + " (existing generation=" + existing.generation() + ")");
        }
        return session;
    }

    /**
     * 取得指定 UUID 的 session（若存在）。
     *
     * @param uuid 玩家 UUID；不可為 null
     * @return 對應 session；若不存在回傳 null
     * @throws NullPointerException 當 {@code uuid} 為 null
     */
    public GuiSession getSession(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return sessions.get(uuid);
    }

    /**
     * 結束並移除指定 UUID 的 session。
     *
     * @param uuid 玩家 UUID；不可為 null
     * @return 被移除的 session；若無對應 session 回傳 null
     * @throws NullPointerException 當 {@code uuid} 為 null
     */
    public GuiSession endSession(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return sessions.remove(uuid);
    }

    /**
     * 取得當前 active session 數。
     */
    public int size() {
        return sessions.size();
    }

    /**
     * 是否為空（{@link #size()} == 0）。
     */
    public boolean isEmpty() {
        return sessions.isEmpty();
    }

    /**
     * 清除所有 session（reload / disable 使用）。
     *
     * <p>冪等：重複呼叫不丟例外。Generation counter 不重置 — 確保 reload 之後
     * 取得的 generation 仍大於 reload 之前的最大值，避免外部 caller 持有舊
     * reference 嘗試操作。</p>
     */
    public void clear() {
        sessions.clear();
    }
}
