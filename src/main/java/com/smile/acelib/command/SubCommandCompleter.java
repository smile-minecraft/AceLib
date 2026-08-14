package com.smile.acelib.command;

import java.util.List;

/**
 * 子指令 tab completion 提供者。
 *
 * <p>由 {@link SubCommandSpec#completer()} 持有；dispatcher 在 tab complete
 * 流程中呼叫，用於產生該子指令層級的補全候選。</p>
 *
 * <p>實作要點：</p>
 * <ul>
 *   <li>回傳空 list = 無建議（dispatcher 不會補任何東西）</li>
 *   <li>回傳的 list 為不可變快照；dispatcher 不會修改</li>
 *   <li>caller 應該自行根據 {@link CommandContext#args()} 當下長度判斷
 *       該補哪一個層級的參數</li>
 *   <li>補全不應暴露無權限子指令 — dispatcher 已在呼叫本 completer 之前
 *       過濾掉 sender 不可見的子指令</li>
 * </ul>
 *
 * @see SubCommandSpec
 * @since 1.0.0
 */
@FunctionalInterface
public interface SubCommandCompleter {

    /**
     * 產生 tab completion 候選清單。
     *
     * @param context     指令 context（不可為 null）
     * @param currentArgs 當前已輸入的 args（含子指令名）；
     *                    不可為 null（可能是空 list）
     * @return 補全候選清單；永不為 null（可能為空）
     */
    List<String> complete(CommandContext context, List<String> currentArgs);

    /**
     * 不提供補全的預設 completer。
     */
    SubCommandCompleter NONE = (context, args) -> List.of();

    /**
     * 固定清單補全器（測試使用）。
     */
    static SubCommandCompleter fixed(List<String> candidates) {
        return (context, args) -> List.copyOf(candidates);
    }
}