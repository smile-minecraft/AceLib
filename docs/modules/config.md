# 設定管理

本頁解決插件需要設定檔、預設值、版本遷移與安全重載時，如何使用 config 模組的問題。

## 何時需要

需要保存 plugin 設定、支援舊設定版本，或提供多語系檔案時使用此模組。

## 怎麼取得／建立

建立 `ConfigSchema` 描述欄位，再建構 `ConfigManager` 並呼叫 `load()`。需要舊版本轉換時，先註冊 `ConfigMigration`；`ConfigMigration` 是由外部提供遷移行為的 SPI（Service Provider Interface）。

## 最短範例

```java
ConfigSchema schema = new ConfigSchema(
    new ConfigVersion(1, 0),
    List.of(new FieldSpec("locale", "zh_TW", true)));
ConfigManager config = new ConfigManager(
    plugin, "config.yml", schema, new ConfigVersion(1, 0));
config.load();
String locale = config.getString("locale", "zh_TW");
config.save();
```

## 不能做什麼

- 不要在 `load()` 前呼叫 `set()`；這會拋 `IllegalStateException`。
- 不要把 `reload()` 的失敗當成可覆寫舊值；失敗時保留舊值。
- 不要把 `ConfigMigration` 當成任意副作用程式；遷移鏈必須能依 `fromVersion()`／`toVersion()` 連續套用。

## 深入說明

契約唯一來源是 `src/main/java/com/smile/acelib/config/**` source 與 tests；本頁為導覽鏡像，不複製完整 JavaDoc。

## 1. 取得方式

config 模組以「建構 + 綁定」方式取得，不經 `AceLibApi` facade（把內部實作包起來的 API 外觀）：

```java
import com.smile.acelib.config.ConfigManager;
import com.smile.acelib.config.ConfigSchema;
import com.smile.acelib.config.ConfigVersion;
import com.smile.acelib.config.FieldSpec;
import com.smile.acelib.config.LangManager;
import java.util.List;

// 1. 描述設定結構（schema）
ConfigSchema schema = new ConfigSchema(
    new ConfigVersion(1, 0),
    List.of(
        new FieldSpec("locale", "zh_TW", true),
        new FieldSpec("prefix", "&7[AceLib] ", true)
    ));

// 2. 建立 manager 並載入（load 內部處理檔案不存在→生成預設、版本過舊→遷移）
ConfigManager config = new ConfigManager(plugin, "config.yml", schema, new ConfigVersion(1, 0))
    .registerMigration(new MyMigration());
config.load();

// 3. 多語系訊息（Locale 回退）
LangManager lang = new LangManager(plugin, Locale.TAIWAN);

// 4. 輕量綁定工廠（可選）：以 plugin 為 key 的快取
com.smile.acelib.config.AceLibConfig.bind(plugin);
```

- 值型別：`ConfigSchema` / `FieldSpec` / `ConfigVersion` / `MigrationResult`
  皆不可變；`ConfigVersion` 可比較（先比 major 再比 minor）。
- 若透過 `AceLibConfig.bind(plugin)`，之後可 `AceLibConfig.get(plugin)`
  取得綁定實例；`unbind(plugin)` 於 disable 時釋放。

## 2. 最小正確範例

```java
// 讀取 / 寫入已載入的設定
String locale = config.getString("locale", "zh_TW");   // 缺 key 回 default
config.set("prefix", "&7[MyPlugin] ");
config.save();                                          // 落盤

// reload：重新讀檔、必要時遷移；成功回 true
boolean ok = config.reload();
```

- `get` 對不存在路徑回傳 `null`（或 type-default）；`get(null)` 拋 NPE。
- `set` 前必須已 `load()`；否則拋 `IllegalStateException`。
- `load()` 冪等：第二次以後為 no-op。
- 遷移（SPI）：實作 `ConfigMigration` 提供 `fromVersion()/toVersion()/migrate(...)`，
  以 `registerMigration(...)` 加入 chain；載入時依 from→to 依序套用。

## 3. 生命週期與 nullability

| 情境 | 行為 |
| --- | --- |
| 設定檔不存在 | `load()` 自動生成預設檔（schema 的 field 預設值） |
| 檔案版本過舊 | 依 `MigrationChain` 套用遷移；無可用遷移拋 `ACELIB-CFG-004` |
| 檔案版本已是當前 | 不觸發遷移（no-op） |
| 必填欄位缺失 | 載入時補上預設值；`validate` 缺失拋 `ACELIB-CFG-005` |
| reload 失敗 | 保留舊值（不回退破壞性覆寫）；需依錯誤碼處理 |
| dataFolder 不存在 | `load()` 自動建立子目錄 |
| `get(null)` / `set(null)` | 丟 NPE（`get(null)` 測試鎖定） |
| 建構 `fileName` 空白 | 丟 `IllegalArgumentException` |

## 常見失敗與錯誤碼

`ACELIB-CFG-001` ~ `ACELIB-CFG-005`（見 `ConfigException` / `ConfigManager`）：

- `ACELIB-CFG-001` 設定檔不存在且無法生成（寫入失敗）
- `ACELIB-CFG-002` 設定檔格式錯誤（YAML 解析失敗）
- `ACELIB-CFG-003` 設定檔載入失敗且無舊值可回退
- `ACELIB-CFG-004` 設定遷移失敗
- `ACELIB-CFG-005` 必填欄位缺失（不允許重載）

## 查核來源

- 型別：`ConfigManager`、`LangManager`、`AceLibConfig`、`ConfigSchema`、
  `FieldSpec`、`ConfigVersion`、`MigrationChain`、`MigrationResult`、
  `ConfigException`；SPI：`ConfigMigration`
- 測試：`src/test/java/com/smile/acelib/config/ConfigManagerTest.java`、
  `LangManagerTest.java`、`ConfigVersionTest.java`、`MigrationChainTest.java`、
  `ConfigExceptionTest.java`、`AceLibConfigTest.java`
- 下一步：[docs/modules/message.md](message.md)（LangManager 下游消費）、
  [docs/consumer/quickstart.md](../consumer/quickstart.md)
