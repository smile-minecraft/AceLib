# 核心入口與生命週期

本頁解決插件如何取得 `AceLibApi`、判斷 ready 狀態，以及正確應對 enable、reload、disable 的問題。
契約唯一來源是 `src/main/java/com/smile/acelib` source 與 tests；本頁為導覽鏡像，不複製完整 JavaDoc。

## 前置條件與最短路徑

先完成 [Consumer Quick Start](../consumer/quickstart.md)，再從 `ServicesManager` 取得 `AceLibApi.AceLibProvider`，讀取 `provider.api()` 並檢查 `isReady()`。

## 預期結果

下游程式只使用 `AceLibApi` 與其 service facade；未 ready 或停用時拒絕操作，不依賴 `AceLibPlugin` 等 Internal 類別。

## 1. 取得方式

正式取得入口是 Bukkit/Paper `ServicesManager` 註冊的
`AceLibApi.AceLibProvider`（見
[docs/consumer/provider-lifecycle.md](../consumer/provider-lifecycle.md)）：

```java
import com.smile.acelib.AceLibApi;
import org.bukkit.plugin.RegisteredServiceProvider;

RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
    getServer().getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);
AceLibApi api = registration == null ? null : registration.getProvider().api();
if (api != null && api.isReady()) {
    // 使用 api 提供的各 service
}
```

- **不要**直接依賴 `AceLibPlugin` 或 static singleton（Internal，不屬穩定契約）。
- `provider.api()` 永不回傳 null；但 disable 後回傳 shutdown facade，
  `api().isReady()` 為 false，呼叫端必須檢查。

## 2. 最小安全範例

```java
// 依平台能力選擇排程路徑
PlatformCapability cap = api.getPlatformCapability();
if (cap.regionScheduling()) {
    // Folia：regionized 排程可用
} else if (cap.globalScheduler()) {
    // Paper：全域 scheduler
} else {
    // UNKNOWN 平台：保守降級
}

// 取得 service facade（永不為 null，不需 null 判斷）
WorldService world = api.getWorldService();
GuiService gui = api.getGuiService();
```

`AceLibApi` 本身不可變：一旦建立，版本、平台與 service reference 皆不可變動。
`isReady()` 透過 callback 反映 plugin 目前生命週期狀態。

## 3. Folia／執行緒契約

- `AceLibApi`（含 `AceLibProvider.api()`）可在任何 thread 安全呼叫；
  Paper 與 Folia 環境行為一致。
- plugin enable 後 provider 註冊、disable 時解除註冊；reload **不**解除註冊。
  只有 reload 成功 commit 時 `provider.api()` 才會反映新的 facade；
  reload rollback 至既有 binding 時可能維持舊 facade。
- 不要長時間快取 `AceLibApi` 並假設不變：需要最新狀態時重新讀
  `provider.api()`；但重新讀取只能取得「目前」facade，不能作為可靠的
  reload success 判斷（成功 commit 前後 facade 都可能暫時相同）。

## 4. 生命週期與 nullability

| 情境 | 行為 |
| --- | --- |
| 未啟用（enable 前） | `uninitialized()` facade；`isReady() = false`；service 回 `NOT_READY` |
| 已啟用 | `ready(...)` facade；`isReady() = true`；service 為實際實作 |
| 已停用（disable 後） | `shutDown(...)` facade；`isReady() = false`；service 回 `SHUTDOWN` |
| reload 成功 | 同一 provider 回傳新 facade；`api()` 為最新 |
| reload 失敗 | 成功路徑更新 facade；失敗路徑可能 rollback 至既有 binding（不一定降級） |

> `AceLibApi.reload()` 回傳型別為 **void**（不回傳 boolean）：它只是觸發 plugin 端
> reload 流程的 callback（`uninitialized()` 或未啟用時為 no-op）。
> 公開 API（`AceLibApi` / `AceLibProvider`）**不提供** reload 成敗的 boolean 結果，
> 也不保證失敗時一律降級為 FAILED（實作可能 rollback 並保留既有 binding）。
> 因此不要把 `isReady()` 當作 reload 成敗指標；重新讀 `provider.api()` 只能取得
> 目前 facade（成功 commit 後才更新），不能作為可靠的 reload success 判斷。

- `AceLibApi` 的 service facade（world / gui / external）永不為 null。
- registration 缺失（plugin 尚未 enable）時 `getRegistration(...)` 回傳 null，
  呼叫端必須自行處理。

## 常見失敗與錯誤處理

- 平台偵測失敗（UNKNOWN）時 plugin 輸出 warning log 攜帶
  `ACELIB-PLAT-004`，並以全 false capability 保守降級。
- service 不可用時各 facade 回錯誤結果或例外，代碼見各模組 `*ErrorCode`
  （`ACELIB-WORLD-*` / `ACELIB-GUI-*` 等）。
- reload 失敗時可能 rollback 至既有 binding（以 `ACELIB-DBG-001` 記錄；失敗路徑
  不一定降級為 FAILED，也不一定改變 `isReady()`）。公開 API 不提供 reload 成敗
  boolean 結果；reload 後重新讀 `provider.api()`，以 facade 是否更新觀察
  成功路徑是否生效。

## 查核來源

- 型別：`AceLibApi`、`AceLibApi.AceLibProvider`、`AceLibVersion`
  （`AceLibPlugin` 為 Internal，僅供參考）
- 測試：`src/test/java/com/smile/acelib/AceLibProviderTest.java`、
  `AceLibPluginTest.java`
- 下一步：[docs/consumer/quickstart.md](../consumer/quickstart.md)、
  [docs/consumer/provider-lifecycle.md](../consumer/provider-lifecycle.md)
