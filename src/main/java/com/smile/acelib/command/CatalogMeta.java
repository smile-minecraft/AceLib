package com.smile.acelib.command;

import com.smile.acelib.form.FormImage;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 隨 {@link CommandSpec} 發布的目錄元資料。
 *
 * <p>描述「這次發布」的展示分類、圖示，以及哪些子指令需要二次確認。
 * 本身不攜帶任何可執行物件。</p>
 *
 * @param category 展示分類（例如 {@code "economy"}）；不可為 null（空字串表示未分類）
 * @param icon 表單圖示；不可為 null（{@link Optional#empty()} 表示沒有）
 * @param confirmSubcommands 需要二次確認的子指令名稱集合；不可為 null
 *        （空集合表示沒有）。發布時只比對該次發布已知的子指令名稱，
 *        出現未知名稱以 {@link IllegalArgumentException} 拒絕
 * @since 1.3.0
 */
public record CatalogMeta(String category, Optional<FormImage> icon,
        Set<String> confirmSubcommands) {

    /**
     * 正規化建構子：拒絕 null 必要欄位，並對集合做深層防禦複製。
     *
     * @throws NullPointerException 當任一欄位為 null
     */
    public CatalogMeta {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(confirmSubcommands, "confirmSubcommands");
        confirmSubcommands = Set.copyOf(confirmSubcommands);
    }
}
