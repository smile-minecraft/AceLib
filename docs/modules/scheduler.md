# Paper 與 Folia 的安全排程

`SafeScheduler` 用同一套 API 處理全域、非同步、玩家、實體與位置工作。Folia 會送到對應 region 或 entity scheduler；Paper 會走 Bukkit scheduler。

## 建立 scheduler

Consumer 使用自己的 `JavaPlugin`，搭配 AceLib 偵測到的平台資訊：

```java
import com.smile.acelib.scheduler.AceLibScheduler;
import com.smile.acelib.scheduler.SafeScheduler;

SafeScheduler scheduler = AceLibScheduler.create(
    this,
    api.getPlatform(),
    api.getPlatformCapability());
```

不要使用接收 `AceLibPlugin` 的 overload，也不要直接依賴 `SafeSchedulerImpl`。

## 派送工作

```java
ScheduledTask playerTask = scheduler.runForPlayer(player, () -> {
    // Folia：玩家的 entity scheduler
    // Paper：主執行緒
});

ScheduledTask locationTask = scheduler.runAtLocation(location, () -> {
    // Folia：該位置的 region
});

ScheduledTask asyncTask = scheduler.runAsync(() -> {
    // 不要在這裡修改玩家、實體、方塊或 inventory
});
```

全域工作可用 `runGlobal`，延遲與重複工作使用對應的 later/timer 方法。各方法都會回傳 `ScheduledTask`，可呼叫 `cancel()`；取消是冪等操作。

## 無法執行時

玩家離線、實體失效、chunk 未載入、平台不支援或 plugin 已停用時，scheduler 會回傳已取消的 no-op task，並記錄 `ACELIB-SCHED-*`。不要把「已取得 task」解讀為工作一定執行成功。

在 plugin 停用或你自己的服務重載時，呼叫：

```java
scheduler.cancelAll();
```

Folia 上不要把全域 `BukkitScheduler` 當成玩家、實體或位置工作的預設路徑。若工作從 async callback 回來後要修改遊戲物件，請再次用 scheduler 送回正確位置。

錯誤代碼與觸發原因見[錯誤碼](../reference/error-codes.md)。需要先判斷目前執行緒是否允許操作時，請看[上下文安全](context.md)。
