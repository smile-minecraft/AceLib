package com.smile.acelib.command;

import com.mojang.brigadier.arguments.ArgumentType;

/**
 * Brigadier 引數型別工廠（公開 SPI；下游實作自訂引數時使用）。
 *
 * <p>vanilla 引數型別（玩家選擇器、世界、時間等）由伺服器 runtime 提供；
 * 以本介面隔離「結構」與「vanilla 型別實例化」，使樹拓撲可在無伺服器下
 * 以真實 Brigadier 節點測試，生產環境再換成 vanilla 型別。</p>
 *
 * <p>下游實作 {@link CommandArgument} 時，在
 * {@link CommandArgument#brigadierType(ArgumentTypeFactory)} 內選用對應的
 * 型別；只需要解析函式與補全函式的自訂引數可直接用
 * {@link CommandArgument#custom} 建立（其 Brigadier 型別固定為
 * {@link #stringWord()}，單 token 開放式引數）。</p>
 *
 * <ul>
 *   <li>生產實作：package 內 {@code PaperArgumentTypes}（呼叫
 *       {@code io.papermc.paper.command.brigadier.argument.ArgumentTypes}；
 *       由框架傳入，呼叫端不需自行建構）</li>
 *   <li>測試替身：純 Brigadier 實作（例如測試源碼內的替身）</li>
 * </ul>
 *
 * <p>自 AceLib 1.5.0 起公開；既有方法簽章與語意不變。</p>
 */
public interface ArgumentTypeFactory {

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
