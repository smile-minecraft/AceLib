package com.smile.acelib.command;

import com.smile.acelib.diagnostics.DiagnosticReport;
import com.smile.acelib.diagnostics.DiagnosticsService;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@code /acelib status} 子指令處理器（Plan §十一 Phase 6 ＋ Plan §二十五 #7）。
 *
 * <p>呼叫 {@link DiagnosticsService#buildReport()} 取得不可變快照，再以
 * {@link DiagnosticReport#format(boolean) format(false)} 輸出人類可讀字串；
 * 不直接讀取 scheduler / player service / internal mutable state，避免把
 * 內部 reference 或 hash code 暴露到 chat / console。</p>
 *
 * <h2>設計取捨</h2>
 * <ul>
 *   <li>handler 對外不暴露 {@link DiagnosticsService} reference — 透過
 *       {@link Supplier} 注入，使 plugin 可以在 reload 之後把新 diagnostics
 *       傳給 handler（reload 不重建 handler，但 diagnostics 內容已更新）</li>
 *   <li>handler 對外不接收 sender 以外的 context；permission、cooldown、玩家
 *       / console 限定都由 {@link CommandRegistryImpl} 統一檢查</li>
 *   <li>reply 走 {@link ReplySink}（BukkitReplySink 內部處理 console / 玩家
 *       分流與 Folia region-safe 派送）</li>
 * </ul>
 *
 * @since v0.1.0
 */
public final class AceLibStatusHandler implements SubCommand {

    /**
     * 從 plugin 取得當下 diagnostics 的 supplier；reload 之後回傳的 instance
     * 可能更新（{@code rebindPlugin} 改寫 metadata），因此 supplier 在每次
     * 呼叫時重新讀取，而非快取。
     */
    private final Supplier<DiagnosticsService> diagnosticsSupplier;

    /**
     * @param diagnosticsSupplier 取得 {@link DiagnosticsService} 的 supplier；
     *                            不可為 null。可在 plugin 尚未 onEnable 時
     *                            回傳 safe-default instance 或 null（handler
     *                            對 null 會輸出 fallback 訊息而非拋例外）
     */
    public AceLibStatusHandler(Supplier<DiagnosticsService> diagnosticsSupplier) {
        this.diagnosticsSupplier = Objects.requireNonNull(diagnosticsSupplier,
            "diagnosticsSupplier");
    }

    @Override
    public void execute(CommandContext context) {
        DiagnosticsService ds = diagnosticsSupplier.get();
        if (ds == null) {
            context.reply("=== AceLib Status ===\n(plugin not enabled)");
            return;
        }
        // DiagnosticReport 為 immutable snapshot；format 結果為純字串，
        // 不含 Java 物件 reference / hash，符合「不暴露 mutable 內部」契約。
        DiagnosticReport report = ds.buildReport();
        context.reply(report.format(false));
    }
}
