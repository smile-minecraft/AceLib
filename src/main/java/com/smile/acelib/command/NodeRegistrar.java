package com.smile.acelib.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Collection;

/**
 * Brigadier 平台節點註冊器（package 內 SPI）。
 *
 * <p>隔離「平台生命週期」（Paper {@code LifecycleEvents.COMMANDS}）
 * 與註冊邏輯，使重複註冊／回滾／shutdown 語意可在無伺服器下測試。
 * 生產實作為 {@code LifecycleNodeRegistrar}；測試使用錄製替身。</p>
 */
interface NodeRegistrar {

    /**
     * 註冊根節點（連同別名與描述）。
     *
     * <p>生產實作把實際 {@code registrar().register(...)} 延後到平台
     * 觸發 {@code COMMANDS} 事件時執行；節點建構（vanilla 引數型別需
     * 伺服器 runtime）同樣延後到事件觸發時經 {@code nodeSupplier}
     * 求值，呼叫本身只掛上 lifecycle handler（必須在 {@code onEnable}
     * 期間呼叫）。</p>
     *
     * @param name         根指令名；不可為 null
     * @param description  help 描述；可為 null
     * @param aliases      別名；不可為 null（可為空）
     * @param nodeSupplier 根 literal 節點供應器（事件觸發時求值，
     *                     求值失敗由平台記錄）；不可為 null
     */
    void register(String name, String description, Collection<String> aliases,
                  java.util.function.Supplier<LiteralCommandNode<CommandSourceStack>> nodeSupplier);

    /**
     * 盡力移除該名稱的平台節點。
     *
     * <p>Paper 未提供 lifecycle 指令的取消註冊 API：生產實作為 no-op，
     * 平台在 plugin disable 時自動移除其全部指令；本方法僅讓錄製替身
     * 與本地簿記保持一致。依賴「移除即時生效」的呼叫端應改走
     * {@link BrigadierRegistrar#shutdown()} 語意（本地停用＋文件化
     * 的平台保證）。</p>
     *
     * @param name 根指令名；不可為 null
     */
    void unregister(String name);
}
