# 上下文安全與執行

本頁解決如何確認目前執行緒能否安全操作玩家、實體與方塊，以及如何把工作派回正確上下文的問題。

## 第一個安全操作

玩家、實體或位置的修改先送到正確上下文；讀取工作才放入非同步流程：

```java
SafeExecutor.executeOnRegion(plugin, platform, capability, player, () -> {
    // 玩家相關 mutate；Folia 在玩家 region，Paper 在主執行緒
});

SafeExecutor.executeAsync(plugin, platform, capability,
    OperationType.READ_ONLY, () -> {
        // 不修改 Bukkit 遊戲物件的讀取
    });
```

## 不能做什麼

- 不要把玩家、實體或方塊的 mutate 放進 `executeAsync`。
- 不要假設非同步 callback 仍位於原本的 region context。
- UNKNOWN 平台不要執行受限操作；模組會回報 `ACELIB-CTX-004`。

## 深入說明

契約唯一來源是 `src/main/java/com/smile/acelib/context/**` source 與 tests；本頁為導覽鏡像，不複製完整 JavaDoc。

### 取得方式

`SafeExecutor` 與 `ContextInspector` 皆為 static utility，不需持有 instance：

```java
import com.smile.acelib.context.SafeExecutor;
import com.smile.acelib.context.OperationType;
import com.smile.acelib.context.ThreadContext;
import com.smile.acelib.context.ContextInspector;
import com.smile.acelib.context.ContextCheckResult;
```

`DebugMode` 亦為 static 開關（system property `acelib.debug` 最高優先）。

### 最小安全範例

```java
// 在玩家所屬 region 執行 mutate（Folia entity scheduler / Paper main thread）
SafeExecutor.executeOnRegion(plugin, platform, capability, player, () -> {
    // 玩家相關 mutate
});

// 非同步 read-only（executeAsync 只接受 READ_ONLY）
SafeExecutor.executeAsync(plugin, platform, capability,
    OperationType.READ_ONLY, () -> {
        // 任何執行緒都可執行的讀取
    });

// 主動檢查上下文（不執行，只查詢）
ThreadContext ctx = ContextInspector.currentContext(platform);
ContextCheckResult result = ContextInspector.check(ctx, OperationType.PLAYER_MUTATE, platform);
if (!result.safe()) {
    // result.code() / result.reason() 可讀
}
```

### Folia／執行緒契約

- `SafeExecutor.executeOnRegion(Player/Entity/Location, ...)` 自動選擇正確
  scheduler：Folia 走 entity / region scheduler、Paper 走 main thread。
- `executeAsync` 只接受 `READ_ONLY`；對 mutate 操作會拋
  `ContextException`（從 main thread 送出為 `ACELIB-CTX-002`，
  從 async thread 送出為 `ACELIB-CTX-001`）。
- `ContextInspector.check(...)` 規則表（純函式）：
  - FOLIA：`FOLIA_REGION` 允許 mutate；非 region thread 對 region-bound
    物件回 `ACELIB-CTX-003`。
  - PAPER：`PAPER_MAIN` 允許 mutate；其餘回 `ACELIB-CTX-001`。
  - UNKNOWN 平台：一律回 `ACELIB-CTX-004`（READ_ONLY 也拒絕）。
  - `READ_ONLY` 在已知平台永遠允許。
- 執行緒安全分型別：
  - `ContextCheckResult` 為不可變 record、`ContextException` 為不可變例外，
    可在任何 thread 安全讀取。
  - `ContextInspector` 為無自身 mutable state 的 static utility（不持有欄位、
    不輸出 log），可在任何 thread 呼叫；但 `currentContext(...)` 讀取目前
    執行緒 / Bukkit 全域狀態，回傳值依賴呼叫當下環境，非嚴格純函式。
  - `SafeExecutor` 亦為 static utility（無自身 mutable field），方法可跨
    thread 安全呼叫，但每次呼叫會建立新的 `SafeSchedulerImpl`（有狀態）並
    派送任務，且有 side effect（log）。
  - `DebugMode` 是受控的 mutable static state（`setEnabled` /
    `clearExplicit` / `clearCache` 修改 process-global 狀態，內部以
    `AtomicReference` + `synchronized` 保護，跨 thread 讀寫安全，但測試
    建議於 teardown 重置）。

### 生命週期與 nullability

| 情境 | 行為 |
| --- | --- |
| `executeAsync` READ_ONLY | 直接派送 async pool |
| `executeAsync` mutate（main thread） | 拋 `ContextException` `ACELIB-CTX-002` |
| `executeAsync` mutate（async thread） | 拋 `ContextException` `ACELIB-CTX-001` |
| Folia 非 region thread mutate | `ACELIB-CTX-003` |
| UNKNOWN 平台 mutate | `ACELIB-CTX-004` |
| 必要參數為 null（`plugin` / `platform` / `capability` / `player` / `entity` / `location` / `runnable`，以及 `executeAsync` 的 `op`） | 丟 `NullPointerException` |
| `executeOnRegion` 的 `op` 為 null | **可接受**：只影響除錯模式的 annotate log（`op` 只供 debug 輸出），不影響實際派送路徑 |

> 註 1：`platform` / `capability` 必須非 null — `executeAsync` 直接以
> `requireNonNull` 檢查；`executeOnRegion` 內部建立 `SafeSchedulerImpl` 時
> （建構子 L106-108）同樣要求非 null，任一為 null 最終都會拋
> `NullPointerException`。
>
> 註 2：`executeOnRegion` 三個 overload 的 `op`（Player/Entity/Location 版本皆同）
> 未做 `requireNonNull` 檢查，正常與取消路徑皆可為 null；與 `executeAsync` 的
> `op`（有 `requireNonNull`）契約不同。

- `ContextCheckResult.allowed()`：`safe=true`、`code=null`、`reason=null`。
- `ContextCheckResult.denied(code, reason)`：`safe=false` 且攜帶 code/reason；
  code/reason 不可為 null。
- `ContextException` 的 `getCode()` / `getCurrentContext()` /
  `getOperationType()` 不可為 null；`getTargetInfo()` 可為 null。
- `plugin` / `platform` / `capability` / `player` / `entity` / `location` /
  `runnable` 皆不可為 null（任一 null → `NullPointerException`）。
- `executeOnRegion` 的 `op` 可為 null（僅供 debug annotate log）；`executeAsync`
  的 `op` 不可為 null（有 `requireNonNull`）。
- `DebugMode` 優先順序：system property → `setEnabled(boolean)` → 預設 false
  （system property 存在時覆寫 explicit 值）。

### 常見失敗與錯誤碼

全部錯誤代碼見 `ContextInspector` / `SafeExecutor`（`ACELIB-CTX-001` ~
`ACELIB-CTX-004`）：

- `ACELIB-CTX-001` 在錯誤上下文 mutate 遊戲物件（一般 async/global）
- `ACELIB-CTX-002` 非同步流程完成後嘗試 mutate（main thread 送出 runAsync mutate）
- `ACELIB-CTX-003` Folia 環境下非 region thread 操作 region-bound 物件
- `ACELIB-CTX-004` 平台不支援此操作（UNKNOWN）

### 查核來源

- 型別：`SafeExecutor`、`ContextInspector`、`ContextCheckResult`、
  `ContextException`、`ThreadContext`、`OperationType`、`DebugMode`
- 測試：`src/test/java/com/smile/acelib/context/SafeExecutorTest.java`、
  `ContextInspectorTest.java`、`ThreadContextTest.java`、
  `ContextExceptionTest.java`、`DebugModeTest.java`
- 下一步：[docs/modules/scheduler.md](scheduler.md)、
  [docs/modules/platform.md](platform.md)
