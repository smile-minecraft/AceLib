package com.smile.acelib.display;

import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;

/**
 * 全息字快照（Supported）。
 *
 * <p>不可變值物件：建立時對位置做防禦性複製，不長期持有
 * {@code Player} 或其他 Bukkit 可變物件。實際實體的生命週期由
 * {@link DisplayService} 追蹤，呼叫端只拿本快照做查詢與識別。</p>
 *
 * @since 1.4.0
 */
public final class Hologram {

    private final UUID id;
    private final Location location;
    private final Component text;

    /**
     * 建立全息字快照。
     *
     * @param id 全息字追蹤 id；不可為 null
     * @param location 生成位置；不可為 null（內部複製）
     * @param text 當前文字；不可為 null
     */
    public Hologram(UUID id, Location location, Component text) {
        this.id = Objects.requireNonNull(id, "id");
        this.location = Objects.requireNonNull(location, "location").clone();
        this.text = Objects.requireNonNull(text, "text");
    }

    /** @return 追蹤 id；永不為 null */
    public UUID id() {
        return id;
    }

    /** @return 生成位置的防禦性複製；永不為 null */
    public Location location() {
        return location.clone();
    }

    /** @return 當前文字；永不為 null */
    public Component text() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Hologram other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Hologram{id=" + id + ", location=" + location + '}';
    }
}
