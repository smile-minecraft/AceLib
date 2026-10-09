package com.example;

import com.smile.acelib.command.BrigadierRegistrar;
import com.smile.acelib.command.CommandArgument;
import com.smile.acelib.command.CommandErrorKind;
import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandMessages;
import com.smile.acelib.command.DefaultCommandMessages;
import com.smile.acelib.command.TypedCommand;
import com.smile.acelib.command.TypedSubCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * AceLib 1.5.0 自訂引數型別的外部 consumer 範例。
 *
 * <p>本類別位於 AceLib 外部套件（{@code com.example}，不是
 * {@code com.smile.acelib.command}），證明公開 SPI 可用：只需要解析函式與
 * 補全函式就能建立引數，不需實作 {@code CommandArgument} 的全部方法。</p>
 *
 * <p>示範引數把裸數字 {@code 75} 解析為 {@code 0.75}（範圍 0–100）。
 * 補全只列裸數字選項；非法值的錯誤訊息經傳入的 {@link CommandMessages}
 * 產生，走在地化。自訂引數是單 token 開放式引數：值需符合 Brigadier
 * {@code word} 字元集（{@code [0-9A-Za-z_-.+]}），超出該字元集的值只在
 * 傳統路徑可用——本範例刻意只用字元集內的裸數字，使註冊的指令在兩條路徑
 * 都能成功執行。</p>
 */
public final class CommandV150Example {

    private CommandV150Example() {
    }

    /**
     * 百分比引數：裸數字 {@code 75} 解析為 {@code 0.75}。
     *
     * @param name 引數名；不可為 null 或空字串
     * @return 百分比自訂引數；永不為 null
     */
    public static CommandArgument<Double> percent(String name) {
        return CommandArgument.custom(name, "<" + name + ":percent>",
            (raw, messages) -> {
                CommandMessages effective = messages == null
                    ? DefaultCommandMessages.instance()
                    : messages;
                try {
                    double value = Double.parseDouble(raw);
                    if (!Double.isNaN(value) && !Double.isInfinite(value)
                        && value >= 0 && value <= 100) {
                        return value / 100.0;
                    }
                } catch (NumberFormatException ignored) {
                    // 落到下方的統一錯誤。
                }
                throw new CommandException(CommandErrorKind.INVALID_ARGUMENT,
                    effective.invalidArgument(name, raw,
                        "expected <number> in 0-100"),
                    Map.of("arg", name, "value", raw));
            },
            prefix -> {
                Objects.requireNonNull(prefix, "prefix");
                List<String> out = new ArrayList<>();
                for (String candidate : List.of("25", "50", "75", "100")) {
                    if (candidate.toLowerCase(Locale.ROOT)
                        .startsWith(prefix.toLowerCase(Locale.ROOT))) {
                        out.add(candidate);
                    }
                }
                return List.copyOf(out);
            });
    }

    /**
     * 組裝使用自訂引數的型別化指令。
     *
     * @param plugin 擁有指令的 plugin（取 logger 用）；不可為 null
     * @return 折扣指令；永不為 null
     */
    public static TypedCommand discountCommand(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        CommandArgument<Double> rateArg = percent("rate");
        return TypedCommand.builder("discount")
            .description("折扣指令")
            .subcommand(TypedSubCommand.builder("set")
                .description("設定折扣")
                .argument(rateArg)
                .executes(ctx -> plugin.getLogger()
                    .info("discount rate: " + ctx.get(rateArg)))
                .build())
            .build();
    }

    /**
     * 在 {@code onEnable} 期間註冊自訂引數指令（呼叫一次即可）。
     *
     * @param plugin 擁有指令的 plugin；不可為 null
     * @return 已註冊的 registrar（disable 時由擁有者關閉）；永不為 null
     */
    public static BrigadierRegistrar register(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        BrigadierRegistrar registrar = new BrigadierRegistrar(plugin);
        registrar.register(discountCommand(plugin));
        return registrar;
    }
}
