package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.Objects;

/**
 * 型別化指令引數契約。
 *
 * <p>同一個引數定義同時服務兩條執行路徑：</p>
 * <ul>
 *   <li>傳統 Bukkit 路徑（{@link BukkitCommandBridge}／
 *       {@link CommandRegistry#dispatch}）：以 {@link #parse(String, CommandMessages)}
 *       把原始字串轉為型別值；{@link #suggest(String)} 提供 tab 補全</li>
 *   <li>Brigadier 路徑（{@link TypedCommand#toBrigadierNode}）：以
 *       {@link #brigadierType(ArgumentTypeFactory)} 取得送給客戶端的 vanilla
 *       引數型別（客戶端驗證＋補全），再以
 *       {@link #resolve(CommandContext)} 從解析結果取值</li>
 * </ul>
 *
 * <h2>固定選項</h2>
 * <p>{@link #isFixedOptions()} 為 true 的引數（列舉、固定字串）在 Brigadier
 * 樹中編譯為 <strong>literal 分支</strong>而非 argument 節點 — 原本預期這是
 * 基岩版（Geyser）看得見補全的結構，但 2026-10-08 真人基岩客戶端實測顯示
 * 基岩端建議列並未出現（Geyser Current Limitations，Unfixable；見模組頁
 * 補全支援矩陣）；伺服器即時算出的建議同樣送不到基岩版。
 * 此類引數的 {@link #resolve(CommandContext)} 不會在正常流程被呼叫。</p>
 *
 * <h2>單 token 不變條件</h2>
 * <p>所有開放式引數的 {@code parse} 必須拒絕含空白字元的輸入
 * （{@code ACELIB-CMD-015}）：Brigadier 執行委派依原始輸入空白切分重建
 * args，含空白的 token 會破壞切分與解析的一致性。</p>
 *
 * <h2>自訂引數型別</h2>
 * <p>下游以 {@link #custom} 只需解析函式與補全函式即可建立自己的引數型別；
 * 自訂引數一律是單 token 開放式引數（Brigadier 型別固定為
 * {@code stringWord}）。自 AceLib 1.5.0 起提供。</p>
 *
 * @param <T> 解析後的型別值
 * @see Arguments
 * @since 1.4.0
 */
public interface CommandArgument<T> {

    /**
     * 自訂引數的解析函式。
     *
     * @param <T> 解析後的型別值
     * @since 1.5.0
     */
    @FunctionalInterface
    interface Parser<T> {

        /**
         * 以指定訊息表把原始字串解析為型別值。
         *
         * @param raw      玩家輸入的原始字串；不可為 null（已通過單 token 檢查）
         * @param messages 錯誤訊息表；永不為 null（呼叫端傳 null 時框架已換為預設英文）
         * @return 解析後的型別值；永不為 null
         * @throws CommandException 解析失敗（建議 {@code ACELIB-CMD-015}；
         *         訊息經 {@code messages} 產生以走在地化）
         */
        T parse(String raw, CommandMessages messages);
    }

    /**
     * 自訂引數的補全函式。
     *
     * @since 1.5.0
     */
    @FunctionalInterface
    interface Suggester {

        /**
         * 列出符合前綴的候選。
         *
         * <p>大小寫不敏感比對、前綴過濾由實作自行完成；框架不再二次過濾，
         * 回傳即為兩條路徑共用的建議清單。</p>
         *
         * @param prefix 已輸入前綴；不可為 null
         * @return 符合前綴的候選（不可變，可能為空）；永不為 null
         */
        List<String> suggest(String prefix);
    }

    /**
     * 以解析函式與補全函式建立自訂引數（下游實作自己的引數型別的入口）。
     *
     * <p>建成實例的行為：</p>
     * <ul>
     *   <li>傳統路徑 {@code parse} 先過單 token 不變條件
     *      （含空白即拋 {@code ACELIB-CMD-015}），再委派給 {@code parser}</li>
     *   <li>{@code brigadierType} 固定回傳 {@code factory.stringWord()}
     *      （自訂引數是單 token 開放式引數，編譯為 argument 節點）</li>
     *   <li>{@code resolve} 從 Brigadier context 取出原始字串後走同一個
     *       {@code parser}；此時無訊息表在作用域，取預設英文，
     *       接受的值與傳統路徑一致</li>
     * </ul>
     *
     * <p>兩條路徑的值一致，但可接受的字元受 Brigadier {@code word} 型別限制
     * （{@code [0-9A-Za-z_-.+]}；{@code word} 不處理引號形式，超出該字元集
     * 的值連同引號形式一併被拒）；超出該字元集的值只在傳統路徑可用。</p>
     *
     * @param name       引數名；不可為 null 或空字串
     * @param usageToken help／錯誤提示用的 token，例如 {@code <rate:percent>}；
     *                   不可為 null 或空字串
     * @param parser     解析函式；不可為 null
     * @param suggester  補全函式；不可為 null
     * @param <T>        解析後的型別值
     * @return 自訂引數；永不為 null
     * @throws NullPointerException 當任一參數為 null
     * @throws IllegalArgumentException 當 {@code name} 或 {@code usageToken} 為空字串
     * @since 1.5.0
     */
    static <T> CommandArgument<T> custom(String name, String usageToken,
                                         Parser<T> parser, Suggester suggester) {
        return new CustomArgument<>(name, usageToken, parser, suggester);
    }

    /** 引數名（Brigadier 節點名、usage token、錯誤 vars 共用）。 */
    String name();

    /** help／錯誤提示用的 token，例如 {@code <amount:1-64>}。 */
    String usageToken();

    /**
     * 以指定訊息表把原始字串解析為型別值。
     *
     * @param raw      玩家輸入的原始字串；不可為 null
     * @param messages 錯誤訊息表；null 視為 {@link DefaultCommandMessages}
     * @return 解析後的型別值；永不為 null
     * @throws NullPointerException 當 {@code raw} 為 null
     * @throws CommandException 解析失敗（{@code ACELIB-CMD-015}；
     *         玩家離線情境為 {@code ACELIB-CMD-007}）
     */
    T parse(String raw, CommandMessages messages);

    /**
     * 以預設英文訊息解析（便利多載）。
     *
     * @param raw 玩家輸入的原始字串；不可為 null
     * @return 解析後的型別值
     */
    default T parse(String raw) {
        return parse(raw, DefaultCommandMessages.instance());
    }

    /**
     * 伺服器端補全（Java 版客戶端；基岩版實測建議列未顯示，
     * 見模組頁補全支援矩陣）。
     *
     * @param prefix 已輸入前綴（大小寫不敏感比對）；不可為 null
     * @return 符合前綴的候選（不可變，可能為空）；永不為 null
     */
    List<String> suggest(String prefix);

    /**
     * 送給客戶端的 vanilla 引數型別（客戶端驗證＋補全用）。
     *
     * <p>固定選項引數不使用此方法（編譯為 literal 分支），仍須回傳合法型別
     * 以滿足契約。</p>
     *
     * @param factory 引數型別工廠（生產環境為 vanilla 型別）；不可為 null
     * @return 客戶端可見的引數型別；永不為 null
     */
    ArgumentType<?> brigadierType(ArgumentTypeFactory factory);

    /**
     * 從 Brigadier 解析結果取值。
     *
     * @param ctx Brigadier 指令 context；不可為 null
     * @return 型別值
     * @throws CommandSyntaxException 取值失敗（標準 Brigadier 錯誤流程）
     */
    T resolve(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException;

    /** 是否為固定選項（編譯為 literal 分支）。 */
    default boolean isFixedOptions() {
        return false;
    }

    /** 固定選項清單（canonical 形式）；非固定選項回傳空 list。 */
    default List<String> fixedOptions() {
        return List.of();
    }

    /**
     * 該引數在基岩版是否有可用補全：預設等於 {@link #isFixedOptions()}。
     * 開放式引數的伺服器端建議送不到基岩版（Geyser 限制，見模組頁矩陣）。
     * 實測備註（不改變預設值與行為）：2026-10-08 真人基岩客戶端實測顯示
     * 基岩端建議列未顯示（literal 分支與玩家引數皆然）；此預設為結構性
     * 預設，語意變更留待維護者裁定。
     */
    default boolean bedrockVisible() {
        return isFixedOptions();
    }
}
