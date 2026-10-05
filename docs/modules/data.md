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

## 空值與路徑

含 JSON `null` 的資料載入後不會讓存檔失敗。`has` 對 `null` 條目視為不存在，`get` 回傳 `null`，存檔時仍以 JSON `null` 落盤，重新載入後結果一致。

所有路徑方法對 `null` 或空白路徑都會回報 `ACELIB-DATA-003`，包含 `getRecord`。路徑不存在時，有預設值回傳預設值，沒有預設值回傳 `null`，不會拋錯。

## 原子寫入失敗時

寫入一律先寫暫存檔、落盤後再取代目標檔。暫存檔寫入、落盤或搬移失敗時，原檔維持不變並回報 `ACELIB-DATA-001`；暫存檔會盡力清除，清除本身失敗時會掛在原始錯誤的 `suppressed`，不會蓋掉原本的寫入原因。`close()` 內寫入失敗不會拋錯，但會以 `ACELIB-DATA-001` 分類記入日誌。

## 相關頁面

- [玩家資料與 session](player.md)
- [設定檔](config.md)
- [錯誤碼](../reference/error-codes.md)
