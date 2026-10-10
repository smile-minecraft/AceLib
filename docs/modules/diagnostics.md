# 診斷

> 適合要透過狀態指令或程式收集 AceLib 運行診斷的開發者與管理員。


伺服器管理員不需要寫程式。直接執行：

```text
/acelib status
```

玩家需要 `acelib.admin` 權限，server console 可直接執行。報告會列出版本、平台、ready 狀態、模組摘要與最近錯誤：

```text
=== AceLib Diagnostics Report ===
Version: 1.4.0
Platform: Paper
Ready: true

Modules:
  scheduler: READY - ...
```

發現 `FAILED`、`DEGRADED` 或 `ACELIB-*` 時，保留完整報告與啟動日誌，再到[錯誤碼](../reference/error-codes.md)查觸發原因。

## 給 plugin 開發者

目前的 `AceLibApi` 不提供 `getDiagnosticsService()`。下游 plugin 不應 cast 到 `AceLibPlugin` 取得 AceLib 內部的 diagnostics instance；AceLib 自己的狀態請透過 `/acelib status` 查看。

`DiagnosticsService` 仍是公開類別，可用來建立獨立的診斷資料：

```java
DiagnosticsService diagnostics = new DiagnosticsService(Clock.system());
diagnostics.bindPlugin(
    api.getVersion(),
    api.getPlatform(),
    api.getPlatformCapability());

DiagnosticSnapshot snapshot = diagnostics.buildSnapshot();
String report = diagnostics.buildReport().format(false);
```

這個新 instance 不會自動連到 AceLib runtime 的模組或錯誤紀錄。呼叫端必須自行註冊 module state、記錄錯誤並管理生命週期。

`buildSnapshot()` 回傳不可變快照。`recordError(code, detail)` 會套用節流，避免相同錯誤在同一視窗大量重複；`buildReport().format(true)` 會額外輸出 capability 與 throttle 統計。

未知或大小寫不符的錯誤碼會歸類為 `UNKNOWN`，不會拋出例外。

## 清理長時間沒再出現的節流紀錄

`ErrorThrottler` 會為每個出現過的錯誤代碼保留一個視窗。長時間沒再出現的代碼，可由呼叫端在適當時機清理（例如輸出狀態報告前或 reload 流程內）：

```java
int removed = throttler.evictIdleCode(60_000L); // 移除閒置超過 60 秒的代碼
```

閒置指「距上次出現已超過 `idleMillis`」，以建立時注入的時鐘判定。清理是惰性的，內部沒有背景執行緒；只移除閒置視窗，仍活躍視窗的統計不受影響。`idleMillis` 必須大於 0，否則拋 `IllegalArgumentException`。回傳值為本次移除的代碼數量。

## 相關頁面

- [錯誤碼](../reference/error-codes.md)
- [核心 API](core.md)
- [平台能力](platform.md)
