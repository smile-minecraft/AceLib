package com.example.acelibformprobe;

import com.smile.acelib.form.FormImage;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.message.FormText;
import com.smile.acelib.message.FormTextOptions;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * 固定表單相容性案例目錄。
 *
 * <p>每個案例都是固定、可重複建構的 {@link FormSpec}，對應
 * docs/reference/bedrock-form-compatibility-matrix.md 矩陣的縱軸。案例內容
 * 不依賴線上狀態或隨機值，確保 Bedrock（經 Geyser）客戶端觀察可重現。</p>
 *
 * <p>本目錄只使用 AceLib 公開 Supported API：文字先經 {@link FormText#render}
 * 轉為表單安全字串再填入 {@link FormSpec} builder，圖示經 {@link FormImage}
 * 建構時驗證。轉換屬性本身（例如 hex 降為哪一個 16 色）由 AceLib 自身測試覆蓋，
 * 本目錄只保證「每個案例都能建構出合法規格」。</p>
 *
 * <p>設計約束：</p>
 * <ul>
 *   <li>所有 URL 皆為無害的固定示例（不指向真實下載）；PATH 皆為語法合法的
 *       資源包路徑，其中 {@code icon-broken-fallback} 刻意使用不存在的路徑，
 *       觀察客戶端「圖示空白但按鈕仍可點、索引正確」的部分成功狀態。</li>
 *   <li>按鈕文字皆為固定短字串，不含不可逆操作暗示；點擊後的實際行為
 *       由真人觀察回填矩陣，不在本探針預設。</li>
 * </ul>
 */
public final class FormProbeCases {

    private FormProbeCases() {
    }

    /** 回傳固定、可重複的表單探針案例清單（順序即矩陣縱軸順序）。 */
    public static List<FormProbeCase> buildCatalog() {
        List<FormProbeCase> cases = new ArrayList<>();
        cases.add(new FormProbeCase(
            "icon-path-item",
            "PATH 圖示（物品貼圖）：按鈕帶 textures/items/diamond_sword。",
            () -> FormSpec.simple("探針：圖示 PATH（物品）")
                .content("此表單測試物品貼圖圖示是否顯示。")
                .button("鑽石劍", FormImage.path("textures/items/diamond_sword"))
                .build()));
        cases.add(new FormProbeCase(
            "icon-path-block",
            "PATH 圖示（方塊貼圖）：按鈕帶 textures/blocks/diamond_block。",
            () -> FormSpec.simple("探針：圖示 PATH（方塊）")
                .content("此表單測試方塊貼圖圖示是否顯示。")
                .button("鑽石方塊", FormImage.path("textures/blocks/diamond_block"))
                .build()));
        cases.add(new FormProbeCase(
            "icon-url",
            // 圖源選用公開穩定的 Wikimedia 範例 PNG（實測 HTTP 200、content-type
            // image/png）：客戶端自行下載，載入有延遲，圖小適合表單按鈕；
            // 下載失敗（例如離線）不是伺服器端錯誤。
            "URL 圖示（https 圖片）：按鈕帶公開 Wikimedia 範例 PNG；客戶端自行下載，載入有延遲，失敗不是伺服器錯誤。",
            () -> FormSpec.simple("探針：圖示 URL")
                .content("此表單測試 https 網址圖示是否顯示。")
                .button("網址圖示", FormImage.url(
                    "https://upload.wikimedia.org/wikipedia/commons/4/47/PNG_transparency_demonstration_1.png"))
                .build()));
        cases.add(new FormProbeCase(
            "icon-broken-fallback",
            "錯誤路徑：第二顆按鈕的圖示路徑不存在；圖示應空白但三顆按鈕仍可點且索引正確。",
            () -> FormSpec.simple("探針：圖示遺失退回")
                .content("第二顆按鈕的圖示不存在；請依序點擊三顆按鈕，確認索引 0／1／2 正確。")
                .button("第一顆")
                .button("第二顆（圖示遺失）", FormImage.path("textures/probe/does_not_exist"))
                .button("第三顆")
                .build()));
        cases.add(new FormProbeCase(
            "text-hex-downgrade",
            "FormText hex 降級：#ff8800 橘色應降為最接近的 16 色顯示。",
            () -> {
                Component input = Component.text("hex #ff8800 橘色文字")
                    .color(TextColor.fromHexString("#ff8800"));
                return FormSpec.simple("探針：hex 降級")
                    .content(FormText.render(input))
                    .button("確定")
                    .build();
            }));
        cases.add(new FormProbeCase(
            "text-gradient-downgrade",
            "FormText gradient 降級：紅→藍漸層應降為 16 色顯示。",
            () -> {
                Component input = MiniMessage.miniMessage()
                    .deserialize("<gradient:#ff0000:#0000ff>漸層文字 gradient text</gradient>");
                return FormSpec.simple("探針：gradient 降級")
                    .content(FormText.render(input))
                    .button("確定")
                    .build();
            }));
        cases.add(new FormProbeCase(
            "text-decoration-stripped",
            "FormText 裝飾清理：底線與刪除線應移除，粗體與斜體保留。",
            () -> {
                Component input = Component.text("粗體＋底線＋刪除線＋斜體")
                    .decorate(TextDecoration.BOLD)
                    .decorate(TextDecoration.UNDERLINED)
                    .decorate(TextDecoration.STRIKETHROUGH)
                    .decorate(TextDecoration.ITALIC);
                return FormSpec.simple("探針：裝飾清理")
                    .content(FormText.render(input))
                    .button("確定")
                    .build();
            }));
        cases.add(new FormProbeCase(
            "translatable-with-fallback",
            "translatable 有 fallback：未註冊的鍵應顯示 fallback 文字。",
            () -> {
                // 注意：fallback 必須以 .fallback(...) 設定；translatable(key, args)
                // 的後續參數是 translation 參數，不是備援（曾因此誤設而顯示鍵名）。
                Component input = Component.translatable()
                    .key("acelib.probe.case.missing")
                    .fallback("Fallback 備援文字")
                    .build();
                return FormSpec.simple("探針：translatable 有 fallback")
                    .content(FormText.render(input))
                    .button("確定")
                    .build();
            }));
        cases.add(new FormProbeCase(
            "translatable-key-only",
            "translatable 無 fallback：未註冊的鍵應保留 key 顯示。",
            () -> {
                Component input = Component.translatable("acelib.probe.case.key.only");
                return FormSpec.simple("探針：translatable 無 fallback")
                    .content(FormText.render(input))
                    .button("確定")
                    .build();
            }));
        cases.add(new FormProbeCase(
            "multiline",
            "多行文字：換行保留，每行結尾補 §r，跨行樣式延續。",
            () -> {
                Component input = Component.text("第一行純文字\n")
                    .append(Component.text("第二行紅色粗體\n")
                        .color(NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD))
                    .append(Component.text("第三行延續"));
                return FormSpec.simple("探針：多行換行")
                    .content(FormText.render(input))
                    .button("確定")
                    .build();
            }));
        cases.add(new FormProbeCase(
            "overlong-truncation",
            "超長文字：以完整可見字元計數截斷並補省略號。",
            () -> {
                String longText = "超長文字測試："
                    + "AceLib 表單探針相容性驗證樣本。"
                        .repeat(10);
                Component input = Component.text(longText);
                String rendered = FormText.render(input,
                    new FormTextOptions(false, 60, null));
                return FormSpec.simple("探針：超長截斷")
                    .content(rendered)
                    .button("確定")
                    .build();
            }));
        return List.copyOf(cases);
    }
}
