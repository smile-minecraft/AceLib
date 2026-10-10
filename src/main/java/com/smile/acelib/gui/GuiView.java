package com.smile.acelib.gui;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.inventory.ItemStack;

/**
 * GUI 視圖描述（Supported API）。
 *
 * <p>描述「一個畫面長什麼樣、哪些欄位可動、按鈕按了找誰」：下游以 builder
 * 組出視圖，交給 {@link GuiScope} 開啟／導航；實際物品渲染仍由下游的 renderer
 * 負責（本型別不做版面配置與資料綁定）。</p>
 *
 * <h2>預設全擋、只開放指定欄位</h2>
 * <p>點擊規則（由 {@link GuiScope#handleClick} 執行）：</p>
 * <ul>
 *   <li>落在按鈕欄位 → 執行該按鈕回呼（回呼路徑與欄位保護相互獨立，
 *       不得拿 {@code SLOT_PROTECTED} 當按鈕訊號）</li>
 *   <li>落在 {@link #allowedSlots()} → 放行（{@code ALLOWED}）</li>
 *   <li>其餘 → 拒絕（{@code SLOT_PROTECTED}）</li>
 * </ul>
 *
 * <h2>鐵砧視圖</h2>
 * <p>{@link Kind#ANVIL} 視圖開啟鐵砧 inventory（固定 3 欄：0、1 輸入，2 結果）。
 * 輸入文字經聊天／鐵砧輸入流程（見 {@link GuiScope#promptAnvil}）送出；
 * 結果欄位點擊會攜帶當下更名文字（見 {@link GuiButtonClick#anvilText()}）。</p>
 *
 * <h2>按鈕物品的 classpath 依賴</h2>
 * <p>帶物品的按鈕多載使用 Bukkit {@code ItemStack}：純單元測試引用本型別的
 * 物品相關方法時，classpath 需要 Bukkit／Paper API。遮罩與純回呼按鈕無此依賴。</p>
 *
 * @see GuiScope
 * @see GuiButton
 * @since 1.4.0
 */
public final class GuiView {

    /**
     * 視圖底層 inventory 種類。
     */
    public enum Kind {
        /** 箱子視圖（9～54 格，9 的倍數）。 */
        CHEST,
        /** 鐵砧視圖（固定 3 欄）。 */
        ANVIL
    }

    private final Kind kind;
    private final String title;
    private final int size;
    private final Set<Integer> allowedSlots;
    private final Map<Integer, GuiButton> buttons;
    private final Map<String, Consumer<GuiButtonClick>> handlers;
    private final Map<Integer, ItemStack> buttonIcons;
    private final Map<Integer, String> buttonLabels;

    private GuiView(Builder builder) {
        this.kind = builder.kind;
        this.title = builder.title;
        this.size = builder.kind == Kind.ANVIL ? 3 : builder.size;
        this.allowedSlots = Set.copyOf(builder.allowed);
        Map<Integer, GuiButton> buttonEntries = new LinkedHashMap<>();
        Map<String, Consumer<GuiButtonClick>> handlerEntries = new LinkedHashMap<>();
        Map<Integer, ItemStack> iconEntries = new LinkedHashMap<>();
        Map<Integer, String> labelEntries = new LinkedHashMap<>();
        for (Map.Entry<Integer, ButtonSpec> entry : builder.buttons.entrySet()) {
            ButtonSpec spec = entry.getValue();
            buttonEntries.put(entry.getKey(),
                new GuiButton(spec.id, spec.cooldownMillis));
            handlerEntries.put(spec.id, spec.handler);
            if (spec.icon != null) {
                iconEntries.put(entry.getKey(), spec.icon);
            }
            if (spec.label != null) {
                labelEntries.put(entry.getKey(), spec.label);
            }
        }
        this.buttons = Map.copyOf(buttonEntries);
        this.handlers = Map.copyOf(handlerEntries);
        this.buttonIcons = Map.copyOf(iconEntries);
        this.buttonLabels = Map.copyOf(labelEntries);
    }

    /**
     * 開始建立箱子視圖。
     *
     * @param title 視圖標題；不可為 null
     * @param size 總格數；必須為 9～54 且為 9 的倍數
     * @return 新的 builder
     */
    public static Builder chest(String title, int size) {
        return new Builder(Kind.CHEST, title, size);
    }

    /**
     * 以字元遮罩開始建立箱子視圖。
     *
     * <p>視圖格數取自 {@link GuiMask#size()}；遮罩符號換算的欄位清單
     * （{@link GuiMask#slots(char)}）可同時用於 {@code allow} 與
     * {@code button} 宣告。本方法只決定形狀，不做自動版面配置。</p>
     *
     * @param title 視圖標題；不可為 null
     * @param mask 字元遮罩；不可為 null
     * @return 新的 builder
     */
    public static Builder chest(String title, GuiMask mask) {
        Objects.requireNonNull(mask, "mask");
        return new Builder(Kind.CHEST, title, mask.size());
    }

    /**
     * 開始建立鐵砧視圖。
     *
     * @param title 視圖標題；不可為 null
     * @return 新的 builder
     */
    public static Builder anvil(String title) {
        return new Builder(Kind.ANVIL, title, 9);
    }

    /** @return 視圖種類；永不為 null */
    public Kind kind() {
        return kind;
    }

    /** @return 視圖標題；永不為 null */
    public String title() {
        return title;
    }

    /** @return 總格數（鐵砧視圖固定為 3）；永遠 {@code > 0} */
    public int size() {
        return size;
    }

    /**
     * @return 放行欄位集合（不可變；預設為空＝全部擋下）
     */
    public Set<Integer> allowedSlots() {
        return allowedSlots;
    }

    /**
     * @return 按鈕表（欄位 → 按鈕，不可變）
     */
    public Map<Integer, GuiButton> buttons() {
        return buttons;
    }

    /**
     * @return 按鈕回呼表（按鈕識別字 → handler，不可變）
     */
    public Map<String, Consumer<GuiButtonClick>> handlers() {
        return handlers;
    }

    /**
     * @return 按鈕物品表（欄位 → 宣告時傳入的物品 reference，不可變表；
     *     內容為 live reference：宣告後、開啟前修改該物品會反映到放置結果；
     *     放置時於玩家 region 內即時複製，互不影響。純回呼按鈕視圖回空表）
     */
    public Map<Integer, ItemStack> buttonIcons() {
        return buttonIcons;
    }

    /**
     * @return 按鈕標籤表（欄位 → 基岩可見文字，不可變；只有宣告時給了
     *     非 null 標籤的按鈕才會出現在此表，無標籤按鈕不在表內。
     *     本表為無序快照：迭代順序不代表欄位順序，需要欄位順序時請自行排序）
     */
    public Map<Integer, String> buttonLabels() {
        return buttonLabels;
    }

    @Override
    public String toString() {
        return "GuiView{kind=" + kind + ", title=" + title + ", size=" + size
            + ", allowedSlots=" + allowedSlots + ", buttons=" + buttons + "}";
    }

    /**
     * {@link GuiView} 的 builder。
     */
    public static final class Builder {

        private final Kind kind;
        private final String title;
        private final int size;
        private final Set<Integer> allowed = new HashSet<>();
        private final Map<Integer, ButtonSpec> buttons = new LinkedHashMap<>();

        private Builder(Kind kind, String title, int size) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.title = Objects.requireNonNull(title, "title");
            if (kind == Kind.CHEST
                && (size < 9 || size > 54 || size % 9 != 0)) {
                throw new IllegalArgumentException(
                    "[" + GuiErrorCode.INVALID_INPUT + "] 箱子視圖 size 必須為"
                        + " 9～54 且為 9 的倍數；實際: " + size);
            }
            this.size = size;
        }

        private int limit() {
            return kind == Kind.ANVIL ? 3 : size;
        }

        private void checkSlot(int slot) {
            if (slot < 0 || slot >= limit()) {
                throw new IllegalArgumentException(
                    "[" + GuiErrorCode.INVALID_INPUT + "] slot 越界: " + slot
                        + "（視圖格數=" + limit() + "）");
            }
        }

        /**
         * 放行指定欄位（可多次呼叫累加）。
         *
         * @param slots 欄位編號；不可為 null
         * @return this
         */
        public Builder allow(int... slots) {
            Objects.requireNonNull(slots, "slots");
            for (int slot : slots) {
                checkSlot(slot);
                allowed.add(slot);
            }
            return this;
        }

        /**
         * 放行指定欄位集合（可多次呼叫累加）。
         *
         * @param slots 欄位編號集合；可為 null（視為不新增）
         * @return this
         */
        public Builder allow(Collection<Integer> slots) {
            if (slots != null) {
                for (int slot : slots) {
                    checkSlot(slot);
                    allowed.add(slot);
                }
            }
            return this;
        }

        /**
         * 註冊按鈕（無冷卻）。
         *
         * @param slot 欄位編號
         * @param id 按鈕識別字；不可為 null／空白，且視圖內唯一
         * @param handler 點擊回呼；不可為 null（執行於玩家 region context，
         *     不得在其中做長時間工作或跨 region 操作）
         * @return this
         */
        public Builder button(int slot, String id, Consumer<GuiButtonClick> handler) {
            return button(slot, id, 0L, handler);
        }

        /**
         * 註冊按鈕（含點擊冷卻）。
         *
         * @param slot 欄位編號
         * @param id 按鈕識別字；不可為 null／空白，且視圖內唯一
         * @param cooldownMillis 同一玩家重複點擊冷卻毫秒數；不可為負
         * @param handler 點擊回呼；不可為 null
         * @return this
         */
        public Builder button(int slot, String id, long cooldownMillis,
                Consumer<GuiButtonClick> handler) {
            return register(slot, id, null, null, cooldownMillis, handler);
        }

        /**
         * 註冊帶標籤的按鈕（無冷卻）。
         *
         * <p>標籤是基岩可見文字：同一份宣告經
         * {@link GuiFlowStep#labeled(String, GuiView, java.util.Map)}
         * 產生基岩簡單表單時，有標籤的按鈕依欄位升序排成表單按鈕；
         * 無標籤（null）的按鈕不進表單。Java 呈現不受標籤影響。</p>
         *
         * <p>傳 null 標籤時請轉型為 {@code (String) null}，否則編譯器無法在
         * 本多載與帶物品多載之間選擇。</p>
         *
         * @param slot 欄位編號
         * @param id 按鈕識別字；不可為 null／空白，且視圖內唯一
         * @param label 基岩可見文字；可為 null（不進表單），非 null 時不可空白
         * @param handler 點擊回呼；不可為 null（執行於玩家 region context，
         *     不得在其中做長時間工作或跨 region 操作）
         * @return this
         */
        public Builder button(int slot, String id, String label,
                Consumer<GuiButtonClick> handler) {
            return register(slot, id, label, null, 0L, handler);
        }

        /**
         * 註冊帶標籤的按鈕（含點擊冷卻）。
         *
         * <p>標籤語意與 {@link #button(int, String, String, Consumer)} 相同。</p>
         *
         * <p>傳 null 標籤時請轉型為 {@code (String) null}。</p>
         *
         * @param slot 欄位編號
         * @param id 按鈕識別字；不可為 null／空白，且視圖內唯一
         * @param label 基岩可見文字；可為 null（不進表單），非 null 時不可空白
         * @param cooldownMillis 同一玩家重複點擊冷卻毫秒數；不可為負
         * @param handler 點擊回呼；不可為 null
         * @return this
         */
        public Builder button(int slot, String id, String label, long cooldownMillis,
                Consumer<GuiButtonClick> handler) {
            return register(slot, id, label, null, cooldownMillis, handler);
        }

        /**
         * 註冊帶物品的按鈕（無冷卻）。
         *
         * <p>宣告時<strong>不</strong>複製 {@code icon}：開啟前修改該物品，
         * 會反映到開啟時放置的物品。開啟視圖時，AceLib 於玩家所在執行緒、
         * 放入欄位之前即時複製（同一宣告給多位玩家開啟時各自獨立）；
         * 放置失敗（例如欄位異常）只記錄，不影響點擊語意。</p>
         *
         * <p>純單元測試引用本方法時，classpath 需要 Bukkit／Paper API
         * （{@code ItemStack} 為 Bukkit 型別）。</p>
         *
         * @param slot 欄位編號
         * @param id 按鈕識別字；不可為 null／空白，且視圖內唯一
         * @param icon 按鈕物品；不可為 null（只存參照，不複製）
         * @param handler 點擊回呼；不可為 null
         * @return this
         */
        public Builder button(int slot, String id, ItemStack icon,
                Consumer<GuiButtonClick> handler) {
            Objects.requireNonNull(icon, "icon");
            return register(slot, id, null, icon, 0L, handler);
        }

        /**
         * 註冊帶物品與標籤的按鈕（無冷卻）。
         *
         * <p>物品語意與 {@link #button(int, String, ItemStack, Consumer)} 相同；
         * 標籤語意與 {@link #button(int, String, String, Consumer)} 相同。</p>
         *
         * <p>純單元測試引用本方法時，classpath 需要 Bukkit／Paper API。</p>
         *
         * @param slot 欄位編號
         * @param id 按鈕識別字；不可為 null／空白，且視圖內唯一
         * @param icon 按鈕物品；不可為 null（只存參照，不複製）
         * @param label 基岩可見文字；可為 null（不進表單），非 null 時不可空白
         * @param handler 點擊回呼；不可為 null
         * @return this
         */
        public Builder button(int slot, String id, ItemStack icon, String label,
                Consumer<GuiButtonClick> handler) {
            Objects.requireNonNull(icon, "icon");
            return register(slot, id, label, icon, 0L, handler);
        }

        /**
         * 註冊帶物品的按鈕（含點擊冷卻）。
         *
         * <p>複製語意與 {@link #button(int, String, ItemStack, Consumer)}
         * 相同：宣告時不複製，放置前於玩家 region 內即時複製。</p>
         *
         * <p>純單元測試引用本方法時，classpath 需要 Bukkit／Paper API。</p>
         *
         * @param slot 欄位編號
         * @param id 按鈕識別字；不可為 null／空白，且視圖內唯一
         * @param icon 按鈕物品；不可為 null（只存參照，不複製）
         * @param cooldownMillis 同一玩家重複點擊冷卻毫秒數；不可為負
         * @param handler 點擊回呼；不可為 null
         * @return this
         */
        public Builder button(int slot, String id, ItemStack icon, long cooldownMillis,
                Consumer<GuiButtonClick> handler) {
            Objects.requireNonNull(icon, "icon");
            return register(slot, id, null, icon, cooldownMillis, handler);
        }

        /**
         * 註冊帶物品與標籤的按鈕（含點擊冷卻）。
         *
         * <p>物品語意與 {@link #button(int, String, ItemStack, Consumer)} 相同；
         * 標籤語意與 {@link #button(int, String, String, Consumer)} 相同。</p>
         *
         * <p>純單元測試引用本方法時，classpath 需要 Bukkit／Paper API。</p>
         *
         * @param slot 欄位編號
         * @param id 按鈕識別字；不可為 null／空白，且視圖內唯一
         * @param icon 按鈕物品；不可為 null（只存參照，不複製）
         * @param label 基岩可見文字；可為 null（不進表單），非 null 時不可空白
         * @param cooldownMillis 同一玩家重複點擊冷卻毫秒數；不可為負
         * @param handler 點擊回呼；不可為 null
         * @return this
         */
        public Builder button(int slot, String id, ItemStack icon, String label,
                long cooldownMillis, Consumer<GuiButtonClick> handler) {
            Objects.requireNonNull(icon, "icon");
            return register(slot, id, label, icon, cooldownMillis, handler);
        }

        private Builder register(int slot, String id, String label, ItemStack icon,
                long cooldownMillis, Consumer<GuiButtonClick> handler) {
            checkSlot(slot);
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            if (id.isBlank()) {
                throw new IllegalArgumentException(
                    "[" + GuiErrorCode.INVALID_INPUT + "] button id 不可為空白");
            }
            if (label != null && label.isBlank()) {
                throw new IllegalArgumentException(
                    "[" + GuiErrorCode.INVALID_INPUT + "] button label 不可為空白"
                        + "（不用標籤請傳 null）");
            }
            if (cooldownMillis < 0L) {
                throw new IllegalArgumentException(
                    "[" + GuiErrorCode.INVALID_INPUT + "] 按鈕冷卻不可為負；實際: "
                        + cooldownMillis);
            }
            if (buttons.containsKey(slot)) {
                throw new IllegalArgumentException(
                    "[" + GuiErrorCode.INVALID_INPUT + "] slot 已有按鈕: " + slot);
            }
            for (ButtonSpec spec : buttons.values()) {
                if (spec.id.equals(id)) {
                    throw new IllegalArgumentException(
                        "[" + GuiErrorCode.INVALID_INPUT + "] button id 重複: " + id);
                }
            }
            buttons.put(slot, new ButtonSpec(id, label, icon, cooldownMillis, handler));
            return this;
        }

        /**
         * 產出不可變視圖.
         *
         * @return 新的 {@link GuiView}
         */
        public GuiView build() {
            return new GuiView(this);
        }
    }

    private static final class ButtonSpec {
        final String id;
        final String label;
        final ItemStack icon;
        final long cooldownMillis;
        final Consumer<GuiButtonClick> handler;

        ButtonSpec(String id, String label, ItemStack icon, long cooldownMillis,
                Consumer<GuiButtonClick> handler) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.cooldownMillis = cooldownMillis;
            this.handler = handler;
        }
    }
}
