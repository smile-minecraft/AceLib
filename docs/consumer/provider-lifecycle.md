# AceLib Provider 的啟用、重載與停用

本頁解決下游插件在 provider 不存在、尚未 ready、重載或 AceLib 停用時，應如何判斷與處理的問題。

## 前置條件與最短路徑

先完成 [Quick Start](quickstart.md) 的 dependency 與 `depend: [AceLib]` 設定。每次需要使用服務時，取得 provider、讀取 `provider.api()`，再檢查 `api.isReady()`。

## 預期結果

啟用後可取得 ready facade；重載後同一 provider 反映目前 facade；停用後 provider 解除註冊，已持有 provider 的呼叫會看到 `isReady() == false`。

## 1. Provider 如何註冊

AceLib 在 `onEnable` 成功後，把 **`AceLibApi.AceLibProvider`** 註冊到
Bukkit/Paper `ServicesManager`（`ServicePriority.Normal`）。`ServicesManager` 是 Bukkit 提供的服務登錄表：

```java
// 伺服器內部（AceLib）：
server.getServicesManager().register(
    AceLibApi.AceLibProvider.class, provider, this, ServicePriority.Normal);
```

下游經 `getRegistration(...)` 取得；**不要**依賴 `AceLibPlugin` 或 static singleton。

## 2. 四種狀態

| 情境 | `getRegistration(...)` | `provider.api()` | `api().isReady()` |
| --- | --- | --- | --- |
| AceLib 尚未 enable | `null` | —（無法取得 provider） | — |
| AceLib 已 enable | 非 null | 目前 facade | `true` |
| AceLib reload 後 | 非 null（同一 provider） | **新的** facade（不殘留 stale） | `true` |
| AceLib disable 後 | `null`（已解除註冊） | 已持有者讀到 shutdown facade | `false` |

### 2.1 missing / not-ready（`getRegistration` 回傳 null）

即使宣告 `depend: [AceLib]`，仍可能遇到 provider 不存在：

- AceLib enable 失敗（例如 platform 判定失敗降級）。
- AceLib 已被 disable（disable 會解除註冊）。

處理方式：視為無法使用，停用自身 plugin 或走 fallback，**不要**硬取 API。

### 2.2 reload 後（同一個 provider，新 facade）

AceLib 的 `reload()` 是**內部交易式重載**（非 Bukkit `/reload`）：重新偵測
平台並建立新的 `AceLibApi` facade。provider **不會**被重新註冊 — 同一個
registration 保留，`api()` 讀到的是 reload 後的最新 facade。

因此：**不要在 `onEnable` 快取 `AceLibApi` 後永久持有並假設不變**；
需要最新狀態時呼叫 `provider.api()`，或每次重新 `getRegistration(...)`。
reload 失敗時 AceLib 維持舊 facade 並進入 FAILED/non-ready policy（`isReady()=false`）。

### 2.3 disable 後（registration 移除、cached provider 讀到 shutdown facade）

disable 時 AceLib 先解除 registration，再把 provider 內部 reference 切換為
shutdown facade。已持有 provider 的呼叫端會讀到：

- `api()` 不為 null，但 `api().isReady() == false`
- service facade 操作被拒絕並帶錯誤碼（例如 `ACELIB-WORLD-002`、`ACELIB-GUI-002`）

因此 `provider.api()` 回傳非 null **不代表可用**；呼叫端必須檢查
`api().isReady()`。

### 2.4 建議的防禦 pattern

```java
AceLibApi api = null;
RegisteredServiceProvider<AceLibApi.AceLibProvider> reg =
    getServer().getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);
if (reg != null) {
    api = reg.getProvider().api();
}
if (api == null || !api.isReady()) {
    // missing 或 not-ready：停用自身或走 fallback
    getServer().getPluginManager().disablePlugin(this);
    return;
}
```

## 3. Thread context 與 Folia-safe

- `provider.api()` 內部以 `volatile` 快照目前 facade，任何 thread 呼叫都安全；facade 是包住服務實作的不可變 API 外觀。
  回傳的 `AceLibApi` 本身不可變。Paper 與 Folia 行為一致。
- `AceLibApi` 的 service facade（world / gui / external）本身 thread-safe，
  但 **mutate 遊戲物件（玩家 / 實體 / 方塊）必須在正確的執行緒上下文**：

  - **Folia**：region-bound 物件只能在該 region thread 操作；非 region thread
    的 mutate 觸發 `ACELIB-CTX-003`。排程使用 AceLib 安全排程 API，
    不要以 `Bukkit.getServer().getScheduler()` 作為預設路徑。
  - **Paper**：可使用全域 scheduler；`getPlatformCapability().globalScheduler()`
    為 `true` 表示安全。

## 4. `depend` ordering 與 provider 的關係

`plugin.yml` 的 `depend: [AceLib]` 提供兩層保證：

1. 載入順序：AceLib 先 enable，下游 plugin 才 enable（因此 `onEnable` 內
   `getRegistration(...)` 通常已有 provider）。
2. 前置檢查：AceLib 不存在時，下游 plugin 不會被載入。

但 `depend` **不保證** AceLib 已 ready 或一直保持 ready；reload / disable
情境仍需 §2 的 runtime 防禦。

## 5. 常見錯誤

| 錯誤 | 後果 | 修正 |
| --- | --- | --- |
| `import com.smile.acelib.AceLib; AceLib.getApi()` | 編譯失敗（class 不存在） | 用 `ServicesManager` + `AceLibProvider` |
| unchecked cast 到 `AceLibPlugin` | 依賴 Internal 類別、破壞穩定契約 | 只用 `AceLibApi.AceLibProvider` |
| 快取 `AceLibApi` 永不更新 | reload 後讀到 stale facade | 每次需要時讀 `provider.api()` |
| 只檢查 `api() != null` | disable 後誤用 shutdown facade | 同時檢查 `api().isReady()` |
| Folia 下用全域 scheduler | 執行緒違規、錯誤碼 `ACELIB-SCHED-005` / `ACELIB-CTX-003` | 依 capability 分流，走安全 API |

## 下一步

- 需要可編譯接入範例：回到 [Quick Start](quickstart.md)。
- 需要確認版本與伺服器基線：查看 [相容性與發布狀態](compatibility.md)。
- 需要查模組 API 與錯誤碼：查看 [模組指南](../modules/)。
