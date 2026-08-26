# 執行緒上下文安全

> 適合需要判斷或保證在正確執行緒或 region 操作遊戲物件的插件開發者。


Paper 通常要求在主執行緒修改遊戲物件；Folia 要求在目標所屬 region 執行。`SafeExecutor` 可把工作送到正確位置，`ContextInspector` 則用來檢查目前上下文。

## 修改玩家或世界物件

```java
SafeExecutor.executeOnRegion(
    this,
    api.getPlatform(),
    api.getPlatformCapability(),
    player,
    () -> {
        // 玩家相關修改
    });
```

`executeOnRegion` 也有 `Entity` 與 `Location` overload。Folia 會使用 entity 或 region scheduler；Paper 會回到主執行緒。

## 執行背景工作

`executeAsync` 只接受 `OperationType.READ_ONLY`：

```java
SafeExecutor.executeAsync(
    this,
    api.getPlatform(),
    api.getPlatformCapability(),
    OperationType.READ_ONLY,
    () -> {
        // 不存取或修改需要 server thread 的 Bukkit 物件
    });
```

不要把玩家、實體、方塊或 inventory 修改放進 async 工作。背景運算完成後，仍需再用 `executeOnRegion` 或 `SafeScheduler` 把修改送回目標 region。

## 只檢查、不派送

```java
ThreadContext current = ContextInspector.currentContext(api.getPlatform());
ContextCheckResult result = ContextInspector.check(
    current,
    OperationType.PLAYER_MUTATE,
    api.getPlatform());

if (!result.safe()) {
    getLogger().warning(result.code() + ": " + result.reason());
}
```

已知平台的 `READ_ONLY` 檢查會通過。Folia 在錯誤 region 修改會得到 `ACELIB-CTX-003`；Paper 在非主執行緒修改會得到 `ACELIB-CTX-001`；未知平台會以 `ACELIB-CTX-004` 拒絕受限操作。

方法所需的 plugin、platform、capability、目標物件與 runnable 不可為 `null`。完整代碼說明見[錯誤碼](../reference/error-codes.md)。

## 相關頁面

- [安全排程](scheduler.md)
- [世界操作](world.md)
- [平台能力](platform.md)
