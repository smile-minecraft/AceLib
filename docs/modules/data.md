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

## Schema 遷移

實作 `DataMigration`，提供來源版本、目標版本與轉換內容，再於 `init()` 前呼叫 `registerMigration(...)`。遷移鏈中任何一步失敗時，初始化會以 `ACELIB-DATA-004` 失敗，既有資料不應被部分覆寫。

On-disk schema 比程式支援的版本新時，store 會拒絕降版寫入。完整錯誤查表見[錯誤碼](../reference/error-codes.md)。玩家資料服務建立在這個介面上，請看[玩家資料](player.md)。

## 相關頁面

- [玩家資料與 session](player.md)
- [設定檔](config.md)
- [錯誤碼](../reference/error-codes.md)
