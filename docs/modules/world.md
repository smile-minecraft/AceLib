# 世界操作

> 適合要以安全方式讀寫方塊、實體與傳送的插件開發者。


從 ready 的 `AceLibApi` 取得 `WorldService`：

```java
WorldService world = api.getWorldService();
```

服務不會回傳 `null`。AceLib 尚未就緒或已停用時，操作會回傳拒絕結果。

## 使用 snapshot 描述目標

世界 API 使用不可變的 `LocationSnapshot` 與 `EntityReference`，避免長時間保存可變的 Bukkit 物件。

```java
UUID worldId = bukkitWorld.getUID();
LocationSnapshot location = LocationSnapshot.of(worldId, 100, 64, -200);

BlockResult block = world.readBlock(location);
if (block.isSuccess()) {
    getLogger().info(block.blockKey());
}
```

其他操作包括 `writeBlock`、`spawnEntity`、`removeEntity`、`playEffect`、附近實體查詢，以及玩家或實體傳送。

## 傳送是非同步操作

```java
world.teleportPlayer(player.getUniqueId(), target, false)
    .thenAccept(result -> {
        // 檢查 SUCCESS、REJECTED、FAILED、CANCELLED 或 PARTIAL
    });
```

不要假設傳送在方法回傳時已完成。跨 region 操作可能部分完成，結果會標記為 `PARTIAL`。

## 呼叫端負責正確執行緒

`WorldService` 不會替每一個同步讀寫自動切換執行緒。Folia 的方塊、實體與位置操作必須在目標 region；Paper 則需在主執行緒。先使用 `SafeScheduler.runAtLocation`、`runForEntity` 或 `runForPlayer` 派送，再呼叫 world API。

世界不存在、chunk 未載入、實體失效與玩家離線都會回傳明確結果。`null` 或無效半徑等輸入會直接拋出帶 `ACELIB-WORLD-007` 的例外。完整代碼見[錯誤碼](../reference/error-codes.md)。

## 相關頁面

- [安全排程](scheduler.md)
- [上下文安全](context.md)
- [玩家資料與 session](player.md)
