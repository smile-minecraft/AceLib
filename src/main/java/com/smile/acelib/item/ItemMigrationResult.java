package com.smile.acelib.item;

import java.util.List;
import java.util.Objects;

/**
 * 物品 migration 結果（immutable record）。
 *
 * <p>對應 Plan Phase 12「舊版資料升級」需求。
 * 任何 migration 失敗都會被包裝為 {@link #failure(String, Throwable)}，
 * 並由 {@link ItemMigrationChain} 觸發 rollback，<strong>輸入 ItemStack 不被破壞</strong>。</p>
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
