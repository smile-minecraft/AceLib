package com.smile.acelib.data;

import java.util.List;
import java.util.Objects;

/**
 * 遷移結果（immutable record）。
 *
 * <p>任何 migration 失敗都會被包裝為 {@link #failure(String, Throwable)}，
 * 並由 {@link DataStore} 觸發 rollback，<strong>既有資料不被破壞</strong>。</p>
 *
 * <h2>使用約定</h2>
 * <ul>
 *   <li>{@link #success()}：整個 chain 全部成功；可取得最終版本</li>
 *   <li>{@link #failure(String, Throwable)}：chain 中任一失敗；保留錯誤訊息與原因</li>
 *   <li>呼叫端應以 {@link #success()} 分支判斷，失敗則拋 {@link DataStoreException}</li>
 * </ul>
 *
 * @param success      整個 chain 是否全部成功
 * @param finalVersion 最終 schema 版本；失敗或無 migration 套用時可為 null
 * @param errorMessage 失敗原因；成功時為 null
 * @param cause        底層例外；成功時為 null
 * @param appliedSteps 已套用的遷移步驟清單；不可為 null（可能為空）
 * @since 1.0.0
 */
public record MigrationResult(boolean success, SchemaVersion finalVersion,
                              String errorMessage, Throwable cause,
                              List<SchemaVersion> appliedSteps) {

    /**
     * 成功結果。
     *
     * <p>{@code finalVersion} 在「無 migration 套用」情境下可為 {@code null}：
     * 從鏈中沒有任何符合 from→target 的 migration 時，沒有實際的「最終 schema 版本」
     * 可填，呼叫端應透過 {@link #appliedSteps} 是否為空判斷此情境。
     * 已經過正常升級（至少一個 step 套用）時則必須傳入對應的最終版本。</p>
     *
     * @param finalVersion 最終 schema 版本；無 migration 套用時可為 null
     * @param appliedSteps 已套用的遷移步驟清單（不可為 null）
     * @return 不可變的 {@link MigrationResult}
     */
    public static MigrationResult success(SchemaVersion finalVersion, List<SchemaVersion> appliedSteps) {
        Objects.requireNonNull(appliedSteps, "appliedSteps");
        return new MigrationResult(true, finalVersion, null, null, List.copyOf(appliedSteps));
    }

    /**
     * 失敗結果。
     *
     * @param errorMessage 錯誤訊息；可為 null
     * @param cause        底層例外；可為 null
     * @return 不可變的 {@link MigrationResult}
     */
    public static MigrationResult failure(String errorMessage, Throwable cause) {
        return new MigrationResult(false, null, errorMessage, cause, List.of());
    }
}