package com.example;

import com.smile.acelib.data.JsonCodecImpl;
import com.smile.acelib.data.JsonFileDataStore;
import com.smile.acelib.data.PlayerDataStore;
import com.smile.acelib.data.PlayerDataStore.ConditionalWriteResult;
import com.smile.acelib.data.PlayerDataStore.FieldChange;
import com.smile.acelib.data.PlayerDataStore.Outcome;
import com.smile.acelib.data.PlayerDataStore.ReadCheckApplyResult;
import com.smile.acelib.data.PlayerDataStores;
import com.smile.acelib.data.Record;
import com.smile.acelib.data.SchemaVersion;
import com.smile.acelib.player.PlayerDataService;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * AceLib 1.5.0 資料線的外部 consumer 範例。
 *
 * <p>本類別位於 AceLib 外部套件（{@code com.example}），只用公開 API：
 * 以 {@link PlayerDataStores#fromDataStore} 把檔案後端包成逐玩家存取；
 * 以 {@link PlayerDataStore#applyIfRevision} 做有條件寫入（{@code revision}
 * 不符不寫並回報）；以 {@link PlayerDataStore#readCheckApply} 把讀取、檢查、
 * 套用放在同一個原子區段；以
 * {@link PlayerDataService#getOfflineDataAsync} 在不阻塞呼叫端執行緒的情況下
 * 讀取離線玩家資料。</p>
 *
 * <p>全部進入點都不需要 Bukkit 伺服器，可在單元測試以臨時目錄實際執行。</p>
 *
 * <p><strong>檔案轉接的 revision 限制</strong>：{@link #fileBackedStore} 回傳的是
 * 檔案後端的轉接（{@code fromDataStore} 包裝），它的 revision 只存在於
 * <strong>該轉接實例的記憶體</strong>，且是 per-player 粗粒計數
 * （該玩家任一欄位寫入都推進，不分欄位）。重建轉接（例如重啟）後歸零：
 * 對已有資料的玩家以舊 {@code expectedRevision}（例如 0）呼叫
 * {@code applyIfRevision} 會誤判套用、重複寫入，不可用作跨重啟的發放冪等。
 * 需要持久化、per-field 的 revision 條件寫入時，改用
 * {@link PlayerDataStores#sqlite}／{@code jdbc} 後端。</p>
 */
public final class PlayerStoreV150Example {

    /** 定期保存週期（毫秒）：範例用一分鐘，呼叫端可另行關閉服務。 */
    private static final long SAVE_INTERVAL_MILLIS = 60_000L;

    private PlayerStoreV150Example() {
    }

    /**
     * 以檔案後端建立逐玩家 store（已初始化，可直接使用）。
     *
     * <p>回傳的是檔案轉接：revision 只活在該轉接實例的記憶體，
     * 重建後歸零（見類別說明）。用畢呼叫回傳 store 的 {@code close()}
     * 即可；轉接的 {@code close()} 不代關底層檔案 delegate，
     * 而本範例的 delegate 每次寫入即落盤、不持有開啟中的控制代碼，
     * 因此無需額外關閉。長駐服務請改用
     * {@link PlayerDataStores#sqlite}／{@code jdbc} 後端並依其生命週期管理。</p>
     *
     * @param directory 資料目錄；不可為 null
     * @param fileName 資料檔名稱；不可為 null 或空白
     * @return 已初始化的逐玩家 store；永不為 null
     */
    public static PlayerDataStore fileBackedStore(Path directory, String fileName) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(fileName, "fileName");
        JsonFileDataStore delegate = new JsonFileDataStore(
            "example-player-data", directory.resolve(fileName),
            SchemaVersion.V1_0, new JsonCodecImpl());
        delegate.init();
        return PlayerDataStores.fromDataStore(delegate);
    }

    /**
     * 為逐玩家 store 建立服務（含定期保存）。
     *
     * <p>I/O executor 用呼叫端直跑（{@code Runnable::run}），只適用測試與
     * 開機初始化：呼叫端不等待、不阻塞，因此可在測試主執行緒呼叫。
     * 實戰（尤其 region 執行緒）要換成真正的背景 I/O 執行緒，
     * 不可在玩家所在執行緒上阻塞等待。</p>
     *
     * @param store 已初始化的逐玩家 store；不可為 null
     * @return 玩家資料服務；永不為 null（用畢請呼叫 {@code shutdown}）
     */
    public static PlayerDataService openService(PlayerDataStore store) {
        Objects.requireNonNull(store, "store");
        Executor direct = Runnable::run;
        return new PlayerDataService(store, direct, SAVE_INTERVAL_MILLIS);
    }

    /**
     * 有條件發幣：只有目前 {@code revision} 符合預期才寫入。
     *
     * <p>{@code expectedRevision} 必須是<strong>同一個轉接實例</strong>回報的
     * {@code currentRevision}（重試時用失敗回傳裡帶回的實際值）。
     * 檔案轉接重建後 revision 歸零，沿用重啟前的值會誤判套用、重複發放；
     * 跨重啟的發放冪等請用持久化後端（見類別說明）。</p>
     *
     * @param store 逐玩家 store；不可為 null
     * @param uuid 玩家 UUID；不可為 null
     * @param amount 發放數量
     * @param expectedRevision 預期的目前 {@code revision}；必須 {@code >= 0}
     * @return 寫入結果（不符時未寫入，並帶回實際值供重試）；永不為 null
     */
    public static ConditionalWriteResult grantCoinsIfExpected(PlayerDataStore store,
            UUID uuid, int amount, long expectedRevision) {
        Objects.requireNonNull(store, "store");
        return store.applyIfRevision(uuid, "coins", amount, expectedRevision);
    }

    /**
     * 只發一次的獎勵：該玩家尚未領過才寫入，領過就拒絕且不留修改。
     *
     * @param store 逐玩家 store；不可為 null
     * @param uuid 玩家 UUID；不可為 null
     * @param bonus 獎勵數量
     * @return 套用結果；永不為 null
     */
    public static ReadCheckApplyResult grantBonusOnce(PlayerDataStore store,
            UUID uuid, int bonus) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(uuid, "uuid");
        return store.readCheckApply(uuid,
            current -> current.map(record -> record.get("bonus") == null).orElse(true),
            List.of(FieldChange.upsert(uuid, "bonus", bonus)));
    }

    /**
     * 非同步讀取離線玩家資料（從未寫入過時完成為 empty）。
     *
     * @param service 玩家資料服務；不可為 null
     * @param uuid 玩家 UUID；不可為 null
     * @return 讀取結果的 future；永不為 null
     */
    public static CompletableFuture<Optional<Record>> readOffline(
            PlayerDataService service, UUID uuid) {
        Objects.requireNonNull(service, "service");
        return service.getOfflineDataAsync(uuid);
    }

    /**
     * 讀取目前金幣餘額（離線讀取的同步便利包裝，純展示用）。
     *
     * @param service 玩家資料服務；不可為 null
     * @param uuid 玩家 UUID；不可為 null
     * @return 金幣餘額；無資料時為 0
     * @throws Exception 當讀取失敗
     */
    public static int coinBalance(PlayerDataService service, UUID uuid) throws Exception {
        Objects.requireNonNull(service, "service");
        Optional<Record> loaded = service.getOfflineDataAsync(uuid).get();
        return loaded.map(record -> {
            Object coins = record.get("coins");
            return coins instanceof Number number ? number.intValue() : 0;
        }).orElse(0);
    }

    /**
     * 檢查獎勵是否已發放（讀檢查語意的展示：通過才算數）。
     *
     * @param outcome 讀檢查套用的結果；不可為 null
     * @return 檢查通過且已套用時為 true
     */
    public static boolean bonusGranted(ReadCheckApplyResult outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return outcome.outcome() == Outcome.APPLIED;
    }
}
