# 資料儲存

> 適合要描述資料儲存初始化、遷移與持久化的插件開發者。


`DataStore` 是 AceLib 的公開儲存介面，支援初始化、schema 遷移、同步保存、非同步工作與關閉。

目前的 `AceLibApi` 不會直接提供 `DataStore`。內建的 JSON 與 JDBC 實作屬於內部組裝類別；一般 consumer 只有在自己的整合層提供 `DataStore` instance 時，才應使用本頁 API。不要直接依賴 `JsonFileDataStore` 或 `JdbcDataStore`。

## 使用已提供的 store

```java
DataStore store = providedStore;
store.init();

Record root = store.root();
root.set("user.balance", 12345);
store.save();
```

`root()` 只能在 `init()` 成功後使用。修改會留在記憶體中，直到呼叫 `save()` 或 `flush()`。

## 非同步工作與關閉

```java
CompletableFuture<Void> saved = store.submit(executor, () -> {
    store.root().set("user.xp", 100);
    store.save();
    return null;
});

store.flush();
store.close();
```

`flush()` 會等待已提交的非同步工作並寫回資料。`close()` 會 flush 後釋放資源，可重複呼叫；關閉後再操作會得到 `ACELIB-DATA-005`。

同步 `root()` 與 `save()` 的並行安全由呼叫端負責。不要從多個未協調的執行緒同時修改同一個 store。

## JDBC store 與 MySQL / MariaDB

`JdbcDataStore` 以標準 JDBC 寫入關聯表，不依賴 ORM。表形狀為
`(store_name, k_hash, k, v)`、主鍵 `(store_name, k_hash)`：
`k_hash` 是 key 的 SHA-256 hex（固定 64 字元），主鍵最壞
`(255 + 64) * 4 = 1276 bytes`，在 utf8mb4 的 InnoDB 索引上限
（3072 bytes）之內；完整 key 存於 `k`（TEXT），無 key 長度上限。
MySQL / MariaDB 建表時自動加上 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4`，
其他資料庫維持可攜寫法。

舊版形狀 `(store_name, k, v)` 的表在 `init()` 時自動升級：
讀整張舊表全部 store 的舊資料 → 只有本 store 跑版本檢查與資料遷移
（舊表不動，其他 store 的列原樣保留：`store_name`/`k`/`v` 不變，
`k_hash` 由 `k` 重算，不跑遷移）→ 一起寫入暫存表並讀回驗證
（本 store 筆數與版本一致，且暫存表總筆數涵蓋整張舊表，
否則刪暫存表、舊表保留）→ 刪除舊表 → 暫存表改名。
其他 store 的版本列也原樣保留，等它們各自 `init()` 時再走新形狀路徑遷移。
`init()` 全程只用一個連線；SQL 失敗以
`ACELIB-DATA-008` 回報，訊息前綴標示階段（`[jdbc:create-table]`、
`[jdbc:read]`、`[jdbc:migrate-table]`、`[jdbc:write]`、`[jdbc:save]`、
`[jdbc:init]`）。表名需符合 `[A-Za-z_][A-Za-z0-9_]*`
（不符為 `ACELIB-DATA-011`），且在 MySQL / MariaDB 上連同升級後綴
（`__acelib_migrate`）不得超過 64 字元識別字上限。

升級中斷（舊表已刪、改名未完成）會在下次 `init()` 開頭、建表之前先復原：
只剩暫存表時接續改名，雙表並存時丟棄暫存表後重新升級。
復原必須先於建表，否則新建的空正式表會讓復原誤判而刪掉唯一副本。

併發限制：同一 JVM 內同表的併發 `init()` 已序列化；
跨行程或跨主機共享同一張表時，呼叫端必須在外部序列化 `init()`
（同一時間只有一個升級在跑），否則 `DROP` 加 `RENAME` 會互相踩踏丟資料。

## Schema 遷移

實作 `DataMigration`，提供來源版本、目標版本與轉換內容，再於 `init()` 前呼叫 `registerMigration(...)`。遷移鏈中任何一步失敗時，初始化會以 `ACELIB-DATA-004` 失敗，既有資料不應被部分覆寫。

每一步的寫入視圖都是當下最新狀態的隔離拷貝。`MemoryRecord`（生產路徑的 JSON 與 JDBC store 一律使用它）會先遞迴重建巢狀 `Map` 與 `List` 再交給 migration 修改，失敗時連同之前步驟一起丟棄，呼叫端原本的視圖維持原值。第三方 `Record` 實作依其自身 `copy()` 約定隔離，不可無條件視為深拷貝。`Record.copy()` 本身維持淺拷貝，隔離只在遷移鏈內部執行，不會改變合法值的實際型別。

On-disk schema 比程式支援的版本新時，store 會拒絕降版寫入。完整錯誤查表見[錯誤碼](../reference/error-codes.md)。玩家資料服務建立在這個介面上，請看[玩家資料](player.md)。

## 逐玩家儲存 `PlayerDataStore`

`PlayerDataStore` 是另一位玩家一組頂層欄位、由 store 自己負責增量落盤的專用介面，
與 `DataStore`（單一 store、整棵 record tree 由呼叫端組裝）並行存在。以
`PlayerDataStores` 工廠取得實作：

```java
// 預設後端：單一 SQLite 檔案承載全部玩家資料
PlayerDataStore store = PlayerDataStores.sqlite(Path.of("players.db"), SchemaVersion.V1_0);
store.init();

// 可換成 MySQL / MariaDB：DataSource（含帳密與連線池）由呼叫端管理，store 不會關閉它
PlayerDataStore remote = PlayerDataStores.jdbc(dataSource, SchemaVersion.V1_0);
remote.init();

// 遷移期間沿用既有 DataStore（例如現有的 JsonFileDataStore）
PlayerDataStore wrapped = PlayerDataStores.fromDataStore(existingStore);
```

`owner` 與玩家 UUID、欄位名一起構成主鍵，讓多個下游能在同一張資料表各自存放資料
而不互相覆寫；AceLib 內建玩家資料服務使用 `PlayerDataStores.DEFAULT_OWNER`。
`fromDataStore(...))` 要求 delegate 已 `init()`，否則以 `ACELIB-DATA-005` 失敗。

`load(uuid)` 是離線讀取的主要入口：不需要該玩家有 active session，從未寫入任何欄位
時回傳 empty。`close()` 冪等；關閉後操作以 `ACELIB-DATA-005` 拒絕。

### SQLite 後端的耐久性

SQLite 檔案以 WAL 模式開啟（讀寫不互相阻塞）、`synchronous=FULL`（每次交易提交都
fsync）、`busy_timeout=5000`。WAL 會在檔案旁產生 `-wal`／`-shm` 附檔；要取一致
快照做備份必須用 `VACUUM INTO` 或 SQLite 的備份 API，直接複製 `.db` 會遺失尚未
checkpoint 的頁。`init()` 需要 `org.xerial:sqlite-jdbc` 在 classpath 上，缺席時以
`ACELIB-DATA-012` 失敗；plugin 由 `plugin.yml` 的 `libraries:` 在啟動時下載。

### 逐玩家表的形狀與增量寫入

`JdbcPlayerDataStore` 與 `SqlitePlayerDataStore` 共用同一份 SQL 骨架：

```
acelib_player_data (
    player_uuid CHAR(36)  NOT NULL,
    owner       VARCHAR(255) NOT NULL,
    field_hash  CHAR(64)  NOT NULL,   -- 欄位名的 SHA-256 hex
    field       VARCHAR(255) NOT NULL, -- 完整欄位名
    payload     {MEDIUMTEXT | TEXT} NOT NULL,
    schema_ver  VARCHAR(16) NOT NULL,
    revision    BIGINT     NOT NULL,
    updated_at  BIGINT     NOT NULL,
    PRIMARY KEY (player_uuid, owner, field_hash)
)
```

主鍵最壞位元組數在 utf8mb4 下為 `36 + 255*4 + 64*4 = 1352` bytes，遠低於 InnoDB
索引上限 3072。主鍵最左前綴即 `(player_uuid, owner)`，已覆蓋「讀某玩家全部欄位」
與「列出某 owner 的全部玩家」，不需額外索引。

MySQL／MariaDB 的 `payload` 使用 `MEDIUMTEXT`，單欄位上限為 16,777,215 bytes（約 16 MB）；
SQLite 維持 `TEXT`。原先使用的 MySQL `TEXT` 上限只有 65,535 bytes，玩家資料經 JSON
編碼後稍大的值就會失敗，因此採用 `MEDIUMTEXT` 避免 64 KiB 陷阱，同時保留明確的容量上限。
上限以編碼後的 payload bytes 計算，不是原始字串字元數。

`applyChanges(List<FieldChange>)` 只寫有變動的欄位：值與現有內容相同的 upsert 不
產生寫入、也不推進 `revision`（`revisionOf(uuid, field)` 可用來觀察哪些欄位真的被
寫過）。欄位從資料中消失時以 `FieldChange.deletion(uuid, field)` 明確刪除，store 不
留孤兒列。整批變更在**單一交易**內套用：任一筆失敗即整批 rollback，既有資料維持變更
前的內容（`ACELIB-DATA-008`）。每次實際變動的欄位寫入都讓 `revision` 加一：新列從 1
開始，同值重寫不算寫入；同一批次重複指定欄位時依最後一筆為準，收斂後只推進一次。
upsert 不使用 `ON DUPLICATE KEY UPDATE` 等 vendor 專屬語法，改以先 `INSERT`、遇到主鍵
衝突再 `UPDATE` 達成，保持 SQL 可攜。這也避免 InnoDB `REPEATABLE READ` 下先刪除不存在
的主鍵會取得 gap lock，與並行插入互相等待而形成死結。

併發限制與通用 JDBC store 相同：`init()` 在同一 JVM 內已序列化；跨行程同時寫同一張
表仍需呼叫端自行序列化，本介面不宣稱跨行程安全。介面本身不保執行緒安全，呼叫端必須
自行序列化所有操作（AceLib 內部由 `PlayerDataService` 的 per-store serial executor
保證）。

### DataStore 轉接的失敗回復範圍

`PlayerDataStores.fromDataStore(...)` 包出來的轉接在改動記憶體樹之前，
先對受影響玩家的欄位與 `revision` 做快照。`save()` 失敗時把記憶體樹與
`revision` 還原為操作前，再以 `ACELIB-DATA-008` 回報（含原始原因）；
還原本身失敗時，原因掛在回報例外的 `suppressed`，訊息標示還原不完整。

這個保證只涵蓋 AceLib 自己的記憶體視圖：delegate 的 `save()` 若已部分落盤，
不可逆；失敗不自動重試，也不承諾跨伺服器或跨行程原子性。`revision` 只放在
轉接實例的記憶體裡，重建轉接即歸零。還原以整節點深拷貝寫回，因此頂層 `null`
值、空玩家節點與字面點號鍵都能回到原始結構。

### 條件寫入與讀檢查套用

兩個寫入路徑同時改同一個欄位時，先讀後寫會互相覆寫。用欄位 `revision`
當條件，一次只讓一個成功：

```java
PlayerDataStore.ConditionalWriteResult result =
    store.applyIfRevision(uuid, "balance", 130, expectedRevision);
if (!result.applied()) {
    // 別人先寫了：用回報的實際 revision 重讀、重算、重試（本方法不自動重試）
    long actual = result.currentRevision();
}
```

`applyIfRevision(uuid, field, value, expectedRevision)` 只有當該欄位目前的
`revision` 等於期望值時才寫入，成功後 `revision` 遞增。不存在的欄位視為
`0`，因此期望 `0` 即「不存在才建立」。條件不符不寫入，回傳 `applied=false`
與實際 `revision`。條件相符即視為一次寫入（即使值相同也推進 `revision`，
與 `applyChanges` 的同值跳過不同）。`value` 不可為 `null`；刪除欄位仍用
`FieldChange.deletion` 搭配 `applyChanges`。

```java
PlayerDataStore.ReadCheckApplyResult done = store.readCheckApply(uuid,
    present -> present.isPresent() && present.get().getInt("balance", 0) >= 10,
    List.of(PlayerDataStore.FieldChange.upsert(uuid, "balance", 5)));
// done.outcome() 為 APPLIED 或 CHECK_REJECTED
```

條件寫入、讀檢查套用與離線讀取的完整可編譯寫法見 [`PlayerStoreV150Example`](../../examples/consumer-plugin/src/main/java/com/example/PlayerStoreV150Example.java)（可照抄的路徑是 sqlite 後端；檔案轉接只作教材，限制見該檔 Javadoc）。

`readCheckApply(uuid, check, changes)` 把讀取、判定、套用放在同一個原子區段：
以剛讀到的資料執行 `check`，通過才套用 `changes`。檢查不通過回傳
`CHECK_REJECTED` 且不做任何修改；基礎設施失敗（`ACELIB-DATA-008`）整批回滾，
同樣不留部分修改。`check` 在交易內執行，必須是純判定：不可呼叫 store 的任何
方法，也不要有外部副作用；`check` 自己拋的例外在回滾後原樣傳遞。
`changes` 必須全屬同一個 `uuid`（空批次只做檢查）。

退回一覽：條件不符或檢查不通過是正常回傳（不是例外）；已關閉為
`ACELIB-DATA-005`；交易失敗為 `ACELIB-DATA-008`；`null` 參數為
`NullPointerException`，空白欄位名、負數期望值、跨玩家批次為
`IllegalArgumentException`；不支援型別的值為 `ACELIB-DATA-006`。
外部自備的 `PlayerDataStore` 實作可以不實作這兩個方法，未覆寫時預設拋
`UnsupportedOperationException`（明確拒絕，不以先讀後寫假裝原子）。

限制（各後端相同）：單一儲存行程內的單一交易，不保證跨伺服器或跨行程的原子
性，也不做自動重試。保證的只是**單一呼叫內部**的讀寫一致與不留部分修改：
條件檢查與寫入（或讀取、判定、套用）在同一交易／監視器內完成。**同一玩家上
的併發呼叫不互斥**——兩個同時進行的呼叫可能基於同一個舊 `revision` 或同一份
舊資料各自判定，呼叫端必須序列化所有操作（AceLib 內部由 `PlayerDataService`
的 per-store serial executor 保證），或拿 `applied=false` 與實際 `revision`
自行重試。併發測試通過（恰好一個成功）證明的是失敗側誠實回報、成功側完整寫
入，不代表呼叫端可以省略序列化。JDBC／SQLite 的 `revision` 存在資料表裡，關閉重開後保留；
轉接的 `revision` 只放在轉接實例的記憶體裡，重建即歸零，而且是 per-player
粗粒計數（任一欄位寫入都會推進該玩家所有欄位可見的值），精度不如 JDBC／SQLite
的 per-field 計數。呼叫端的執行緒序列化義務與 `applyChanges` 相同。

### 既有玩家資料的轉換

`PlayerDataConverter` 把舊版 JSON 檔（`player-data.json`）或舊 `acelib_data_kv` 的
`players` 列轉換成逐玩家 store，並在 `backup/` 留下備份與校驗報告。流程同步完成於
服務啟動前。轉換失敗時 plugin 以 `ACELIB-PLAYER-006` 記錄並不啟動玩家資料服務，
避免玩家以空資料建立新紀錄；來源與已建立的備份保留，修復後可安全重跑。

轉換只**補目標缺少的玩家**，來源檔案與來源資料表從不被修改或刪除。因此中斷後重跑是
安全的（只補缺行），轉換後才發生的現場資料永遠勝出。校驗會統計來源玩家數、完整比對
本次待匯入玩家的欄位與內容雜湊；既有玩家只確認仍有資料，不拿舊來源覆核現場更新。
轉換結果的 `convertedPlayers` 只計入實際有欄位寫入的玩家，空紀錄不計。校驗不通過以
`ACELIB-DATA-013` 失敗並在報告檔標出失敗玩家；來源保持不動，規則對 SQLite 與 MySQL
相同。

## record 資料模型編解碼

`PlayerDataCodec<T>` 在資料模型（record）與頂層欄位之間轉換：AceLib 內建
`RecordPlayerDataCodec` 以 record component 名對映欄位。資料與模型不符（型別錯誤、
巢狀節點缺形狀）時 `decode` 以 `ACELIB-DATA-002` 失敗並在訊息帶出問題欄位，不猜、
不填預設值；單純「欄位不存在」不算錯誤，依 component 型別取預設值（primitive 為型別
零值、參考型別為 `null`）。下游要改用別的映射規則（別名、加密欄位）可自行實作本
介面注入服務。

## 空值與路徑

含 JSON `null` 的資料載入後不會讓存檔失敗。`has` 對 `null` 條目視為不存在，`get` 回傳 `null`，存檔時仍以 JSON `null` 落盤，重新載入後結果一致。

所有路徑方法對 `null` 或空白路徑都會回報 `ACELIB-DATA-003`，包含 `getRecord`。路徑不存在時，有預設值回傳預設值，沒有預設值回傳 `null`，不會拋錯。

## 原子寫入失敗時

寫入一律先寫暫存檔、落盤後再取代目標檔。暫存檔寫入、落盤或搬移失敗時，原檔維持不變並回報 `ACELIB-DATA-001`；暫存檔會盡力清除，清除本身失敗時會掛在原始錯誤的 `suppressed`，不會蓋掉原本的寫入原因。`close()` 內寫入失敗不會拋錯，但會以 `ACELIB-DATA-001` 分類記入日誌。

## 相關頁面

- [玩家資料與 session](player.md)
- [設定檔](config.md)
- [錯誤碼](../reference/error-codes.md)
