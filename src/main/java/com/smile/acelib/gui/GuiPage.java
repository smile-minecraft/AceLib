package com.smile.acelib.gui;

import java.util.List;
import java.util.Objects;

/**
 * 不可變分頁 model（Supported API）。
 *
 * <p>本 model 與 {@link GuiSession} 完全獨立：它不持有 {@code Player} reference、
 * 不依賴 session generation，也不會污染 registry 的 generation 計數。後續插件可單獨
 * 使用本 model 計算「某一頁要顯示哪些 items」，再自行決定如何渲染。</p>
 *
 * <h2>呈現種類（{@link Kind}）</h2>
 * <ul>
 *   <li>{@link Kind#CONTENT} — 有內容的某一頁（攜帶 {@code pageIndex} / {@code totalPages} / {@code items}）</li>
 *   <li>{@link Kind#EMPTY} — 資料源為空，顯示空資料替代畫面</li>
 *   <li>{@link Kind#LOADING} — 資料尚未載入完成，顯示等待替代畫面</li>
 *   <li>{@link Kind#ERROR} — 資料載入失敗，攜帶 {@code ACELIB-GUI-*} 錯誤代碼</li>
 * </ul>
 *
 * <h2>分頁邊界</h2>
 * <p>透過 {@link #page(List, int, int)} 計算單頁時，要求的頁碼會被 clamp 到
 * {@code [0, totalPages-1]}；負數與超過上限都不會讓結果越界，也不會丟例外。</p>
 *
 * <h2>不可變</h2>
 * <p>所有欄位為 {@code final}；{@link #items()} 回傳不可變 {@link List}（caller 修改會
 * 收到 {@link UnsupportedOperationException}）。本物件執行緒安全。</p>
 *
 * @param <T> 頁面項目型別
 * @see GuiErrorCode
 * @since 1.0.0
 */
public final class GuiPage<T> {

    /**
     * 分頁呈現種類。
     *
     * <p>順序凍結，不得更動（與 {@link GuiState} 對齊的序列化相容原則）。</p>
     */
    public enum Kind {
        CONTENT,
        EMPTY,
        LOADING,
        ERROR
    }

    private final Kind kind;
    private final int pageIndex;
    private final int totalPages;
    private final List<T> items;
    private final String errorCode;
    private final String detail;

    private GuiPage(Kind kind, int pageIndex, int totalPages, List<T> items,
                    String errorCode, String detail) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.pageIndex = pageIndex;
        this.totalPages = totalPages;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.errorCode = errorCode;
        this.detail = detail == null ? "" : detail;
    }

    /**
     * 建立內容頁。
     *
     * @param pageIndex  目前頁碼（0-based）；必須在 {@code [0, totalPages)} 內
     * @param totalPages 總頁數；必須 &gt; 0
     * @param items      本頁項目；可為 null（視為空集合）
     * @param <T>        項目型別
     * @return 新的內容頁
     * @throws IllegalArgumentException 當 {@code totalPages <= 0} 或 {@code pageIndex} 越界
     */
    public static <T> GuiPage<T> content(int pageIndex, int totalPages, List<T> items) {
        if (totalPages <= 0) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] totalPages 必須 > 0；實際: " + totalPages);
        }
        if (pageIndex < 0 || pageIndex >= totalPages) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] pageIndex 越界: " + pageIndex
                    + " (totalPages=" + totalPages + ")");
        }
        return new GuiPage<>(Kind.CONTENT, pageIndex, totalPages, items, null, "");
    }

    /**
     * 建立空資料 fallback 頁（資料源為空）。
     *
     * @param <T> 項目型別
     * @return 空資料頁
     */
    public static <T> GuiPage<T> empty() {
        return new GuiPage<>(Kind.EMPTY, 0, 0, List.of(), null, "");
    }

    /**
     * 建立載入中 fallback 頁（資料尚未載入完成）。
     *
     * @param <T> 項目型別
     * @return 載入中頁
     */
    public static <T> GuiPage<T> loading() {
        return new GuiPage<>(Kind.LOADING, 0, 0, List.of(), null, "");
    }

    /**
     * 建立錯誤 fallback 頁（資料載入失敗）。
     *
     * @param errorCode 錯誤代碼；必須為 {@code ACELIB-GUI-*} 格式
     * @param detail    人類可讀說明；可為 null（視為空字串）
     * @param <T>      項目型別
     * @return 錯誤頁
     * @throws NullPointerException     當 {@code errorCode} 為 null
     * @throws IllegalArgumentException 當 {@code errorCode} 非 {@code ACELIB-GUI-*} 格式
     */
    public static <T> GuiPage<T> error(String errorCode, String detail) {
        Objects.requireNonNull(errorCode, "errorCode");
        if (!errorCode.startsWith("ACELIB-GUI-")) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 錯誤代碼必須為 ACELIB-GUI-*；實際: "
                    + errorCode);
        }
        return new GuiPage<>(Kind.ERROR, 0, 0, List.of(), errorCode, detail);
    }

    /**
     * 從完整資料源計算單頁（分頁邊界 clamp）。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>{@code allItems} 為 null → 丟 {@link NullPointerException}</li>
     *   <li>{@code pageSize <= 0} → 丟 {@link IllegalArgumentException} 帶 {@link GuiErrorCode#INVALID_INPUT}</li>
     *   <li>{@code allItems} 為空 → 回 {@link #empty()}（空資料 fallback）</li>
     *   <li>要求的頁碼負數 → clamp 到 0（第一頁）</li>
     *   <li>要求的頁碼超過上限 → clamp 到 {@code totalPages - 1}（最後一頁）</li>
     * </ul>
     *
     * <p>本方法不依賴任何 {@link GuiSession} 或 {@link GuiSessionRegistry}，
     * 因此不會影響 session generation。</p>
     *
     * @param allItems     完整資料源；不可為 null
     * @param pageSize     每頁項目數；必須 &gt; 0
     * @param requestedPage 要求的頁碼（0-based，可越界，會被 clamp）
     * @param <T>          項目型別
     * @return 對應 {@link GuiPage}（CONTENT 或 EMPTY）
     */
    public static <T> GuiPage<T> page(List<T> allItems, int pageSize, int requestedPage) {
        Objects.requireNonNull(allItems, "allItems");
        if (pageSize <= 0) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] pageSize 必須 > 0；實際: " + pageSize);
        }
        if (allItems.isEmpty()) {
            return empty();
        }
        int totalPages = (allItems.size() + pageSize - 1) / pageSize;
        int clamped = clampPage(requestedPage, totalPages);
        int from = clamped * pageSize;
        int to = Math.min(from + pageSize, allItems.size());
        List<T> pageItems = List.copyOf(allItems.subList(from, to));
        return new GuiPage<>(Kind.CONTENT, clamped, totalPages, pageItems, null, "");
    }

    private static int clampPage(int requested, int totalPages) {
        if (requested < 0) {
            return 0;
        }
        if (requested >= totalPages) {
            return totalPages - 1;
        }
        return requested;
    }

    public Kind kind() {
        return kind;
    }

    public int pageIndex() {
        return pageIndex;
    }

    public int totalPages() {
        return totalPages;
    }

    /**
     * 本頁項目（不可變 {@link List}）。僅 {@link Kind#CONTENT} 有意義；
     * 其他種類回傳空集合。
     */
    public List<T> items() {
        return items;
    }

    /**
     * 錯誤代碼（僅 {@link Kind#ERROR} 有意義）。
     */
    public String errorCode() {
        return errorCode;
    }

    public String detail() {
        return detail;
    }

    public boolean isContent() {
        return kind == Kind.CONTENT;
    }

    public boolean isEmpty() {
        return kind == Kind.EMPTY;
    }

    public boolean isLoading() {
        return kind == Kind.LOADING;
    }

    public boolean isError() {
        return kind == Kind.ERROR;
    }

    @Override
    public String toString() {
        return "GuiPage{kind=" + kind
            + ", pageIndex=" + pageIndex
            + ", totalPages=" + totalPages
            + ", items=" + items
            + ", errorCode=" + errorCode
            + ", detail=" + detail
            + "}";
    }
}
