# 玩家資料與 session

> 適合要管理玩家載入、session 與離線保存的插件開發者。


`PlayerDataService` 在玩家加入時載入資料、維護 session，並在離線或服務關閉時保存 dirty record。它需要一個已初始化的 `DataStore` 與 I/O executor。

目前 AceLib 不會透過 `AceLibApi` 提供 `PlayerDataService`。只有已在自己的組裝層取得 `DataStore` 的 consumer 才能建立它。

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

## 關閉服務

```java
players.shutdown();
```

`shutdown()` 會停止新工作、等待進行中的操作、保存 dirty record 並清除 session，可重複呼叫。關閉後的 join 或 quit 會以 `ACELIB-PLAYER-007` 拒絕。

Future callback 的執行緒不等於玩家所在 region。若 callback 要操作 Bukkit 玩家或世界物件，請再用[安全排程](scheduler.md)送回正確上下文。完整代碼見[錯誤碼](../reference/error-codes.md)。

## 相關頁面

- [資料儲存](data.md)
- [基岩版玩家](bedrock.md)
- [錯誤碼](../reference/error-codes.md)
