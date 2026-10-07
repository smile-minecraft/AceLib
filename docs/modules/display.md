# 每玩家顯示

> 適合要為玩家提供獨立計分板、BossBar，或在世界位置顯示短文字的插件開發者。

先從 ready 的 `AceLibApi` 取得 `DisplayService`。玩家以 UUID 指定；顯示服務負責選擇玩家、位置或實體的正確排程上下文。

## 目錄

- [取得服務與結果](#取得服務與結果)
- [計分板與 BossBar](#計分板與-bossbar)
- [位置型全息字](#位置型全息字)
- [排程、關閉與限制](#排程關閉與限制)
- [Paper／Folia 實機檢查](#paperfolia-實機檢查)
- [相關頁面](#相關頁面)

## 取得服務與結果

```java
var registration = getServer().getServicesManager()
    .getRegistration(AceLibApi.AceLibProvider.class);
if (registration == null || !registration.getProvider().api().isReady()) {
    return;
}

DisplayService displays = registration.getProvider().api().getDisplayService();
```

`getDisplayService()` 永不回傳 `null`。尚未啟用或已停用時會提供 unavailable facade；變更操作回 `FAILED` 與對應的 `ACELIB-DISP-001`／`ACELIB-DISP-002`。不要在每次操作時自行建立另一個服務。

變更操作回 `DisplayResult`：`SUCCESS` 表示已完成，`ACCEPTED` 表示排程已接受但尚未執行，`REJECTED` 表示輸入或目標狀態不符，`FAILED` 表示服務不可用或底層操作失敗。拒絕與失敗結果會帶錯誤碼；輸入參數為 null 時則拋出帶 `ACELIB-DISP-003` 的 `IllegalArgumentException`。

## 計分板與 BossBar

```java
UUID playerId = player.getUniqueId();
DisplayResult board = displays.showScoreboard(playerId,
    Component.text("戰績"), List.of(Component.text("擊殺 3"), Component.text("死亡 1")));

DisplayResult bar = displays.showBossBar(playerId,
    Component.text("首領"), 0.65, BarColor.BLUE, BarStyle.SOLID);
```

- 每位玩家各有一份新計分板及一條專屬 BossBar。重複呼叫會更新同一份追蹤資源。
- 計分板最多 15 行；空行清單會清除分數並保留標題。相同內容不重送。
- BossBar 進度接受 `[0.0, 1.0]`，不會自動截斷；更新既有條時只改標題與進度。
- 玩家離線時回 `REJECTED + ACELIB-DISP-004`。呼叫 `hideScoreboard`、`hideBossBar` 或 `closePlayer` 可清理服務自己的追蹤。
- 服務不保存玩家呼叫前使用的計分板；清理時只有玩家仍使用服務建立的那份計分板才會設回主計分板，不會還原先前的自訂板。若另一個 plugin 已換上自己的板，清理不會覆蓋它。

## 位置型全息字

全息字使用原生 `TextDisplay`，固定在指定 `Location`，不提供跟隨玩家變體：

```java
DisplayResult created = displays.showHologram(player.getLocation(), Component.text("補給點"));
if (created.hologramId() != null) {
    UUID hologramId = created.hologramId();
    DisplayResult updated = displays.updateHologram(hologramId, Component.text("補給點：已補貨"));
    displays.setHologramVisible(hologramId, player.getUniqueId(), true);
    // 完成後：displays.removeHologram(hologramId);
}
```

生成時先設 `setVisibleByDefault(false)` 與 `setPersistent(false)`；文字與可見性只由服務指定的 API 修改。全息字位置所在 chunk 未載入時回 `REJECTED + ACELIB-DISP-006`，不會為了生成而載入 chunk。生成排程若尚未執行，結果會是 `ACCEPTED`，此時 `findHologram(id)` 仍可能為空；完成後可查詢不可變的 `Hologram` 快照，它不暴露 Bukkit `Entity`。

可見性由 `setHologramVisible` 指定觀看者，並使用 plugin-scoped `hideEntity`／`showEntity` 語意；預設對所有玩家隱藏，不影響其他 plugin 的可見性旗標。移除實體或確認實體已退休後，服務會清除自己的追蹤。

## 排程、關閉與限制

- 計分板、BossBar、觀看者可見性變更派送到目標玩家的上下文；全息字生成派送到位置所在 region；全息字更新派送到實體上下文。
- 尚未執行的變更會保留排程句柄。服務 shutdown 時取消在途派送，runnable 另有停用守衛，避免取消競爭後仍套用更新。
- 玩家顯示關閉走玩家原生 scheduler；全息字實體清理走 `Entity#getScheduler()`，不依賴可能已停用的 `SafeScheduler` 任務追蹤。
- reload 時 AceLib 尚未停用，原生實體排程仍可執行清理。plugin disable 時，Folia 上的 EntityScheduler 清理很可能因 plugin 已停用而不執行；disable 清理只屬 best-effort，不保證立即移除全息字。全息字設為不持久且預設隱藏，區塊卸載或伺服器重啟不會把它寫回磁碟；仍應在 plugin 自己的生命週期適時呼叫移除。
- Paper／Folia API 的直接實體操作只在 `Bukkit.isOwnedByCurrentRegion(entity)` 成立時就地執行；否則經實體 scheduler 派送，並提供 retired callback。玩家清理遵循相同的擁有者上下文判斷。

Folia EntityScheduler 說明其 task 在實體擁有的 region 執行、實體退休時呼叫 retired callback，且 plugin 停用時 task 不會執行：[EntityScheduler API](https://github.com/papermc/folia/blob/ver/26.2.x/_autodocs/api-reference/entity-scheduler.md)。Paper 的暫時顯示實體範例也明確設定 `setPersistent(false)`，並提醒仍要手動移除：[Display entities](https://docs.papermc.io/paper/dev/display-entities)。

## Paper／Folia 實機檢查

MockBukkit 4.113.1 提供 `TextDisplayMock`，但它繼承的 `EntityMock#setVisibleByDefault` 尚未實作，不能用來驗證正式後端的安全預設。單元測試以內部 `DisplayPlatform` seam 覆蓋生成、更新、隱藏預設與清理派送；Paper／Folia 的真實執行緒、玩家可見性與停用行為使用[顯示模組實機探針](../../examples/display-probe/README.md)驗證。

## 相關頁面

- [錯誤碼參考](../reference/error-codes.md)
- [安全排程](scheduler.md)
- [Provider 啟用、重載與停用](../consumer/provider-lifecycle.md)
