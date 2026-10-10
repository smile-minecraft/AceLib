package com.smile.acelib.gui;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 分頁清單元件（Supported API）。
 *
 * <p>以 {@link GuiMask} 版面與 {@link GuiPage} 資料組出分頁畫面：
 * 項目區放本頁項目、上一頁／下一頁按鈕只在有目標頁時出現、
 * 標題帶目前頁碼；空資料、載入中、錯誤三種狀態各有無導覽按鈕的替代畫面。</p>
 *
 * <h2>無自動資料綁定</h2>
 * <p>翻頁由呼叫端驅動：上一頁／下一頁按鈕的回呼（{@link Actions}）內，
 * 呼叫端自行取新頁（例如 {@code GuiPage.page(allItems, pager.itemCapacity(), index)}），
 * 再以 {@link #viewFor(GuiPage, Actions)} 重組視圖並開啟
 *（{@code openView}／{@code pushView}／{@code replaceView}）。
 * 本元件不記住頁碼、不監聽資料、呼叫之間不保留狀態。</p>
 *
 * <h2>無快取、無自建 generation</h2>
 * <p>每次 {@code viewFor} 都建全新視圖；點擊語意沿用既有 session／generation 機制：
 * 舊畫面的按鈕在新畫面開啟後自然失效（回 {@code GENERATION_MISMATCH}），
 * 不需要、也不做額外的過期判斷。</p>
 *
 * @param <T> 頁面項目型別
 * @see GuiPage
 * @see GuiMask
 * @since 1.5.0
 */
public final class GuiPager<T> {

    /** 上一頁按鈕識別字（保留：項目渲染器不得使用，否則建視圖時被拒）。 */
    public static final String PREV_BUTTON_ID = "pager-prev";

    /** 下一頁按鈕識別字（保留：項目渲染器不得使用，否則建視圖時被拒）。 */
    public static final String NEXT_BUTTON_ID = "pager-next";

    /**
     * 項目渲染器：在指定欄位宣告該項目的按鈕。
     *
     * <p>呼叫端在其中呼叫 {@code builder.button(slot, ...)}（可帶標籤，
     * 供後續基岩表單使用）；同一個欄位或識別字重複宣告會在建視圖時被拒。
     * 渲染器抛例外直接透出，不包成錯誤結果（屬於程式設計錯誤）。</p>
     *
     * @param <T> 頁面項目型別
     */
    public interface ItemRenderer<T> {

        /**
         * 在指定欄位宣告該項目的按鈕。
         *
         * @param builder 正在組裝的視圖 builder；不可為 null
         * @param slot 項目欄位編號（來自項目符號換算的欄位）
         * @param item 本欄位的項目；可為 null（由呼叫端約定）
         */
        void render(GuiView.Builder builder, int slot, T item);
    }

    /**
     * 翻頁動作（呼叫端提供，翻頁時自行取新頁再開新畫面）。
     *
     * @param onNext 下一頁按鈕的點擊回呼；不可為 null
     * @param onPrev 上一頁按鈕的點擊回呼；不可為 null
     */
    public record Actions(Consumer<GuiButtonClick> onNext,
                          Consumer<GuiButtonClick> onPrev) {

        /**
         * 正規化建構子。
         *
         * @throws NullPointerException 當任一回呼為 null
         */
        public Actions {
            Objects.requireNonNull(onNext, "onNext");
            Objects.requireNonNull(onPrev, "onPrev");
        }
    }

    private final GuiMask mask;
    private final char itemSymbol;
    private final char prevSymbol;
    private final char nextSymbol;
    private final String titlePrefix;
    private final ItemRenderer<T> renderer;
    private final List<Integer> itemSlots;
    private final List<Integer> prevSlots;
    private final List<Integer> nextSlots;

    private GuiPager(GuiMask mask, char itemSymbol, char prevSymbol, char nextSymbol,
            String titlePrefix, ItemRenderer<T> renderer,
            List<Integer> itemSlots, List<Integer> prevSlots, List<Integer> nextSlots) {
        this.mask = mask;
        this.itemSymbol = itemSymbol;
        this.prevSymbol = prevSymbol;
        this.nextSymbol = nextSymbol;
        this.titlePrefix = titlePrefix;
        this.renderer = renderer;
        this.itemSlots = itemSlots;
        this.prevSlots = prevSlots;
        this.nextSlots = nextSlots;
    }

    /**
     * 建立分頁元件描述。
     *
     * @param mask 版面遮罩；不可為 null
     * @param itemSymbol 項目區符號；必須在遮罩出現至少一個欄位
     * @param prevSymbol 上一頁符號；必須與其他兩符號相異且至少出現一個欄位
     * @param nextSymbol 下一頁符號；必須與其他兩符號相異且至少出現一個欄位
     * @param titlePrefix 視圖標題前綴；不可為 null／空白
     * @param renderer 項目渲染器；不可為 null
     * @param <T> 頁面項目型別
     * @return 新的元件描述；永不為 null
     * @throws NullPointerException 當 {@code mask}／{@code titlePrefix}／
     *     {@code renderer} 為 null
     * @throws IllegalArgumentException 當標題前綴空白、保留符號重複，
     *     或任一符號在遮罩無對應欄位
     */
    public static <T> GuiPager<T> of(GuiMask mask, char itemSymbol, char prevSymbol,
            char nextSymbol, String titlePrefix, ItemRenderer<T> renderer) {
        Objects.requireNonNull(mask, "mask");
        Objects.requireNonNull(titlePrefix, "titlePrefix");
        Objects.requireNonNull(renderer, "renderer");
        if (titlePrefix.isBlank()) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 分頁標題前綴不可為空白");
        }
        if (itemSymbol == prevSymbol || itemSymbol == nextSymbol
                || prevSymbol == nextSymbol) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 分頁保留符號必須相異；實際: 項目='"
                    + itemSymbol + "'，上一頁='" + prevSymbol + "'，下一頁='" + nextSymbol
                    + "'");
        }
        List<Integer> items = mask.slots(itemSymbol);
        List<Integer> prev = mask.slots(prevSymbol);
        List<Integer> next = mask.slots(nextSymbol);
        if (items.isEmpty()) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 項目符號 '" + itemSymbol
                    + "' 在遮罩無對應欄位");
        }
        if (prev.isEmpty()) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 上一頁符號 '" + prevSymbol
                    + "' 在遮罩無對應欄位");
        }
        if (next.isEmpty()) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 下一頁符號 '" + nextSymbol
                    + "' 在遮罩無對應欄位");
        }
        return new GuiPager<>(mask, itemSymbol, prevSymbol, nextSymbol,
            titlePrefix, renderer, items, prev, next);
    }

    /** @return 版面遮罩；永不為 null */
    public GuiMask mask() {
        return mask;
    }

    /** @return 項目區符號 */
    public char itemSymbol() {
        return itemSymbol;
    }

    /** @return 上一頁符號 */
    public char prevSymbol() {
        return prevSymbol;
    }

    /** @return 下一頁符號 */
    public char nextSymbol() {
        return nextSymbol;
    }

    /** @return 視圖標題前綴；永不為 null */
    public String titlePrefix() {
        return titlePrefix;
    }

    /**
     * @return 單頁最多顯示幾個項目（＝項目符號換算的欄位數；呼叫端取頁時
     *     建議以此值為 {@code pageSize}，避免項目被截斷）
     */
    public int itemCapacity() {
        return itemSlots.size();
    }

    /**
     * 依頁面狀態組出視圖（每次呼叫都建全新視圖，不保留狀態）。
     *
     * <p>標題格式：內容頁為「{@code 前綴 — 頁 i/n}」（i 自 1 起算）；
     * 空資料為「{@code 前綴 — 空資料}」；載入中為「{@code 前綴 — 載入中}」；
     * 錯誤為「{@code 前綴 — 錯誤（ACELIB-GUI-*）}」（錯誤說明 {@code detail}
     * 不渲染進標題，呼叫端需要時自行記錄）。</p>
     *
     * <p>內容頁的項目數必須 ≤ 項目欄位數，否則建視圖時直接被拒
     * （fail-fast，不靜默截斷：超出欄位的項目玩家看不到也無導覽可達，
     * 靜默丟棄等同資料遺漏）：呼叫端以 {@link #itemCapacity()} 為
     * {@code pageSize} 取頁即可完全避免。上一頁按鈕只在 {@code pageIndex > 0}
     * 時出現，下一頁按鈕只在還有後續頁時出現，分別放在該符號的第一個欄位；
     * 三種替代畫面皆無導覽按鈕。</p>
     *
     * @param page 頁面資料；不可為 null
     * @param actions 翻頁動作；不可為 null（替代畫面用不到，但仍須提供）
     * @return 新的視圖；永不為 null
     * @throws NullPointerException 當 {@code page} 或 {@code actions} 為 null
     * @throws IllegalArgumentException 當本頁項目數超過項目欄位數，
     *     或項目渲染器宣告了保留按鈕識別字
     */
    public GuiView viewFor(GuiPage<T> page, Actions actions) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(actions, "actions");
        return switch (page.kind()) {
            case CONTENT -> contentView(page, actions);
            case EMPTY -> GuiView.chest(titlePrefix + " — 空資料", mask).build();
            case LOADING -> GuiView.chest(titlePrefix + " — 載入中", mask).build();
            case ERROR -> GuiView.chest(
                titlePrefix + " — 錯誤（" + page.errorCode() + "）", mask).build();
        };
    }

    private GuiView contentView(GuiPage<T> page, Actions actions) {
        String title = titlePrefix + " — 頁 " + (page.pageIndex() + 1)
            + "/" + page.totalPages();
        GuiView.Builder builder = GuiView.chest(title, mask);
        List<T> items = page.items();
        if (items.size() > itemSlots.size()) {
            throw new IllegalArgumentException(
                "[" + GuiErrorCode.INVALID_INPUT + "] 本頁項目數超過項目欄位數: items="
                    + items.size() + "（項目欄位數=" + itemSlots.size()
                    + "）；請以 itemCapacity() 為 pageSize 取頁");
        }
        for (int i = 0; i < items.size(); i++) {
            renderer.render(builder, itemSlots.get(i), items.get(i));
        }
        if (page.pageIndex() > 0) {
            builder.button(prevSlots.get(0), PREV_BUTTON_ID, actions.onPrev());
        }
        if (page.pageIndex() + 1 < page.totalPages()) {
            builder.button(nextSlots.get(0), NEXT_BUTTON_ID, actions.onNext());
        }
        return builder.build();
    }

    @Override
    public String toString() {
        return "GuiPager{titlePrefix=" + titlePrefix
            + ", itemSymbol=" + itemSymbol
            + ", prevSymbol=" + prevSymbol
            + ", nextSymbol=" + nextSymbol
            + ", itemCapacity=" + itemSlots.size() + "}";
    }
}
