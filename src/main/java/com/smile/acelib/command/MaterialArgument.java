package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.LiteralMessage;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.inventory.ItemType;

/**
 * 材質引數（package 內實作；經 {@link Arguments#material(String)} 建立）。
 *
 * <p>傳統路徑以 {@code Material.matchMaterial} 解析（大小寫、底線、
 * 舊名皆可）；不存在拋 {@code ACELIB-CMD-015}。Brigadier 路徑用
 * vanilla item registry（客戶端驗證＋補全），取值後轉
 * {@code Material}（registry 內非物品條目轉不出時為標準解析錯誤）。</p>
 */
final class MaterialArgument extends BaseArgument<Material> {

    MaterialArgument(String name) {
        super(name);
    }

    @Override
    public String usageToken() {
        return "<" + name + ":material>";
    }

    @Override
    public Material parse(String raw, CommandMessages messages) {
        requireSingleToken(raw);
        Material material = Material.matchMaterial(raw);
        if (material == null) {
            throw invalid(raw, "unknown material", messages);
        }
        return material;
    }

    @Override
    public List<String> suggest(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        List<String> names = new ArrayList<>();
        for (Material material : Material.values()) {
            names.add(material.name().toLowerCase(Locale.ROOT));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return filterPrefix(names, prefix);
    }

    @Override
    public ArgumentType<?> brigadierType(ArgumentTypeFactory factory) {
        Objects.requireNonNull(factory, "factory");
        return factory.material();
    }

    @Override
    public Material resolve(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        Objects.requireNonNull(ctx, "ctx");
        ItemType item = ctx.getArgument(name, ItemType.class);
        Material material = item.asMaterial();
        if (material == null) {
            throw new SimpleCommandExceptionType(
                new LiteralMessage("not a material: " + item.key())).create();
        }
        return material;
    }
}
