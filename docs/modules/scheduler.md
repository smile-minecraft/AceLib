# Folia-safe 排程

本頁解決插件如何在 Paper 與 Folia 使用同一套安全排程 API 的問題。

## 何時需要

需要延遲、重複、非同步，或需要依玩家、實體、位置所在 region 執行工作時使用此模組。

## 怎麼取得／建立

先取得 ready 的 `AceLibApi`，再以自身 `JavaPlugin`、`Platform` 與 `PlatformCapability` 呼叫 public 三參數 `AceLibScheduler.create(...)`。`ServicesManager` 是插件服務登錄表；`Internal` 表示 AceLib 內部型別；不要使用 `AceLibPlugin` overload。

## 最短範例

```java
SafeScheduler scheduler = AceLibScheduler.create(
    plugin, api.getPlatform(), api.getPlatformCapability());
ScheduledTask task = scheduler.runForPlayer(player, () -> {
    // 玩家所屬 region 的同步操作
});
// 停用或重載時取消已派送工作
scheduler.cancelAll();
```

## 不能做什麼

- Folia 上不要以全域 `BukkitScheduler` 作為玩家、實體或位置工作的預設路徑。
- 不要直接依賴 `SafeSchedulerImpl` 或 `AceLibPlugin` 等 Internal 實作。
- 不要忽略玩家離線、實體失效、chunk 未載入與 plugin disable；模組會回傳 no-op task 與對應 `ACELIB-SCHED-*`。

## 深入說明

契約唯一來源是 `src/main/java/com/smile/acelib/scheduler/**` source 與 tests；本頁為導覽鏡像，不複製完整 JavaDoc。

## 1. 取得方式

透過 `AceLibScheduler` factory 建立 `SafeScheduler`。Consumer 使用
**public** 三參數 overload（`JavaPlugin` + `Platform` + `PlatformCapability`），
不要依賴 `AceLibPlugin`（Internal，不屬穩定契約）：

```java
import com.smile.acelib.AceLibApi;
import com.smile.acelib.scheduler.AceLibScheduler;
import com.smile.acelib.scheduler.SafeScheduler;

AceLibApi api = registration.getProvider().api(); // 經 ServicesManager

SafeScheduler scheduler = AceLibScheduler.create(plugin,
    api.getPlatform(), api.getPlatformCapability());
```

- `plugin` 為 consumer 自身的 `JavaPlugin`；`platform` / `capability`
  建議從 `api.getPlatform()` / `api.getPlatformCapability()` 取得，確保與 AceLib
  偵測結果一致。三者皆不可為 null（任一個 null → `NullPointerException`）。
- `AceLibScheduler.create(AceLibPlugin)` 為 AceLib 內部（Internal）便利方法，
  consumer 不應直接依賴 `AceLibPlugin`。
- 所有 `runXxx(...)` 方法都會回傳非 null 的 `ScheduledTask` 控制代碼。
- 任務型別對照 `TaskType`：`GLOBAL` / `ASYNC` / `LATER` / `TIMER` /
  `PLAYER` / `PLAYER_LATER` / `ENTITY` / `LOCATION`。

## 2. 最小安全範例

```java
import com.smile.acelib.scheduler.ScheduledTask;

// 全域同步任務（下一個 tick 跑）
ScheduledTask t1 = scheduler.runGlobal(() -> {
    // 主執行緒安全操作
});

// 玩家相關任務（Folia 下使用玩家自己的 entity scheduler）
ScheduledTask t2 = scheduler.runForPlayer(player, () -> {
    // 玩家所屬 region 同步操作
});

// 實體任務：實體失效/死亡時回傳已 cancelled 的 no-op，不丟例外
ScheduledTask t3 = scheduler.runForEntity(entity, () -> { });

// 取消所有已派送任務（disable / reload 時呼叫）
scheduler.cancelAll();
```

## 3. Folia／執行緒契約

- `SafeScheduler` 自動依 `PlatformCapability` 分流：
  - `regionScheduling = true`（Folia）：`runGlobal` 走 GlobalRegionScheduler、
    `runForPlayer`/`runForEntity` 走 entity scheduler、`runAtLocation` 走
    RegionScheduler、`runAsync` 走 AsyncScheduler。
  - `globalScheduler = true`（Paper）：全走 `BukkitScheduler`。
  - 兩者皆 false（UNKNOWN）：回傳 no-op task 並記錄 `ACELIB-SCHED-005`。
- Folia API 以 reflection 呼叫；classpath 不含 Folia API 時落入 fallback，
  記錄 `ACELIB-SCHED-005` 不丟例外。
- 所有 public 方法皆可在多 region 並行環境下安全使用。

## 4. 生命週期與 nullability

| 情境 | 行為 |
| --- | --- |
| 玩家離線 | no-op task + `ACELIB-SCHED-002` |
| 實體失效/死亡 | no-op task + `ACELIB-SCHED-003` |
| chunk 未載入 | no-op task + `ACELIB-SCHED-004` |
| 平台不支援 | no-op task + `ACELIB-SCHED-005` |
| 插件停用（`onPluginDisable` 後） | 所有 `runXxx` 回 no-op + `ACELIB-SCHED-006` |
| 任務內部拋例外 | 記錄 `ACELIB-SCHED-001`，不影響後續任務 |
| null runnable / 目標 | 丟 `NullPointerException`（`delayTicks < 0` 丟 `IllegalArgumentException`） |

- `ScheduledTask.cancel()` 必須冪等；取消後 `isCancelled()` 為 true。
- `getRecorderErrors(int)` 回傳不可變的「時間由舊到新」紀錄清單。
- `AceLibScheduler.bind/unbind` 維護 plugin → scheduler 的 process-local 綁定表；
  disable 時需由 caller 呼叫 `unbind`（或由 plugin 自身 onDisable teardown）。

## 常見失敗與錯誤碼

全部錯誤代碼見 `SafeSchedulerImpl` 與 `TaskErrorRecord`（`ACELIB-SCHED-001` ~
`ACELIB-SCHED-006`）：

- `ACELIB-SCHED-001` 任務內部拋 exception
- `ACELIB-SCHED-002` 玩家離線
- `ACELIB-SCHED-003` 實體失效
- `ACELIB-SCHED-004` chunk 不可用
- `ACELIB-SCHED-005` 平台不支援
- `ACELIB-SCHED-006` 插件停用

## 查核來源

- 介面：`SafeScheduler`、`ScheduledTask`
- 型別：`AceLibScheduler`、`TaskType`、`TaskErrorRecord`、`TaskErrorRecorder`
  （`SafeSchedulerImpl` 為 Internal）
- 測試：`src/test/java/com/smile/acelib/scheduler/SafeSchedulerTest.java`、
  `SafeSchedulerErrorTest.java`、`SafeSchedulerLifecycleTest.java`、
  `TaskErrorRecorderTest.java`
- 下一步：[docs/modules/context.md](context.md)、
  [docs/modules/platform.md](platform.md)
