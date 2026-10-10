# GUI 作用域、導航與元件

> 適合要為玩家開啟受保護 inventory GUI、做按鈕、分頁、確認、輸入的插件開發者。


從 ready 的 `AceLibApi` 取得共用 `GuiService`，再以自己 plugin 建立作用域：

```java
GuiService service = api.getGuiService();
GuiScope gui = GuiScopes.create(this, service);
```

每個 plugin 只能操作自己開的 GUI；跨 plugin 操作回 `NOT_OWNER`（`ACELIB-GUI-019`）。
AceLib 尚未就緒或停用時，GUI 操作會回傳 `NOT_READY` 或 `SHUTDOWN`，不會回傳 `null` service。

`onDisable` 務必關閉自己的作用域（只結束自己的 GUI，不碰其他 plugin）：

```java
@Override
public void onDisable() {
    GuiScopes.close(this);
}
```

任一下游 plugin 停用時，AceLib 也會自動關閉它的作用域；但不要依賴自動關閉，
`onDisable` 主動關閉才能保證 classloader 及時釋放。

成功 reload 後，舊 `GuiService` 實例已停用（舊 listener 已解除註冊，不會留下雙 listener），
舊實例的操作回 `SHUTDOWN`。作用域若以 supplier 建立（`GuiScopes.create(plugin, supplier, clock, forms, probe)`），
reload 後自動讀到新服務；舊 session 已失效，以 `reopen(player)` 用目前視圖重開。
不要繼續持有舊 reference。

公開介面上不再有關閉整個服務的方法（1.4.0 破壞性變更，見[遷移說明](../consumer/provider-lifecycle.md#gui-作用域與公開-shutdown-移除)）：
結束 GUI 請關閉自己的作用域；內部停用只由 AceLib 的 reload／disable 接線執行。

## 開啟與導航

```java
GuiView shop = GuiView.chest("商店", 27)
    .allow(10, 11, 12)
    .button(13, "buy", 5_000L, click -> {
        // click.playerUuid() / click.buttonId() / click.generation()
    })
    .build();

GuiResult opened = gui.openView(player.getUniqueId(), shop);
long generation = opened.session().generation();
```

`open`、`push`、`replace`、`back`、`close` 共用同一份返回歷史。
每次切換畫面都開啟新 session（generation 遞增）：舊 generation 的點擊、票券、輸入一律失效。
玩家開著別的 plugin 的 GUI 時，新的 GUI 取代舊的，原本的 plugin 會收到取代通知
（`gui.onReplaced((uuid, oldSession, newSession) -> { ... })`；通知內不得做長時間工作或跨 region 操作）。

```java
gui.pushView(playerId, categoryView);   // 前進，可 back
gui.replaceView(playerId, updatedView); // 換掉目前頁，不增加層數
gui.back(playerId);                     // 回上一頁；無歷史回 NO_PREVIOUS_VIEW
gui.close(playerId);                    // 關閉
```

每個玩家的 session 由 UUID 與 `generation` 識別；過時操作回 `GENERATION_MISMATCH`，
session 不會被舊代誤刪。

## 按鈕、欄位與冷卻

點擊規則（預設全擋，只開放指定欄位）：

- 落在按鈕欄位 → 執行該按鈕回呼（回 `SUCCESS`）。
- 落在 `allow(...)` 欄位 → 放行（`ALLOWED`，由遊戲邏輯繼續處理）。
- 其餘 → 拒絕（`SLOT_PROTECTED`）。

按鈕點擊走專屬回呼路徑：禁止拿「受保護欄位被拒絕」錯誤當按鈕訊號。
`SLOT_PROTECTED` 只代表擋下，不代表玩家按了某顆按鈕。

按鈕可帶點擊冷卻（同一玩家、同一按鈕；冷卻中回 `COOLDOWN_ACTIVE`，回呼不執行）：

```java
.button(13, "buy", 5_000L, click -> { /* 5 秒內只執行一次 */ })
```

## 用字元遮罩描述版面

幾行等長字串描述一個箱子，每個字元代表一群欄位：

```java
GuiMask mask = GuiMask.of(
    "#########",
    "#..BBB..#",
    "#########");

GuiView shop = GuiView.chest("商店", mask)
    .allow(mask.slots('.'))
    .button(mask.slots('B').get(0), "buy", click -> { /* ... */ })
    .build();
```

- `GuiMask.of(...)` 在建立時檢查形狀：列數 1～6、每列等長、
  列數 × 列寬為合法箱子尺寸（9／18／27／36／45／54），不符擲
  `IllegalArgumentException`（訊息攜帶 `ACELIB-GUI-007` 與實際數值）。
- `mask.slots('#')` 回該符號的欄位清單（列優先、有序、不可變；
  未出現的符號回空清單），可同時用於 `allow` 與 `button`。
- 遮罩只換算固定欄位，不做自動版面配置，資料更新由呼叫端
  以 `replaceView` 給新頁（不做資料綁定）。

## 按鈕物品

按鈕宣告時可一併給物品，開啟時 AceLib 把它放進與按鈕相同的欄位：

```java
GuiView shop = GuiView.chest("商店", mask)
    .button(13, "buy", new ItemStack(Material.DIAMOND_SWORD),
        click -> { /* ... */ })
    .build();
```

- 宣告時**不**複製：開啟前修改該 `ItemStack`，會反映到開啟時放置的物品。
- 物品在開啟時、於玩家所在執行緒（Paper 主執行緒／Folia 該玩家 region）、
  放入欄位之前即時複製；同一宣告給多位玩家開啟時各自獨立，
  修改某位玩家的箱內物品不影響其他人，也不回寫宣告物品。
- 單一欄位放置失敗記 `WARNING`（`ACELIB-GUI-012`，攜帶完整例外）並跳過該欄，
  不影響開啟結果與點擊語意。
- 既有純回呼按鈕（不帶物品）行為完全不變。
- 純單元測試用到物品相關 API 時，classpath 需要 Bukkit／Paper API
 （`ItemStack` 為 Bukkit 型別）；遮罩與純回呼按鈕無此依賴。

分頁用 `GuiPage` 計算某一頁的內容（與 session 獨立，不污染 generation）：

```java
GuiPage<String> page = GuiPage.page(allItems, 21, pageIndex);
if (page.isEmpty()) { /* 空資料替代畫面 */ }
GuiPage<String> loading = GuiPage.loading();
GuiPage<String> error = GuiPage.error(GuiErrorCode.OPERATION_FAILED, "載入失敗");
```

## 做翻頁清單

`GuiPager` 把遮罩與頁面資料組成上一頁／下一頁／頁碼畫面。
先留三種符號：項目區、上一頁、下一頁，再給標題前綴與項目渲染器：

```java
GuiMask mask = GuiMask.of(
    "#########",
    "#IIIIIII#",
    "PPP###NNN");

GuiPager<String> pager = GuiPager.of(mask, 'I', 'P', 'N', "名單",
    (builder, slot, name) -> builder.button(slot, "roster-" + slot,
        click -> { /* 點到 name */ }));

GuiPage<String> page = GuiPage.page(allNames, pager.itemCapacity(), pageIndex);
GuiView view = pager.viewFor(page, new GuiPager.Actions(
    click -> showPage(page.pageIndex() + 1), // 下一頁：呼叫端自己取新頁再開
    click -> showPage(page.pageIndex() - 1)));
gui.openView(playerId, view);
```

- 上一頁按鈕只在 `pageIndex > 0` 時出現，下一頁按鈕只在還有後續頁時出現，
  分別放在該符號的第一個欄位；最後一頁沒有下一頁按鈕。
- 標題自動帶頁碼（`名單 — 頁 1/4`）；空資料、載入中、錯誤各有無導覽按鈕的
  替代畫面（錯誤標題帶 `ACELIB-GUI-*` 代碼，說明文字不渲染進標題）。
- 翻頁由呼叫端驅動，不做自動資料綁定：回呼內自行取新頁，
  再以 `viewFor` 重組視圖並開啟（建議用 `replaceView`，避免歷史堆疊）。
  取頁時以 `pager.itemCapacity()` 為 `pageSize`，項目就不會超出欄位。
- 每次 `viewFor` 都是全新視圖，不記頁碼、不建快取：舊畫面的按鈕在開新畫面後
  自然失效（回 `GENERATION_MISMATCH`），載入中退服沿既有 session 清理，不殘留。
- 項目渲染器內不得使用保留識別字 `pager-prev`／`pager-next`（建視圖時由
  `GuiView` 的按鈕識別字重複檢查擲 `IllegalArgumentException` 拒絕）。
- 本頁項目數超過項目欄位數時建視圖直接被拒（`ACELIB-GUI-007`，訊息帶實際
  項目數與欄位數），不靜默截斷；取頁時以 `pager.itemCapacity()` 為 `pageSize`
  即可避免。

字元遮罩、按鈕物品、翻頁清單與標籤步驟的完整可編譯寫法見 [`GuiV150Example`](../../examples/consumer-plugin/src/main/java/com/example/GuiV150Example.java)。

## 確認票券與送出前重新驗證

確認票券一次性：`confirm`／`cancel` 競爭只解決一次，後到回 `ACTION_ALREADY_RESOLVED`；
session 結束後票券失效，回 `UNKNOWN_ACTION`，callback 永不執行。

```java
GuiResult created = gui.createConfirmation(playerId, generation, "delete-item-42",
    () -> { /* domain action */ });
String token = created.confirmation().actionToken();
gui.confirm(playerId, generation, token);
gui.cancel(playerId, generation, token);
```

送出時必須重新驗證的場景（例如再查一次餘額／庫存），用 `confirmWithRevalidation`：

```java
gui.confirmWithRevalidation(playerId, generation, token, (uuid, gen) ->
    balance.fetch(uuid).map(...)); // 只有 SUCCESS 才執行 domain action
```

驗證失敗（非 `SUCCESS`）時服務自動取消該票券並回傳驗證結果，callback 不執行；
驗證器抛例外視為失敗（fail-closed）。驗證回呼不在任何內部鎖內執行，
可安全呼叫作用域查詢。

## 聊天與鐵砧輸入

```java
GuiInputTicket ticket = gui.promptChat(playerId, generation,
    GuiInputPrompt.chat("請輸入暱稱", 16, 60_000L),
    result -> { /* result.text()，玩家 region 內恰好一次 */ });
gui.submitInput(ticket.token(), "小明"); // 程式送出（測試或指令代填）
```

- 玩家在 session 有效期間的下一則聊天即為輸入內容；命中提示的聊天事件會被取消，未命中放行。
- 票券一次性：送出成功、逾時或 session 結束後失效，重複送出回 `INPUT_EXPIRED`。
- 超長文字被拒（`INVALID_INPUT`）時票券保留，可重試；`timeoutMillis = 0` 表示不逾時。
- 玩家退服、GUI 關閉、作用域關閉、服務停用後送出被拒，consumer 零執行。

鐵砧輸入開啟鐵砧視圖（取代目前畫面），玩家打字後按結果欄送出：

```java
gui.promptAnvil(playerId, GuiInputPrompt.anvil("輸入名稱", 16, 0L),
    result -> { /* result.text() */ });
```

## 共用流程（Java GUI／基岩表單）

同一份 `GuiFlow` 描述兩種呈現：Java 玩家看到 inventory 視圖，基岩玩家收到原生表單。
流程需要表單服務與基岩判定，以完整建立作用域：

```java
GuiScope gui = GuiScopes.create(this, () -> api.getGuiService(),
    Clock.system(), api.getBedrockService().forms(),
    api.getBedrockService()::isBedrockPlayer);

GuiFlow flow = GuiFlow.of(List.of(
    new GuiFlowStep("menu", menuView,
        FormSpec.simple("選單").content("請選擇").button("商店").button("設定").build(),
        Map.of(0, "shop", 1, "settings")),
    new GuiFlowStep("shop", shopView,
        FormSpec.simple("商店").content("買賣").button("買").button("賣").build())),
    "menu", uuid -> { /* 流程走完 */ });

gui.openFlow(playerId, flow);
gui.goTo(playerId, "shop"); // Java 側由按鈕回呼呼叫前進
gui.back(playerId);         // 兩種呈現共用返回歷史
```

- 表單 `VALID` 按轉移表（無對應則線性下一步）自動推進；線性末端結束流程並觸發 `onComplete`。
- 表單 `CLOSED` 結束流程（正常關閉）；`INVALID` 停留；過時回應（推進後才回來）忽略。
- 需要讀 custom 表單元件答案的場景請直接用 `FormService`；流程只負責導航，不做資料綁定。
- 步驟沒有表單時，基岩玩家退回開啟 Java inventory（Geyser 轉譯顯示）。

## 用同一份宣告產生基岩表單

按鈕宣告可順手給基岩可見文字（label），同一個視圖就能長出流程表單，
不用再手寫一份按鈕清單：

```java
GuiView menuView = GuiView.chest("選單", 9)
    .button(2, "shop", "商店", click -> gui.goTo(click.playerUuid(), "shop"))
    .button(6, "settings", "設定", click -> gui.goTo(click.playerUuid(), "settings"))
    .button(8, "deco", click -> { /* 純裝飾：無標籤，不進表單 */ })
    .build();

GuiFlowStep menu = GuiFlowStep.labeled("menu", menuView,
    Map.of(0, "shop", 1, "settings"));
```

- 有標籤的按鈕依欄位升序排成簡單表單的按鈕（與宣告順序無關）；
  轉移表的按鈕索引對應該順序（第 0 顆是欄位最小的有標籤按鈕）。
- 無標籤（`null`）的按鈕不進表單；視圖完全無標籤時退回純 Java 步驟
  （`form` 為 `null`），此時轉移表必須為空。
- 標籤非 `null` 即不可空白；轉移索引超出標籤按鈕範圍會被拒。
  傳 `null` 標籤時請轉型為 `(String) null`，否則編譯器無法在標籤多載
  與物品多載之間選擇。
- 既有的兩份宣告模式（純 Java 建構子、自備表單建構子）維持不變。

## 非同步更新

先用 `beginAsyncUpdate` 建立請求，資料完成後再呼叫 `applyAsyncUpdate`。服務會在套用前重新檢查玩家、session 與 inventory；舊請求會回 `STALE_REQUEST`。

`applyAsyncUpdate` 回傳 `ACCEPTED` 只代表工作已派送，不代表 renderer 已執行完成。

## Paper 與 Folia

Inventory 修改必須在玩家上下文執行。Production GUI service 會透過 `SafeScheduler` 派送；玩家離線或 scheduler 拒絕時，不會留下未完成 session。
退服玩家的 session、票券與輸入由退服事件自動清理；作用域狀態同步丟棄。

Renderer 與各類回呼在玩家 region 內執行，不要從中跨 region 修改其他實體或方塊。
完整錯誤代碼見[錯誤碼](../reference/error-codes.md)。

## 實機驗證探針

Paper／Folia／基岩三端驗證用 `examples/gui-compatibility-probe` 的 `/gprobe` 執行
（部署見該目錄 `build.gradle.kts` 首註解；所有關鍵結果以 `[gprobe-...]` 前綴寫入 server log）：

1. 導航：`/gprobe send <player> nav`（開主畫面）→ 點中央按鈕（push 第二層）→ 點取代（replace 第三層）→ back → close。
   斷言：每次切換 generation 遞增；舊代點擊回 `GENERATION_MISMATCH`；無歷史 `back` 回 `NO_PREVIOUS_VIEW`。
2. 取代通知：A plugin 開 GUI 後，B plugin 對同玩家開 GUI。斷言：A 收到取代通知（舊 session owner=A），玩家畫面為 B 的 GUI。
3. 票券：`/gprobe send <player> ticket`（建票券）→ 點確認鈕或 `/gprobe confirm` → 再執行一次。
   斷言：第一次執行 domain action，第二次回 `ACTION_ALREADY_RESOLVED`；餘額不足時送出前驗證拒絕且不執行。
4. 輸入：`/gprobe send <player> input`（聊天提示）→ 聊天送文字。
   斷言：提示命中時聊天不廣播且 consumer 收到文字（`[gprobe-input]`）；鐵砧輸入另由單元測試覆蓋，實機可以下游 plugin 的鐵砧畫面補測。
5. 冷卻：`/gprobe send <player> cooldown` → 連點中央按鈕。
   斷言：回呼只執行一次，多餘點擊回 `COOLDOWN_ACTIVE`；等待逾時後放行。
6. 基岩：`/gprobe send <player> form` → 基岩客戶端連線觀察彈窗。
   斷言：表單按鈕推進步驟；關閉表單結束流程；Java 端看到同一步驟內容對等。
7. 共用流程：`/gprobe send <player> flow` → 同一份 `GuiFlow` 雙呈現。
   斷言（Java）：第一步箱型畫面 → 點中央按鈕 `goTo` 第二步（`[gprobe-flow] step=confirm-step`）
   → 點完成鈕關閉（`[gprobe-flow] done`）。
   斷言（基岩）：第一步原生表單 → 點「下一步」以 transitions 推進第二步
   → 點「完成」結束流程並觸發完成回呼（`[gprobe-flow] complete`）。
8. 退服／停用：開 GUI 後退服重進、`/acelib` 重載、停用下游 plugin。斷言：舊票券／輸入失效；作用域關閉後操作回 `SCOPE_CLOSED`；不殘留 listener（`HandlerList` 無殘留）。

真人基岩客戶端證據若不可得，據實標記為未驗證，不以 Java 機器人冒充。

## 相關頁面

- [傳送基岩原生表單](form.md)
- [世界操作](world.md)
- [安全排程](scheduler.md)
- [玩家資料與 session](player.md)
- [Provider 生命週期](../consumer/provider-lifecycle.md)
