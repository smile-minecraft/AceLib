# 玩家資料與 session

> 適合要管理玩家載入、session 與離線保存的插件開發者。


`PlayerDataService` 在玩家加入時載入資料、維護 session，並在離線或服務關閉時保存 dirty record。它需要一個已初始化的 `DataStore` 與 I/O executor。

目前 AceLib 不會透過 `AceLibApi` 提供 `PlayerDataService`。只有已在自己的組裝層取得 `DataStore` 的 consumer 才能建立它。

同一個模組另有 `PlayerCooldownService`，處理與指令系統無關的玩家冷卻，可獨立於 `DataStore` 建立。

## 目錄

- [建立服務](#建立服務)
- [Join、讀寫與 quit](#join讀寫與-quit)
- [保存失敗與重登恢復](#保存失敗與重登恢復)
- [Reload 與在線玩家重接](#reload-與在線玩家重接)
- [玩家冷卻](#玩家冷卻)
- [關閉服務](#關閉服務)
- [相關頁面](#相關頁面)

## 建立服務

```java
DataStore store = providedStore;
store.init();

Executor ioExecutor = Executors.newFixedThreadPool(2);
PlayerDataService players = new PlayerDataService(store, ioExecutor);
```

Store 尚未初始化時，建構會以 `ACELIB-PLAYER-006` 失敗。

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

## 保存失敗與重登恢復

`onPlayerQuit(uuid)` 保存失敗時，future 以 `ACELIB-PLAYER-003` 失敗完成，但 session 不會卡在 `UNLOADING`：它會轉為 `ENDED` 並從 registry 移除，後續 join 不再被 `ACELIB-PLAYER-004` 永久拒絕。未落地的 dirty 資料保留在服務快取中，重登時以「遺留整體採用」合併回新載入的資料：保留快照整體覆寫（含 quit 失敗前已 `remove` 的 key 不會因 store 舊值復活）並保持 dirty，下一次 quit 或 `shutdown()` 會重試保存。

保存仍在進行（`UNLOADING`）時重連，不會同步拋出：新 join 會鏈接舊 quit 的 future，quit 完成（成功或保存失敗皆可）後自動重試建立 session，全程 future 鏈接、不需手動再 join；同 UUID 併發重連共享單一 pending chain。等待期間遇到 `shutdown()`，重試以 `ACELIB-PLAYER-007` 失敗，不建立 session。只有「沒有 quit 進行中」的重複 join 才會同步拋 `ACELIB-PLAYER-004`。join／quit 的同步拒絕（`ACELIB-PLAYER-004`／`005`／`007`）與非同步失敗（`ACELIB-PLAYER-002`／`003`）都會以 `ACELIB-PLAYER` 分類記入 logger，不會從 Bukkit event handler 向外拋出。

`withLoadedData(uuid, callback)` 在 session 已 `READY` 時，直接於 caller 所在執行緒執行 callback；仍在 `LOADING` 時才在 I/O executor 上輪詢等待（最多 5 秒，逾時以 `ACELIB-PLAYER-001` 失敗）。callback 內若要操作 Bukkit 玩家或世界物件，仍須用[安全排程](scheduler.md)送回正確上下文，因為 callback 執行緒不等於玩家所在 region。

`shutdown()` 只終止內部 serial store executor；外部注入的 I/O executor 不會被關閉，呼叫端自行管理其生命週期。AceLib 內部裝配的自建 pool 由 plugin 集中管理：成功 reload、`onDisable` 與 INCOMPATIBLE 降級都會在舊服務 shutdown 之後關閉舊 pool 並重建。

## Reload 與在線玩家重接

AceLib 成功 reload 時，舊 `PlayerDataService` 先 shutdown（dirty 已 flush 回 store），再建立新服務；在線玩家會在新服務重建 session，reload 回傳時 `getData`、`markDirty` 與 quit 保存皆可直接使用。reload 前已離線的玩家不會被重建。單一玩家重建失敗只記錄警告，不影響 reload 整體成功。

重建後的載入等待以共用總時限為界（預設 10 秒，不隨在線人數成長）：全部載入完成或總時限耗盡即回傳，未完成的玩家各記一則 `ACELIB-PLAYER-002` 逾時警告，reload 照常成功；該玩家下次 join／quit 時走正常生命週期重建。

已知空窗：舊 player listener 解除到新 listener 註冊之間有短暫空窗；恰好落在空窗內的 join 不會建立 session、quit 會以 `ACELIB-PLAYER-005` 記警告（無 session 可結束）。已 flush 回 store 的資料不受影響，不造成資料遺失；Folia 上跨 region 的事件時序較容易遇到此空窗。

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

本服務只在 quit 與 shutdown 時保存，沒有自動保存；程序崩潰時未落盤的 dirty 資料會遺失，不提供崩潰復原。

Future callback 的執行緒不等於玩家所在 region。若 callback 要操作 Bukkit 玩家或世界物件，請再用[安全排程](scheduler.md)送回正確上下文。完整代碼見[錯誤碼](../reference/error-codes.md)。

## 相關頁面

- [資料儲存](data.md)
- [基岩版玩家](bedrock.md)
- [錯誤碼](../reference/error-codes.md)
