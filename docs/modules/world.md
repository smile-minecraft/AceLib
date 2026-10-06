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

## 事件處理後再傳送請用延後傳送

取消移動事件之後立刻傳送，傳送就算回報成功，位置還是可能被事件處理還原。遇到這種情境時，不要在事件處理內直接呼叫 `teleportPlayer`，改用延後傳送：

```java
world.teleportPlayerDeferred(player.getUniqueId(), target, false, scheduler)
    .thenAccept(result -> {
        // SUCCESS 表示玩家真的在目的地；FAILED + ACELIB-WORLD-018 表示
        // 平台回報成功、但確認時玩家不在目的地（診斷含期望與實際位置）
    });
```

延後傳送把傳送排到事件處理結束之後的 tick，在玩家當下所在的執行緒執行；傳送呼叫返回後立即讀取實際位置做確認。先比世界（不同世界即未到達），同世界再逐軸比座標，每軸誤差在容差內才算到達。預設容差為每軸 0.5 格（`WorldService.DEFAULT_ARRIVAL_TOLERANCE`），可傳入自訂值覆寫。傳送被拒、拋錯、取消的結果原樣透出。

確認時點是傳送呼叫返回後的那一刻；晚於此時才發生的第三方還原不在保證範圍內。

同樣的延後方式也可以用在傳送以外的操作：

```java
TaskTicket<String> ticket = world.deferForPlayer(
    player.getUniqueId(), () -> "done", scheduler);
```

動作在玩家所在執行緒執行一次，終態語意與排程作用域一致（完成、失敗、取消、拒派）。`scheduler` 由呼叫端以自己的 plugin 建立並管理生命週期；玩家退服後動作保證不執行。

## 錯誤碼分層：派送拒絕與服務拒絕

延後入口的離線／停用有兩層，刻意使用不同的錯誤碼前綴：

- 作用域派送層的拒絕說排程的語言：派送當下玩家已離線，`deferForPlayer`
  回票據的終態紀錄為 `ACELIB-SCHED-002`（與直接使用 `scopeFor` 一致）。
- 服務前置檢查的拒絕說世界的語言：`teleportPlayerDeferred` 在派送前
  自己先驗玩家在線、世界存在，失敗直接回 `REJECTED` 傳送結果
  （`ACELIB-WORLD-006`／`ACELIB-WORLD-003` 等），不經過作用域。

呼叫端據此前綴即可判斷該重試哪一層：`SCHED-*` 找排程／玩家狀態，
`WORLD-*` 找世界服務的前置條件。

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
