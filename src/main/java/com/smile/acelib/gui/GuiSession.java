package com.smile.acelib.gui;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 對外不可變的 GUI session 物件（Plan §十六 Phase 11 共同契約）。
 *
 * <p>不持有 {@link org.bukkit.entity.Player} reference — 僅以
 * {@link UUID} 標記玩家；GUI 服務可透過 UUID 重新由 {@code Server.getPlayer(UUID)}
 * 取回當下 Player 物件（必要時），但 session 本身不保留。</p>
 *
 * <h2>識別保證</h2>
 * <ul>
 *   <li>以 {@link UUID} 為唯一識別 key</li>
 *   <li>{@link #generation()} 為「不可重用 window/session generation」—
 *       同一 UUID 重新開啟 GUI 時，generation 必須單調遞增</li>
 *   <li>玩家更名（換 name 但 UUID 相同）→ 既有 session 與資料不變</li>
 *   <li>{@link #equals(Object)} 與 {@link #hashCode()} 以 playerUuid + generation 為基準 —
 *       同一 UUID 不同 generation 視為不同 session（防止 close 舊代後走舊 generation）</li>
 * </ul>
 *
 * <h2>不可變</h2>
 * <p>所有欄位為 {@code final}；callers 不可修改 generation 或 protectedSlots；
 * 每次開啟新 session 必須建立新物件。</p>
 *
 * <h2>執行緒安全</h2>
 * <p>不可變物件，安全於多 region 並行環境下使用。</p>
 *
 * @see GuiSessionRegistry
 * @since Phase 11 (Plan §十六)
 */
public final class GuiSession {

    private final UUID playerUuid;
    private final long generation;
    private final String owner;
    private final String title;
    private final int size;
    private final Set<Integer> protectedSlots;

    /**
     * @param playerUuid     玩家 UUID；不可為 null
     * @param generation     與目前 session 對應的 generation；必須大於 0
     * @param owner          plugin owner 標記（用於診斷）；不可為 null
     * @param title          GUI 顯示標題；不可為 null
     * @param size           GUI 總 slot 數（必須 &gt; 0）
     * @param protectedSlots 受保護 slot 集合；不可為 null（空集合表示無保護）
     */
    public GuiSession(UUID playerUuid,
                      long generation,
                      String owner,
                      String title,
                      int size,
                      Set<Integer> protectedSlots) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        if (generation <= 0L) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] generation 必須 > 0；實際: " + generation);
        }
        this.generation = generation;
        this.owner = Objects.requireNonNull(owner, "owner");
        this.title = Objects.requireNonNull(title, "title");
        if (size <= 0) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] size 必須 > 0；實際: " + size);
        }
        this.size = size;
        this.protectedSlots = Set.copyOf(Objects.requireNonNull(protectedSlots,
            "protectedSlots"));
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public long generation() {
        return generation;
    }

    public String owner() {
        return owner;
    }

    public String title() {
        return title;
    }

    public int size() {
        return size;
    }

    public Set<Integer> protectedSlots() {
        return protectedSlots;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GuiSession that)) {
            return false;
        }
        return playerUuid.equals(that.playerUuid) && generation == that.generation;
    }

    @Override
    public int hashCode() {
        return playerUuid.hashCode() * 31 + Long.hashCode(generation);
    }

    @Override
    public String toString() {
        return "GuiSession{playerUuid=" + playerUuid
            + ", generation=" + generation
            + ", owner=" + owner
            + ", title=" + title
            + ", size=" + size
            + ", protectedSlots=" + protectedSlots
            + "}";
    }
}
