# 訊息服務

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
