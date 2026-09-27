# 指令模型

> 適合要了解指令描述型別能力與目前使用限制的插件開發者。


Command 模組公開 `CommandSpec`、`SubCommandSpec`、`CommandContext`、`CommandRegistry`、`ReplySink` 等型別，用來描述子指令、權限、參數、冷卻與回覆行為。

## 目錄

- [使用限制](#使用限制)
- [已公開的指令描述能力](#已公開的指令描述能力)
- [指令目錄](#指令目錄commandcatalog)
- [消費範例](#消費範例)
- [相關頁面](#相關頁面)

## 使用限制

AceLib 目前沒有提供給下游 plugin 的 Supported factory，可直接建立並接上 Bukkit 的 `CommandRegistry`。目前的 `CommandRegistryImpl`、`BukkitReplySink` 與 `BukkitCommandBridge` 都屬於內部組裝類別。

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

## 指令目錄（CommandCatalog）

`CommandCatalog`（`com.smile.acelib.command`）是只存「指令描述」的共用目錄：各插件把 `CommandSpec` 或純描述發布進來，其他插件讀出不可變快照產生說明頁。從 `AceLibApi.getCommandCatalog()` 取得，永不為 null（未啟用時為 unavailable 退回實作）。

它**只存描述，不註冊、不執行**：快照中的 `CommandDoc` 只保留名稱、別名、描述、用法、權限、分類、圖示與子指令描述，不攜帶 handler、completer、`Plugin` 實例或 spec 實例本身。

### 發布與撤下

兩個 `publish` 多載：

- `publish(Plugin owner, CommandSpec spec, CatalogMeta meta)` — 從 `CommandSpec` 投影描述性欄位；`meta.confirmSubcommands` 只比對該次發布已知的子指令名稱，出現未知名稱以 `IllegalArgumentException` 拒絕。
- `publish(Plugin owner, CommandDoc doc)` — 直接發布純描述；`doc.owner()` 必須等於 `owner.getName()`，否則以 `IllegalArgumentException` 拒絕（不能冒充他插件）。

`unpublishAll(Plugin owner)` 撤下該擁有者的所有資料；撤下不存在的不算變更。`snapshot()` 回傳深層不可變快照（先比 `owner` 再比 `name` 的自然字串順序）；`revision()` 回傳單調遞增的版本號，只在資料實際變更時 +1，不因清空而重設。

### 發布結果與判定順序

每次 `publish` 恰回傳一種 `CatalogResult`，判定優先序為 `UNCHANGED` > `DUPLICATE_NAME` > `REPLACED` > `PUBLISHED`：

| 結果 | 含義 | revision |
| --- | --- | --- |
| `PUBLISHED` | 新增項目 | +1 |
| `REPLACED` | 同擁有者同名、內容不同，且無其他擁有者使用同名，覆蓋舊項目 | +1 |
| `UNCHANGED` | 同擁有者同名、內容相同，不做任何變更（不記錄警告，優先於 `DUPLICATE_NAME`） | 不變 |
| `DUPLICATE_NAME` | 該次發布的名稱有其他擁有者也在使用；資料仍收錄或更新，並記錄 `ACELIB-CMD-013` warning | +1 |
| `REJECTED` | 服務未就緒或已停用，發布被拒，並記錄 `ACELIB-CMD-014` | 不變 |

擁有者以傳入的 `Plugin` 實例識別（不是名稱字串）；同擁有者重複發布同名指令時覆蓋舊的，不同擁有者同名時兩者都保留。根指令名稱以小寫作為比較 key。服務不可用時 `snapshot()` 回空清單，`unpublishAll` 為無害的 no-op。

### 權限可見性

`CommandDoc.visibleTo(viewer)` 與 `SubDoc.visibleTo(viewer)` 只判自己這一層的 `permission`（`permission == null` 即無需求；`viewer` 為 null 回 false）。正確用法：

- 子指令的可見性必須先通過根指令的可見性。
- 它們**不是**執行時的授權機制，不代替執行時的權限檢查。
- 對玩家呼叫 `hasPermission` 的查詢，須在該玩家 region context 執行（Folia 執行緒規則見[上下文安全](context.md)）。

### 快照一致性

`snapshot()` 與 `revision()` 是兩次獨立呼叫、不是原子配對：需要一致性判斷時，先讀 revision、再讀 snapshot、之後重讀 revision 檢查期間是否變動。範例見下一節。

## 消費範例

以下示範只用公開 API：取得目錄、讀 `snapshot()`、以前後 `revision()` 判斷快取是否失效（與 `examples/consumer-plugin` fixture 中的 `demonstrateNewApis` 相同寫法，可編譯）：

```java
import com.smile.acelib.AceLibApi;
import com.smile.acelib.command.CommandCatalog;
import com.smile.acelib.command.CommandDoc;
import java.util.List;
import java.util.logging.Logger;

public final class CatalogReader {

    private final AceLibApi api;
    private final Logger logger;

    public CatalogReader(AceLibApi api, Logger logger) {
        this.api = api;
        this.logger = logger;
    }

    public void printCatalog() {
        CommandCatalog catalog = api.getCommandCatalog();
        long before = catalog.revision();
        List<CommandDoc> docs = catalog.snapshot();
        long after = catalog.revision();
        if (before != after) {
            logger.info("command catalog changed while reading ("
                + before + " -> " + after + "); re-read if a stable view is needed.");
        }
        for (CommandDoc doc : docs) {
            logger.info(doc.owner() + "/" + doc.name()
                + ": " + doc.description());
        }
    }
}
```

`api.getCommandCatalog()` 永不為 null；disable 後的目錄為已停用的同一實例（發布一律回 `REJECTED`，快照為空），呼叫端無需 null 判斷，只需處理空快照。

## 相關頁面

- [事件註冊](event.md)
- [平台能力](platform.md)
- [錯誤碼](../reference/error-codes.md)
