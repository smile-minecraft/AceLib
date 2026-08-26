# 平台能力

> 適合需要依運行平台能力分流 Paper 與 Folia 邏輯的插件開發者。


AceLib 會把 server 判定為 `FOLIA`、`PAPER` 或 `UNKNOWN`。一般 consumer 不需要自行建立 `PlatformDetector`，直接從 ready 的 `AceLibApi` 讀取結果即可。

```java
Platform platform = api.getPlatform();
PlatformCapability capability = api.getPlatformCapability();
```

## 用能力決定做法

```java
if (capability.regionScheduling()) {
    // Folia：玩家、實體與位置工作要送到所屬 region
} else if (capability.globalScheduler()) {
    // Paper：可使用全域同步排程
} else {
    // UNKNOWN：保守停用這項功能
}
```

| 能力 | Folia | Paper | Unknown |
| --- | --- | --- | --- |
| `regionScheduling()` | `true` | `false` | `false` |
| `globalScheduler()` | `true` | `true` | `false` |
| `bukkitApi()` | `true` | `true` | `false` |
| `foliaThreadedRegionsApi()` | `true` | `false` | `false` |

`UNKNOWN` 的能力全部是 `false`。不要在未知平台猜測 Paper 路徑可用；AceLib 會以 `ACELIB-PLAT-004` 記錄判定失敗。

`Platform` 與 `PlatformCapability` 都是不可變值，可以跨執行緒讀取。這只代表平台資訊本身可安全讀取，不代表 Bukkit 世界物件可在任何執行緒操作。

下一步請看[安全排程](scheduler.md)與[上下文安全](context.md)。完整公開型別分類可查 [API surface](../reference/api-surface.md)。

## 相關頁面

- [安全排程](scheduler.md)
- [上下文安全](context.md)
- [核心 API](core.md)
