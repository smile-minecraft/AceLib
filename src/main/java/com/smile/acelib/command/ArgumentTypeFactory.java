package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;

/**
 * Brigadier 引數型別工廠（package 內 SPI）。
 *
 * <p>vanilla 引數型別（玩家選擇器、世界、時間等）由伺服器 runtime 經
 * ServiceLoader 提供，單元測試環境不存在；以本介面隔離「結構」
 * 與「vanilla 型別實例化」，使樹拓撲可在無伺服器下以真實 Brigadier
 * 節點測試，生產環境再換成 vanilla 型別。</p>
 *
 * <ul>
 *   <li>生產實作：{@code PaperArgumentTypes}（呼叫
 *       {@code io.papermc.paper.command.brigadier.argument.ArgumentTypes}）</li>
 *   <li>測試替身：測試源碼內的純 Brigadier 實作</li>
 * </ul>
 */
interface ArgumentTypeFactory {

    /** 在線玩家選擇器。 */
    ArgumentType<?> player();

    /** 離線玩家 profile 選擇器。 */
    ArgumentType<?> offlinePlayer();

    /** 有上下限的整數（vanilla integer(min, max)，客戶端驗證）。 */
    ArgumentType<?> boundedInt(int min, int max);

    /** 有上下限的小數（vanilla double(min, max)，客戶端驗證）。 */
    ArgumentType<?> boundedDouble(double min, double max);

    /** 時間長度（vanilla time 語法，ticks）。 */
    ArgumentType<?> duration();

    /** 世界（vanilla world，客戶端驗證＋補全）。 */
    ArgumentType<?> world();

    /** 材質（vanilla item registry，客戶端驗證＋補全）。 */
    ArgumentType<?> material();

    /** 純字串（固定選項契約 placeholder、測試替身用）。 */
    ArgumentType<?> stringWord();
}
