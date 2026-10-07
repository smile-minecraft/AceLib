package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;

/**
 * 世界引數（package 內實作；經 {@link Arguments#world(String)} 建立）。
 *
 * <p>傳統路徑依序嘗試：</p>
 * <ol>
 *   <li>{@code Bukkit.getWorld(raw)} 精確比對 legacy Bukkit 世界名</li>
 *   <li>大小寫不敏感掃描已載入世界（沿用既有行為）</li>
 *   <li><strong>維度鍵解析</strong>：{@link NamespacedKey#fromString(String, Plugin)}
 *       （第二參數為 null，故裸名預設 {@code minecraft} 命名空間）後
 *       {@code Bukkit.getWorld(key)}</li>
 * </ol>
 *
 * <h2>為什麼需要維度鍵 fallback</h2>
 * <p>Brigadier 樹送給客戶端的是 vanilla world 型別，它只接受<strong>維度鍵</strong>
 * （{@code overworld}／{@code minecraft:overworld}），而 {@code Bukkit.getWorld(String)}
 * 認的是 legacy Bukkit 世界名（主世界為 {@code world}）。兩者對主世界並不相同，
 * 只認 Bukkit 名會使 vanilla 已驗證通過的輸入在 parse 階段被拒。
 * 加上維度鍵 fallback 後，兩條路徑的輸入集合一致。</p>
 *
 * <p>維度鍵為小寫規範形式；{@code NamespacedKey.fromString} 對含大寫的輸入回
 * null（官方文件明載 casing matters），因此一併以小寫重試，讓本引數與其他
 * 引數的大小寫不敏感語意一致。</p>
 */
final class WorldArgument extends BaseArgument<World> {

    WorldArgument(String name) {
        super(name);
    }

    @Override
    public String usageToken() {
        return "<" + name + ">";
    }

    @Override
    public World parse(String raw, CommandMessages messages) {
        requireSingleToken(raw);
        World exact = Bukkit.getWorld(raw);
        if (exact != null) {
            return exact;
        }
        for (World loaded : Bukkit.getWorlds()) {
            if (loaded.getName().equalsIgnoreCase(raw)) {
                return loaded;
            }
        }
        World byKey = byDimensionKey(raw);
        if (byKey != null) {
            return byKey;
        }
        throw invalid(raw, "unknown world", messages);
    }

    /**
     * 以維度鍵查找世界；裸名以 {@code minecraft} 命名空間解讀。
     *
     * <p>查詢本身不應拋例外（輸入非法時 {@code fromString} 回 null），
     * 但仍以 try/catch 包住：任何意外都不該讓指令解析中斷，退回「查不到」
     * 由呼叫端產生 {@code ACELIB-CMD-015}。</p>
     *
     * @param raw 原始輸入；不可為 null
     * @return 對應世界；查不到時為 null
     */
    private static World byDimensionKey(String raw) {
        World found = lookupByKey(raw);
        if (found != null) {
            return found;
        }
        // NamespacedKey 拒絕大寫；小寫重試讓大小寫不敏感語意成立。
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.equals(raw) ? null : lookupByKey(lower);
    }

    private static World lookupByKey(String raw) {
        try {
            // 第二參數 null → 裸名使用 minecraft 命名空間。
            NamespacedKey key = NamespacedKey.fromString(raw, null);
            return key == null ? null : Bukkit.getWorld(key);
        } catch (Throwable ignored) {
            // 查不到就是查不到；不讓解析流程因查找例外中斷。
            return null;
        }
    }

    @Override
    public List<String> suggest(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        List<String> names = new ArrayList<>();
        for (World loaded : Bukkit.getWorlds()) {
            names.add(loaded.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return filterPrefix(names, prefix);
    }

    @Override
    public ArgumentType<?> brigadierType(ArgumentTypeFactory factory) {
        Objects.requireNonNull(factory, "factory");
        return factory.world();
    }

    @Override
    public World resolve(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Objects.requireNonNull(ctx, "ctx");
        return ctx.getArgument(name, World.class);
    }
}
