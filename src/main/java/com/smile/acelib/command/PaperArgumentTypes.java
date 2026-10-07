package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.registry.RegistryKey;

/**
 * 生產環境引數型別工廠（package 內）：回傳送給客戶端的 vanilla 型別。
 *
 * <p>所有方法皆需伺服器 runtime（vanilla provider 經 ServiceLoader
 * 取得）；單元測試改用測試替身。呼叫時機為指令註冊（construction
 * 本身不觸發 provider，只在方法被呼叫時解析）。</p>
 */
final class PaperArgumentTypes implements ArgumentTypeFactory {

    private static final PaperArgumentTypes INSTANCE = new PaperArgumentTypes();

    private PaperArgumentTypes() {
    }

    static PaperArgumentTypes instance() {
        return INSTANCE;
    }

    @Override
    public ArgumentType<?> player() {
        return ArgumentTypes.player();
    }

    @Override
    public ArgumentType<?> offlinePlayer() {
        return ArgumentTypes.playerProfiles();
    }

    @Override
    public ArgumentType<?> boundedInt(int min, int max) {
        return IntegerArgumentType.integer(min, max);
    }

    @Override
    public ArgumentType<?> boundedDouble(double min, double max) {
        return DoubleArgumentType.doubleArg(min, max);
    }

    @Override
    public ArgumentType<?> duration() {
        return ArgumentTypes.time(0);
    }

    @Override
    public ArgumentType<?> world() {
        return ArgumentTypes.world();
    }

    @Override
    public ArgumentType<?> material() {
        return ArgumentTypes.resource(RegistryKey.ITEM);
    }

    @Override
    public ArgumentType<?> stringWord() {
        return com.mojang.brigadier.arguments.StringArgumentType.word();
    }
}
