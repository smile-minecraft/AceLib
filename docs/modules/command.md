# 指令模型

Command 模組公開 `CommandSpec`、`SubCommandSpec`、`CommandContext`、`CommandRegistry`、`ReplySink` 等型別，用來描述子指令、權限、參數、冷卻與回覆行為。

## 1.0.0 的使用限制

AceLib 1.0.0 沒有提供給下游 plugin 的 Supported factory，可直接建立並接上 Bukkit 的 `CommandRegistry`。目前的 `CommandRegistryImpl`、`BukkitReplySink` 與 `BukkitCommandBridge` 都屬於內部組裝類別。

因此一般 consumer 不應照著這些 public class 的建構子自行接線，也不應把它們當成穩定 API。若你的 plugin 需要註冊 Bukkit 指令，請先使用 Paper/Bukkit 自己的 command API；AceLib 的 command model 可在未來有正式 factory 或由其他 Supported 組裝入口提供時再採用。

公開 API 的分類可查 [API surface](../reference/api-surface.md)。

## 已公開的指令描述能力

組裝端若提供 `CommandRegistry`，可以使用這些穩定型別：

- `CommandSpec`：根指令名稱、權限、用途與子指令集合。
- `SubCommandSpec`：handler、權限、玩家或 console 限制、參數數量、冷卻與補全器。
- `CommandContext`：sender、參數、玩家檢查與回覆方法。
- `CommandException`、`CommandErrorKind`：帶 `ACELIB-CMD-*` 的拒絕與錯誤。
- `ReplySink`：由組裝端提供實際回覆方式。

玩家回覆仍須遵守 Folia region 規則。不要從任意背景執行緒直接操作 Bukkit `Player`；請由提供 registry 的組裝端安排 region-safe 回覆。

完整錯誤代碼見[錯誤碼](../reference/error-codes.md)。
