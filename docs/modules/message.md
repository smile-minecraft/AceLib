# 訊息服務

本頁解決如何載入多語系訊息、格式化文字，以及在 Paper／Folia 傳送玩家訊息的問題。契約唯一來源是
> `src/main/java/com/smile/acelib/message/MessageService.java` 與 tests；
> 本頁為導覽鏡像，不複製完整 JavaDoc。

## 前置條件與最短路徑

先建立 `LangManager`，再以 plugin 與語系管理器建構 `MessageService`，最後使用 `format`、`sendChat` 或其他輸出方法。

## 預期結果

訊息可完成格式化與輸出；缺少 key 或玩家不可用時回傳安全的降級結果並記錄對應 warning，不中斷其他流程。

## 1. 取得方式

`MessageService` 以「建構注入」方式取得（不經 `AceLibApi` facade）：

```java
import com.smile.acelib.config.LangManager;
import com.smile.acelib.message.MessageService;
import java.util.Locale;

LangManager lang = new LangManager(plugin, Locale.TAIWAN);
MessageService messages = new MessageService(plugin, lang);
```

- 建構子會優先重用 `AceLibPlugin` 的 canonical platform / capability 快取；
  一般 plugin 才透過 `PlatformDetector` 自行偵測。呼叫端不需手動注入
  `Platform` 與 `PlatformCapability`。
- 玩家導向訊息通用的 prefix key 為 `message.prefix`（在 `<locale>.yml` 內）。

## 2. 最小正確範例

```java
import java.util.Map;

// 純文字格式化（含 message.prefix）
String text = messages.format("command.reload.done", Map.of("plugin", "MyPlugin"));

// 玩家 chat / action bar / title（key 缺失時回傳空字串並記錄 warning，不中斷）
messages.sendChat(player, "command.reload.done", Map.of("plugin", "MyPlugin"));
messages.sendActionBar(player, "actionbar.ready", Map.of());

// title + 可選 subtitle（subtitleKey 為 null 時不送出）
messages.sendTitle(player, "title.welcome", Map.of("name", player.getName()),
    null, Map.of());

// 全服廣播與 console
messages.broadcast("broadcast.announcement", Map.of());
messages.sendConsole("console.started", Map.of());
```

## 3. Folia／執行緒契約與生命週期

- 玩家訊息在 Folia 環境下走 native API；若捕獲
  `IllegalStateException`（non-owned region 的標準例外），記錄
  `ACELIB-MSG-002` warning 並降級為 silent no-op。
- Paper / UNKNOWN 平台下 player API 拋 `IllegalStateException`（非 Folia
  context 語意）→ 視為一般格式/輸出降級，輸出 `ACELIB-MSG-003` warning；
  不誤標為 `ACELIB-MSG-002`。
- 玩家 null 或離線 → no-op + 適當警告，不中斷執行。
- 執行緒安全：本類別為不可變狀態（所有欄位建構後不變），
  符合多 region 並行安全。

## 常見失敗與錯誤碼

- `ACELIB-MSG-001` 訊息 key 缺失（回傳空字串 + warning）
- `ACELIB-MSG-002` 在不安全上下文操作玩家訊息（Folia）
- `ACELIB-MSG-003` 訊息格式錯誤，或 Paper / UNKNOWN 平台下 player API 拋
  `IllegalStateException` 的安全降級

## 查核來源

- 型別：`MessageService`（依賴 `LangManager`、`Platform`、`PlatformCapability`）
- 測試：`src/test/java/com/smile/acelib/message/MessageServiceTest.java`、
  `MessageServiceFoliaTest.java`
- 下一步：[docs/modules/config.md](config.md)（LangManager）、
  [docs/modules/command.md](command.md)（指令回覆出口消費格式化結果）
