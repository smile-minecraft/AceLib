# GUI 服務

本頁解決如何開啟 GUI、保存 session generation、處理非同步更新，以及避免玩家 inventory 跨 region 操作的問題。契約唯一來源是
> `src/main/java/com/smile/acelib/gui/**` source 與 tests；
> 本頁為導覽鏡像，不複製完整 JavaDoc。

## 前置條件與最短路徑

從 ready 的 `AceLibApi` 取得 `GuiService`，建立 `GuiArgument`，在玩家 region context（Paper 為主執行緒）呼叫 `openInventory`。

## 預期結果

成功開啟後取得 generation；後續 click、close 與非同步更新都使用同一 session 識別，過時或不匹配的操作會被拒絕。

## 1. 取得方式

正式取得入口是 `AceLibApi.getGuiService()`：

```java
import com.smile.acelib.AceLibApi;
import com.smile.acelib.gui.GuiService;

AceLibApi api = registration.getProvider().api(); // 經 ServicesManager
GuiService gui = api.getGuiService();             // 永不為 null
```

- 未啟用／已停用時為 `GuiServiceUnavailableImpl`：所有操作回
  `REJECTED + NOT_READY / SHUTDOWN`（`ACELIB-GUI-001/002`），不丟例外。
- 也可直接 `GuiService.forProduction(scheduler)`（需有 `SafeScheduler`）或
  `GuiService.forUnavailable(code)` 取得實作實例。

## 2. 最小安全範例

```java
import com.smile.acelib.gui.GuiArgument;
import com.smile.acelib.gui.GuiResult;
import org.bukkit.entity.Player;

Player player = ...; // 已在正確執行緒（region/main）
GuiArgument argument = GuiArgument.builder(player, "Shop")
    .size(27)
    .protectedSlots(Set.of(13)) // 中央 slot 受保護
    .build();

GuiResult opened = gui.openInventory(argument);
if (opened.isSuccess()) {
    long generation = opened.session().generation(); // 保存，之後操作要帶
    // 之後：gui.validateClick(player.getUniqueId(), generation, slot);
    // 關閉：gui.closeInventory(player.getUniqueId(), generation);
}
```

## 3. Session 與生命週期契約

- `GuiSession` 是不可變物件，以 `playerUuid` + `generation` 複合識別；
  同一 UUID 重新開啟 GUI 時 generation 單調遞增（不可重用）。
- `closeInventory` / `validateClick` 必須傳入正確 generation；不符回
  `GENERATION_MISMATCH`（`ACELIB-GUI-011`）。
- 玩家已開 GUI 重複 `openInventory` → `SESSION_EXISTS`（`ACELIB-GUI-009`）。
- 確認／取消流程：`createConfirmation` 回傳 `GuiConfirmation`（含不透明
  `actionToken`）→ `confirm` / `cancel` 一次性解析；重複呼叫回
  `ACTION_ALREADY_RESOLVED`（`ACELIB-GUI-014`）。
- shutdown／reload 時所有 active session 被清理；之後呼叫一律回
  `SHUTDOWN`（`ACELIB-GUI-002`）。
- 非同步更新：`beginAsyncUpdate` 取得 `GuiAsyncRequest` → 資料回來後
  `applyAsyncUpdate(request, page, renderer)`；套用前重新驗證，舊請求回
  `STALE_REQUEST`（`ACELIB-GUI-016`）。

## 4. Folia／執行緒契約

- **inventory mutation 必須在玩家 region context 執行**：Folia 走 entity
  scheduler、Paper 走主執行緒。實作層透過 `SafeScheduler.runForPlayer`
  派送；`PlayerContextExecutor.runOnPlayerRegion` 回傳 false（scheduler
  disabled／player offline）時，`openInventory` 回 `SCHEDULER_REJECTED`
  （`ACELIB-GUI-013`），不留 stale session。
- `renderer`（`applyAsyncUpdate` 的 callback）在玩家 region 內**恰好執行一次**；
  callback 不得直接 mutate inventory／實體／方塊，否則 Folia 觸發跨 region
  違規（`ACELIB-CTX-001`）。
- 延遲 executor（production）下 `applyAsyncUpdate` 回 `ACCEPTED` 表示
  「已派送、尚未執行」，不得視為 renderer 已完成。

## 常見失敗與錯誤碼

見 `GuiErrorCode`（`ACELIB-GUI-001` ~ `ACELIB-GUI-018`），例如：

- `ACELIB-GUI-010 SLOT_PROTECTED`：玩家操作受保護 slot
- `ACELIB-GUI-017 PLAYER_OFFLINE`：非同步結果回來時玩家已離線
- `ACELIB-GUI-018 INVENTORY_MISMATCH`：目前開啟的 inventory 已非本 session 綁定

## 查核來源

- 介面：`GuiService`
- 型別：`GuiArgument`、`GuiSession`、`GuiResult`、`GuiState`、`GuiPage`、
  `GuiConfirmation`、`GuiAsyncRequest`、`GuiErrorCode`
- 測試：`src/test/java/com/smile/acelib/gui/GuiServiceImplTest.java`、
  `GuiFoliaPathTest.java`、`GuiAsyncUpdateTest.java`、
  `GuiConfirmationTest.java`、`GuiServiceInventoryLifecycleTest.java`、
  `GuiServiceSchedulerRejectionTest.java`
- 下一步：[docs/consumer/quickstart.md](../consumer/quickstart.md#5-使用-facade-與平台分支)
