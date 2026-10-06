package com.smile.acelib.gui;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

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

    private GuiView(Builder builder) {
        this.kind = builder.kind;
        this.title = builder.title;
        this.size = builder.kind == Kind.ANVIL ? 3 : builder.size;
        this.allowedSlots = Set.copyOf(builder.allowed);
        Map<Integer, GuiButton> buttonEntries = new LinkedHashMap<>();
        Map<String, Consumer<GuiButtonClick>> handlerEntries = new LinkedHashMap<>();
        for (Map.Entry<Integer, ButtonSpec> entry : builder.buttons.entrySet()) {
            ButtonSpec spec = entry.getValue();
            buttonEntries.put(entry.getKey(),
                new GuiButton(spec.id, spec.cooldownMillis));
            handlerEntries.put(spec.id, spec.handler);
        }
        this.buttons = Map.copyOf(buttonEntries);
        this.handlers = Map.copyOf(handlerEntries);
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
            checkSlot(slot);
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
            if (id.isBlank()) {
                throw new IllegalArgumentException(
                    "[" + GuiErrorCode.INVALID_INPUT + "] button id 不可為空白");
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
            buttons.put(slot, new ButtonSpec(id, cooldownMillis, handler));
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
        final long cooldownMillis;
        final Consumer<GuiButtonClick> handler;

        ButtonSpec(String id, long cooldownMillis, Consumer<GuiButtonClick> handler) {
            this.id = id;
            this.cooldownMillis = cooldownMillis;
            this.handler = handler;
        }
    }
}
