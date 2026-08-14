# 診斷服務

本頁解決如何建立診斷快照、產生人類可讀報告，以及判讀錯誤代碼與節流統計的問題。契約唯一來源是
> `src/main/java/com/smile/acelib/diagnostics/**` source 與 tests；
> 本頁為導覽鏡像，不複製完整 JavaDoc。

## 前置條件與最短路徑

從 ready 的 `AceLibApi` 取得 `DiagnosticsService`，呼叫 `buildSnapshot()` 查詢一致快照，或呼叫 `buildReport().format(false)` 產生文字報告。

## 預期結果

報告包含版本、平台、模組狀態、錯誤與節流資訊；未知或大小寫不符的錯誤碼分類為 `UNKNOWN`，不會拋出例外。

## 1. 取得方式

正式取得入口是 `AceLibApi.getDiagnosticsService()`（`DiagnosticsService`）：

```java
import com.smile.acelib.diagnostics.DiagnosticsService;
import com.smile.acelib.diagnostics.DiagnosticSnapshot;

DiagnosticsService diagnostics = api.getDiagnosticsService();
DiagnosticSnapshot snapshot = diagnostics.buildSnapshot(); // 不可變
String report = diagnostics.buildReport().format(false);   // 人類可讀
```

- `DiagnosticsService` 為 singleton-ish service（由 plugin 綁定）；未 bind
  plugin 時 `getVersion()` 回 `AceLibVersion.VERSION`、`getPlatform()` 回
  `Platform.UNKNOWN`（保守降級）。

## 2. Snapshot 一致性契約

- `buildSnapshot()` 在單一呼叫內建立一致的版本／平台／模組／錯誤／節流快照；
  節流統計使用 `ErrorThrottler.snapshotStats()` 併發安全快照，避免
  `trackedKeys() + getStats()` 之間被 `reset()` 介入得到 null。
- 所有集合欄位（`modules()` / `recentErrors()` / `throttleSnapshot()`）為
  不可變視圖；呼叫端修改會拋 `UnsupportedOperationException`。
- `timestamp()` 為 `Instant` 推導值（不可寫入）。
- 模組狀態：`ModuleState`（`READY` / `NOT_INITIALIZED` / `UNAVAILABLE` /
  `FAILED` / `DEGRADED`）；預設 5 個模組（scheduler / config / lang /
  integration / data）未註冊時標記 `NOT_INITIALIZED`。

## 3. 錯誤節流契約

`ErrorThrottler` 對每個 error code 維護獨立視窗：

```java
ThrottleDecision decision = diagnostics.recordError("ACELIB-SCHED-001", "detail");
if (decision.kind() == ThrottleDecision.Kind.ALLOWED) {
    // 視窗內前 N 次（N = maxPerWindow）放行
} else {
    // SUPPRESSED：視窗內已達上限；detail 保留最近一次 ALLOWED 的訊息
}
```

- `DiagnosticsService` 內部使用 `maxPerWindow = 1`（duplicate suppression）：
  視窗內同 code 第二次起 SUPPRESSED。
- `ErrorThrottler` 通用預設 `maxPerWindow = 5`（視窗內前 5 次放行）。
- 累計語意：`getAllowedCount` / `getSuppressedCount` 跨視窗累計；
  `getStats` 回當前視窗內計數。
- 測試全程使用 deterministic `Clock`，禁止 sleep。

## 常見失敗與錯誤代碼

- `ErrorCodeRegistry.categorize(code)` 把 `ACELIB-<AREA>-<CODE>` 抽出 AREA
  並對應 `ErrorCategory`；未知／大小寫不符一律 `UNKNOWN`，不丟例外。
- `ErrorCodeRegistry.lookup(code)` 回已知代碼的 `ErrorCodeInfo`；未知回 null。
- `ErrorCategory` 是唯一分類來源（PLAT / SCHED / CTX / CFG / MSG / LANG /
  CMD / EVT / PLAYER / WORLD / GUI / ITEM / DATA / EXT / DBG / UNKNOWN）。

## 5. 生命週期

- `bindPlugin`（一次性）設定 version／platform／capability；重複呼叫拋
  `IllegalStateException`。
- `rebindPlugin` 供 reload 更新 metadata（冪等，可覆寫）；
  `restoreMetadata` 供 reload rollback 還原（不改 `bound`／`ready`）。
- `markSchedulerDisabled` 把 scheduler 模組降級為 `FAILED + ACELIB-SCHED-006`；
  `resetThrottler` 清空節流狀態。

## 下一步

- 伺服器管理員：查看根 [README.md](../../README.md) 的 `/acelib status` 與錯誤碼說明。
- 需要模組 API 分類：查看 [API surface](../reference/api-surface.md)。

## 查核來源

- 入口：`DiagnosticsService`（Supported）
- SPI：`Clock`
- 型別：`DiagnosticSnapshot`、`DiagnosticReport`、`ErrorThrottler`、
  `ThrottleDecision`、`ThrottleStats`、`ModuleState`、`ModuleStatus`、
  `ErrorCategory`、`ErrorCodeInfo`、`ErrorSummaryLine`、`ErrorCodeRegistry`
- 測試：`src/test/java/com/smile/acelib/diagnostics/DiagnosticsServiceTest.java`、
  `DiagnosticSnapshotTest.java`、`ErrorThrottlerTest.java`、
  `ErrorCodeRegistryTest.java`
