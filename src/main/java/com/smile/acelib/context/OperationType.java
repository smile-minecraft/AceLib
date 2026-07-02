package com.smile.acelib.context;

/**
 * 操作類型列舉。
 *
 * <p>對應 Plan §八 Phase 3：將遊戲物件的操作語意分為 6 類，
 * 供 {@link ContextInspector} 與 {@link SafeExecutor} 決定該操作是否允許。</p>
 *
 * <h2>語意對照</h2>
 * <ul>
 *   <li>{@link #WORLD_MUTATE} — 修改世界狀態（生成方塊、爆炸、天氣等）</li>
 *   <li>{@link #PLAYER_MUTATE} — 修改玩家狀態（傳送、背包、hp、遊戲模式等）</li>
 *   <li>{@link #ENTITY_MUTATE} — 修改實體狀態（移動、生成、移除、屬性）</li>
 *   <li>{@link #BLOCK_MUTATE} — 修改方塊狀態（破壞、放置、紅石觸發）</li>
 *   <li>{@link #GUI_MUTATE} — 修改 GUI / inventory（開啟、關閉、設定物品）</li>
 *   <li>{@link #READ_ONLY} — 純讀取操作；任何執行緒皆可執行</li>
 * </ul>
 *
 * <h2>序列化相容</h2>
 * 列舉常數順序凍結，不得更動。
 *
 * @see ThreadContext
 * @see ContextInspector
 * @since Phase 3 (Plan §八)
 */
public enum OperationType {

    WORLD_MUTATE,
    PLAYER_MUTATE,
    ENTITY_MUTATE,
    BLOCK_MUTATE,
    GUI_MUTATE,
    READ_ONLY
}