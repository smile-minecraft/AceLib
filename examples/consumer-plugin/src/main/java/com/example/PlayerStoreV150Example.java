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
 */
public final class PlayerStoreV150Example {

    /** 定期保存週期（毫秒）：範例用一分鐘，呼叫端可另行關閉服務。 */
    private static final long SAVE_INTERVAL_MILLIS = 60_000L;

    private PlayerStoreV150Example() {
    }

    /**
     * 以檔案後端建立逐玩家 store（已初始化，可直接使用）。
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
