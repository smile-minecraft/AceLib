# 訊息服務

> 適合要發送本地化聊天、動作欄與標題訊息的插件開發者。


`MessageService` 使用 `LangManager` 載入的語系內容，提供格式化、聊天、action bar、title、廣播與 console 輸出。Consumer 自行建立這兩個物件。

## 建立服務

```java
LangManager lang = new LangManager(this, Locale.TAIWAN);
MessageService messages = new MessageService(this, lang);
```

玩家訊息的共用前綴 key 是 `message.prefix`。

## 格式化與傳送

```java
String text = messages.format(
    "command.reload.done",
    Map.of("plugin", getName()));

messages.sendChat(player, "command.reload.done", Map.of("plugin", getName()));
messages.sendActionBar(player, "actionbar.ready", Map.of());
messages.sendTitle(player, "title.welcome", Map.of("name", player.getName()));
messages.broadcast("broadcast.announcement", Map.of());
messages.sendConsole("console.started", Map.of());
```

缺少訊息 key 時會回傳空字串並記錄 warning，不會中斷其他流程。玩家為 `null` 或已離線時，玩家輸出會安全略過。

## Paper 與 Folia

對玩家送訊息仍受 server 的執行緒規則約束。Folia 在不屬於玩家的 region 操作時，AceLib 會以 `ACELIB-MSG-002` 記錄並略過；其他格式或輸出問題使用 `ACELIB-MSG-003`。

如果訊息來自背景工作，先用[安全排程](scheduler.md)或[上下文安全](context.md)回到玩家所在 region。完整代碼見[錯誤碼](../reference/error-codes.md)。

## Adventure Component 管線

除了 `String` 路徑，`MessageService` 也提供原生 `net.kyori.adventure.text.Component` 管線，讓 hover、click、顏色等結構原樣送出，不會被攤平成純文字。

```java
// 從語系 rich template（MiniMessage 字串）渲染 Component，自動套用 message.prefix
Component c = messages.formatComponent("rich.greeting", Map.of("player", name));

// 直接解析明確的 MiniMessage 字串（<key> placeholder 以 unparsed 注入）
Component c2 = messages.parseMiniMessage(
    "<click:open_url:https://example.com>Visit</click>", Map.of());

messages.sendChat(player, c);
messages.sendActionBar(player, c);
messages.sendTitle(player, title, subtitle);
messages.broadcast(c);
```

- `formatComponent(key, vars)`：讀取 raw MiniMessage 模板並保留 `{var}`，由 AceLib 做安全替換（使用者值會先跳脫，避免值中的 `<tag>` 被當成 MiniMessage 標籤注入），再反序列化為 Component，並套用 `message.prefix`。
- `parseMiniMessage(input, vars)`：直接解析 MiniMessage 字串；`vars` 以 `<key>` placeholder 形式、一律 `unparsed` 注入，使用者值不會被解析成標籤或 click/hover 互動。
- `sendChat` / `sendActionBar` / `sendTitle` / `broadcast`（Component 多載）：直接送出原始 Component，**不**套 prefix、**不**執行任何 Bedrock fallback；prefix 與 key 模板請使用 `formatComponent`。

> **Bedrock 相容性**：本管線不對 Bedrock 玩家做特殊處理或 fallback。實機觀察顯示 Bedrock 端視覺樣式（顏色、gradient、rainbow、translatable 等）保留、四種 click（`open_url`／`run_command`／`suggest_command`／`copy_to_clipboard`）無效果、hover tooltip 尚未驗證；詳見 [Bedrock 訊息相容性矩陣](../reference/bedrock-message-compatibility-matrix.md)。該矩陣為 beta 探索性觀察（Folia `26.2-4` beta + Geyser `2.11.2-b1232`），不作為穩定版保證。

## 讓 Bedrock 玩家看懂失效的 click（顯式 WithFallback）

上一節的 Component 多載**不會**自動對 Bedrock 做任何處理。若要讓 Bedrock 玩家看到可讀的 click 說明，請改用顯式的 `*WithFallback` 入口——只有這四個方法會做 Bedrock routing，其他路徑維持原始 Component。

### 四個入口與呼叫方式

每個方法都多一個 `Locale localeOverride` 參數，**可為 `null`**。沒有不需要 `Locale` 的重載；傳 `null` 就是「不覆寫，用解析到的玩家語系」。

```java
// 單一玩家：chat / action bar
messages.sendChatWithFallback(player, component, null);
messages.sendChatWithFallback(player, component, Locale.TAIWAN); // 明確覆寫

messages.sendActionBarWithFallback(player, component, null);
messages.sendActionBarWithFallback(player, component, Locale.US);

// title / subtitle 分別處理；subtitle 可為 null（視為空）
Component title = Component.text("Title").clickEvent(ClickEvent.runCommand("/warp"));
Component subtitle = Component.text("subtitle here");
messages.sendTitleWithFallback(player, title, subtitle, null);
messages.sendTitleWithFallback(player, title, null, Locale.TAIWAN);

// 廣播：逐玩家判斷，nullable Locale 會依各玩家分別解析
messages.broadcastWithFallback(component, null);
messages.broadcastWithFallback(component, Locale.US);
```

- `sendChatWithFallback(Player, Component, Locale)` — chat。
- `sendActionBarWithFallback(Player, Component, Locale)` — action bar。
- `sendTitleWithFallback(Player, Component title, Component subtitle, Locale)` — title 與 subtitle 分別降級；`title` 為 `null` 時 silent no-op，`subtitle` 為 `null` 視為空。
- `broadcastWithFallback(Component, Locale)` — 對所有線上玩家廣播；每位玩家各自判斷是否為 Bedrock、各自解析 locale，單一玩家失敗不影響其他人。

### Bedrock 做了什麼、沒做什麼

- **只移除失效的 click**：`BedrockFallbackRenderer` 會遍歷整棵 Component 樹，把每個 `ClickEvent` 節點的 click 移除，保留該節點的正文、顏色與裝飾、巢狀 children 與 `HoverEvent`。沒有 click 的 Component 原樣回傳，不會被不必要改寫。
- **提示只可讀，不會執行**：在被移除的節點後面追加一個可讀的文字提示，提示本身也被防禦性剝離所有 `ClickEvent`，因此輸出樹中**不會殘留任何可執行的 ClickEvent**。不會執行指令、不會開啟網址、也不會操作剪貼簿。
- **四種 action 都有對應提示**：

  | ClickEvent.Action | Lang key | 缺 key 時的安全預設 |
  | --- | --- | --- |
  | `RUN_COMMAND` | `message.bedrock.fallback.run_command` | `[Run command: <payload>]` |
  | `SUGGEST_COMMAND` | `message.bedrock.fallback.suggest_command` | `[Suggest command: <payload>]` |
  | `OPEN_URL` | `message.bedrock.fallback.open_url` | `[Open URL: <payload>]` |
  | `COPY_TO_CLIPBOARD` | `message.bedrock.fallback.copy_to_clipboard` | `[Copy to clipboard: <payload>]` |
  | 未來未知 action | `message.bedrock.fallback.unknown` | `[Action: <payload>]` |

  每個模板以 `<payload>` 作為 unparsed placeholder 注入 click 的 `value()`（例如 `/warp`、`https://example.com`），不會被當成 MiniMessage 標籤再次解析，可避免 payload 注入。

> **Hover 不下結論**：第一版保留原始 `HoverEvent`，不因「Bedrock hover 尚未驗證」而刪除或攤平。實機矩陣中 Bedrock hover 仍為「未驗證」，文件與程式都不宣稱 Bedrock 已支援 hover。

### 語系怎麼決定

fallback 提示的語系只影響**提示文字**，不影響既有全域 `formatComponent` / `parseMiniMessage` 的行為。解析順序為：

1. **每次呼叫傳入的 `localeOverride`** — 非 `null` 就直接採用，不再往下看。
2. **`Player.locale()`** — 安全取得；若回傳 `null`、`Locale.ROOT` 或拋例外，視為無效，往下一層。
3. **Floodgate `BedrockPlayerInfo.languageCode`** — 透過 `BedrockService.getPlayerInfo(UUID)` 取得；空字串、`null` 或無法解析的值往下一層。
4. **`LangManager.getDefaultLocale()`** — 建構時傳入的 default locale。

`languageCode` 的解析容錯 `zh_TW`、`zh-TW`、`en_US`、單一語言（如 `en`、 `zh`）、`en-US` 等寫法（`-` 會先轉 `_`），並以 `[a-zA-Z]{2,8}(_[a-zA-Z]{2,8})?` 校驗；空白、空字串、格式不符一律視為無效並回退到 default，不拋例外。解析是每次呼叫現算，沒有保存 `UUID → Locale` 的常駐對照表，因此也沒有 quit 時需要清理的 per-player 狀態；`reload` / `disable` 後的行為由 `MessageService` 既有的 lifecycle 檢查保證為 no-op。

提示模板透過 `LangManager.get(Locale, key)` 讀取，支援 per-locale 快取與「缺檔或缺 key 時退回 default locale 檔案」的 fallback。若該 key 在請求 locale 與 default locale 都缺失，或模板解析失敗，會記錄 `ACELIB-MSG-004` warning 並使用上表的安全預設文字，**不會中斷訊息發送**。`payload` 仍以 `Placeholder.unparsed` 注入，避免 MiniMessage 注入。

在 `lang/<locale>.yml` 加入對應提示可覆蓋預設，例如：

```yaml
# lang/zh_TW.yml
message.bedrock.fallback.run_command: '執行指令：<payload>'
message.bedrock.fallback.suggest_command: '建議指令：<payload>'
message.bedrock.fallback.open_url: '開啟網址：<payload>'
message.bedrock.fallback.copy_to_clipboard: '複製到剪貼簿：<payload>'

# lang/en_US.yml
message.bedrock.fallback.run_command: 'Run command: <payload>'
message.bedrock.fallback.suggest_command: 'Suggest command: <payload>'
message.bedrock.fallback.open_url: 'Open URL: <payload>'
message.bedrock.fallback.copy_to_clipboard: 'Copy to clipboard: <payload>'
```

### 什麼時候維持原始 Component

- **Java 玩家**：`BedrockService.isBedrockPlayer(UUID)` 明確回 `false` 時，直接送出原始 Component，不做任何改寫。
- **Floodgate 缺席或無法判定**：`BedrockService` 以 `forUnavailable` 形式存在、或 `isBedrockPlayer` / `getPlayerInfo` 拋出 `ACELIB-BED-001` / `ACELIB-BED-002` 等例外時，一律視為「無法判定」，維持原始 Component 並記錄 `ACELIB-MSG-004` warning。可在[錯誤碼](../reference/error-codes.md)查詢 `ACELIB-BED-*` 與 `ACELIB-MSG-004` 的定義。
- **無法建立 BedrockService**：以 2 參數 `new MessageService(plugin, lang)` 建立時，若 `AceLibPlugin` 尚未 ready，會自動解析為 unavailable facade，行為同上——不誤判為 Bedrock。

### 生命週期與錯誤隔離

- `player` 或 `message`（`broadcastWithFallback` 為 `message`、`sendTitleWithFallback` 為 `title`）為 `null` → silent no-op 並以 `warnSilently` 留下可追蹤訊息。
- 玩家已離線（`!player.isOnline()`）→ silent no-op，不送出。
- `MessageService` 所屬 plugin 尚未 ready 或已 disable（`isServiceActive() == false`）→ silent no-op；`broadcastWithFallback` 在 `Server` 取不到或無線上玩家時亦同。
- Folia 執行緒限制仍適用：`player.sendMessage` / `sendActionBar` / `showTitle` / `broadcast` 在錯誤 region 拋 `IllegalStateException` 時，Folia 平台記 `ACELIB-MSG-002`，Paper / UNKNOWN 平台記 `ACELIB-MSG-003`；其他 `Throwable` 一律記 `ACELIB-MSG-003`。`broadcastWithFallback` 與一般 `broadcast(Component)` 相同，採逐玩家 `try/catch`，單一玩家失敗不阻斷其他玩家。
- 取得 `Player.locale()` 或 `BedrockService.getPlayerInfo` 拋例外時，記錄對應 warning 後退回下一層 locale，發送本身不中斷。

> **Beta 限制**：相容性觀察基於 Folia `26.2-4` beta + Geyser `2.11.2-b1232` + Floodgate `2.2.5-SNAPSHOT` 的探索性實機測試，結果不作為穩定版保證。Bedrock 的 click 失效與 hover 未驗證狀態以[相容性矩陣](../reference/bedrock-message-compatibility-matrix.md)為準，文件不把觀察寫成穩定承諾。

## 相關頁面

- [設定檔](config.md)
- [平台能力](platform.md)
- [錯誤碼](../reference/error-codes.md)
