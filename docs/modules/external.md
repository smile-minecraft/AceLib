# 外部插件整合

本頁解決如何判斷 Vault、PlaceholderAPI 或 LuckPerms 是否可用，以及如何讓 adapter 安全啟用與停用的問題。契約唯一來源是
> `src/main/java/com/smile/acelib/external/**` source 與 tests；
> 本頁為導覽鏡像，不複製完整 JavaDoc。

## 前置條件與最短路徑

從 ready 的 `AceLibApi` 取得 `ExternalIntegrationService`，以整合名稱呼叫 `getStatus`；不要直接 import 外部插件 API。

## 預期結果

查詢回傳 `AVAILABLE`、`NOT_INSTALLED`、`NOT_ENABLED`、`VERSION_UNSUPPORTED` 或 `INIT_FAILED`，不把「偵測到」誤寫成「runtime 一定可用」。

## 1. 取得方式

正式取得入口是 `AceLibApi.getExternalIntegrationService()`：

```java
import com.smile.acelib.AceLibApi;
import com.smile.acelib.external.ExternalIntegrationService;

AceLibApi api = registration.getProvider().api(); // 經 ServicesManager
ExternalIntegrationService external = api.getExternalIntegrationService();
```

- 未啟用／已停用時為 unavailable facade：所有查詢回
  `IntegrationStatus.INIT_FAILED` + reason（`NOT_READY` / `SHUTDOWN`），
  永不為 null、不丟例外（null 輸入除外）。
- 也可 `ExternalIntegrationService.forUnavailable(code)` 直接取得。

## 2. 最小查詢範例

```java
import com.smile.acelib.external.IntegrationProbeResult;

// integrationId 例如 "vault" / "luckperms" / "placeholderapi"
IntegrationProbeResult result = external.getStatus("vault");
switch (result.status()) {
    case AVAILABLE -> /* 可安全使用整合 */;
    case NOT_INSTALLED -> /* 未安裝 */;
    case NOT_ENABLED -> /* 已安裝未啟用 */;
    case VERSION_UNSUPPORTED -> /* 版本過舊 */;
    case INIT_FAILED -> /* 偵測／初始化失敗 */;
}
```

## 3. 可用性語意（重要）

- **外部 plugin 是否存在依 classpath 反射與 Bukkit `PluginManager` 判定**，
  不得在文件或程式宣稱 runtime 可用；`ExternalPluginProbe` 只回報「目前伺服器
  上偵測到的狀態」。
- 判定順序：marker class 不在 classpath → `NOT_INSTALLED`；plugin 不存在 →
  `NOT_INSTALLED`；plugin 未啟用 → `NOT_ENABLED`；版本低於需求（或無法比較）→
  `VERSION_UNSUPPORTED`；其餘 → `AVAILABLE`。
- 全程 reflection-only：不 import 任何外部插件 API 類別；外部類別不在
  classpath 時仍可正常啟動。classpath 探測遭 sandbox 拒絕（`SecurityException`）
  時回 `INIT_FAILED`，不吞錯。

## 4. Adapter 生命週期（SPI）

`IntegrationAdapter` 為 SPI，具冪等生命週期：

- `initialize()`：已啟用時 no-op；失敗時 adapter 不得保持 active，失敗原因
  經 `getStatus()` 取得，並拋例外（不吞錯）。
- `shutdown()`：未啟用時 no-op；已啟用時釋放資源。
- `IntegrationRegistry` 協調 enable／disable／reload：`initializeAll` /
  `shutdownAll` 對單一 adapter 失敗隔離（不中斷其他）。
- reload 順序：先 `shutdownAll` 清空舊 adapters，再註冊新 adapters 並
  `initializeAll`；舊與新 adapters 不會同時可用。

## 常見失敗與錯誤碼

見 `ExternalIntegrationErrorCodes`（`ACELIB-EXT-001` ~ `ACELIB-EXT-006`）：

- `ACELIB-EXT-001`：整合初始化失敗
- `ACELIB-EXT-002`：版本不支援
- `ACELIB-EXT-003`：未安裝或未啟用
- `ACELIB-EXT-004`：清理失敗
- `ACELIB-EXT-005`：服務未啟用（facade `NOT_READY`）
- `ACELIB-EXT-006`：服務已停用（facade `SHUTDOWN`）

## 下一步

- 下游接入：先完成 [Consumer Quick Start](../consumer/quickstart.md)。
- 版本與外部插件限制：查看 [相容性與發布狀態](../consumer/compatibility.md)。

## 查核來源

- 介面：`ExternalIntegrationService`（Supported）、`IntegrationAdapter`（SPI）
- 型別：`ExternalPluginProbe`、`IntegrationRegistry`、`IntegrationStatus`、
  `IntegrationProbeResult`、`ExternalIntegrationErrorCodes`
- Internal：`ExternalIntegrationServiceImpl`、`VaultIntegrationAdapter`、
  `LuckPermsIntegrationAdapter`、`PlaceholderApiIntegrationAdapter`
- 測試：`src/test/java/com/smile/acelib/external/ExternalPluginProbeTest.java`、
  `IntegrationRegistryTest.java`、`IntegrationAdapterTest.java`、
  `VaultIntegrationAdapterTest.java`、`LuckPermsIntegrationAdapterTest.java`、
  `PlaceholderApiIntegrationAdapterTest.java`、`ExternalIntegrationServiceImplTest.java`
