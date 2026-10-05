# 世界操作

> 適合要以安全方式讀寫方塊、實體與傳送的插件開發者。


從 ready 的 `AceLibApi` 取得 `WorldService`：

```java
WorldService world = api.getWorldService();
```

服務不會回傳 `null`。AceLib 尚未就緒或已停用時，操作會回傳拒絕結果。

成功 reload 後，舊 `WorldService` 實例會 shutdown：`getModuleStatus` 不再是 `READY`，讀寫操作回 `SHUTDOWN`。請重新向 `AceLibApi` 取得新實例，不要繼續持有舊 reference。

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

## 附近查詢只掃 owner-safe 範圍

`findNearbyEntities` 與 `findNearbyPlayers` 在 Paper 上以範圍查詢的平台 API 取 bounded 候選，再以球形距離與類型篩選，不會掃過整個世界。Folia 上若無法證明跨 region 安全，服務直接回 `ACELIB-WORLD-008`，不會呼叫未知實作。

查詢以中心座標所在 chunk 是否載入為 fail-closed 條件：中心 chunk 未載入時直接回 `ACELIB-WORLD-004`，不會觸發載入或掃描鄰近 chunk。

底層 `WorldBackend` 的 `findNearby` 與 `findNearbyPlayers` 為維持二進位相容保留 `List` 回傳：失敗時只能回空清單，無法區分「查過但沒命中」與「無法安全查詢」。需要明確錯誤碼時請使用 `WorldService` 的結構化結果，服務會經由內建 result 橋接保留原始錯誤。

## 相關頁面

- [安全排程](scheduler.md)
- [上下文安全](context.md)
- [玩家資料與 session](player.md)
