package com.smile.acelib.context;

import com.smile.acelib.platform.Platform;
import java.util.Objects;
import org.bukkit.Bukkit;

/**
 * 上下文檢查器（Supported）。
 *
 * <p>根據「目前執行緒/區域上下文」+「平台」+「操作類型」
 * 判定該操作是否允許執行。規則表：</p>
 *
 * <h2>規則表</h2>
 * <table border="1">
 *   <caption>上下文 × 平台 × 操作 → 結果</caption>
 *   <tr><th>平台</th><th>上下文</th><th>mutate 操作</th><th>READ_ONLY</th></tr>
 *   <tr><td>FOLIA</td><td>FOLIA_REGION</td><td>allowed</td><td>allowed</td></tr>
 *   <tr><td>FOLIA</td><td>FOLIA_ASYNC</td><td>denied (CTX-001)</td><td>allowed</td></tr>
 *   <tr><td>FOLIA</td><td>PAPER_MAIN</td><td>denied (CTX-003)</td><td>allowed</td></tr>
 *   <tr><td>FOLIA</td><td>GLOBAL</td><td>denied (CTX-003)</td><td>allowed</td></tr>
 *   <tr><td>FOLIA</td><td>UNKNOWN</td><td>denied (CTX-001)</td><td>allowed</td></tr>
 *   <tr><td>PAPER</td><td>PAPER_MAIN</td><td>allowed</td><td>allowed</td></tr>
 *   <tr><td>PAPER</td><td>PAPER_ASYNC</td><td>denied (CTX-001)</td><td>allowed</td></tr>
 *   <tr><td>PAPER</td><td>GLOBAL</td><td>denied (CTX-001)</td><td>allowed</td></tr>
 *   <tr><td>PAPER</td><td>FOLIA_REGION</td><td>denied (CTX-001)</td><td>allowed</td></tr>
 *   <tr><td>UNKNOWN</td><td>任意</td><td>denied (CTX-004)</td><td>denied (CTX-004)</td></tr>
 * </table>
 *
 * <h2>錯誤代碼語意</h2>
 * <ul>
 *   <li>{@code ACELIB-CTX-001} — 在錯誤上下文 mutate 遊戲物件（一般 async/global）</li>
 *   <li>{@code ACELIB-CTX-002} — 非同步流程完成後嘗試 mutate</li>
 *   <li>{@code ACELIB-CTX-003} — Folia 環境下非 region thread 操作 region-bound 物件</li>
 *   <li>{@code ACELIB-CTX-004} — 平台不支援此操作</li>
 * </ul>
 *
 * <h2>執行緒安全</h2>
 * <p>本類別無自身 mutable state，可跨 thread 呼叫；但它不是嚴格意義的純函式：
 * {@link #currentContext(Platform)} 會讀取 Bukkit 當下環境
 * （{@code Bukkit.isPrimaryThread()} 等），回傳值依賴呼叫當下的執行緒/平台狀態；
 * {@link #check(ThreadContext, OperationType, Platform)} 則完全由輸入決定
 * （輸入決定型檢查），不讀取外部狀態。</p>
 *
 * @see ThreadContext
 * @see OperationType
 * @since 1.0.0
 */
public final class ContextInspector {

    private ContextInspector() {
        // utility class
    }

    /**
     * 推導當前執行緒/區域上下文。
     *
     * <p>規則：</p>
     * <ul>
     *   <li>Folia 平台 + 主執行緒 → {@link ThreadContext#FOLIA_REGION}</li>
     *   <li>Folia 平台 + 非主執行緒 → {@link ThreadContext#FOLIA_ASYNC}</li>
     *   <li>Paper 平台 + 主執行緒 → {@link ThreadContext#PAPER_MAIN}</li>
     *   <li>Paper 平台 + 非主執行緒 → {@link ThreadContext#PAPER_ASYNC}</li>
     *   <li>UNSUPPORTED 平台 → {@link ThreadContext#UNKNOWN}</li>
     * </ul>
     *
     * @param platform 當前平台；不可為 null
     * @return 對應的 {@link ThreadContext}
     * @throws NullPointerException 當 {@code platform} 為 null
     */
    public static ThreadContext currentContext(Platform platform) {
        Objects.requireNonNull(platform, "platform");
        if (platform == Platform.UNKNOWN) {
            return ThreadContext.UNKNOWN;
        }
        boolean main;
        try {
            main = Bukkit.isPrimaryThread();
        } catch (Throwable t) {
            // 純單元測試或 Bukkit 尚未初始化：保守視為非主執行緒
            main = false;
        }
        if (platform == Platform.FOLIA) {
            return main ? ThreadContext.FOLIA_REGION : ThreadContext.FOLIA_ASYNC;
        }
        // Paper
        return main ? ThreadContext.PAPER_MAIN : ThreadContext.PAPER_ASYNC;
    }

    /**
     * 檢查指定上下文 + 平台 + 操作是否安全。
     *
     * @param from     推導或外部注入的當前上下文；不可為 null
     * @param op       操作類型；不可為 null
     * @param platform 偵測到的平台；不可為 null
     * @return 對應的 {@link ContextCheckResult}（allowed 或 denied）
     * @throws NullPointerException 任一參數為 null
     */
    public static ContextCheckResult check(ThreadContext from,
                                           OperationType op,
                                           Platform platform) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(platform, "platform");

        // 規則 1：UNKNOWN 平台 → 一律拒絕（CTX-004）
        if (platform == Platform.UNKNOWN) {
            return ContextCheckResult.denied("ACELIB-CTX-004",
                "platform is UNKNOWN; cannot perform " + op + " from " + from);
        }

        // 規則 2：READ_ONLY 在已知平台永遠允許（無論執行緒）
        if (op == OperationType.READ_ONLY) {
            return ContextCheckResult.allowed();
        }

        // 規則 3：Folia 環境下 region-bound mutate 必須在 region thread
        if (platform == Platform.FOLIA) {
            if (from == ThreadContext.FOLIA_REGION) {
                return ContextCheckResult.allowed();
            }
            // Folia 環境但非 region thread
            return ContextCheckResult.denied("ACELIB-CTX-003",
                "Folia requires region thread for " + op
                    + " but current context is " + from);
        }

        // 規則 4：Paper 環境下 mutate 必須在 main thread
        if (platform == Platform.PAPER) {
            if (from == ThreadContext.PAPER_MAIN) {
                return ContextCheckResult.allowed();
            }
            return ContextCheckResult.denied("ACELIB-CTX-001",
                "Paper requires main thread for " + op
                    + " but current context is " + from);
        }

        // 其他平台（不應該走到這裡）：保守拒絕
        return ContextCheckResult.denied("ACELIB-CTX-004",
            "unsupported platform " + platform + " for " + op);
    }
}