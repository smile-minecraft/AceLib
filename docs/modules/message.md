# 訊息服務

> 適合要發送本地化聊天、動作欄與標題訊息的插件開發者。


`MessageService` 使用 `LangManager` 載入的語系內容，提供格式化、聊天、action bar、title、廣播與 console 輸出。Consumer 自行建立這兩個物件。

## 建立服務

```java
LangManager lang = new LangManager(this, Locale.TAIWAN);
MessageService messages = new MessageService(this, lang);
```

玩家訊息的共用前綴 key 是 `message.prefix`。

## 插件作用域（建議用法）

多個插件各自持有文案時，請經由 `MessageScopes` 統一建立與清理，不要跨 plugin 共用 `LangManager` 或 `MessageService`：

```java
private MessageScope messages;

@Override
public void onEnable() {
    messages = MessageScopes.create(this, Locale.TAIWAN);
    // 升級補 key：只補新 key，管理員改過的文案不動
    messages.syncBuiltinDefaults();
}

@Override
public void onDisable() {
    MessageScopes.close(this);
}
```

- 同一個 key 在不同 plugin 讀到各自的文案，不互讀、不互清；`close` 只移除自己的登記。
- 同一 plugin 重複 `create` 會以 `ACELIB-MSG-006` 拒絕；`close` 後再使用該作用域同樣拋 `ACELIB-MSG-006`。
- `scope.reload()` 重新載入語言檔；`scope.messages()` 取得底層 `MessageService`，`scope.lang()` 取得 `LangManager`。
- 靜態登記強持有 plugin 實例：`onDisable` 務必 `close`，否則阻礙 classloader 釋放。若忘記關閉就停用，下次 `create` 會自動驅逐該殘留登記並重建（啟用中的重複建立仍拒絕）；驅逐是兜底，不要當作正常流程依賴。

## 玩家語系解析器

玩家導向的 `scope.sendChat`／`sendActionBar`／`sendTitle` 會先經解析器決定語系，再以該語系渲染發送。預設解析器跟隨 `Player.locale()`；偏好存在哪裡不做規定，下游可用自己的資料庫實作後換上：

```java
// 例如：從下游自己的資料庫讀偏好，查不到就回 null（退回預設語系）
scope.setResolver(player -> {
    if (player == null) {
        return null;
    }
    return preferences.loadLocale(player.getUniqueId()).orElse(null);
});
```

解析器拋例外或回傳 `null` 時退回作用域預設語系並記錄 `ACELIB-MSG-003`，發送不中斷。

## 語系優先順序與升級補 key

模板讀取順序（由高到低）：

1. 磁碟請求 locale（`<dataFolder>/lang/<locale>.yml`）
2. 磁碟預設 locale
3. 內建資源請求 locale（plugin JAR 內 `lang/<locale>.yml`）
4. 內建資源預設 locale

磁碟自訂文案永遠優先；缺的 key 才讀內建資源；三層皆缺時回空並記錄 `ACELIB-LANG-001`／`ACELIB-MSG-001`。內建資源損壞記 `ACELIB-LANG-002` 並退回下一層。

升級時呼叫 `scope.syncBuiltinDefaults()`（或 `LangManager#syncMissingBuiltinKeys`）把新版 JAR 的新 key 補進磁碟檔：既有 key（含註解與排版）逐位元保留，只附加缺的 key；原檔不存在時以內建資源建立可編輯副本。回傳值為補進的 key 數量，重複呼叫為 0（具冪等性）。

## 共用渲染結果

`MessageService#render` 把同一個模板只讀取、替換、解析一次，回傳的 `RenderedMessage` 同時攜帶三種呈現：

```java
RenderedMessage rendered = messages.render("command.reload.done", Map.of("plugin", getName()));

// GUI 取 Component（含 prefix 與互動結構）
Component gui = rendered.component();
// 表單取安全字串（不帶 prefix）
String form = rendered.formText();
// ActionBar／title 取字串（含 prefix，與 format 輸出一致，保留 MiniMessage 標記不解析）
String text = rendered.text();
```

同一實例可直接餵發送入口，不重讀語言檔：`sendChat(player, rendered)`、`sendActionBar(player, rendered)`、`sendTitle(player, title, subtitle)`、`broadcast(rendered)`。缺 key 的渲染結果不會發送（診斷已在渲染時記錄）。

`render(key, vars, locale)` 以指定語系渲染；`locale` 為 `null` 時跟隨全域目前語系。注意兩點邊界：

- **舊字串入口維持舊語意**：`sendChat(player, key, vars)` 等字串多載仍送 `format()` 字串（經 `player.sendMessage(String)`，MiniMessage 不解析），與歷史行為一致。「共用渲染」只對 `render`／`RenderedMessage`／scope 路徑成立；同一模板在舊字串路徑與新 Component 路徑的顯示可能不同，這是沿用既有行為，不是回歸。
- **prefix 取自全域語系**：`render(locale)` 的模板按指定語系分層讀取，但 `message.prefix` 固定讀全域目前語系，不隨指定語系切換。
- **`formatFormText(key, vars, locale)` 的模板來源**：指定語系非 `null` 時按該語系分層讀取；舊版固定讀全域模板。單語系服無差異，多語系服屬刻意修正（見 CHANGELOG 行為變更）。

## 顯示標籤與程式識別字

GUI 按鈕與表單選項的顯示文字用 `MessageLabel` 與程式分支用的識別字分開：

```java
MessageLabel confirm = scope.label("confirm", "button.confirm", Map.of());
String buttonText = confirm.text(); // 顯示用（管理員可改）
String answerId = confirm.id();     // 程式分支用（永遠是 "confirm"）
```

顯示文字取共用渲染的表單安全字串視圖：不含聊天 prefix，MiniMessage 已解析為可見文字（含 FormText 行尾 `§r` 重置慣例），GUI 按鈕與表單選項可直接顯示。管理員改文案只會改變 `text()`，`id()` 保持穩定；缺標籤 key 時文字退回 `id` 本身並記錄 `ACELIB-MSG-001`。

## 診斷

- `RenderedMessage#missing()` 為 true 表示三層皆缺 key，`diagnosis()` 攜帶 `ACELIB-MSG-001` 說明。缺鍵警告沿用載入週期去重（同一週期同一 key 只記一次，`reload` 後重置）；注意指定語系路徑（`get(Locale, String)`／`render(key, vars, locale)`）缺 key 只記 `MSG-001`，不記 `LANG-001`。
- MiniMessage 解析失敗時退回可見純文字，`diagnosis()` 攜帶 `ACELIB-MSG-003`。渲染失敗每次觸發記一次（與既有 `formatComponent` 解析失敗行為一致，不做跨次去重；日誌量與失敗渲染次數成正比）。
- 語系解析器失敗退回預設語系時記 `ACELIB-MSG-003`。
- 作用域生命週期違規（重複建立、關閉後使用）拋 `IllegalStateException` 並攜帶 `ACELIB-MSG-006`。
- Folia 錯誤 region 發送記 `ACELIB-MSG-002` 並略過；Paper 同型例外記 `ACELIB-MSG-003`。完整代碼見[錯誤碼](../reference/error-codes.md)。

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

> **Bedrock 相容性**：本管線不對 Bedrock 玩家做特殊處理或 fallback。實機觀察顯示 Bedrock 端視覺樣式（顏色、gradient、rainbow、translatable 等）保留、四種 click（`open_url`／`run_command`／`suggest_command`／`copy_to_clipboard`）無效果、hover tooltip 尚未驗證；詳見 [Bedrock 訊息相容性矩陣](../reference/bedrock-message-compatibility-matrix.md)。該矩陣為 beta 探索性觀察（Folia `26.2-4` + Geyser `2.11.2-b1232`），不作為穩定版保證。

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

> **Beta 限制**：相容性觀察基於 Folia `26.2-4` + Geyser `2.11.2-b1232` + Floodgate `2.2.5-SNAPSHOT` 的探索性實機測試，結果不作為穩定版保證。Bedrock 的 click 失效與 hover 未驗證狀態以[相容性矩陣](../reference/bedrock-message-compatibility-matrix.md)為準，文件不把觀察寫成穩定承諾。

## 表單文字轉換（FormText）

`FormText`（`com.smile.acelib.message`）把 Adventure Component 轉為基岩表單可安全顯示的字串，讓同一份 MiniMessage 語系同時餵 Java 聊天與基岩表單。靜態入口可在任意執行緒呼叫，不依賴插件啟用狀態：

```java
import com.smile.acelib.message.FormText;
import com.smile.acelib.message.FormTextOptions;

String label = FormText.render(Component.text("hello"), FormTextOptions.defaults());
```

`FormTextOptions` 只有三個欄位：`clickHints`（是否在原本帶 click 的文字後附加可讀提示；靜態路徑用內建英文提示）、`maxLength`（可見字元上限，色碼不計入、省略號 `…` 計入；`0` 不截斷，負數以 `IllegalArgumentException` 拒絕）、`locale`（`translatable` 解析用；靜態路徑為 null 時用 `Locale.ROOT`）。

`MessageService.formatFormText` 讀語言檔模板走同一條管線，有兩個多載：

```java
// 不附加 click 提示、不截斷；locale 為 null 時用預設語系
String a = messages.formatFormText("form.welcome", Map.of("name", name), Locale.TAIWAN);

// 完整選項：插件語系 click 提示、可見字元上限與 locale
String b = messages.formatFormText("form.welcome", Map.of("name", name),
    new FormTextOptions(true, 120, Locale.TAIWAN));
```

注意以下四點：

- **變數表型別是 `Map<String, Object>`**（與 `format` 一致），不是 `Map<String, String>`。
- **不套 `message.prefix`**：表單內文不需要聊天前綴；`formatConsole` 同樣不套 prefix，只有玩家導向的 `format` 會套。
- **缺 key 回空字串**：與 `format` 一致，記錄 `ACELIB-MSG-001` warning，不中斷執行。
- **多載歧義**：第三個參數若直接傳字面 `null`，編譯器無法在 `Locale` 與 `FormTextOptions` 之間選擇；請先指派給具明確型別的變數再傳入（例如 `Locale locale = null;`）。

轉換規則（完整定義見 `FormText` Javadoc）：

- click 遞迴移除（含 hover 內 Component payload 中的 click）；`clickHints` 為 true 才在原文字後附加可讀提示。
- hover 移除（表單沒有 hover，hover 內容不進入輸出）。
- hex 色、gradient、rainbow 一律降為最接近的 16 色具名色。
- 粗體、斜體、混淆保留；底線與刪除線移除。
- `translatable` 以全域翻譯依 locale 解析，解析不到用 fallback，都沒有則保留 key。
- 換行保留，每行結尾補 `§r`，最終輸出亦以 `§r` 結尾；跨行延續的樣式在下一行重新套用。
- `maxLength` 為正值時以完整可見字元計數截斷（色碼與換行不計入），不切斷代理對、組合字元序列或 emoji。
- 渲染內部失敗時記錄 `ACELIB-MSG-005` warning 並回傳純文字版本，不拋例外。

## 相關頁面

- [設定檔](config.md)
- [平台能力](platform.md)
- [錯誤碼](../reference/error-codes.md)
