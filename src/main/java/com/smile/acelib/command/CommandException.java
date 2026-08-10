package com.smile.acelib.command;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 指令系統例外（Plan §十一 Phase 6）。
 *
 * <p>所有 dispatcher 與 handler 內部錯誤（缺少參數、無權限、未知子指令、
 * 冷卻中、玩家離線等）皆應以 {@link CommandException} 表達；測試與 caller
 * 可透過 {@link #getKind()} + {@link #getCode()} 對應到具體語意。</p>
 *
 * <h2>設計原則</h2>
 * <ul>
 *   <li>{@link #getKind()} 固定（標準 kind）或 {@link CommandErrorKind#CUSTOM}（caller 給 code）</li>
 *   <li>{@link #getCode()} 對應 {@code ACELIB-CMD-NNN} 標準代碼；
 *       CUSTOM 模式下由 caller 透過 {@link #custom(String, String)} 提供</li>
 *   <li>{@link #getVars()} 攜帶 template vars（如 {@code {sub}} / {@code {permission}}），
 *       給 ReplySink 做 i18n 替換；可為空 map</li>
 *   <li>本例外 extends {@link RuntimeException} — dispatcher 與 handler 不需
 *       顯式宣告 throws</li>
 * </ul>
 *
 * @see CommandErrorKind
 * @since Phase 6 (Plan §十一)
 */
public class CommandException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final CommandErrorKind kind;
    private final String code;
    private final Map<String, Object> vars;

    /**
     * 標準 kind 例外（code 自動推導、vars 為空）。
     *
     * @param kind    錯誤分類；不可為 null
     * @param message 詳細訊息；不可為 null
     * @throws NullPointerException 當 {@code kind} 或 {@code message} 為 null
     */
    public CommandException(CommandErrorKind kind, String message) {
        this(kind, message, null);
    }

    /**
     * 標準 kind 例外（可攜帶 template vars）。
     *
     * @param kind    錯誤分類；不可為 null
     * @param message 詳細訊息；不可為 null
     * @param vars    template vars；可為 null（視為空 map）
     * @throws NullPointerException 當 {@code kind} 或 {@code message} 為 null
     */
    public CommandException(CommandErrorKind kind, String message, Map<String, Object> vars) {
        super(Objects.requireNonNull(message, "message"));
        this.kind = Objects.requireNonNull(kind, "kind");
        this.code = kind.defaultCode();
        this.vars = vars == null
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new HashMap<>(vars));
    }

    /**
     * 建立 caller 自訂錯誤（CUSTOM kind + 自訂 code）。
     *
     * <p>典型用例：handler 內部拋出業務特定錯誤（例如「找不到目標玩家」），
     * 既不屬於 dispatcher 內建的 9 種標準 kind，也不適合丟 {@code IllegalStateException}。</p>
     *
     * @param code    自訂錯誤代碼（必須 {@code ACELIB-*} 開頭以便管理員識別）；
     *                不可為 null
     * @param message 詳細訊息；不可為 null
     * @return 帶有 CUSTOM kind 與自訂 code 的 {@link CommandException}
     * @throws NullPointerException 當任一參數為 null
     */
    public static CommandException custom(String code, String message) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        CommandException base = new CommandException(CommandErrorKind.CUSTOM, message);
        // 包裝覆寫 code；保留 kind=CUSTOM 與原 message。
        return new CommandException(base.kind, base.getMessage()) {
            private static final long serialVersionUID = 1L;

            @Override
            public String getCode() {
                return code;
            }
        };
    }

    /**
     * 取得錯誤分類。
     *
     * @return 永不為 null 的 {@link CommandErrorKind}
     */
    public CommandErrorKind getKind() {
        return kind;
    }

    /**
     * 取得錯誤代碼（{@code ACELIB-CMD-NNN} 格式）。
     *
     * @return 永不為 null 的錯誤代碼
     */
    public String getCode() {
        return code;
    }

    /**
     * 取得 template vars（給 ReplySink 做 i18n 替換）。
     *
     * @return 不可變 map；永遠不為 null（可能為空）
     */
    public Map<String, Object> getVars() {
        return vars;
    }
}