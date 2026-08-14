# 玩家狀態與會話

本頁解決如何建立玩家 session、等待資料載入、標記變更並在離線時保存的問題。契約唯一來源是
> `src/main/java/com/smile/acelib/player/**` source 與 tests；
> 本頁為導覽鏡像，不複製完整 JavaDoc。

## 前置條件與最短路徑

準備已 `init()` 的 `DataStore` 與 I/O `Executor`，建構 `PlayerDataService`；在 join、資料就緒與 quit 時依序呼叫對應方法。

## 預期結果

玩家資料依 `LOADING → READY → UNLOADING → ENDED` 管理；標記 dirty 的資料在 quit 或 shutdown 時保存。

## 1. 取得方式

`PlayerDataService` 以「建構注入」方式取得（不經 `AceLibApi` facade）：

```java
import com.smile.acelib.data.DataStore;
import com.smile.acelib.player.PlayerDataService;
import java.util.concurrent.Executor;

DataStore store = /* 已初始化的 DataStore（見 data recipe） */;
Executor ioExecutor = /* I/O executor（daemon 為佳） */;

PlayerDataService players = new PlayerDataService(store, ioExecutor);
```

- 建構時 `store` 必須已 `init()`（否則拋 `PlayerStateException`，
  `ACELIB-PLAYER-006`）。
- 玩家冷卻可獨立建立：`new PlayerCooldownService()`（預設 system clock；
  測試可注入 `Clock`）。

## 2. 最小正確範例

```java
import java.util.UUID;

// 玩家登入（通常在 PlayerJoinEvent 中呼叫）
UUID uuid = player.getUniqueId();
players.onPlayerJoin(uuid, player.getName())
    .thenRun(() -> {
        // 資料已就緒
    });

// 等待資料就緒後執行 callback（非同步）
players.withLoadedData(uuid, record -> {
    record.set("balance", record.getInt("balance", 0) + 10);
    players.markDirty(uuid);            // 標記後 quit 才會保存
    return null;
});

// 讀取當下資料（LOADING 或未登入回 empty）
players.getData(uuid).ifPresent(record -> {
    // record 為執行緒安全包裝，可安全 mutate
});

// 玩家離線（通常在 PlayerQuitEvent 中呼叫；自動保存資料）
players.onPlayerQuit(uuid);

// 關閉服務（disable / reload）：封閉新工作、等待 in-flight、flush、清除
players.shutdown();
```

## 3. 生命週期與執行緒模型

- join：`onPlayerJoin(UUID, String)` 同步建立 session（state=LOADING），
  回傳 `CompletableFuture`；資料載入完成時 future 完成。
- quit：`onPlayerQuit(UUID)` 保存資料並結束 session；玩家離線後不殘留於
  registry。
- 資料格式：底層 `DataStore` 使用 `"players.<uuid>.<key>"` 路徑；變更需
  `markDirty(UUID)`，否則 quit 時不會觸發保存。
- 名稱變更：同 UUID 不同名稱重新登入時，舊 session 必須先 end；新 session
  以新 name snapshot 建立。
- 執行緒：caller 提供的 `Executor` 用於 task queuing；實際 store I/O 由
  內部 per-store serial executor 執行，確保對 `DataStore` 存取永遠序列化。
  `getData(UUID)` 回傳的 `Record` 為執行緒安全包裝（與 service snapshot
  共用 lock），caller 可安全 mutate。
- 關閉：`shutdown()` 設定 atomic flag → 等待 in-flight（最多 5 秒）→ flush
  所有 dirty record → 清除 registry/records → graceful 終止 serial executor；
  冪等。shutdown 後 join/quit 拋 `ACELIB-PLAYER-007`。
- `PlayerSessionState`：`LOADING → READY → UNLOADING → ENDED`；
  `ENDED` 為終態；`isReady()` 只有 READY 回 true。

## 常見失敗與錯誤碼

`ACELIB-PLAYER-001` ~ `ACELIB-PLAYER-008`（見 `PlayerStateException` /
`PlayerDataService`）：

- `ACELIB-PLAYER-001` 資料尚未就緒（caller 在 LOADING 階段讀取）
- `ACELIB-PLAYER-002` 資料載入失敗
- `ACELIB-PLAYER-003` 資料保存失敗
- `ACELIB-PLAYER-004` session 重複登入（同一 UUID 已有 active session）
- `ACELIB-PLAYER-005` session 未找到
- `ACELIB-PLAYER-006` DataStore 未初始化
- `ACELIB-PLAYER-007` 服務已關閉
- `ACELIB-PLAYER-008` 內部 serial executor 終止失敗 / 逾時

## 查核來源

- 型別：`PlayerDataService`、`PlayerSession`、`PlayerSessionRegistry`、
  `PlayerSessionState`、`PlayerCooldownService`、`PlayerStateException`
  （`LockedPlayerRecord` 為 Internal）
- 測試：`src/test/java/com/smile/acelib/player/PlayerDataServiceTest.java`、
  `PlayerDataServiceRaceTest.java`、
  `PlayerDataServiceShutdownFailureTest.java`、
  `PlayerSessionRegistryTest.java`、`PlayerSessionStateTest.java`、
  `PlayerSessionTest.java`、`PlayerCooldownServiceTest.java`、
  `LockedPlayerRecordTest.java`、`PlayerStateExceptionTest.java`
- 下一步：[docs/modules/data.md](data.md)（底層 DataStore）、
  [docs/modules/command.md](command.md)（玩家 sender 抽象）
