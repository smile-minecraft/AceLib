package com.smile.acelib.lifecycle;

import java.util.List;
import java.util.Objects;

/**
 * 生命週期操作的不可變結果與結構化問題清單。
 *
 * @param outcome 操作成功、結構拒絕或執行失敗
 * @param status 操作完成後宿主的狀態
 * @param problems 可採取行動的錯誤代碼與相關模組 id
 * @param moduleIds 此操作涉及的模組 id
 * @since 1.4.0
 */
public record LifecycleResult(Outcome outcome, LifecycleHost.Status status,
                              List<Problem> problems, List<String> moduleIds) {

    /** 建立不可變操作結果。 */
    public LifecycleResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(status, "status");
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        moduleIds = List.copyOf(Objects.requireNonNull(moduleIds, "moduleIds"));
        if (outcome == Outcome.SUCCESS && !problems.isEmpty()) {
            throw new IllegalArgumentException("successful result must not contain problems");
        }
        if (outcome != Outcome.SUCCESS && problems.isEmpty()) {
            throw new IllegalArgumentException("unsuccessful result must contain a problem");
        }
    }

    /** 操作是否完整成功。 */
    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }

    /** 操作結果種類。 */
    public enum Outcome {
        /** 操作已完成，資源與圖狀態符合宿主約定。 */
        SUCCESS,
        /** 輸入結構或目前狀態不允許操作，沒有執行相應副作用。 */
        REJECTED,
        /** 啟用、清理或 reload 失敗；詳情列於 problems。 */
        FAILED
    }

    /** 可供下游檢查的單一結構化問題。 */
    public record Problem(Code code, String moduleId, List<String> relatedModuleIds,
                          String message) {

        /**
         * 建立不可變問題，相關模組 id 以排序順序保存。
         *
         * @param code 問題分類；不可為 null
         * @param moduleId 主要模組 id；無單一對應模組時可為 null
         * @param relatedModuleIds 相關模組 id；不可為 null
         * @param message 可供下游記錄與診斷的說明；不可為 null
         */
        public Problem {
            Objects.requireNonNull(code, "code");
            relatedModuleIds = Objects.requireNonNull(relatedModuleIds, "relatedModuleIds")
                .stream().sorted().toList();
            Objects.requireNonNull(message, "message");
        }
    }

    /** lifecycle 宿主錯誤分類；文字代碼與 error-codes.md 一致。 */
    public enum Code {
        /** 模組宣告不完整或 id 非法。 */
        INVALID_MODULE("ACELIB-LIFE-001"),
        /** 模組 id 與本批或既有圖中的 id 重複。 */
        DUPLICATE_ID("ACELIB-LIFE-002"),
        /** 模組依賴未註冊的 id。 */
        MISSING_DEPENDENCY("ACELIB-LIFE-003"),
        /** 模組圖包含一個或多個循環。 */
        DEPENDENCY_CYCLE("ACELIB-LIFE-004"),
        /** 其他 plugin 的模組仍直接或間接依賴待撤銷模組。 */
        ACTIVE_DEPENDENTS("ACELIB-LIFE-005"),
        /** 模組啟用回呼失敗或未交回 handle。 */
        ENABLE_FAILED("ACELIB-LIFE-006"),
        /** 模組 handle 清理失敗。 */
        CLOSE_FAILED("ACELIB-LIFE-007"),
        /** 宿主目前狀態不接受此操作。 */
        INVALID_STATE("ACELIB-LIFE-008"),
        /** 核心 reload 或 reload 後模組重建失敗，不能宣稱完整回復。 */
        RELOAD_FAILED("ACELIB-LIFE-009");

        private final String code;

        Code(String code) {
            this.code = code;
        }

        /** 回傳完整分類代碼。 */
        public String code() {
            return code;
        }
    }
}
