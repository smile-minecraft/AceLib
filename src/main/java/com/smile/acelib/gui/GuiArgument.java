package com.smile.acelib.gui;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * 對外傳入的 GUI 開啟請求（Plan §十六 Phase 11 共同契約）。
 *
 * <p>不可變 value object；只在 GUI 開啟時攜帶必要 metadata（玩家 UUID、標題、
 * slot 數、受保護 slot 集合）。實際 GUI 渲染、物品配置、callback 等不在
 * 第一個可驗收切片範圍。</p>
 *
 * <h2>使用情境</h2>
 * <ul>
 *   <li>典型用法：{@code GuiArgument.of(player, "Shop", 27, slots)}</li>
 *   <li>需要更客製化：用 {@link #builder(Player, String)} 設定其他欄位</li>
 * </ul>
 *
 * <p>不直接持有 {@link Player} reference — 內部只保存 {@link UUID}，
 * 避免跨執行緒保留 Bukkit entity reference（Plan §十六 §二十一共同契約）。</p>
 *
 * @see GuiService
 * @since Phase 11 (Plan §十六)
 */
public final class GuiArgument {

    private final UUID playerUuid;
    private final String title;
    private final int size;
    private final Set<Integer> protectedSlots;

    private GuiArgument(UUID playerUuid, String title, int size,
                        Set<Integer> protectedSlots) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.title = Objects.requireNonNull(title, "title");
        if (size <= 0) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] size 必須 > 0；實際: " + size);
        }
        this.size = size;
        this.protectedSlots = protectedSlots == null || protectedSlots.isEmpty()
            ? Set.of()
            : Set.copyOf(protectedSlots);
    }

    /**
     * 從 {@link Player} 與最小必要欄位建立。
     *
     * @param player         目標玩家；不可為 null
     * @param title          GUI 標題；不可為 null
     * @param size           GUI 總 slot 數（必須 &gt; 0）
     * @param protectedSlots 受保護 slot 集合；可為 null 或空（視為無保護）
     * @return 新的 {@link GuiArgument}
     * @throws NullPointerException 當 {@code player} 或 {@code title} 為 null
     * @throws IllegalArgumentException 當 {@code size} <= 0
     */
    public static GuiArgument of(Player player, String title, int size,
                                 Collection<Integer> protectedSlots) {
        Objects.requireNonNull(player, "player");
        return new GuiArgument(player.getUniqueId(), title, size,
            protectedSlots == null ? Set.of() : new HashSet<>(protectedSlots));
    }

    /**
     * 從 UUID 直接建立（測試用或非 Player 來源）。
     *
     * @param playerUuid     玩家 UUID；不可為 null
     * @param title          GUI 標題；不可為 null
     * @param size           GUI 總 slot 數（必須 &gt; 0）
     * @param protectedSlots 受保護 slot 集合；可為 null 或空
     * @return 新的 {@link GuiArgument}
     */
    public static GuiArgument of(UUID playerUuid, String title, int size,
                                 Collection<Integer> protectedSlots) {
        return new GuiArgument(playerUuid, title, size,
            protectedSlots == null ? Set.of() : new HashSet<>(protectedSlots));
    }

    /**
     * 建立 {@link Builder}。對 {@code protectedSlots} 等欄位有更細控制時使用。
     *
     * @param player 目標玩家；不可為 null
     * @param title  GUI 標題；不可為 null
     * @return 新的 builder
     */
    public static Builder builder(Player player, String title) {
        return new Builder(player.getUniqueId(), title);
    }

    public UUID playerUuid() {
        return playerUuid;
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

    /**
     * {@link GuiArgument} 的 fluent builder。size 預設 9（單排 chest）。
     */
    public static final class Builder {
        private final UUID playerUuid;
        private final String title;
        private int size = 9;
        private Set<Integer> protectedSlots = Set.of();

        private Builder(UUID playerUuid, String title) {
            this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
            this.title = Objects.requireNonNull(title, "title");
        }

        public Builder size(int size) {
            this.size = size;
            return this;
        }

        public Builder protectedSlots(Collection<Integer> slots) {
            this.protectedSlots = slots == null ? Set.of() : new HashSet<>(slots);
            return this;
        }

        public GuiArgument build() {
            return new GuiArgument(playerUuid, title, size, protectedSlots);
        }
    }
}
