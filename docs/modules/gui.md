# GUI

從 ready 的 `AceLibApi` 取得 `GuiService`：

```java
GuiService gui = api.getGuiService();
```

AceLib 尚未就緒或停用時，GUI 操作會回傳 `NOT_READY` 或 `SHUTDOWN`，不會回傳 `null` service。

## 開啟 GUI

```java
GuiArgument argument = GuiArgument.builder(player, "Shop")
    .size(27)
    .protectedSlots(Set.of(13))
    .build();

GuiResult opened = gui.openInventory(argument);
if (opened.isSuccess()) {
    long generation = opened.session().generation();
    // 保存 generation，後續 click 與 close 都要使用
}
```

每個玩家的 GUI session 由 UUID 與 `generation` 識別。玩家重新開啟 GUI 時，generation 會增加；過時的 click、close 或非同步更新會被拒絕。

```java
GuiResult click = gui.validateClick(player.getUniqueId(), generation, slot);
GuiResult closed = gui.closeInventory(player.getUniqueId(), generation);
```

受保護的 slot 會回傳 `SLOT_PROTECTED`。Generation 不相符會回傳 `GENERATION_MISMATCH`。

## 非同步更新

先用 `beginAsyncUpdate` 建立請求，資料完成後再呼叫 `applyAsyncUpdate`。服務會在套用前重新檢查玩家、session 與 inventory；舊請求會回 `STALE_REQUEST`。

`applyAsyncUpdate` 回傳 `ACCEPTED` 只代表工作已派送，不代表 renderer 已執行完成。

## Paper 與 Folia

Inventory 修改必須在玩家上下文執行。Production GUI service 會透過 `SafeScheduler` 派送；玩家離線或 scheduler 拒絕時，不會留下未完成 session。

Renderer 在玩家 region 內執行，不要從 renderer 跨 region 修改其他實體或方塊。完整錯誤代碼見[錯誤碼](../reference/error-codes.md)。
