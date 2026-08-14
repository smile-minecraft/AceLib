# 指令系統

本頁解決需要註冊 Bukkit 指令、處理子指令與安全回覆時，如何使用 command 模組的問題。

## 何時需要

需要權限、玩家／console 限制、參數檢查、冷卻或 Folia-safe 玩家回覆時使用此模組。指令註冊表（registry）是保存指令規格與冷卻狀態的物件。

## 怎麼取得／建立

以自身 plugin 建立 `CommandRegistryImpl` 與 `BukkitReplySink`，再以 `BukkitCommandBridge` 接到 `plugin.yml` 的指令名稱。

## 最短範例

```java
CommandRegistryImpl registry =
    new CommandRegistryImpl(new BukkitReplySink(plugin));
registry.register(CommandSpec.builder("mycmd")
    .subCommand(SubCommandSpec.builder("reload")
        .playerOnly()
        .handler(ctx -> ctx.reply("Reloaded!"))
        .build())
    .build());
```

## 不能做什麼

- 不要把玩家回覆直接送到錯誤執行緒；使用 `ReplySink` 的 region-safe 路徑。
- 不要在 plugin 停用後繼續 `register` 或 `dispatch`；會得到 `ACELIB-CMD-009`。
- `Internal` 是 AceLib 內部型別，不是穩定下游契約；`SPI` 是留給外部實作者的擴充介面。下游只依賴文件列出的穩定介面與必要建立方式。

## 深入說明

契約唯一來源是 `src/main/java/com/smile/acelib/command/**` source 與 tests；本頁為導覽鏡像，不複製完整 JavaDoc。

## 1. 取得方式

command 模組以「建構 registry + 註冊 spec」方式使用。AceLib 內部以
`CommandRegistryImpl(new BukkitReplySink(plugin))` 建立 registry；下游插件
可自行建立或取得既有 registry：

```java
import com.smile.acelib.command.BukkitReplySink;
import com.smile.acelib.command.CommandRegistryImpl;

CommandRegistryImpl registry = new CommandRegistryImpl(new BukkitReplySink(plugin));
```

- `BukkitReplySink` 是預設回覆出口：玩家回覆走 Folia region-safe 派送，
  console 輸出到 plugin logger。
- 若要把 registry 接到 Bukkit 指令系統，使用
  `BukkitCommandBridge(registry).attach(plugin, "mycmd")`；plugin.yml 必須
  宣告對應指令。

## 2. 最小正確範例

```java
import com.smile.acelib.command.CommandContext;
import com.smile.acelib.command.CommandException;
import com.smile.acelib.command.CommandSpec;
import com.smile.acelib.command.SubCommandSpec;

registry.register(
    CommandSpec.builder("mycmd")
        .permission("myplugin.use")
        .description("My plugin command")
        .subCommand(
            SubCommandSpec.builder("reload")
                .permission("myplugin.reload")
                .playerOnly()
                .minArgs(0)
                .handler(ctx -> ctx.reply("Reloaded!"))
                .build())
        .build());
```

- `SubCommandSpec` 可指定：`permission`（null = 無權限需求）、`playerOnly` /
  `consoleOnly`（二擇一）、`minArgs` / `maxArgs`（-1 = 無上限）、
  `cooldownMillis`（毫秒，≤0 表示無冷卻）、`handler`（不可為 null）、
  `completer`（可為 null）。
- handler 內可用 `ctx.sender()` / `ctx.requirePlayer()` /
  `ctx.requireOnlinePlayer()` / `ctx.args()` / `ctx.reply(String)` /
  `ctx.replyError(Throwable)` / `ctx.replyPlayerAsync(String)`。
- 錯誤以 `CommandException`（含 `CommandErrorKind` + `ACELIB-CMD-*` code）
  表達；dispatcher 會自動呼叫 `ReplySink.sendError`，handler 不需 try-catch。

## 3. Folia／執行緒契約與生命週期

- dispatch / tab complete 在 Bukkit 指令執行緒（Paper main thread / Folia
  對應 context）被呼叫；registry 所有 public 方法 thread-safe。
- 對玩家的回覆一律走 `ReplySink` 的 region-safe 派送：
  - `reply(String)` 同步回覆；
  - `replyPlayerAsync(String)` 跨執行緒回覆，內部經
    `SafeExecutor.executeOnRegion` 派送到玩家 region（async 完成後 mutate
    仍符合 Folia 上下文安全）。
- `requirePlayer()` 不檢查離線；需要 mutate 玩家時使用 `requireOnlinePlayer()`
  （非玩家拋 `ACELIB-CMD-004`，離線拋 `ACELIB-CMD-007`）。
- disable / reload：呼叫 `registry.onPluginDisable()` 標記停用、清指令；
  之後 `register` 拋 `ACELIB-CMD-009`、`dispatch` 回覆錯誤，但既有指令 map
  與冷卻狀態保留供 reload 重新註冊（冷卻 / 防重複觸發不因 reload 破壞）。

## 常見失敗與錯誤碼

全部錯誤代碼見 `CommandErrorKind`（`ACELIB-CMD-001` ~ `ACELIB-CMD-011`）：

- `ACELIB-CMD-001` 缺少必要參數
- `ACELIB-CMD-002` 未知的子指令
- `ACELIB-CMD-003` 沒有權限執行
- `ACELIB-CMD-004` 此指令僅限玩家
- `ACELIB-CMD-005` 此指令僅限 console
- `ACELIB-CMD-006` 冷卻中（防止重複觸發）
- `ACELIB-CMD-007` 玩家已離線 / 失效
- `ACELIB-CMD-008` 非同步指令流程失敗
- `ACELIB-CMD-009` registry 已停用（plugin disable）
- `ACELIB-CMD-010` caller 自訂錯誤代碼
- `ACELIB-CMD-011` 玩家回覆 backend 不可用（非 AceLib owner 無法 region-safe 派送）

## 查核來源

- 介面：`CommandRegistry`、`Sender`、`PlayerHandle`、`ReplySink`；
  SPI：`SubCommand`、`SubCommandCompleter`
- 型別：`CommandContext`、`CommandSpec`、`SubCommandSpec`、`CooldownTracker`、
  `CommandException`、`CommandErrorKind`
- 測試：`src/test/java/com/smile/acelib/command/CommandRegistryTest.java`、
  `CommandRegistryBukkitTest.java`、`CommandExceptionTest.java`、
  `CooldownTrackerTest.java`、`BukkitReplySinkSafetyTest.java`、
  `AceLibStatusCommandTest.java`
- 下一步：[docs/modules/message.md](message.md)（訊息格式化）、
  [docs/modules/context.md](context.md)（SafeExecutor 派送）
