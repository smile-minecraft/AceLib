package com.smile.acelib.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 箱子視圖的字元遮罩（Supported API）。
 *
 * <p>用幾行等長字串描述一個箱子：每個字元是一個「欄位群組符號」，
 * {@link #slots(char)} 把同一符號出現的位置換算成列優先、有序、
 * 不可變的欄位清單。呼叫端拿這份清單同時用於
 * {@link GuiView.Builder#allow(java.util.Collection)} 與
 * {@link GuiView.Builder#button(int, String, java.util.function.Consumer)}
 * 等欄位宣告 — 遮罩本身不做自動版面配置與資料綁定。</p>
 *
 * <h2>合法形狀</h2>
 * <p>建立時檢查三項，不符擲 {@link IllegalArgumentException}
 * （訊息攜帶 {@link GuiErrorCode#INVALID_INPUT} 與實際數值）：</p>
 * <ul>
 *   <li>列數 1～6；</li>
 *   <li>每列等長（列寬由第 0 列決定）；</li>
 *   <li>列數 × 列寬必須為合法箱子尺寸（9／18／27／36／45／54）。</li>
 * </ul>
 *
 * @see GuiView#chest(String, GuiMask)
 * @since 1.5.0
 */
public final class GuiMask {

    /** 合法箱子總格數（9～54 且為 9 的倍數）。 */
    private static final List<Integer> LEGAL_SIZES =
        List.of(9, 18, 27, 36, 45, 54);

    private final List<String> rows;
    private final int width;

    private GuiMask(List<String> rows, int width) {
        this.rows = rows;
        this.width = width;
    }

    /**
     * 以字串列建立遮罩。
     *
     * @param rows 遮罩列；不可為 null、不可為空、列數 1～6、每列不可為 null
     *     且必須等長、列數 × 列寬必須為合法箱子尺寸
     * @return 新的遮罩；永不為 null
     * @throws NullPointerException 當 {@code rows} 為 null
     * @throws IllegalArgumentException 當列數、列寬或總尺寸不合法
     */
    public static GuiMask of(String... rows) {
        Objects.requireNonNull(rows, "rows");
        if (rows.length < 1 || rows.length > 6) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] GuiMask 列數必須為 1～6；實際列數: "
                    + rows.length);
        }
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == null) {
                throw new IllegalArgumentException(
                    "[" + GuiErrorCode.INVALID_INPUT + "] GuiMask 第 " + i
                        + " 列為 null");
            }
        }
        int width = rows[0].length();
        for (int i = 1; i < rows.length; i++) {
            if (rows[i].length() != width) {
                throw new IllegalArgumentException(
                    "[" + GuiErrorCode.INVALID_INPUT + "] GuiMask 每列必須等長；第 0 列寬="
                        + width + "，第 " + i + " 列寬=" + rows[i].length());
            }
        }
        int size = rows.length * width;
        if (!LEGAL_SIZES.contains(size)) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] GuiMask 列數×列寬必須為合法箱子尺寸"
                    + "（9／18／27／36／45／54）；實際: " + rows.length + " 列×"
                    + width + " 寬=" + size);
        }
        return new GuiMask(List.of(rows), width);
    }

    /** @return 列數（1～6） */
    public int rowCount() {
        return rows.size();
    }

    /** @return 列寬（每列字元數） */
    public int width() {
        return width;
    }

    /** @return 總格數（列數 × 列寬；必為合法箱子尺寸） */
    public int size() {
        return rows.size() * width;
    }

    /**
     * 查指定符號的所有欄位（列優先、有序、不可變）。
     *
     * @param symbol 欄位群組符號
     * @return 該符號出現的欄位清單（列優先順序）；未出現回空清單；永不為 null
     */
    public List<Integer> slots(char symbol) {
        List<Integer> found = new ArrayList<>();
        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row);
            for (int col = 0; col < width; col++) {
                if (line.charAt(col) == symbol) {
                    found.add(row * width + col);
                }
            }
        }
        return Collections.unmodifiableList(found);
    }

    @Override
    public String toString() {
        return "GuiMask{rows=" + rows.size() + ", width=" + width
            + ", size=" + size() + "}";
    }
}
