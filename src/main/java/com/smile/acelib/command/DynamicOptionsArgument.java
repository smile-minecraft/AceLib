package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 動態字串選項引數（package 內實作；經 {@link Arguments#dynamic} 建立）。
 *
 * <p>選項集合由供應函式提供：每次 {@code parse} 與 {@code suggest} 都重新
 * 呼叫供應函式取得最新集合，執行期增刪選項立刻反映，不需要重新註冊或重建
 * 指令樹。解析大小寫不敏感，回傳集合內的宣告形式（canonical）。</p>
 *
 * <p>刻意做成開放式引數節點（argument 節點，不是 literal 分支）：平台沒有
 * 取消單一註冊的方法，固定選項分支做不到執行期增刪。同理基岩版補全與其他
 * 開放式引數相同（伺服器端建議送不到基岩版，見模組頁補全支援矩陣）。</p>
 *
 * <p>供應函式的回傳只在當次呼叫有效：回傳的 {@code null} 元素忽略；
 * 空集合時任何值都非法；供應函式本身拋錯或回傳的集合在複製期間被並發修改時，
 * 解析得到在地化 {@code ACELIB-CMD-015}（玩家訊息不變，原始例外以原因鏈附上，
 * 管理員可追查），補全回空清單並記 {@code AceLib} logger 的 WARNING
 * （補全不中斷輸入）。供應函式應回傳穩定快照（自行複製後再回傳），
 * 不要回傳仍被別處修改中的集合。</p>
 */
final class DynamicOptionsArgument extends BaseArgument<String> {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final Supplier<List<String>> optionsSupplier;

    DynamicOptionsArgument(String name, Supplier<List<String>> optionsSupplier) {
        super(name);
        this.optionsSupplier = Objects.requireNonNull(optionsSupplier,
            "optionsSupplier");
    }

    @Override
    public String usageToken() {
        // 選項在執行期變動，token 只標引數名（同世界引數的寫法）。
        return "<" + name + ">";
    }

    /**
     * 取當次有效的選項快照（去 {@code null}、不可變）。
     *
     * @return 當前選項的宣告形式；供應函式回傳 null 時視為空集合
     * @throws CommandException 供應函式拋錯或複製期間被並發修改時轉為在地化
     *         {@code ACELIB-CMD-015}（玩家訊息不變，原始例外附於原因鏈）
     */
    private List<String> snapshot(CommandMessages messages) {
        try {
            List<String> supplied = optionsSupplier.get();
            if (supplied == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>(supplied.size());
            for (String option : supplied) {
                if (option != null) {
                    out.add(option);
                }
            }
            return List.copyOf(out);
        } catch (RuntimeException ex) {
            throw invalid("", "options unavailable", messages, ex);
        }
    }

    @Override
    public String parse(String raw, CommandMessages messages) {
        requireSingleToken(raw);
        CommandMessages effective = effective(messages);
        List<String> options = snapshot(effective);
        for (String option : options) {
            if (option.equalsIgnoreCase(raw)) {
                return option;
            }
        }
        if (options.isEmpty()) {
            throw invalid(raw, "no options available", effective);
        }
        throw invalid(raw, "expected one of " + String.join("|", options), effective);
    }

    @Override
    public List<String> suggest(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        List<String> options;
        try {
            options = snapshot(null);
        } catch (RuntimeException ex) {
            // 補全失敗不中斷輸入；回空之前先記 WARNING（附原始例外堆疊，
            // 管理員可追查；上層相容層同樣吞掉補全例外）。
            logSupplierFailure(ex);
            return List.of();
        }
        return filterPrefix(options, prefix);
    }

    /** 供應失敗的補全路徑紀錄：WARNING 層級，訊息含引數名與失敗事實。 */
    private void logSupplierFailure(RuntimeException failure) {
        try {
            Throwable root = failure.getCause() == null ? failure : failure.getCause();
            LOGGER.log(Level.WARNING,
                "[ACELIB-CMD-015] dynamic options supplier failed "
                    + "for argument '" + name + "': " + root,
                failure);
        } catch (RuntimeException ignored) {
            // 日誌失敗不應中斷補全。
        }
    }

    @Override
    public ArgumentType<?> brigadierType(ArgumentTypeFactory factory) {
        Objects.requireNonNull(factory, "factory");
        return factory.stringWord();
    }

    @Override
    public String resolve(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Objects.requireNonNull(ctx, "ctx");
        String raw = ctx.getArgument(name, String.class);
        return parse(raw, DefaultCommandMessages.instance());
    }
}
