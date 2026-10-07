package com.smile.acelib.external;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 權限查詢結果（主要群組／所屬群組／當前情境）。
 *
 * <p>沿用世界操作結果慣例：狀態＋錯誤代碼＋人類可讀訊息；成功時不攜帶錯誤代碼。
 * 成功時群組與情境為不可變快照；非成功時主要群組為 null、群組與情境為空
 *（查不到不等於「無群組」，呼叫端不得以空集合做授權判斷）。</p>
 *
 * <p>AceLib 只包裝外部權限提供者，不自製權限系統、不代做領域授權判斷。</p>
 *
 * @see ExternalResultState
 * @see PermissionProvider
 * @since 1.4.0
 */
public final class PermissionResult {

    private final ExternalResultState state;
    private final String errorCode;
    private final String detail;
    private final String primaryGroup;
    private final Set<String> groups;
    private final Map<String, Set<String>> contexts;

    private PermissionResult(ExternalResultState state, String errorCode, String detail,
            String primaryGroup, Set<String> groups, Map<String, Set<String>> contexts) {
        this.state = Objects.requireNonNull(state, "state");
        this.detail = Objects.requireNonNull(detail, "detail");
        if (state == ExternalResultState.SUCCESS && errorCode != null) {
            throw new IllegalArgumentException(
                "SUCCESS state must not carry an error code, got: " + errorCode);
        }
        if (state != ExternalResultState.SUCCESS && errorCode == null) {
            throw new IllegalArgumentException(
                "non-SUCCESS state must carry an error code");
        }
        if (state == ExternalResultState.SUCCESS && primaryGroup == null) {
            throw new IllegalArgumentException(
                "SUCCESS state must carry a primary group");
        }
        if (state != ExternalResultState.SUCCESS && primaryGroup != null) {
            throw new IllegalArgumentException(
                "non-SUCCESS state must not carry a primary group");
        }
        this.errorCode = errorCode;
        this.primaryGroup = primaryGroup;
        this.groups = Collections.unmodifiableSet(new TreeSet<>(
            Objects.requireNonNull(groups, "groups")));
        Map<String, Set<String>> copied = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry
                : Objects.requireNonNull(contexts, "contexts").entrySet()) {
            copied.put(Objects.requireNonNull(entry.getKey(), "context key"),
                Set.copyOf(Objects.requireNonNull(entry.getValue(), "context values")));
        }
        this.contexts = Collections.unmodifiableMap(copied);
    }

    /**
     * 建立成功結果。
     *
     * @param primaryGroup 主要群組；不可為 null
     * @param groups 所屬群組；不可為 null（可為空集合；回傳前排序快照）
     * @param contexts 當前情境（key→values）；不可為 null（可為空；深拷貝快照）
     * @param detail 人類可讀訊息；不可為 null
     * @return 成功結果；錯誤代碼為 null
     */
    public static PermissionResult success(String primaryGroup, Set<String> groups,
            Map<String, Set<String>> contexts, String detail) {
        return new PermissionResult(ExternalResultState.SUCCESS, null, detail,
            primaryGroup, groups, contexts);
    }

    /**
     * 建立非成功結果。
     *
     * @param state 狀態；不可為 null 且不可為 SUCCESS
     * @param errorCode 錯誤代碼（{@code ACELIB-EXT-*}）；不可為 null
     * @param detail 人類可讀訊息；不可為 null
     * @return 非成功結果；主要群組為 null、群組與情境為空
     */
    public static PermissionResult failure(ExternalResultState state, String errorCode,
            String detail) {
        if (state == ExternalResultState.SUCCESS) {
            throw new IllegalArgumentException(
                "failure() must not use SUCCESS state");
        }
        return new PermissionResult(state, errorCode, detail, null,
            new LinkedHashSet<>(), new LinkedHashMap<>());
    }

    /** @return 結果狀態；永不為 null */
    public ExternalResultState state() {
        return state;
    }

    /** @return 錯誤代碼；成功時為 null */
    public String errorCode() {
        return errorCode;
    }

    /** @return 人類可讀訊息；永不為 null */
    public String detail() {
        return detail;
    }

    /** @return 主要群組；非成功時為 null */
    public String primaryGroup() {
        return primaryGroup;
    }

    /** @return 所屬群組快照（排序、不可變）；非成功時為空 */
    public Set<String> groups() {
        return groups;
    }

    /** @return 當前情境快照（不可變深拷貝）；非成功時為空 */
    public Map<String, Set<String>> contexts() {
        return contexts;
    }

    /** @return 是否成功 */
    public boolean isSuccess() {
        return state == ExternalResultState.SUCCESS;
    }
}
