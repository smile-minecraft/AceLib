package com.smile.acelib.context;

/**
 * 執行緒/區域上下文列舉（Supported）。
 *
 * <p>將 Bukkit/Paper/Folia 的執行環境分類為 6 種上下文，
 * 供 {@link ContextInspector} 與 {@link SafeExecutor} 判斷「目前執行緒是否能 mutate
 * 遊戲物件」。</p>
 *
 * <h2>語意對照</h2>
 * <ul>
 *   <li>{@link #GLOBAL} — 完全未知或非遊戲相關的全域執行緒（外部 thread pool、
 *       server bootstrap 等）；保守視為不可 mutate 任何遊戲物件</li>
 *   <li>{@link #FOLIA_REGION} — Folia 環境下持有該 region 的執行緒；可以 mutate
 *       該 region 內任何遊戲物件</li>
 *   <li>{@link #FOLIA_ASYNC} — Folia 環境下的 async scheduler；只能做 read-only 操作</li>
 *   <li>{@link #PAPER_MAIN} — Paper/Bukkit 主執行緒；可 mutate 全域物件</li>
 *   <li>{@link #PAPER_ASYNC} — Paper/Bukkit async 排程；只能做 read-only 操作</li>
 *   <li>{@link #UNKNOWN} — 平台/執行緒皆未知；保守視為所有操作都不安全</li>
 * </ul>
 *
 * <h2>預設安全規則（{@link #isSafeFor(OperationType)}）</h2>
 * <ul>
 *   <li>{@link #FOLIA_REGION} / {@link #PAPER_MAIN}：所有 mutate + read-only 都安全</li>
 *   <li>{@link #FOLIA_ASYNC} / {@link #PAPER_ASYNC} / {@link #GLOBAL}：僅 READ_ONLY 安全</li>
 *   <li>{@link #UNKNOWN}：所有操作都不安全（保守降級）</li>
 * </ul>
 *
 * <h2>序列化相容</h2>
 * 列舉常數順序凍結，不得更動。
 *
 * @see OperationType
 * @see ContextInspector
 * @see SafeExecutor
 * @since 1.0.0
 */
public enum ThreadContext {

    GLOBAL,
    FOLIA_REGION,
    FOLIA_ASYNC,
    PAPER_MAIN,
    PAPER_ASYNC,
    UNKNOWN;

    /**
     * 判斷此上下文是否允許執行指定類型的操作。
     *
     * <p>預設規則：</p>
     * <ul>
     *   <li>region / main：對 mutate 操作（{@link OperationType#WORLD_MUTATE} /
     *       {@link OperationType#PLAYER_MUTATE} / {@link OperationType#ENTITY_MUTATE} /
     *       {@link OperationType#BLOCK_MUTATE} / {@link OperationType#GUI_MUTATE}）
     *       與 read-only 皆回傳 {@code true}</li>
     *   <li>async / global：僅對 {@link OperationType#READ_ONLY} 回傳 {@code true}</li>
     *   <li>{@link #UNKNOWN}：永遠回傳 {@code false}（最保守）</li>
     * </ul>
     *
     * @param op 要檢查的操作類型；不可為 null（呼叫端需保證）
     * @return 此上下文是否允許執行該操作
     * @since 1.0.0
     */
    public boolean isSafeFor(OperationType op) {
        if (op == null) {
            return false;
        }
        switch (this) {
            case FOLIA_REGION:
            case PAPER_MAIN:
                // region / main thread：所有操作都安全
                return true;
            case FOLIA_ASYNC:
            case PAPER_ASYNC:
            case GLOBAL:
                // async / global：僅 read-only 安全
                return op == OperationType.READ_ONLY;
            case UNKNOWN:
            default:
                // 未知平台/上下文：最保守
                return false;
        }
    }
}