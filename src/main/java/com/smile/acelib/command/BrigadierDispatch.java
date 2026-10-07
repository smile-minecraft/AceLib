package com.smile.acelib.command;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;

/**
 * Brigadier 執行委派（函數式介面）。
 *
 * <p>Brigadier 樹的 {@code executes} 不直接執行業務，而是把
 * {@link CommandSourceStack} 與重建的 args 交回呼叫端
 * （通常為 {@link BrigadierRegistrar}，轉交
 * {@link CommandRegistry#dispatch}，維持單一真相來源）。</p>
 *
 * @see TypedSubCommand#buildBranch
 * @since 1.4.0
 */
@FunctionalInterface
public interface BrigadierDispatch {

    /**
     * 執行指令。
     *
     * @param stack        Brigadier 來源；不可為 null
     * @param commandLabel 根指令標籤（玩家實際輸入的標籤，可能是別名）；不可為 null
     * @param args         子指令名起的 args（不可變，可能為空）；不可為 null
     */
    void dispatch(CommandSourceStack stack, String commandLabel, List<String> args);
}
