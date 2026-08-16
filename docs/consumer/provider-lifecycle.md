# Provider 生命週期

先完成[快速開始](quickstart.md)。本頁只處理 AceLib 從啟用到停用期間，consumer 應如何保存與使用 provider。

## 啟用時

AceLib 啟用成功後，會把 `AceLibApi.AceLibProvider` 註冊到 Bukkit `ServicesManager`。宣告 `depend: [AceLib]` 後，你的 `onEnable()` 通常可以直接查到 registration，但仍要處理 `null`，以免 AceLib 啟用失敗。

```java
RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
    getServer().getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);

if (registration == null) {
    // 停用本 plugin，或改走不需要 AceLib 的功能
    return;
}

AceLibApi.AceLibProvider provider = registration.getProvider();
AceLibApi api = provider.api();
if (!api.isReady()) {
    return;
}
```

保留 provider 可以，但不要永久保留第一次取得的 `AceLibApi`。需要使用服務時重新呼叫 `provider.api()`，才能取得 AceLib 目前使用的 API 物件。

## 尚未就緒時

`provider.api()` 不會回傳 `null`。不過 `api.isReady()` 可能是 `false`，此時 world、GUI 與 external integration 等服務會拒絕操作並回傳對應錯誤。

Consumer 應選擇一種明確做法：停用自己、暫停依賴 AceLib 的功能，或使用不依賴 AceLib 的替代路徑。不要忽略 `isReady()` 繼續呼叫服務。

## AceLib 自己的 reload

AceLib 的 reload 是函式庫內部生命週期操作，不是 Bukkit `/reload`。Bukkit `/reload` 不受支援。

內部 reload 成功後，同一個 provider 會回傳新的 `AceLibApi`。若 reload 回復到原有服務，provider 也可能繼續回傳舊的 API。公開 API 不提供 reload 成敗的 boolean；`isReady()` 只表示服務目前能不能用，不能拿來判斷 reload 是否成功。

因此 consumer 應重新讀取 `provider.api()`，不要比較物件是否相同，也不要自行推斷 reload 結果。

## 停用時

AceLib 停用時會從 `ServicesManager` 移除 registration：

- 再次呼叫 `getRegistration(...)` 會得到 `null`。
- 已持有的 provider 仍可呼叫 `api()`，但得到的 API 會是未就緒狀態。
- `api.isReady()` 會回傳 `false`，服務操作也會被拒絕。

這也是每次使用服務前重新取得 API 並檢查 `isReady()` 的原因。

Provider 本身可從任何執行緒讀取，但這不代表所有服務操作都可在任何執行緒執行。Folia 上的玩家、實體、方塊與 inventory 操作仍須遵守各自的 region 限制；請看[排程](../modules/scheduler.md)與[上下文安全](../modules/context.md)。
