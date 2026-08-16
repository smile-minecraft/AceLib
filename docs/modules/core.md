# 核心 API

先依[快速開始](../consumer/quickstart.md)取得 ready 的 `AceLibApi`。這個物件提供版本、平台能力，以及 AceLib 對外管理的服務。

```java
AceLibApi api = registration.getProvider().api();
if (!api.isReady()) {
    return;
}

String version = api.getVersion();
Platform platform = api.getPlatform();
PlatformCapability capability = api.getPlatformCapability();

WorldService world = api.getWorldService();
GuiService gui = api.getGuiService();
ExternalIntegrationService external = api.getExternalIntegrationService();
```

上述 getter 不會回傳 `null`。AceLib 尚未就緒或已停用時，服務會回傳拒絕結果，而不是讓 consumer 取得半初始化的實作。不過呼叫端仍應先檢查 `isReady()`。

## 不要直接依賴 `AceLibPlugin`

`AceLibPlugin` 是 AceLib 的 runtime 實作，不是下游 plugin 的取得入口。Consumer 應只透過 `AceLibApi.AceLibProvider` 與公開 service 介面工作。

也不要假設 `AceLibApi` 物件永遠不變。AceLib 自己的 reload 可能更新 provider 回傳的 API；使用服務前重新呼叫 `provider.api()`。啟用、重載與停用的完整行為見 [Provider 生命週期](../consumer/provider-lifecycle.md)。

## 依能力分流

比起自行反射 classpath，優先讀取 `PlatformCapability`：

```java
PlatformCapability capability = api.getPlatformCapability();
if (capability.regionScheduling()) {
    // Folia region-aware 路徑
} else if (capability.globalScheduler()) {
    // Paper 全域排程路徑
} else {
    // 未知平台：不要執行受限操作
}
```

玩家、實體、方塊與 inventory 是否可操作，仍取決於目前執行緒。接著閱讀[平台能力](platform.md)、[安全排程](scheduler.md)與[上下文安全](context.md)。
