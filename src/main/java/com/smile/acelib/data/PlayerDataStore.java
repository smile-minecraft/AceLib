package com.smile.acelib.data;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 逐玩家資料儲存介面（SPI）。
 *
 * <p>與 {@link DataStore} 的差異：{@code DataStore} 是「單一 store、整棵 record tree
 * 由呼叫端組裝」的通用介面；本介面是「一位玩家一組欄位、由 store 自己負責增量落盤」
 * 的專用介面，對應每位玩家獨立儲存的需求。</p>
 *
 * <h2>資料模型</h2>
 * <p>每位玩家的資料是一組<strong>頂層欄位</strong>（{@code field → value}）。欄位值限
 * {@link JsonCodec} 的序列化白名單（基本型別、{@code null}、巢狀 {@code Map<String,Object>}
 * 與 {@code List<Object>}）。巢狀結構不拆到更細的欄位：一個頂層欄位是資料模型
 * （例如 record 的單一 component）的最小寫入單位。</p>
 *
 * <h2>生命週期</h2>
 * <ol>
 *   <li>{@link #init()} — 開啟底層連線／檔案並建立資料表；之後 {@link #isInitialized()} 為 true</li>
 *   <li>{@link #load(UUID)} / {@link #applyChanges(List)} / {@link #deletePlayer(UUID)}</li>
 *   <li>{@link #close()} — 釋放資源；冪等，關閉後操作拋 {@link DataStoreException}
 *       （{@code ACELIB-DATA-005}）</li>
 * </ol>
 *
 * <h2>執行緒模型</h2>
 * <p>本介面<strong>不保證執行緒安全</strong>。呼叫端必須自行序列化所有操作
 * （AceLib 內部由 {@code PlayerDataService} 的 per-store serial executor 保證）。
 * 連線不由此介面關閉以外部資源的生命週期為準。</p>
 *
 * <h2>增量寫入</h2>
 * <p>{@link #applyChanges(List)} 只寫有變動的欄位：值與現有內容相同的 upsert 不會
 * 產生寫入、也不會推進 {@code revision}。欄位從資料中消失時呼叫端以
 * {@link FieldChange#deletion(UUID, String)} 明確刪除，store 不留孤兒列。
 * 整批變更在<strong>單一交易</strong>內套用：任一筆失敗即整批 rollback。</p>
 *
 * @see PlayerDataStores
 * @see FieldChange
 * @since 1.4.0
 */
public interface PlayerDataStore extends AutoCloseable {

    /**
     * 取得此 store 的識別名稱（用於 log、診斷、metrics 與 thread 命名）。
     *
     * @return 不可為 null 的識別名稱
     */
    String name();

    /**
     * 取得此 store 使用的 owner（命名空間）值。
     *
     * <p>owner 與玩家 UUID、欄位名一起構成主鍵，讓多個下游可以在同一張資料表
     * 各自存放資料而互不覆寫。AceLib 內建的玩家資料服務使用
     * {@link PlayerDataStores#DEFAULT_OWNER}。</p>
     *
     * @return 不可為 null 或空白的 owner
     */
    String owner();

    /**
     * 取得當前 schema 版本。
     *
     * @return 不可為 null 的 schema 版本
     */
    SchemaVersion schemaVersion();

    /**
     * 是否已成功 {@link #init()}。
     *
     * @return true 表示已初始化
     */
    boolean isInitialized();

    /**
     * 是否已 {@link #close()}。
     *
     * @return true 表示已關閉
     */
    boolean isClosed();

    /**
     * 開啟底層儲存並建立資料表（已存在則沿用）。
     *
     * <p>冪等：重複呼叫為 no-op。</p>
     *
     * @throws DataStoreException 當驅動程式不可用、連線失敗或建表失敗
     *                             （{@code ACELIB-DATA-012}／{@code ACELIB-DATA-008}）
     */
    void init();

    /**
     * 讀取一位玩家的全部頂層欄位。
     *
     * <p>這是離線讀取的主要入口：不需要該玩家有 active session。
     * 該玩家從未寫入任何欄位時回傳 {@link Optional#empty()}。</p>
     *
     * @param uuid 玩家 UUID；不可為 null
     * @return 該玩家的欄位視圖；無資料時為 empty
     * @throws IllegalStateException 當尚未 {@link #init()}
     * @throws DataStoreException    當已關閉（{@code ACELIB-DATA-005}）或讀取失敗
     *                               （{@code ACELIB-DATA-008}）
     */
    Optional<Record> load(UUID uuid);

    /**
     * 在單一交易內套用一批欄位變更。
     *
     * <p>整批原子：任一筆失敗即全部 rollback，既有資料維持變更前的內容。
     * 空批次為 no-op，不開交易。批次內重複指定同一個 {@code (uuid, field)} 時，
     * 以最後一次為準。</p>
     *
     * @param changes 欄位變更批次；不可為 null
     * @throws IllegalStateException 當尚未 {@link #init()}
     * @throws DataStoreException    當已關閉（{@code ACELIB-DATA-005}）或交易失敗
     *                               （{@code ACELIB-DATA-008}）
     */
    void applyChanges(List<FieldChange> changes);

    /**
     * 刪除一位玩家的全部欄位。
     *
     * <p>該玩家從未寫入時為 no-op。</p>
     *
     * @param uuid 玩家 UUID；不可為 null
     * @throws IllegalStateException 當尚未 {@link #init()}
     * @throws DataStoreException    當已關閉（{@code ACELIB-DATA-005}）或刪除失敗
     *                               （{@code ACELIB-DATA-008}）
     */
    void deletePlayer(UUID uuid);

    /**
     * 以欄位 {@code revision} 為條件的單欄位 upsert。
     *
     * <p>只有當該欄位目前的 {@code revision} 等於 {@code expectedRevision}
     * 時才寫入；成功後 {@code revision} 遞增（不存在的欄位視為 {@code 0}，
     * 因此 {@code expectedRevision == 0} 即「欄位必須不存在才建立」）。
     * 條件不符時不寫入，回傳 {@code applied=false} 與當下實際
     * {@code revision}，呼叫端可用它重試（本方法不做自動重試）。</p>
     *
     * <p>與 {@link #applyChanges(List)} 的差異：條件相符即視為一次寫入並推進
     * {@code revision}，即使值與現有內容相同（呼叫端明確要求寫入，不沿用
     * {@code applyChanges} 的同值跳過優化）。刪除語意不在範圍：{@code value}
     * 不可為 {@code null}，移除欄位請用
     * {@link FieldChange#deletion(UUID, String)} 搭配 {@link #applyChanges}。</p>
     *
     * <p>原子性只在單一儲存行程內成立：JDBC／SQLite 以單一交易內的條件更新
     * 達成，不保證跨伺服器或跨行程的原子性。呼叫端的執行緒序列化義務與
     * {@link #applyChanges(List)} 相同（見類別 Javadoc 的執行緒模型）。</p>
     *
     * <p><strong>序列化前提</strong>：本方法保證的是<strong>單一呼叫內部</strong>的
     * 原子性（條件檢查與寫入在同一交易內，不會留下部分修改），<strong>不</strong>保證
     * 同一玩家上併發呼叫之間互斥。兩個同時進行的呼叫可能基於同一個舊
     * {@code revision} 各自判定（普通讀取不加鎖），先提交者成功、後提交者收到
     * {@code applied=false}——這正是呼叫端拿實際 {@code revision} 重試的設計，
     * 不是互斥。呼叫端必須序列化所有操作（AceLib 內部由
     * {@code PlayerDataService} 的 per-store serial executor 保證），或自行實作
     * 重試迴圈。不得把「交易內一致」解讀為「可併發使用」；要跨呼叫互斥需另做
     * 設計，本版本不提供。</p>
     *
     * <p>預設實作明確拒絕：外部 SPI 實作者可不實作本方法；未覆寫時拋
     * {@link UnsupportedOperationException}，不以「先讀後寫」假裝原子，
     * 呼叫端收到的是誠實的失敗而非假的成功。</p>
     *
     * @param uuid             玩家 UUID；不可為 null
     * @param field            頂層欄位名；不可為 null 或空白
     * @param value            欄位值；不可為 null（須符合 {@link JsonCodec} 白名單）
     * @param expectedRevision 預期的目前 {@code revision}；必須 {@code >= 0}
     * @return 寫入結果；不可為 null
     * @throws NullPointerException          當 {@code uuid}／{@code field}／
     *                                       {@code value} 為 null
     * @throws IllegalArgumentException      當 {@code field} 為空白或
     *                                       {@code expectedRevision} 為負數
     * @throws IllegalStateException         當尚未 {@link #init()}
     * @throws UnsupportedOperationException 當實作未支援條件寫入（預設）
     * @throws DataStoreException            當已關閉（{@code ACELIB-DATA-005}）
     *                                       或寫入失敗（{@code ACELIB-DATA-008}）
     * @since 1.5.0
     */
    default ConditionalWriteResult applyIfRevision(UUID uuid, String field, Object value,
            long expectedRevision) {
        throw new UnsupportedOperationException(
            "PlayerDataStore '" + getClass().getName()
                + "' does not support applyIfRevision; refusing to fake it with read-then-write");
    }

    /**
     * 在單一原子區段內讀取、檢查、套用。
     *
     * <p>流程：讀取該玩家的目前資料 → 在同一原子區段內以剛讀到的資料執行
     * {@code check} → 通過才套用 {@code changes}。檢查不通過時回傳
     * {@code CHECK_REJECTED} 且不留下任何修改；基礎設施失敗時整批回滾並拋
     * {@link DataStoreException}（{@code ACELIB-DATA-008}），同樣不留部分修改。</p>
     *
     * <p>{@code check} 在交易內執行：必須是對給定 record 的純判定，不可呼叫
     * 本 store 的任何方法（會死結或耗盡連線），也不應有外部副作用。
     * {@code check} 本身拋出的例外在回滾後原樣傳遞，不包裝成
     * {@link DataStoreException}。{@code changes} 必須全屬同一 {@code uuid}
     *（含空批次：空批次只做檢查、不寫入），否則拋
     * {@link IllegalArgumentException}。</p>
     *
     * <p>原子性與 {@link #applyIfRevision} 相同範圍：單一儲存行程內的單一交易，
     * 不保證跨伺服器或跨行程；不做自動重試，呼叫端自行決定重試。</p>
     *
     * <p><strong>序列化前提</strong>：本方法保證的是<strong>單一呼叫內部</strong>的
     * 原子性（讀取、判定、套用在同一交易／監視器內，判定不過或失敗都不留部分
     * 修改），<strong>不</strong>保證同一玩家上併發呼叫之間互斥。兩個同時進行的
     * {@code readCheckApply} 可能讀到同一份舊資料並各自通過檢查（普通讀取不加鎖，
     * 也沒有版本驗證），呼叫端必須序列化所有操作，或把需要互斥的判斷改寫成
     * {@link #applyIfRevision} 的 revision 條件。不得把「交易內一致」解讀為
     * 「可併發使用」；要跨呼叫互斥需另做設計，本版本不提供。</p>
     *
     * <p>預設實作明確拒絕，理由同 {@link #applyIfRevision}。</p>
     *
     * @param uuid    玩家 UUID；不可為 null
     * @param check   對剛讀到資料的判定；不可為 null
     * @param changes 檢查通過後套用的變更；不可為 null，元素不可為 null，
     *                且 {@code uuid} 必須全等於本參數的 {@code uuid}
     * @return 套用結果；不可為 null
     * @throws NullPointerException          當任一參數為 null
     * @throws IllegalArgumentException      當 {@code changes} 含有其他玩家的變更
     * @throws IllegalStateException         當尚未 {@link #init()}
     * @throws UnsupportedOperationException 當實作未支援讀檢查套用（預設）
     * @throws DataStoreException            當已關閉（{@code ACELIB-DATA-005}）
     *                                       或套用失敗（{@code ACELIB-DATA-008}）
     * @since 1.5.0
     */
    default ReadCheckApplyResult readCheckApply(UUID uuid, Predicate<Optional<Record>> check,
            List<FieldChange> changes) {
        throw new UnsupportedOperationException(
            "PlayerDataStore '" + getClass().getName()
                + "' does not support readCheckApply; refusing to fake it with read-then-write");
    }

    /**
     * 取得某位玩家某個欄位目前的 {@code revision}（每次實際寫入遞增）。
     *
     * <p>供測試與診斷觀察「哪些欄位真的被寫過」；非既有欄位回傳 0。</p>
     *
     * @param uuid  玩家 UUID；不可為 null
     * @param field 欄位名；不可為 null 或空白
     * @return revision；欄位不存在時為 0
     * @throws IllegalStateException 當尚未 {@link #init()}
     * @throws DataStoreException    當已關閉或讀取失敗
     */
    long revisionOf(UUID uuid, String field);

    /**
     * 取得某位玩家目前的欄位數。
     *
     * <p>供測試與診斷觀察；玩家無資料時回傳 0。</p>
     *
     * @param uuid 玩家 UUID；不可為 null
     * @return 欄位數
     * @throws IllegalStateException 當尚未 {@link #init()}
     * @throws DataStoreException    當已關閉或讀取失敗
     */
    int fieldCount(UUID uuid);

    /**
     * 釋放底層資源。冪等；重複呼叫不丟例外。
     *
     * <p>不負責關閉外部注入的 {@code DataSource} 或連線池。</p>
     */
    @Override
    void close();

    /**
     * 條件寫入的結果。
     *
     * @param applied         條件相符且已寫入時為 true；不符時為 false（未寫入）
     * @param currentRevision 回傳當下該欄位的實際 {@code revision}：
     *                        成功時為寫入後的值，不符時為供重試用的實際值
     */
    record ConditionalWriteResult(boolean applied, long currentRevision) {
        /**
         * 檢查結果的不變條件。
         */
        public ConditionalWriteResult {
            if (currentRevision < 0) {
                throw new IllegalArgumentException(
                    "currentRevision 不可為負數：" + currentRevision);
            }
        }
    }

    /**
     * 讀檢查套用的結果狀態。
     */
    enum Outcome {
        /** 檢查通過且變更已套用。 */
        APPLIED,
        /** 檢查不通過，未做任何修改。 */
        CHECK_REJECTED
    }

    /**
     * 讀檢查套用的結果。
     *
     * @param outcome 套用結果；不可為 null
     */
    record ReadCheckApplyResult(Outcome outcome) {
        /**
         * 檢查結果的不變條件。
         */
        public ReadCheckApplyResult {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    /**
     * 一筆欄位變更。
     *
     * <p>{@link #deletion(UUID, String)} 表示刪除該欄位，其餘為 upsert。</p>
     *
     * @param uuid     玩家 UUID
     * @param field    頂層欄位名
     * @param value    欄位值；刪除時為 {@code null}
     * @param deletion 是否為刪除
     */
    record FieldChange(UUID uuid, String field, Object value, boolean deletion) {

        /**
         * 建立一筆 upsert。
         *
         * @param uuid  玩家 UUID；不可為 null
         * @param field 欄位名；不可為 null 或空白
         * @param value 欄位值；不可為 null（移除欄位請用 {@link #deletion}）
         * @return 欄位變更
         * @throws NullPointerException     當任一參數為 null
         * @throws IllegalArgumentException 當 {@code field} 為空白
         */
        public static FieldChange upsert(UUID uuid, String field, Object value) {
            Objects.requireNonNull(uuid, "uuid");
            requireField(field);
            Objects.requireNonNull(value,
                "value 不可為 null；移除欄位請使用 FieldChange.deletion");
            return new FieldChange(uuid, field, value, false);
        }

        /**
         * 建立一筆刪除。
         *
         * @param uuid  玩家 UUID；不可為 null
         * @param field 欄位名；不可為 null 或空白
         * @return 欄位刪除
         * @throws NullPointerException     當任一參數為 null
         * @throws IllegalArgumentException 當 {@code field} 為空白
         */
        public static FieldChange deletion(UUID uuid, String field) {
            Objects.requireNonNull(uuid, "uuid");
            requireField(field);
            return new FieldChange(uuid, field, null, true);
        }

        private static void requireField(String field) {
            Objects.requireNonNull(field, "field");
            if (field.isBlank()) {
                throw new IllegalArgumentException("field 不可為空白");
            }
        }
    }
}