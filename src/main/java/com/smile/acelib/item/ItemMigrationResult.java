package com.smile.acelib.item;

import java.util.List;
import java.util.Objects;

/**
 * 物品 migration 結果（immutable record）。
 *
 * <p>任何 migration 失敗都會被包裝為 {@link #failure(String, Throwable)}，
 * 並由 {@link ItemMigrationChain} 觸發 rollback，<strong>輸入 ItemStack 不被破壞</strong>。</p>
 *
 * @param success      是否成功
 * @param finalVersion 最終 schema 版本（失敗或 no-op 時可為 null）
 * @param errorCode    失敗時的錯誤代碼（{@code ACELIB-ITEM-*}）；成功時為 null
 * @param errorMessage 失敗時的人類可讀訊息；可為 null
 * @param cause        失敗時的底層例外；可為 null
 * @param appliedSteps 已套用的遷移步驟清單（不可變；不可為 null）
 */
public record ItemMigrationResult(boolean success, ItemSchemaVersion finalVersion,
                                   String errorCode, String errorMessage,
                                   Throwable cause, List<ItemSchemaVersion> appliedSteps) {

    /**
     * 成功結果。
     *
     * @param finalVersion 最終 schema 版本；無 migration 套用時可為 null
     * @param appliedSteps 已套用的遷移步驟清單（不可為 null）
     * @return 不可變的 {@link ItemMigrationResult}
     */
    public static ItemMigrationResult success(ItemSchemaVersion finalVersion,
                                              List<ItemSchemaVersion> appliedSteps) {
        Objects.requireNonNull(appliedSteps, "appliedSteps");
        return new ItemMigrationResult(true, finalVersion, null, null, null,
            List.copyOf(appliedSteps));
    }

    /**
     * 失敗結果。
     *
     * @param errorMessage 錯誤訊息；可為 null
     * @param cause        底層例外；可為 null
     * @return 不可變的 {@link ItemMigrationResult}（errorCode 固定為 {@link ItemErrorCode#MIGRATION_FAILED}）
     */
    public static ItemMigrationResult failure(String errorMessage, Throwable cause) {
        return new ItemMigrationResult(false, null, ItemErrorCode.MIGRATION_FAILED,
            errorMessage, cause, List.of());
    }
}
