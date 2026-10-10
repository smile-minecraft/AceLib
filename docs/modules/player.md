# 玩家資料與 session

> 適合要管理玩家載入、session 與離線保存的插件開發者。


`PlayerDataService` 在玩家加入時載入資料、維護 session，並在離線或服務關閉時保存 dirty record。它需要一個已初始化的 `DataStore` 與 I/O executor。

目前 AceLib 不會透過 `AceLibApi` 提供 `PlayerDataService`。只有已在自己的組裝層取得 `DataStore` 的 consumer 才能建立它。

同一個模組另有 `PlayerCooldownService`，處理與指令系統無關的玩家冷卻，可獨立於 `DataStore` 建立。

## 目錄

- [建立服務](#建立服務)
- [Join、讀寫與 quit](#join讀寫與-quit)
- [資料就緒通知與離線讀取](#資料就緒通知與離線讀取)
- [定期保存與異常終止](#定期保存與異常終止)
- [保存失敗與重登恢復](#保存失敗與重登恢復)
- [Reload 與在線玩家重接](#reload-與在線玩家重接)
- [併發寫入同一欄位](#併發寫入同一欄位)
- [玩家冷卻](#玩家冷卻)
- [關閉服務](#關閉服務)
- [相關頁面](#相關頁面)

## 建立服務

AceLib 內建的玩家資料服務使用逐玩家儲存（預設 SQLite、可換 MySQL），資料模型由
AceLib 以 `PlayerDataCodec` 編解碼：

```java
PlayerDataStore store = PlayerDataStores.sqlite(Path.of("players.db"), SchemaVersion.V1_0);
store.init();

Executor ioExecutor = Executors.newFixedThreadPool(2);
PlayerDataService players =
    new PlayerDataService(store, ioExecutor, PlayerDataService.DEFAULT_SAVE_INTERVAL_MS);
```

Store 尚未初始化時，建構會以 `ACELIB-PLAYER-006` 失敗。第三個參數是定期保存週期
（毫秒，預設 `DEFAULT_SAVE_INTERVAL_MS`）；另有第四參數的建構子可自訂單批 flush 的
等待上限，逾時語意與第三參數版本相同。舊的 `DataStore` 建構子保留，行為不變。

plugin 啟動即建立這個服務（SQLite 檔案為 `plugins/AceLib/players.db`）；舊版
`player-data.json` 若尚未轉換，會在服務啟動前同步轉換，並在 `backup/` 留下來源備份
與校驗報告。轉換失敗時以 `ACELIB-PLAYER-006` 記錄並
停用玩家資料服務，不讓玩家登入後以空資料建立新紀錄；plugin 其餘功能仍可啟動。
來源與已產生的備份不會修改或刪除，修復原因後重新啟動或 reload 可重跑轉換。

## Join、讀寫與 quit

```java
UUID uuid = player.getUniqueId();

players.onPlayerJoin(uuid, player.getName())
    .thenRun(() -> {
        // session 已進入 READY
    });

players.withLoadedData(uuid, record -> {
    int balance = record.getInt("balance", 0);
    record.set("balance", balance + 10);
    players.markDirty(uuid);
    return null;
});

players.onPlayerQuit(uuid);
```

Session 依序經過 `LOADING`、`READY`、`UNLOADING`、`ENDED`。`getData(uuid)` 在尚未就緒或找不到 session 時回傳 empty。修改資料後要呼叫 `markDirty(uuid)`，否則 quit 不會因該修改觸發保存。

## 登入前預載

插件在 `AsyncPlayerPreLoginEvent` 以 UUID 與名稱快照呼叫既有的 `PlayerDataService.onPlayerJoin`。
該事件在非同步執行緒執行；handler 不讀取 `Player`、世界或實體，只讓服務透過既有 I/O executor
與序列化 store executor 載入，並沿用同一個 `LOADING → READY` session。資料可在
`PlayerJoinEvent` 前就緒並通知 `PlayerDataReadyListener`。

`PlayerJoinEvent` 會接手已建立的預載 session，不再讀一次 store。若沒有可接手的預載、預載載入
失敗，或預載失敗與 join 同時發生，join 會在預載 session 清理後回退到原本的載入流程；預載錯誤
不會單獨拒絕登入。store 中沒有該玩家資料時，既有載入語意仍提供空 record。

成功預載後若 30 秒內沒有 join，服務會透過原本的 quit 流程結束該 session，清掉玩家資料與差分快取，
避免登入中斷留下長期狀態。停用或 reload 會解除整個 lifecycle listener、取消清理排程並關閉舊服務；
關閉後到達的預載會以 `ACELIB-PLAYER-007` 拒派。快速重連仍由 `pendingQuits` 的單一重試鏈處理。

## 資料就緒通知與離線讀取

資料載入完成、session 轉為 `READY` 後，服務會依序通知註冊的
`PlayerDataReadyListener`；同一玩家的同一次載入只通知一次，載入失敗則不通知（失敗以
`onPlayerJoin` 的 future 表達）。回呼在服務的 I/O executor 執行緒上被呼叫，不是主執行
緒也不是 region 執行緒；要操作玩家、實體或世界必須再用[安全排程](scheduler.md)送回
正確上下文。單一 listener 拋錯只記 `ACELIB-PLAYER-009`，不影響 session 與其他
listener。回呼 handle 的 `close()` 可解除註冊。

這不是 Bukkit Event：I/O executor 不在主執行緒也不在任何 region，Bukkit 同步 Event 的
dispatch 規則（主執行緒／region 執行緒）都不適用，因此以 SPI 表達，執行緒語意由
呼叫端清楚掌握。

```java
handle = players.addReadyListener((uuid, record) -> {
    // 在 I/O executor 上：資料已就緒
});
handle.close(); // 不再收到後續通知
```

`getOfflineData(uuid)` 讀取離線玩家資料，不需要該玩家有 active session；資料來自 store
的實際內容，刪除過的欄位不會在此復活，也不會改寫活躍玩家的差分基準快取。讀取在內部
serial store executor 上執行、呼叫端同步等待，因此不應在 region 執行緒上呼叫。以舊 `DataStore` 建構的服務沒有
逐玩家 store，此方法以 `ACELIB-PLAYER-006` 失敗。

需要在 region 執行緒（例如事件處理中）讀取時，用 `getOfflineDataAsync(uuid)`：
語意與同步版一致，但呼叫端不等待、讀取完成時 future 完成。同玩家在線時拿到的是
已持久化內容，session 內尚未落盤的變更不在內，不得把結果當成最新資料。
呼叫端取消回傳的 future 不會中止讀取（讀取照跑、結果丟棄）；`shutdown()` 強制終止
executor 時尚未完成的讀取以 `ACELIB-PLAYER-008` 完成，不會永久 pending。
失敗以 future 的 exceptional 完成表達：服務已關閉為 `ACELIB-PLAYER-007`、
舊 `DataStore` 建構為 `ACELIB-PLAYER-006`、內部 executor 已終止或派送被拒為
`ACELIB-PLAYER-008`、讀取失敗為 `ACELIB-PLAYER-002`。`uuid` 為 null 時同步拋
`NullPointerException`。

```java
players.getOfflineDataAsync(uuid).thenAccept(offline -> {
    // callback 執行緒不保證（future 已完成後才註冊回呼時，回呼可能直接在呼叫端執行）；
    // 要操作玩家或世界，先用安全排程送回正確上下文
});
```

## 定期保存與異常終止

服務建構時即啟用定期保存（週期為第三個建構參數），每個週期把全部 dirty record
寫回 store。週期性失敗只記 `ACELIB-PLAYER-009`，不中斷排程：下一個週期會重試，
未落盤的資料仍保留在快取中。

因此異常終止（程序被殺、伺服器崩潰）時，資料遺失上限為**一個保存週期內的變更量**，
不含已成功落盤的內容。保存失敗不會把 dirty 標記為已清除：序號只在確認落盤之後才推進，
失敗的批次在下次週期或 quit／shutdown 時完整重試。要在本機驗證這個上限，可執行
`scripts/player-store-crash-test.sh`：它以獨立 JVM 跑真服務與真 SQLite store，
再 `SIGKILL` 直接殺掉行程（不走 shutdown／flush），重開資料檔比對遺失筆數。

需要每次變更都立即落盤時，仍應在重要時機明確 `onPlayerQuit(uuid)` 或
`shutdown()`，不要依賴週期保存。

## 保存失敗與重登恢復

`onPlayerQuit(uuid)` 保存失敗時，future 以 `ACELIB-PLAYER-003` 失敗完成，但 session 不會卡在 `UNLOADING`：它會轉為 `ENDED` 並從 registry 移除，後續 join 不再被 `ACELIB-PLAYER-004` 永久拒絕。未落地的 dirty 資料保留在服務快取中，重登時以「遺留整體採用」合併回新載入的資料：保留快照整體覆寫（含 quit 失敗前已 `remove` 的 key 不會因 store 舊值復活）並保持 dirty，下一次 quit 或 `shutdown()` 會重試保存。

保存仍在進行（`UNLOADING`）時重連，不會同步拋出：新 join 會鏈接舊 quit 的 future，quit 完成（成功或保存失敗皆可）後自動重試建立 session，全程 future 鏈接、不需手動再 join；同 UUID 併發重連共享單一 pending chain。等待期間遇到 `shutdown()`，重試以 `ACELIB-PLAYER-007` 失敗，不建立 session。只有「沒有 quit 進行中」的重複 join 才會同步拋 `ACELIB-PLAYER-004`。join／quit 的同步拒絕（`ACELIB-PLAYER-004`／`005`／`007`）與非同步失敗（`ACELIB-PLAYER-002`／`003`）都會以 `ACELIB-PLAYER` 分類記入 logger，不會從 Bukkit event handler 向外拋出。

`withLoadedData(uuid, callback)` 在 session 已 `READY` 時，直接於 caller 所在執行緒執行 callback；仍在 `LOADING` 時才在 I/O executor 上輪詢等待（最多 5 秒，逾時以 `ACELIB-PLAYER-001` 失敗）。callback 內若要操作 Bukkit 玩家或世界物件，仍須用[安全排程](scheduler.md)送回正確上下文，因為 callback 執行緒不等於玩家所在 region。

`shutdown()` 只終止內部 serial store executor；外部注入的 I/O executor 不會被關閉，呼叫端自行管理其生命週期。`PlayerDataStore` 的生命週期也由呼叫端管理：`shutdown()` 不會關閉它（關閉後服務已無法 flush），必須在 `shutdown()` 之後另行 `close()`。AceLib 內部裝配的自建 pool 與 store 由 plugin 集中管理：成功 reload、`onDisable` 與 INCOMPATIBLE 降級都會在舊服務 shutdown 之後關閉舊 store 與舊 pool 再重建。

## Reload 與在線玩家重接

AceLib 成功 reload 時，舊 `PlayerDataService` 先 shutdown（dirty 已 flush 回 store）、
舊 store 隨之關閉，再建立新服務；在線玩家會在新服務重建 session，reload 回傳時 `getData`、`markDirty` 與 quit 保存皆可直接使用。reload 前已離線的玩家不會被重建。單一玩家重建失敗只記錄警告，不影響 reload 整體成功。

重建後的載入等待以共用總時限為界（預設 10 秒，不隨在線人數成長）：全部載入完成或總時限耗盡即回傳，未完成的玩家各記一則 `ACELIB-PLAYER-002` 逾時警告，reload 照常成功；該玩家下次 join／quit 時走正常生命週期重建。

已知空窗：舊 player listener 解除到新 listener 註冊之間有短暫空窗；恰好落在空窗內的 join 不會建立 session、quit 會以 `ACELIB-PLAYER-005` 記警告（無 session 可結束）。已 flush 回 store 的資料不受影響，不造成資料遺失；Folia 上跨 region 的事件時序較容易遇到此空窗。

## 併發寫入同一欄位

有 session 的玩家一律走 `getData`／`markDirty`，由服務序列化保存。服務管不到的
寫入路徑（離線玩家、外部工具）若會同時改同一個欄位，直接對 store 用條件寫入：

```java
long expected = store.revisionOf(uuid, "balance");
PlayerDataStore.ConditionalWriteResult result =
    store.applyIfRevision(uuid, "balance", 130, expected);
```

一次只讓一個成功，輸家拿回報的實際 `revision` 重試。需要「先看再決定寫不寫」
時用 `readCheckApply`（讀取、檢查、套用在同一原子區段，檢查不過不留修改）。
語意、退回碼與各後端限制見[資料儲存](data.md)；服務本身不包裝這兩個方法，
有 session 時也不要繞過服務直接寫 store（服務的差分基準看不到外部寫入，
下次保存會蓋掉它）。reload 會關閉舊 store：JDBC／SQLite 的 `revision` 留在
資料表裡，重開後可用；轉接的 `revision` 只在記憶體裡，重建即歸零。

## 玩家冷卻

`PlayerCooldownService` 以玩家 UUID 與自訂 key 管理冷卻，不需要 `DataStore`，可直接建立：

```java
PlayerCooldownService cooldowns = new PlayerCooldownService();

if (!cooldowns.tryAcquire(uuid, "skill.dash", 3000)) {
    return; // 冷卻中
}
cooldowns.remainingMillis(uuid, "skill.dash");
cooldowns.end(uuid, "skill.dash");
```

`start(...)` 會直接覆寫既有冷卻（重新觸發場景），`durationMillis` 必須大於 0，否則以 `IllegalArgumentException` 失敗；`tryAcquire(...)` 的 `durationMillis` 小於或等於 0 時視為無冷卻、一律回 true。`end(...)` 只移除指定 key，`endAll(...)` 移除該玩家的全部 key，`clearAll()` 移除所有玩家的全部冷卻且可重複呼叫。

### 定期清理要由呼叫端安排

`pruneExpired()` 沒有任何自動 caller。AceLib 不會自行排程定時清理，過期紀錄會一直留在 map 裡，直到呼叫端自己呼叫：

```java
cooldowns.pruneExpired();
```

有可清理的 API 不等於累積量自動受到限制；key 種類多、玩家數多的長期運行伺服器，仍要由呼叫端依自己的生命週期安排清理時機（例如 reload 前或定期維護時）。

清理與冷卻寫入不會互相遺失：`start`、`tryAcquire`、`end` 與 `pruneExpired` 都在外層 map 的同一個 `compute`／`computeIfPresent` 內完成，共用同一把 per-player 鎖。`pruneExpired()` 若在表清空時把整張內層 map 從外層摘掉，並發的寫入不會落進沒有人引用的舊表。

`end(...)` 只移除 key，玩家層的 map 即使變空也會保留，要等下一次 `pruneExpired()` 才會被摘掉。`end(...)` 不會順手移除整張表，因此不會出現移除與併發寫入交錯、讓新冷卻寫進已無引用的 map 的情況。

## 關閉服務

```java
players.shutdown();
```

`shutdown()` 會停止新工作、等待進行中的操作、保存 dirty record 並清除 session，可重複呼叫。關閉後的 join 或 quit 會以 `ACELIB-PLAYER-007` 拒絕。

關閉時 N 位 dirty 玩家只觸發一次底層 `save()`：全部 dirty snapshot 先寫回 store，最後落盤一次；沒有 dirty 時完全不呼叫 `save()`。單次 save 失敗即整批視為未落盤：dirty 全保留、`shutdown()` 會回滾關閉旗標並以 `ACELIB-PLAYER-003` 失敗，呼叫端可稍後重試，重試會重新快照並完整重寫。flush 等待逾時（與內部 executor 終止共用同一上限，見原始碼 `SERIAL_EXECUTOR_TERMINATION_MS`）以 `ACELIB-PLAYER-008` 失敗並取消 flush 任務，但底層 `save()` 可能仍在進行 — 逾時不等於 flush 成功，不得把逾時前的資料當成已落盤，dirty 照樣保留、可重試。

本服務在 quit 與 shutdown 時保存，也另有[定期保存](#定期保存與異常終止)；程序崩潰時
未落盤的 dirty 資料會遺失，上限為一個保存週期內的變更量，不提供崩潰復原。

Future callback 的執行緒不等於玩家所在 region。若 callback 要操作 Bukkit 玩家或世界物件，請再用[安全排程](scheduler.md)送回正確上下文。完整代碼見[錯誤碼](../reference/error-codes.md)。

## 相關頁面

- [資料儲存](data.md)
- [基岩版玩家](bedrock.md)
- [錯誤碼](../reference/error-codes.md)
