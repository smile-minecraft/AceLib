# 資料儲存

本頁解決插件需要持久化資料、執行 schema 遷移，以及在關閉前完成 flush 時，如何使用 `DataStore` 的問題。

## 何時需要

需要保存設定以外的玩家、玩法或插件資料，且希望以版本化 schema 管理資料時使用此模組。

## 怎麼取得／建立

consumer 取得組裝端注入的 `DataStore`、`Record` 與 `SchemaVersion`。`Internal` 表示 AceLib 內部實作，不是下游穩定契約；`JsonFileDataStore` 與 `JdbcDataStore` 不應在下游 production code 直接建立。

## 最短範例

```java
DataStore store = providedStore;
store.init();
Record root = store.root();
root.set("user.balance", 12345);
store.save();
store.close();
```

## 不能做什麼

- 不要在 `init()` 前讀取 `root()`，也不要在 `close()` 後繼續操作。
- 不要讓同步 `root()`／`save()` 在未同步的多執行緒中並行使用；呼叫端必須自行確保執行緒安全。
- 不要直接依賴 `JsonFileDataStore`、`JdbcDataStore` 或其他 Internal 實作。

## 深入說明

契約唯一來源是 `src/main/java/com/smile/acelib/data/**` source 與 tests；本頁為導覽鏡像，不複製完整 JavaDoc。

## 1. 取得方式

data 模組的 **consumer 入口是 `DataStore` abstraction**：下游只依賴
`DataStore` / `Record` / `SchemaVersion` 介面，instance 由組裝端
（AceLib 或宿主 plugin）注入，或由 assembly 端自行建立。

### Consumer path（公開 abstraction）

```java
import com.smile.acelib.data.DataStore;
import com.smile.acelib.data.Record;

// store 由組裝端提供（例如 AceLibPlugin 內部建立的 instance）；
// consumer 只使用 DataStore / Record 介面，不依賴特定實作。
DataStore store = providedStore;
store.init();                       // 建立底層、執行遷移
```

> **契約**：`DataStore` 為 Supported API；內建實作
> `JsonFileDataStore`（本地 JSON 原子寫入）與 `JdbcDataStore`
> （標準 JDBC，需自備 `DataSource`）在 API surface 皆標為 **Internal**，
> 非消費者契約。consumer 不得在 production code 直接 `new` 或依賴這些
> 實作類別；僅供組裝端在 plugin 內部建立後以 `DataStore` 型別注入。

### Assembly-only（組裝端專用範例）

只有組裝端（AceLib 或宿主 plugin 的組裝邏輯）才需要建立 Internal 實作：

```java
import com.smile.acelib.data.JsonCodecImpl;
import com.smile.acelib.data.JsonFileDataStore;
import com.smile.acelib.data.SchemaVersion;
import java.nio.file.Path;

// 組裝端專用：建立 Internal 實作後以 DataStore 型別對外提供
DataStore store = new JsonFileDataStore(
    "my-plugin-data",
    Path.of(plugin.getDataFolder().getPath(), "data.json"),
    new SchemaVersion(1, 0),
    new JsonCodecImpl());
```

- schema 遷移為 SPI（Service Provider Interface，留給外部實作者的擴充介面）：實作 `DataMigration` 提供
  `fromVersion()/toVersion()/migrate(DataMigrationContext)`，以
  `store.registerMigration(...)` 加入 chain；`init()` 時依 from→to 依序套用。

## 2. 最小正確範例

以下範例的 `store` 為組裝端注入的 `DataStore` instance
（consumer 只使用 abstraction）：

```java
import com.smile.acelib.data.Record;
import java.util.concurrent.Executor;

Record root = store.root();                     // 根視圖（init 後才可用）
root.set("user.balance", 12345);                // 點分隔 path
root.set("players.uuid-1.lastLogin", 1700000000000L);
store.save();                                   // 同步寫回

// 型別化 getter（缺失回 default，不丟例外）
int balance = root.getInt("user.balance", 0);

// 非同步寫入（不阻塞 caller）
CompletableFuture<Void> f = store.submit(executor, () -> {
    store.root().set("user.xp", 100);
    store.save();
    return null;
});

// 關閉：flush + 釋放資源（冪等；重複呼叫不丟例外）
store.close();
```

## 3. 生命週期與執行緒模型

| 階段 | 行為 |
| --- | --- |
| 初始化 | `init()` 建立底層、執行 schema 遷移；`isInitialized()` 後才可用 |
| 版本過舊 | 依 `MigrationChain` 套用遷移；失敗觸發 rollback，<strong>既有資料不變</strong> |
| on-disk 版本較新 | 拒絕降版覆寫（`ACELIB-DATA-010`） |
| 無可用 migration | 偵測到舊版本但 chain 無對應 from → `ACELIB-DATA-009` |
| 寫入 | `root()` 修改 → `save()` / `flush()` 寫回；`flush()` 同時等待 async |
| 關閉 | `close()` flush + 釋放資源；之後操作拋 `ACELIB-DATA-005` |

- 同步 `root()` / `save()`：呼叫端須自行確保執行緒安全。
- 非同步 `submit(executor, task)`：透過 `Executor` 排程，不阻塞 caller；
  結果以 `CompletableFuture` 回傳。
- `flush()` / `close()` 為 block-and-wait，可從任意執行緒呼叫。
- `Record` 值型別支援：基本型別（String/Integer/Long/Double/Boolean）、
  null、巢狀 `Record`、`Map<String, Object>`、`List<Object>`；
  不支援型別寫入拋 `ACELIB-DATA-006`。
- null 或空白 path → `ACELIB-DATA-003`。

## 常見失敗與錯誤碼

`ACELIB-DATA-001` ~ `ACELIB-DATA-011`（見 `DataStoreException`）：

- `ACELIB-DATA-001` IO 失敗（檔案讀寫、目錄建立、磁碟空間）
- `ACELIB-DATA-002` 資料損壞（檔案格式錯誤、反序列化失敗）
- `ACELIB-DATA-003` 索引錯誤（key/path 為 null、空白、不合法）
- `ACELIB-DATA-004` 遷移失敗（chain 中任一版本轉換失敗）
- `ACELIB-DATA-005` 儲存已關閉
- `ACELIB-DATA-006` 序列化失敗（型別不支援、循環參考）
- `ACELIB-DATA-007` 非同步逾時
- `ACELIB-DATA-008` 資料源不可用（JDBC 連線拒絕、SQL 語法錯誤）
- `ACELIB-DATA-009` 無可用 migration
- `ACELIB-DATA-010` on-disk schema 版本比 current 新（拒絕降版覆寫）
- `ACELIB-DATA-011` 非法 SQL identifier（`JdbcDataStore` table 名稱驗證）

## 查核來源

- 介面：`DataStore`、`Record`；SPI：`DataMigration`、`JsonCodec`
- 型別：`SchemaVersion`、`MigrationChain`、`MigrationResult`、
  `DataMigrationContext`、`DataStoreException`
  （`JsonCodecImpl` / `JsonFileDataStore` / `JdbcDataStore` / `MemoryRecord`
  為 Internal）
- 測試：`src/test/java/com/smile/acelib/data/JsonFileDataStoreTest.java`、
  `JdbcDataStoreTest.java`、`MemoryRecordTest.java`、`JsonCodecTest.java`、
  `MigrationChainTest.java`、`SchemaVersionTest.java`、
  `DataStoreExceptionTest.java`
- 下一步：[docs/modules/player.md](player.md)（PlayerDataService 底層）、
  [docs/modules/config.md](config.md)（ConfigVersion 差異對照）
