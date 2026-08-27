package com.example.acelibmsgprobe;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * 固定相容性案例目錄。
 *
 * <p>每個案例都是固定、可重複建構的 Adventure Component，對應
 * docs/reference/bedrock-message-compatibility-matrix.md 矩陣的縱軸。案例內容
 * 不依賴線上狀態或隨機值，確保 Java 與 Bedrock（經 Geyser）客戶端觀察可重現。</p>
 *
 * <p>設計約束：</p>
 * <ul>
 *   <li>所有 click action 都使用無破壞性指令／URL／文字：RUN_COMMAND 指向
 *       {@code /list}（唯讀、列出線上玩家），不得指向刪除、重建世界、付款或
 *       外部訊息等不可逆操作。</li>
 *   <li>gradient / rainbow 經 MiniMessage 解析產生（MiniMessage 僅作案例來源，
 *       本探針不實作任何 MiniMessage parser 或正式訊息 API）。</li>
 * </ul>
 */
public final class CompatibilityCases {

    private CompatibilityCases() {
    }

    /** 回傳固定、可重複的相容性案例清單（順序即矩陣縱軸順序）。 */
    public static List<CompatibilityCase> buildCatalog() {
        List<CompatibilityCase> cases = new ArrayList<>();
        cases.add(new CompatibilityCase(
            "plain",
            "純文字：無任何樣式，作為對照基準。",
            Component.text("Hello, AceLib compatibility probe")));
        cases.add(new CompatibilityCase(
            "style",
            "顏色與裝飾：紅色加粗。",
            Component.text("red bold")
                .color(NamedTextColor.RED)
                .decorate(TextDecoration.BOLD)));
        cases.add(new CompatibilityCase(
            "nested",
            "巢狀 Component：兩個不同顏色的子元件串接。",
            Component.text("Ace")
                .color(NamedTextColor.AQUA)
                .append(Component.text("Lib").color(NamedTextColor.GOLD))));
        cases.add(new CompatibilityCase(
            "hover-showtext",
            "HoverEvent.ShowText：滑鼠懸停顯示提示文字。",
            Component.text("hover me")
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                    Component.text("tooltip: AceLib probe")))));
        cases.add(new CompatibilityCase(
            "click-run-command",
            "ClickEvent.RUN_COMMAND：點擊執行無破壞性指令 /list。",
            Component.text("run /list")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/list"))));
        cases.add(new CompatibilityCase(
            "click-suggest-command",
            "ClickEvent.SUGGEST_COMMAND：點擊把指令填入輸入框。",
            Component.text("suggest command")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent
                    .suggestCommand("/tell <player> hello from AceLib probe"))));
        cases.add(new CompatibilityCase(
            "click-open-url",
            "ClickEvent.OPEN_URL：點擊開啟公開倉庫 URL。",
            Component.text("open AceLib repo")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent
                    .openUrl("https://github.com/smile-minecraft/AceLib"))));
        cases.add(new CompatibilityCase(
            "click-copy-to-clipboard",
            "ClickEvent.COPY_TO_CLIPBOARD：點擊複製固定文字。",
            Component.text("copy to clipboard")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent
                    .copyToClipboard("AceLib probe clipboard text"))));
        cases.add(new CompatibilityCase(
            "translatable",
            "translatable：伺服器端翻譯鍵（無引數）。",
            Component.translatable("block.minecraft.diamond_block")));
        cases.add(new CompatibilityCase(
            "hex",
            "hex 顏色：#ff8800 橘色。",
            Component.text("hex #ff8800")
                .color(TextColor.fromHexString("#ff8800"))));
        cases.add(new CompatibilityCase(
            "gradient",
            "gradient：紅→藍漸層文字（經 MiniMessage 解析）。",
            MiniMessage.miniMessage()
                .deserialize("<gradient:#ff0000:#0000ff>gradient text</gradient>")));
        cases.add(new CompatibilityCase(
            "rainbow",
            "rainbow：彩虹文字（經 MiniMessage 解析）。",
            MiniMessage.miniMessage()
                .deserialize("<rainbow>rainbow text</rainbow>")));
        return List.copyOf(cases);
    }
}
