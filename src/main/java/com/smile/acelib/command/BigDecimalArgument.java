package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 精確數值引數（package 內實作；經 {@link Arguments#bigDecimal} 建立）。
 *
 * <h2>精確解析（不經 double 中轉）</h2>
 * <p>全程以 {@link BigDecimal#BigDecimal(String)} 解析並以
 * {@link BigDecimal#compareTo} 比對範圍：{@code 0.10} 與 {@code 0.1}
 * 的 scale 差保留，{@code 0.1 + 0.2} 不受 double 誤差影響。
 * 本引數不做四捨五入、不做幣別與金額政策判斷（格式化、負號政策、千分位
 * 皆為下游責任）。</p>
 *
 * <h2>範圍與小數位</h2>
 * <p>範圍端點包含（{@code [min, max]}）。小數位上限依<b>輸入的 scale</b>
 * 檢查：{@code maxScale=2} 時 {@code 1.234} 被拒（不四捨五入）；
 * {@code maxScale=0} 時 {@code 10.0} 同樣被拒（輸入 scale 為 1）。
 * 尾隨零計入 scale（{@code 1.20} 的 scale 為 2）。</p>
 *
 * <h2>科學記號一律拒絕</h2>
 * <p>{@code 1E3} 這類寫法兩條路徑皆拒絕（{@code ACELIB-CMD-015}），
 * 請改寫為一般十進位（{@code 1000}）；錯誤訊息明確說明此規則。</p>
 *
 * <h2>兩條路徑一致（stringWord 節點）</h2>
 * <p>Brigadier 路徑送給客戶端的是字串單詞（{@code stringWord}）節點：
 * 節點只承接單 token，不做任何數值驗證，合法性完全由伺服器端同一個
 * 解析器判定。傳統路徑的執行委派把原始輸入切分後走同一個解析器；
 * {@code resolve} 直接取該節點的字串值走同一解析器（不經 double 中轉），
 * 接受的值與傳統路徑完全一致。</p>
 *
 * <p>取捨：字串節點沒有 vanilla double 的客戶端數值提示（範圍提示、
 * 即時語法檢查皆無），換來兩條路徑完全一致的伺服器端錯誤
 *（四類非法值皆為 {@code ACELIB-CMD-015}，訊息來自指定的訊息表）。
 * 需要客戶端數值提示的下游請改用 {@code doubleArg}。</p>
 *
 * <p>上述一致性以 Brigadier {@code word()} 字元集（{@code [0-9A-Za-z_-.+]}）內的輸入為前提：字元集外的輸入
 *（例如含逗號的 {@code 1,000}）在 Brigadier 路徑會被平台提前拒絕、
 * 拿不到 {@code ACELIB-CMD-015}，此時請改用不帶分隔的寫法或走傳統路徑。</p>
 */
final class BigDecimalArgument extends BaseArgument<BigDecimal> {

    /** 一般十進位寫法（無指數部）：可選正負號、整數或小數。 */
    private static final Pattern PLAIN_DECIMAL =
        Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");

    private final BigDecimal min;
    private final BigDecimal max;
    private final int maxScale;

    BigDecimalArgument(String name, BigDecimal min, BigDecimal max, int maxScale) {
        super(name);
        this.min = Objects.requireNonNull(min, "min");
        this.max = Objects.requireNonNull(max, "max");
        if (min.compareTo(max) > 0) {
            throw new IllegalArgumentException(
                "min (" + min + ") must be <= max (" + max + ")");
        }
        if (maxScale < 0) {
            throw new IllegalArgumentException(
                "maxScale (" + maxScale + ") must be >= 0");
        }
        this.maxScale = maxScale;
    }

    @Override
    public String usageToken() {
        return "<" + name + ":" + min.toPlainString() + "-" + max.toPlainString() + ">";
    }

    @Override
    public BigDecimal parse(String raw, CommandMessages messages) {
        requireSingleToken(raw);
        if (!PLAIN_DECIMAL.matcher(raw).matches()) {
            if (isScientificNotation(raw)) {
                throw invalid(raw,
                    "scientific notation is not allowed; use plain decimal notation"
                        + " (e.g. 1000, not 1E3)",
                    messages);
            }
            throw invalid(raw,
                "not a decimal number in range "
                    + min.toPlainString() + "-" + max.toPlainString(),
                messages);
        }
        // 已通過一般十進位正則：建構必定成功（防禦性接住，避免格式錯誤逃逸）。
        BigDecimal value;
        try {
            value = new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            throw invalid(raw,
                "not a decimal number in range "
                    + min.toPlainString() + "-" + max.toPlainString(),
                messages);
        }
        if (value.scale() > maxScale) {
            throw invalid(raw,
                "too many fraction digits (at most " + maxScale + " decimal places)",
                messages);
        }
        if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            throw invalid(raw,
                "out of range " + min.toPlainString() + "-" + max.toPlainString(),
                messages);
        }
        return value;
    }

    /**
     * 判斷是否為科學記號寫法（一般十進位正則未通過，但
     * {@link BigDecimal} 可解析 → 必為指數形式）。
     */
    private static boolean isScientificNotation(String raw) {
        try {
            new BigDecimal(raw);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    @Override
    public List<String> suggest(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return List.of();
    }

    @Override
    public ArgumentType<?> brigadierType(ArgumentTypeFactory factory) {
        Objects.requireNonNull(factory, "factory");
        return factory.stringWord();
    }

    @Override
    public BigDecimal resolve(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Objects.requireNonNull(ctx, "ctx");
        String raw = ctx.getArgument(name, String.class);
        return parse(raw, DefaultCommandMessages.instance());
    }
}
