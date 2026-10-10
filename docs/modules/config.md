# 設定檔

> 適合要在自己 plugin 中建立、讀寫與遷移設定檔的開發者。


`ConfigManager` 管理 YAML 設定、預設值、版本與遷移。它由 consumer 自行建立，不是從 `AceLibApi` 取得。

## 建立並啟動

```java
ConfigSchema schema = new ConfigSchema(
    new ConfigVersion(1, 0),
    List.of(
        new FieldSpec("locale", "zh_TW", true),
        new FieldSpec("prefix", "&7[MyPlugin] ", true)
    ));

ConfigManager config = new ConfigManager(
    this,
    "config.yml",
    schema,
    new ConfigVersion(1, 0));

StartupResult result = config.startup();
```

`startup()` 把「檔案不存在」拆成四類：`FRESH_INSTALL`（首次安裝，剛生成預設檔）、`LOADED`（有效設定）、`CORRUPT`（損壞設定，原檔不動）、`MISSING_AFTER_USE`（用過之後被刪，已用最後成功副本或預設值重建）。識別依據是安裝狀態 sidecar（只在驗證成功後寫入），不是檔案是否存在。損壞時禁止哪些操作由下游依 `result.status()` 與 `result.snapshot()`（是否為 null）自行決定。

損壞時快照依序取用：最後驗證成功副本 → 呼叫端指定的保守後備 → 無可用時為 null（診斷帶 `ACELIB-CFG-003`）：

```java
YamlConfiguration fallback = new YamlConfiguration();
fallback.set("locale", "zh_TW");
StartupResult result = config.startup(fallback);
```

使用後缺檔的重建同樣受版本政策約束：最後成功副本比當前版本新時拒絕還原（`ACELIB-CFG-006`），副本不動，改用預設值重建。

缺檔還原前可由下游攔截：`registerMissingFileHandler` 登記的規則只在使用後缺檔（檔案不存在、且曾經成功載入過）時，於還原最後成功副本之前依序執行。規則正常回傳即照現行流程還原，未登記時行為不變：

```java
config.registerMissingFileHandler(missing -> {
    throw new ConfigException("ACELIB-EXT-001",
        "設定檔遺失，拒絕以舊副本啟動：" + missing.getAbsolutePath());
});
```

規則拋 `ConfigException` 即拒絕還原：不寫入目標檔、不產生預設檔、不發布新快照、不推進世代、不動最後成功副本。`startup()` 回傳 `MISSING_AFTER_USE`（快照取記憶體舊快照 → 呼叫端後備 → null，診斷帶該例外的錯誤碼與訊息），`load()` 原樣拋出。首次安裝不觸發；最後成功副本版本較新時的 `ACELIB-CFG-006` 拒絕與其他啟動路徑不受影響。拒絕用的錯誤碼由下游自行決定，AceLib 不新增錯誤碼。

`load()` 保留舊語意（損壞時直接拋 `ConfigException`）；需要分類或損壞時繼續跑請用 `startup()`。`load()` 可重複呼叫，每次都會重新驗證並寫回。

## 讀寫與重載

```java
Object locale = config.get("locale");
config.set("prefix", "&7[MyPlugin] ");
config.save();

boolean reloaded = config.reload();
```

必須先 `load()` 才能 `set()` 或 `save()`。`reload()` 失敗時會保留舊值，不會用部分讀取的內容覆寫目前設定。

成功的 `reload()` 會把補齊欄位、執行遷移並收斂版本之後的內容做保註解合併寫回磁碟；即使磁碟上的版本與當前版本相同、內容其實沒有變動，一樣會寫回。`load()` 走的是同一條路徑，兩邊行為一致。

## 不可變快照

整份設定驗證通過後一次發布 `ConfigSnapshot`，同輪操作（`get`／`set`／`save`）固定用同一個實例，只有 `load`／`reload`／`startup` 成功才換新實例。快照深層不可變，任何修改都會拋 `UnsupportedOperationException`；`reload` 失敗保留舊快照實例。

```java
ConfigSnapshot snapshot = config.snapshot();
String locale = snapshot.getString("locale", "zh_TW");
```

快照帶世代（`snapshot.generation()`）：每次成功發布（`load`／`reload`／`startup` 成功）世代 +1，即使內容與上一版相同也 +1；失敗不發布新世代，世代不變。`equals`／`hashCode` 只比較深層內容，世代不參與：同內容的兩個快照相等，世代不同不影響相等判斷。判斷「設定是否重載過」時比較世代，比較「內容是否相同」時直接比較快照。

例外：損壞啟動等後備路徑（最後成功副本、呼叫端指定的保守後備）產生的快照世代為 0；比較世代時以 `ConfigManager.snapshot()` 的發布快照為準。

## 數值讀取（`getInt`／`getLong`／`getDouble`）

```java
int port = snapshot.getInt("server.port", 8080);
long total = snapshot.getLong("stats.total", 0L);
double rate = snapshot.getDouble("stats.rate", 1.0);
```

三個方法的缺值語意一致：路徑不存在、值為 null 或值不是數字時回傳呼叫端給的預設值，不拋例外。數字值的檢查是嚴格的：`getInt`／`getLong` 遇到小數、NaN／無限大或超出目標型別範圍的值時拋 `ACELIB-CFG-007`（訊息含完整路徑）；`getDouble` 遇到 NaN／正負無限大（例如 YAML 的 `.nan`／`.inf`）時同樣拋 `ACELIB-CFG-007`。這與 `ConfigBinder.bind` 的 `int`／`long`／`double` 轉換是同一套規則。

> **破壞性變更（1.5.0）**：`getInt` 過去對任何數字取 `intValue()`（小數靜默截斷、超大值靜默溢位）。現在小數與溢位改為拋錯，不再截斷，也不再回傳預設值。遷移方式：過去依賴截斷的寫法（例如把 `2.9` 讀成 `2`），請改用 `getDouble` 再自行取整；不確定欄位是否為整數時，先用 `snapshot.get(path) instanceof Number` 確認，或改走 `bind()` 的範圍約束一次驗證。

## 型別綁定

`ConfigBinder.bind` 把快照綁定到 record 或一般類別（無參建構＋欄位注入），載入時驗證型別、數值範圍與列舉值，失敗拋 `ACELIB-CFG-007` 並帶完整欄位路徑：

```java
public record ServerSettings(
    @ConfigBinder.ConfigKey("server.host") String host,
    @ConfigBinder.ConfigKey("server.port")
    @ConfigBinder.ConfigRange(min = 1, max = 65535) int port,
    @ConfigBinder.ConfigKey("server.mode") Mode mode) {}

ServerSettings settings = config.bind(ServerSettings.class);
```

不寫 `@ConfigKey` 時以 component／欄位名為路徑；含點的 `@ConfigKey` 視為從根起的絕對路徑。缺失語意：缺失的基本型別報錯，缺失的參考型別（`String`／`Integer`／列舉／集合／巢狀型別）為 null，由呼叫端決定是否接受；`int`／`long` 嚴格轉換（小數、NaN／無限大、超出範圍一律報錯，不靜默截斷或溢位）；`double` 非有限值（NaN／無限大）一律報錯，不論有無 `@ConfigRange`，有範圍時訊息一併帶出允許範圍；列舉按名稱精確比對（大小寫敏感），失敗訊息列出全部合法選項。

## 集合綁定

```java
public record Limits(
    @ConfigBinder.ConfigKey("scores") Map<String, Integer> scores,
    @ConfigBinder.ConfigKey("tags") Set<String> tags,
    @ConfigBinder.ConfigKey("endpoints") List<Endpoint> endpoints) {}

public record Endpoint(String host, int port) {}
```

集合的元素／值型別取自宣告的泛型參數，逐元素驗證：`Map` 只支援 `Map<String, T>`（鍵為 YAML 鍵名，值依 `T` 嚴格驗證）；`Set` 由 YAML 清單建構，依 `equals` 去重並保留首次出現順序，元素一律嚴格驗證；`List<T>` 的 `T` 為巢狀 record／POJO 時逐元素綁定。`List<String>`、元素為 `Object` 與未指定泛型的 `List` 維持既有行為（元素逐個轉字串）；其他清單元素型別（數值、列舉、巢狀型別）走與純量欄位相同的嚴格規則，欄位上的 `@ConfigRange` 同樣套用於數值元素。`Map`／`Set` 不走清單的相容轉換：`String` 元素只接受字串（數字、布林等一律報錯），`Object` 元素原值保留。萬用字元／型別變數推斷不出驗證規則時明確報錯，不默默轉字串。YAML 的 null 元素保留為 null，不跳過。錯誤一律 `ACELIB-CFG-007` 並帶完整元素路徑：Map 項目為 `scores.bob`、Set 與清單元素為 `modes[1]`、巢狀元素內欄位為 `endpoints[0].port`；集合元素內的絕對路徑（含點的 `@ConfigKey`）仍從根解析。

## 跨欄位驗證

```java
config.registerCrossFieldValidator(candidate -> {
    int min = ((Number) candidate.get("limits.min")).intValue();
    int max = ((Number) candidate.get("limits.max")).intValue();
    if (min > max) {
        throw new ConfigBindingException("limits",
            "規則 minNotGreaterThanMax 失敗：下限 " + min + " 大於上限 " + max);
    }
});
```

規則在候選快照通過既有驗證（schema 預設補齊、遷移、版本收斂）之後、發布新快照之前執行；多條規則依登記順序執行，前一條失敗就停住。規則讀候選快照的多個路徑，檢查它們之間的關係；通過就直接回傳，失敗拋 `ConfigBindingException`（`ACELIB-CFG-007`），訊息內寫明失敗的規則與相關路徑。失敗時不發布新快照、不推進世代、不改磁碟、不動最後成功副本：`load()` 原樣拋出、`reload()` 回傳 `false` 並保留舊快照、`startup()` 走損壞路徑（原檔不動，沿用最後成功副本）。

> **後備語意**：`startup()` 損壞時的後備快照（最後成功副本、呼叫端指定的保守後備、記憶體舊快照）**不**重新執行這裡登記的規則。後備是當時已驗證通過的狀態；對新規則重新驗證會把可恢復的啟動變成硬失敗，因此管線刻意跳過。後備快照的內容只保證「當時驗證通過」，不保證通過現行全部規則。監看重載走 `reload()` 管線，候選內容仍要過規則，失敗時保留舊快照並以錯誤碼診斷。

## 檔案監看

```java
config.startWatching(new ConfigChangeListener() {
    @Override public void onReload(ConfigSnapshot snapshot) { /* 新快照已發布 */ }
    @Override public void onInvalidReload(String code, String detail) { /* 舊快照保留 */ }
});
// ...
config.close(); // plugin disable 時呼叫，不殘留監看執行緒
```

外部修改且驗證通過時自動重載；新內容無效時保留舊快照、原檔不動，並以錯誤碼診斷。自己的寫回不會觸發重載迴圈（內容雜湊比對＋去抖動）。`reload()` 不影響監看；重複 `startWatching` 會先停掉舊監看器，不洩漏執行緒。監看執行緒為 daemon，不擋 JVM 退出；回呼內不得修改遊戲物件。

> **平台延遲差異**：JDK 的 `WatchService` 在 macOS 上以輪詢實作（靈敏度分級的設計即為此而來，預設約 10 秒），外部修改的觀測可能延遲數秒；Linux（inotify）近即時。去抖動（200ms）是事件到達後才起算，不含平台本身的觀測延遲。

> **併發寫入互斥**：`load`／`reload`／`save`／`startup`／`set` 以 manager 實例為鎖互斥，監看執行緒的重載與主執行緒的寫入不會併發落盤。監看回呼在鎖外執行，回呼內再呼叫寫入方法不會死鎖，但應避免耗時工作。

## 寫回保留註解

> **設定檔裡的註解會保留**：寫回走行級合併，只改值真的變了的行內值段、只補缺的 key（含 `setFieldDescription` 設定的欄位說明），其餘行逐位元保留。`set(path, null)` 明確刪除的 key 會真的刪行（含因此變空的祖先節頭），migration 移除的 key 同理；原檔有但新值沒有、且非明確刪除的 key 一律保留，不刪除使用者資料；整段變更的清單／節點內部註解不保留，前後註解保留。

> **多行字串退回全量序列化**：值含多行字串（或合併後語意比對不一致）時，合併器退回全量序列化以保證語意正確，該次寫回的註解不保留。純量值（含單行字串、數字、布林）的寫回不受影響。

```java
config.setFieldDescription("maxPlayers", "同時在線人數上限");
config.load(); // 缺的 maxPlayers 會連同說明一起補進檔案
```

版本較新被拒絕、或替換開始前的失敗不動目標檔；取代開始後的保證見下一節「原子寫入與暫存檔清理」。需要長期存在的說明仍建議放在文件或 schema 欄位說明，不要只依賴設定檔註解。

## 版本較新時拒絕降版

磁碟上的 `version` 比目前的 `ConfigVersion` 新時，`load()` 會拋 `ACELIB-CFG-006` 並保持檔案逐位元不變；`reload()` 則回傳 `false`、保留舊設定且不改檔。`save()` 在未成功 `load()` 前會被擋下，因此不會把較新版本降版寫回。

## 重載失敗保留舊值並記錄原因

`reload()` 沿用回傳 `boolean` 的約定：成功回傳 `true`，失敗回傳 `false` 且已生效的設定不變。每次失敗都會以 `WARNING` 記錄原因並附上 `ACELIB-CFG-*` 代碼與檔名，可從日誌直接追查，不做靜默的 `false`。版本比較、預設值補齊與遷移的順序和 `load()` 共用同一條路徑，兩邊行為一致。

## 原子寫入與暫存檔清理

設定與語系檔寫入都走暫存檔再取代：先把內容寫到與目標檔同目錄的暫存檔，完整寫好後才取代目標檔；暫存檔與目標檔在同一目錄，不涉及跨檔案系統搬移。暫存檔建置與寫入階段失敗時目標檔逐位元不變，且暫存檔會被清掉；清理自己失敗時，清理錯誤以 `suppressed` 附在原始寫入錯誤上，兩邊原因都可在例外鏈查到。原子取代只需要父目錄可寫，不要求目標檔本身可寫。

取代時先要求 `ATOMIC_MOVE`；只有當底層明確回報不支援原子搬移時，才改用 `REPLACE_EXISTING` 重試一次。降級只是多一次機會，不是保證：取代本身仍可能失敗（此時拋 `ACELIB-CFG-001`；原子搬移的檔案系統上舊檔保留，降級取代的檔案系統上一旦取代開始後失敗，不保證舊檔完整），所以寫入流程**不保證在各平台都完成**，**原子性也不是保證**：只有在支援原子搬移的檔案系統上，取代才真的具備「要嘛整份換成新檔、要嘛完全不動」的性質。在只能降級的檔案系統上，取代是覆寫原項目，寫入期間目標檔可能短暫處於不完整狀態；這條限制無法從 API 層消除，只能靠選擇支援原子搬移的檔案系統來避免。

> **目標檔的檔案權限會盡力保留（已實測）**：取代前先記下目標檔的 POSIX 權限，成功後還原；還原失敗（非 POSIX 檔案系統、權限不足）時靜默維持 temp 檔預設權限，不把寫入成功翻成失敗。需要固定權限的環境仍應在部署後確認，或在檔案系統層設定預設權限。

## 遷移舊設定

實作 `ConfigMigration`，提供 `fromVersion()`、`toVersion()` 與 `migrate(...)`，再於 `load()` 前註冊：

```java
config.registerMigration(new MyConfigMigration());
config.load();
```

遷移必須形成連續版本鏈。缺少必要遷移或遷移失敗時，AceLib 會保留原資料並回報 `ACELIB-CFG-*`。

多語系檔案由 `LangManager` 處理，訊息輸出方式見[訊息服務](message.md)。錯誤碼見[完整查表](../reference/error-codes.md)。

## 相關頁面

- [訊息服務](message.md)
- [資料儲存](data.md)
- [錯誤碼](../reference/error-codes.md)
