# 指令模型

> 適合要了解指令描述型別能力與目前使用限制的插件開發者。


Command 模組公開 `CommandSpec`、`SubCommandSpec`、`CommandContext`、`CommandRegistry`、`ReplySink` 等型別，用來描述子指令、權限、參數、冷卻與回覆行為。

## 目錄

- [使用限制](#使用限制)
- [已公開的指令描述能力](#已公開的指令描述能力)
- [型別化指令框架](#型別化指令框架)
- [註冊生命週期與相容對照](#註冊生命週期與相容對照)
- [型別化引數](#型別化引數)
- [自訂引數型別](#自訂引數型別)
- [補全支援矩陣](#補全支援矩陣)
- [錯誤在地化](#錯誤在地化)
- [指令冷卻的清理時機](#指令冷卻的清理時機)
- [指令目錄](#指令目錄commandcatalog)
- [消費範例](#消費範例)
- [相關頁面](#相關頁面)

## 使用限制

v1.4.0 起 `BrigadierRegistrar` 是給下游 plugin 的正式組裝入口（Supported），可在 `onEnable` 期間註冊型別化指令；不再需要 `plugin.yml` 的 `commands` 宣告。`CommandRegistryImpl`、`BukkitReplySink`、`BukkitCommandBridge` 與 `BukkitSender` 仍屬內部組裝類別（Internal），consumer 不應照著它們的建構子自行接線。

`CommandCatalog` 維持只描述、不註冊不執行；需要真正註冊與執行時用 `BrigadierRegistrar`。

公開 API 的分類可查 [API surface](../reference/api-surface.md)。

## 已公開的指令描述能力

組裝端若提供 `CommandRegistry`，可以使用這些穩定型別：

- `CommandSpec`：根指令名稱、權限、用途與子指令集合。
- `SubCommandSpec`：handler、權限、玩家或 console 限制、參數數量、冷卻與補全器。
- `CommandContext`：sender、參數、玩家檢查與回覆方法。
- `CommandException`、`CommandErrorKind`：帶 `ACELIB-CMD-*` 的拒絕與錯誤。
- `ReplySink`：由組裝端提供實際回覆方式。

玩家回覆仍須遵守 Folia region 規則。不要從任意背景執行緒直接操作 Bukkit `Player`；請由提供 registry 的組裝端安排 region-safe 回覆。

### 預設回覆出口的回覆路徑

預設的 `BukkitReplySink` 對玩家回覆會先判斷「目前執行緒是否已擁有該玩家 region」，再決定路徑：

| 情況 | 路徑 | 說明 |
| --- | --- | --- |
| **執行緒已擁有該玩家 region** | 直接送達 | 典型是指令 dispatch 執行緒——handler 執行當下已在玩家 region（Paper 主執行緒恆為 owned；Folia 為對應 region 執行緒）。此時跨執行緒派送並無必要。 |
| **未擁有／判斷失敗** | 走 `SafeExecutorBackend` 派送 | 由 `SafeExecutor.executeOnRegion` 確保送達發生在玩家 region 執行緒。 |
| **未擁有，且 owner 不是 `AceLibPlugin`** | 拒絕 | 記錄 `ACELIB-CMD-011` warning，不送達。跨執行緒回覆需要 region-safe backend；非 `AceLibPlugin` 的 owner 沒有 canonical platform／capability 快取，因此拒絕而非冒險 inline。 |

擁有權判斷本身出錯時一律視為「未擁有」（fail-closed）：判斷不出擁有權就退回 backend 路徑，不會因為查詢失敗而放行 inline 送達。

這代表下游 plugin 用 `BrigadierRegistrar` 註冊的指令，**在自己的 handler 裡直接 `ctx.reply(...)` 就能把訊息送達玩家**，不需要是 `AceLibPlugin`、也不需要自己安排 region 派送——只要回覆發生在指令 dispatch 執行緒（預設情況）。跨執行緒的回覆（例如排程器完成後才回覆）仍須遵守上表後兩列。

完整錯誤代碼見[錯誤碼](../reference/error-codes.md)。

## 型別化指令框架

`TypedCommand` 與 `TypedSubCommand` 是 v1.4.0 的型別化組裝入口。同一個 builder 會產出兩種註冊形式，語意一致：

- `TypedSubCommand.toSubCommandSpec()` — `SubCommandSpec` 相容層，走既有的傳統 dispatch 流程。
- `BrigadierRegistrar.register(...)` — 同時寫入內部 `CommandRegistry` 與 Brigadier 節點。

固定選項（列舉、固定字串）在 Brigadier 樹中編譯為 literal 分支而非 argument 節點 — 原本預期這是基岩版看得見的補全結構，但真人基岩客戶端實測顯示建議列並未出現（見[補全支援矩陣](#補全支援矩陣)）。開放式引數（玩家、世界、材質、數值、時間）則是 argument 節點，送給客戶端的是 vanilla 引數型別，範圍由客戶端先行驗證 — 唯一的例外是 `bigDecimal`，它送的是字串單詞節點（合法性完全由伺服器端判定，見下）。

```java
CommandArgument<Material> itemArg = Arguments.material("item");
CommandArgument<Integer> amountArg = Arguments.intArg("amount", 1, 64);
CommandArgument<String> modeArg = Arguments.fixed("mode", "buy", "sell");

TypedCommand shop = TypedCommand.builder("shop")
    .description("商店指令")
    .permission("shop.use")
    .aliases("s")
    .subcommand(TypedSubCommand.builder("trade")
        .description("交易")
        .cooldownMillis(1_000L)
        .argument(itemArg)
        .argument(amountArg)
        .argument(modeArg)
        .executes(ctx -> {
            Material item = ctx.get(itemArg);
            int amount = ctx.get(amountArg);
            String mode = ctx.get(modeArg);
            // 業務邏輯：不再碰原始字串
        })
        .build())
    .build();

registrar.register(shop);   // 在 onEnable 期間呼叫一次
```

`ctx.get(arg)` 以**引數實例**為 key（不是字串名），因此同一個 builder 產生的實例可安全重複使用；傳入未參與本次解析的實例會拋 `IllegalArgumentException`。全必要宣告時 `minArgs` 與 `maxArgs` 都等於引數數；尾段引數也可以省略或重複（見下一節），零引數子指令則直接執行 handler。

一個子指令名稱只能有一組引數：`TypedCommand` 與 `CommandSpec` 都在建構時拒絕重複的子指令名稱（`IllegalArgumentException: duplicate subcommand name`），同一個子指令名稱不會出現兩組不同的引數配置。需要多種參數形狀時，請拆成不同名稱的子指令。

子指令也可以有別名（AceLib 1.5.0 起）：`TypedSubCommand.builder("ban").aliases("b")`。別名在兩條執行路徑都視為主名——傳統路徑經 `CommandSpec.findSubCommand` 解析為主規格，Brigadier 路徑掛上指向同一子樹的額外 literal 分支；handler 拿到的型別值、權限檢查與冷卻 key 都用主名，help 只列主名。比較一律小寫：別名與自身主名相同、同一子指令內重複、與其他子指令的主名或別名相同，都在建構時以 `IllegalArgumentException` 拒絕並說明。根指令的別名機制不變。

### 省略引數與重複引數

尾段引數可以省略或重複，不用為每種參數形狀各寫一個子指令（AceLib 1.5.0 起提供）：

```java
CommandArgument<Material> itemArg = Arguments.material("item");
CommandArgument<Integer> amountArg = Arguments.intArg("amount", 1, 64);
CommandArgument<String> tagArg = Arguments.fixed("tag", "red", "rare");

TypedCommand shop = TypedCommand.builder("shop")
    .subcommand(TypedSubCommand.builder("give")
        .argument(itemArg)
        .optional(amountArg, sender -> 1)
        .repeatable(tagArg)
        .executes(ctx -> {
            Material item = ctx.get(itemArg);
            int amount = ctx.get(amountArg);        // 省略時為預設值
            List<String> tags = ctx.getList(tagArg); // 零個為空 list，不可變
        })
        .build())
    .build();
```

宣告有三條限制，違反時建構直接拒絕並說明原因：省略引數必須是連續尾段（必要引數不得接在後面）；重複引數只能有一個且必須是最後一個（後面不得再有引數）。

執行時的規則：

- 已提供的值先填滿省略引數，剩下的才歸重複引數；位置語法不能跳過中間引數，想跳過省略引數就必須明確給值。
- 省略時預設值依本次執行的 sender 計算一次，有提供值時不計算，也不跨執行快取。依 sender 決定的預設（例如線上玩家預設為自己）算不出來時（例如 console），提供者應拋指令例外，由回覆出口走在地化錯誤，不靜默換成別的值。
- 重複引數零個合法；`get(重複引數)` 與 `getList(單值引數)` 都會拒絕並指引對方。
- 用法字串自動標示形狀：省略加中括號、重複加省略號（自行指定用法字串時以指定的為準）。
- Brigadier 樹把重複引數編譯為 greedy 尾節點：無上限承接，執行時同樣切分重建、逐個解析。客戶端不做逐元素驗證，非法值由伺服器端回在地化錯誤（取捨比照 `bigDecimal` 的字串單詞節點）。
- 省略引數在 Brigadier 樹一律是 stringWord 開放節點（必要引數才用各自的 vanilla 型別）：客戶端不先行驗證範圍與格式，越界與格式錯誤都由伺服器端同一個解析器判定，兩條路徑同回在地化 `ACELIB-CMD-015`。
- 重複的固定選項（例如 `repeatable(fixed(...))`）同樣走開放式 greedy 節點，不再展開為 literal 分支：客戶端看不到選項字面結構，但補全仍列出選項、解析仍只接受選項，語意不變。

## 註冊生命週期與相容對照

`BrigadierRegistrar` 透過 Paper 的 `LifecycleEvents.COMMANDS` 註冊節點。平台要求 lifecycle handler 必須在 `onEnable` 期間掛載，實際的 `registrar().register(...)` 則由平台在命令同步時機執行。因此：

| 階段 | 行為 |
| --- | --- |
| `onEnable` | 建立 registrar 並 `register`；內部 registry 與節點雙寫入。任一步失敗都回滾，不殘留半註冊。 |
| reload | 不重建、不重複註冊。節點由平台持有；handler 以 supplier 取得 reload 後的最新狀態（比照 `AceLibStatusHandler` 對 diagnostics 的做法）。 |
| 重複註冊同名 | 內部 registry 原子拒絕（`IllegalArgumentException`），平台側不掛上第二個節點。 |
| `shutdown()` / plugin disable | 內部 registry 標記 disabled 並清空本地簿記；殘留 dispatch 一律回 `ACELIB-CMD-009`。平台在 plugin disable 時自動移除其指令。 |

平台未提供「取消單一 lifecycle 指令註冊」的 API，因此 `BrigadierRegistrar.unregister(name)` 只清理本地簿記與內部 registry，平台側節點要等 plugin disable 才移除。依賴「移除即時生效」的呼叫端應改看 `shutdown()` 的語意。

**相容對照**：`SubCommandSpec` 與 `BukkitCommandBridge` 保留原樣。既有以 `plugin.yml` 宣告＋bridge attach 的 plugin 不受影響；v1.4.0 起新寫的 plugin 應改用 `BrigadierRegistrar`。AceLib 自身的 `/acelib` 已遷移，`plugin.yml` 不再有 `commands` 區塊（`acelib.admin` 權限節點仍在 `permissions` 宣告）。

## 型別化引數

`Arguments` 提供十個引數型別，每種都有解析、驗證與自動補全：

| 引數 | 解析結果 | 驗證 |
| --- | --- | --- |
| `player(name)` | 在線玩家的 `PlayerHandle` | 不在線或不存在 → `ACELIB-CMD-007` |
| `offlinePlayer(name)` | `OfflinePlayer` | 從未上線 → `ACELIB-CMD-015` |
| `intArg(name, min, max)` | `Integer` | 非數字、`int` 溢位、超出 `[min, max]` → `ACELIB-CMD-015`（不 wrap、不截斷） |
| `doubleArg(name, min, max)` | `Double` | 非數字、`NaN`、無限大、超出範圍 → `ACELIB-CMD-015` |
| `bigDecimal(name, min, max, maxScale)` | `BigDecimal`（精確值，不經 double 中轉） | 語法錯、超出 `[min, max]`、小數位超過 `maxScale`、科學記號 → `ACELIB-CMD-015`（見下） |
| `duration(name)` | `Long`（ticks） | 語法不符或溢位 → `ACELIB-CMD-015` |
| `world(name)` | `World` | 未載入的世界 → `ACELIB-CMD-015`（接受 Bukkit 世界名與維度鍵兩種形式，見下） |
| `enumArg(name, E.class)` | 列舉常數 | 不在常數內 → `ACELIB-CMD-015` |
| `fixed(name, options...)` | canonical 字串 | 不在選項內 → `ACELIB-CMD-015` |
| `material(name)` | `Material` | 未知材質 → `ACELIB-CMD-015` |
| `dynamic(name, optionsSupplier)`（自 1.5.0 起） | canonical 字串 | 不在當前集合內 → `ACELIB-CMD-015`（見下） |

**單 token 不變條件**：所有開放式引數拒絕空白、空字串與含空白字元的輸入。這不是形式限制 — Brigadier 執行委派依原始輸入的空白切分重建 args，含空白的 token 會破壞切分與解析的一致性。固定選項的字面值本身也不含空白。

**精確數值語法**：`bigDecimal(name, min, max, maxScale)` 全程以 `BigDecimal` 解析，不經 double 中轉 — `0.10` 與 `0.1` 的 scale 差保留，`0.1 + 0.2` 不受 double 誤差影響。範圍端點包含；小數位上限依**輸入的 scale** 檢查，不自動四捨五入（`maxScale=2` 時 `1.234` 被拒；`maxScale=0` 時連 `10.0` 都被拒，因為輸入 scale 為 1；尾隨零計入 scale）。科學記號（`1E3` 這類寫法）一律拒絕，請改寫為一般十進位；`+.5`、`0001.20` 這類解析器接受的寫法兩條路徑都成功。Brigadier 樹送給客戶端的是字串單詞（`stringWord`）節點，只承接單 token、不做數值驗證：語法錯、超範圍、超小數位、科學記號四類非法值在兩條路徑都回 `ACELIB-CMD-015`，訊息來自指定的訊息表。取捨是客戶端沒有 vanilla double 的數值提示（範圍提示與即時語法檢查皆無），換來兩條路徑完全一致的伺服器端錯誤；需要客戶端數值提示時請改用 `doubleArg`。上述兩路徑一致以 Brigadier `word()` 字元集（`[0-9A-Za-z_-.+]`）內的輸入為前提：字元集外的輸入（例如含逗號的 `1,000`）在 Brigadier 路徑仍會被平台提前拒絕、拿不到 `ACELIB-CMD-015`，此時請改用不帶分隔的寫法或走傳統路徑。本引數不承擔幣別與金額政策：格式化、負號政策、千分位都是下游的責任。

**時間長度語法**：整數或小數＋可選單位，與 vanilla time 一致 — `100`（ticks）、`1t`、`1.5s`（30 ticks）、`1d`（24000 ticks）。回傳 ticks。`h`／`m` 單位兩端都不接受，因為客戶端的 vanilla time 語法同樣拒絕；只在伺服器端放行會造成「客戶端擋、伺服器放」的分歧。計算全程以 `long` 精確運算，不走 double（避免大數精度遺失），溢位拋 `ACELIB-CMD-015`。

**世界引數的兩種名稱形式**：世界引數會依序嘗試「legacy Bukkit 世界名」→「大小寫不敏感掃描」→「維度鍵」。這是必要的，因為兩端對主世界的稱呼不同：

| 形式 | 例子 | 來源 |
| --- | --- | --- |
| Bukkit 世界名 | `world` | `Bukkit.getWorld(String)`（legacy 名稱） |
| 維度鍵（裸名） | `overworld` | `NamespacedKey.fromString(raw, null)` → `minecraft:overworld` |
| 維度鍵（完整） | `minecraft:overworld` | 同上 |

Brigadier 樹送給客戶端的是 vanilla world 型別，它只接受**維度鍵**；而 `Bukkit.getWorld(String)` 認的是 **Bukkit 世界名**。主世界在這兩套命名下分別是 `overworld` 與 `world`，若只認後者，客戶端已驗證通過的輸入會在解析階段被拒。維度鍵為小寫規範形式，解析時也會以小寫重試，讓大小寫不敏感語意一致。

補全仍回傳 Bukkit 世界名（`world.suggest("")` 列出已載入世界的名稱），因為那是下游 handler 拿到 `World` 後可直接使用的名稱。

**動態選項**：`Arguments.dynamic(name, optionsSupplier)` 的選項集合由供應函式提供，每次解析與補全都重新呼叫，供應集合用可變 `List` 在執行期增刪時立刻反映，不需要重新註冊或重建指令樹。解析大小寫不敏感，回傳集合內的宣告形式；值不在當前集合時走 `CommandMessages` 在地化錯誤（`ACELIB-CMD-015`）；供應集合為空時任何值都非法；供應函式拋錯時解析得到在地化錯誤、補全回空（補全不中斷輸入）；清單中的 `null` 元素忽略。本引數是開放式引數節點（argument 節點，不是 literal 分支 — 平台沒有取消單一註冊的方法，固定選項分支做不到執行期增刪），因此基岩版補全與其他開放式引數相同（伺服器端建議送不到基岩版，見下表）。選項固定不變時請用 `fixed`（literal 分支）。

供應函式必須回傳穩定快照（呼叫內自行複製後再回傳），不要回傳仍被別處修改中的集合：回傳的集合在複製期間被並發修改時視為供應失敗（解析得在地化 `ACELIB-CMD-015`、補全回空）。供應失敗可以追查：解析例外的玩家訊息不變，但原始例外附於原因鏈（`getCause()`）；補全路徑另以 `AceLib` logger 記 WARNING（含引數名、供應失敗事實與原始例外堆疊）。管理員看到 `ACELIB-CMD-015` 的 `options unavailable` 時，先查原因鏈或 console 的 WARNING，再找供應函式。

值的字元有限制：Brigadier `stringWord` 節點只接受 `[0-9A-Za-z_-.+]`。中文、冒號這類字元集外的值在傳統路徑解析可成功（`parse` 本身不設字元集限制），但 Brigadier 路徑到不了 `resolve`（平台以標準字元集錯誤提前拒絕）；補全是伺服器端建議，不受此限制，仍可能列出字元集外的值。兩條路徑都要走的值請用字元集內的寫法。

## 自訂引數型別

下游以 `CommandArgument.custom` 只需解析函式與補全函式就能建立自己的引數型別（自 AceLib 1.5.0 起提供）。解析函式接收原始字串與 `CommandMessages`，回傳型別值或擲出帶在地化訊息的 `CommandException`；補全函式接收前綴，回傳候選清單（大小寫不敏感比對與前綴過濾由實作自行完成）。

```java
CommandArgument<Double> rateArg = CommandArgument.custom("rate", "<rate:percent>",
    (raw, messages) -> {
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isNaN(value) && !Double.isInfinite(value)
                && value >= 0 && value <= 100) {
                return value / 100.0;
            }
        } catch (NumberFormatException ignored) {
            // 落到下方的統一錯誤，不讓數字格式錯誤逃逸為未在地化例外。
        }
        throw new CommandException(CommandErrorKind.INVALID_ARGUMENT,
            messages.invalidArgument("rate", raw, "expected <number> in 0-100"),
            Map.of("arg", "rate", "value", raw));
    },
    prefix -> List.of("25", "50", "75", "100").stream()
        .filter(option -> option.startsWith(prefix)).toList());
```

自訂引數一律是單 token 開放式引數：`parse` 拒絕含空白的輸入（`ACELIB-CMD-015`）；Brigadier 型別固定為 `stringWord`，編譯為 argument 節點；`resolve` 取出原始字串後走同一個解析函式（此時取預設英文訊息，接受的值一致）。完整可編譯的寫法見 `examples/consumer-plugin` 的 `CommandV150Example`（外部 `com.example` 套件，裸數字百分比，註冊的指令在兩條路徑都能執行）。

值的字元有限制：Brigadier `word` 型別只接受 `[0-9A-Za-z_-.+]`（`word` 不處理引號形式，超出該字元集的值連同引號形式一併被拒）。超出該字元集的值只在傳統路徑可用，Brigadier 路徑會以標準字元集錯誤拒絕；兩條路徑都要走的值請用字元集內的寫法。

## 補全支援矩陣

伺服器即時算出的建議送不到基岩版（Geyser 限制）；真人基岩客戶端實測顯示，連編譯進指令結構的固定選項也未出現建議列。下表「基岩版」欄中，`enumArg`／`fixed` 與 `player` 為實測結果，其餘型別列為推論自同一平台限制（未逐項實測）：

| 引數 | Java 版（伺服器建議） | 基岩版（Geyser） | 說明 |
| --- | --- | --- | --- |
| `enumArg` / `fixed` | 可用 | **實測未出現建議列** | literal 分支已編譯進指令結構，但基岩建議列仍未出現（見下段歸因）。 |
| `player` | 可用（列出在線玩家） | **實測未出現建議列** | 樣本含玩家引數（`parse`）；伺服器建議送不到基岩。 |
| `world` | 可用（列出已載入世界） | 推論：無建議列（未逐項實測） | 推論自同一平台限制（基岩 UI 無法按型別區分），見下段樣本說明。 |
| `material` | 可用（列出材質名） | 推論：無建議列（未逐項實測） | 同上。 |
| `intArg` / `doubleArg` | 無建議（範圍由客戶端驗證） | 推論：無建議列（未逐項實測） | 該型別本就不列候選；同一平台限制下基岩同樣無建議列。 |
| `bigDecimal` | 無建議（Brigadier 節點為字串單詞，無客戶端數值提示；合法性完全由伺服器端 `BigDecimal` 解析器決定，兩條路徑錯誤一致） | 推論：無建議列（未逐項實測） | 同上。 |
| `duration` | 建議語法範例（`1s`、`1d`…） | 推論：無建議列（未逐項實測） | 範例僅供 Java 版參考；同一平台限制一體適用。 |
| `offlinePlayer` | best-effort（只列在線玩家） | 推論：無建議列（未逐項實測） | 離線名單無法低成本枚舉；同一平台限制下基岩同樣無建議列。 |
| `dynamic` | 可用（每次補全重新呼叫供應函式） | 推論：無建議列（未逐項實測） | 開放式引數節點；伺服器建議送不到基岩，同上。 |

**基岩補全實測結果**（2026-10-08，真人基岩客戶端 `.linoQsmile`，Folia 26.2-7＋Geyser 2.11.3-b1247；完整觀測見 `.ultrawork/evidence/acelib-v140/t07/BEDROCK-RESULT.md`）：literal 分支（`parse-mode`、`parse-fixed`、`trade`）與玩家引數（`parse`）送出後皆回 `missing arguments`（參數數 1／1／2／1，與預期結構相符），**建議列皆未出現**。歸因是 Geyser 官方 Current Limitations 的 Unfixable 條目：「Anything that relies on tab complete or typing in the chat UI … Bedrock sends no packet that indicates they are in this menu」（https://geysermc.org/wiki/geyser/current-limitations/）；基岩端僅內建指令有自動完成。非本框架缺陷。樣本涵蓋 literal 分支與玩家引數；該平台限制對所有引數型別一體適用（基岩 UI 無法按型別區分）。

**執行與回應不受影響**：`/cprobe parse-mode buy` 實測回 `[parse-mode] ok value=BUY type=Mode thread=Folia Region Scheduler Thread #0` — 分支解析正常，handler 在 Folia Region Scheduler 執行緒執行。

固定選項的大小寫：literal 分支以小寫常數名編譯，若基岩版顯示補全會是小寫形式（本次實測建議列未出現）；傳統路徑的 `parse` 大小寫不敏感，故兩種大小寫在兩條路徑都能執行。

## 第三階段引數的實機驗證（1.5.0）

2026-10-09 以同一顆候選 JAR（`com.smile:acelib:1.4.0` 座標、SHA-256 `2d6d92c2…87b39a`，內容含第三階段全部類別）在兩個平台實測，探針為 `acelib-command-compatibility-probe-1.1.0-SNAPSHOT`（64 案例）：**Folia 26.2-7**（MC 26.2）與 **Paper 26.2-133**（MC 26.2，同一世界直接切換平台）。兩平台 24 個外掛全數載入，含下游 AceEconomy 2.2.0 與 ChunkLand 0.1.0；錯誤後備節點（`CustomArgumentType` 包裝）在兩平台都成功註冊與分派。

實測結果（兩平台一致，handler 執行緒分別為 Folia Region Scheduler Thread 與 Server thread）：

- 省略引數：省略時拿到型別化預設值（`amount=1 source=default`），提供時拿到解析值（`amount=5 source=provided`）。
- 重複引數：零個為空清單、一個與多個為對應型別化清單（`notes=[STONE]`／`[STONE, DIRT]`）。
- `bigDecimal`：`0.10` 精確解析（`value=0.10 scale=2`）；超小數位、科學記號、超範圍三類錯誤皆為伺服器端在地化 `ACELIB-CMD-015`（非平台錯誤）。
- `dynamic`：執行期新增（`dyn-add gamma` 後立即可解析）與移除（`dyn-remove beta` 後 `expected one of alpha|gamma`）即時反映，無需重新註冊。
- 自訂引數（`CommandArgument.custom`）：`75` 解析為 `0.75`，非法值走訊息表。
- 子指令別名：`cprobe pi 5` 與 `cprobe parse-int 5` 結果完全相同。
- 固定選項集合外值：`cprobe-args trade loud 5` 回 `invalid value for <mode>: 'loud' (expected one of buy|sell)`（錯誤後備節點路徑，非平台解析錯誤）。

未觀察項目：補全彈窗的客戶端渲染（機器人不渲染建議列、無按 Tab 的自動化工具；補全內容由傳統 `tabComplete` 與 Brigadier `getCompletionSuggestions` 逐位置比對的單元測試覆蓋）；基岩版補全依 Geyser 平台限制推論不可見。完整紀錄見任務 acelib-v150-t10 的實機驗證紀錄。

## 錯誤在地化

`CommandMessages` 是錯誤訊息的在地化契約，`LocalizingReplySink` 是 presentation 層的裝飾器：`CommandException` 依 kind 轉為在地化字串後再送出，缺 key 時退回例外原文（不送空字串），非 `CommandException` 原樣轉交。

三種實作：

- `DefaultCommandMessages` — 內建英文預設，AceLib 自身管理指令使用。
- `MessageServiceCommandMessages` — 經 message 模組查 key（key 為 `prefix + suffix`，例如 `command.error.invalid-argument`），語言檔模板以 `{var}` 引用。
- 自訂實作 — 未覆寫的方法回傳空字串即視為缺 key。

型別化引數的解析錯誤在建構子以 `.messages(...)` 指定訊息表（預設英文），錯誤訊息由該表產生。

不是所有錯誤訊息都能客製。Brigadier 路徑中，送給客戶端的 vanilla 引數型別會先被平台驗證：沒通過時平台直接回自己的客戶端訊息，根本到不了 AceLib 的解析器與訊息表；同一個輸入在傳統路徑則回上表的 `ACELIB-CMD-*` 在地化錯誤。平台先驗證、不能客製的情況如下（皆為 Brigadier 路徑）：

| 引數 | 平台產生的錯誤 | 說明 |
| --- | --- | --- |
| `player` | 選擇器語法錯、選不到人 | vanilla 玩家選擇器先驗證；`resolve` 拿不到人時亦為平台標準錯誤 |
| `offlinePlayer` | 同上（profile 選擇器） | 同上 |
| `intArg`／`doubleArg` | 非數字、超出範圍 | vanilla bounded 型別由客戶端先行驗證 |
| `duration` | 語法不符 | vanilla time 型別先行驗證 |
| `world` | 未知的維度鍵 | vanilla world 型別只接受維度鍵 |
| `material` | 未知的物品鍵 | vanilla item registry 先行驗證 |

必要固定選項（`enumArg`／`fixed`）不在上表：literal 分支保留成功與補全，集合外的值由錯誤後備節點（單 token 字串、不提供補全；以 Paper `CustomArgumentType` 包裝、原生為字串單詞，平台註冊可轉換）承接，切分重建後走相容層解析，回在地化 `ACELIB-CMD-015`（訊息表可客製）；合法值仍走 literal 分支。省略的固定選項本就是字串節點，同樣走訊息表。

`bigDecimal`、自訂引數、`dynamic` 與省略／重複位置皆為字串節點，沒有平台先驗證：字元集內的輸入在兩條路徑都走訊息表（`ACELIB-CMD-015`）；字元集外的輸入仍會被平台提前拒絕（見上）。

**權限過濾**：說明與補全都依權限過濾。傳統路徑由 `CommandRegistry` 的既有流程處理（無權限時不列出該子指令）；Brigadier 路徑以 `requires` 過濾客戶端可見結構，並在 `suggests` 回呼再次確認權限。兩條路徑都只做可見性過濾，執行時的授權仍由 dispatcher 統一檢查。

完整錯誤代碼見[錯誤碼](../reference/error-codes.md)。

## 指令冷卻的清理時機

`SubCommandSpec.cooldownMillis()` 大於 0 時，dispatcher 會以 `CooldownTracker` 判斷是否放行，冷卻中則以 `COOLDOWN_ACTIVE` 拒絕。key 為 `<command>:<subcommand>`，只在玩家 sender 生效，console 不受冷卻限制。

`CooldownTracker` 在 API 分類中是 Supported，但它是 dispatcher 內部持有的實例，`AceLibApi` 沒有提供取得途徑。因此 dispatch 使用的冷卻表由組裝層維護，consumer 無從自行呼叫清理；`CommandRegistry.onPluginDisable()` 也不會清除冷卻狀態，reload 後冷卻仍然有效（需要完全重置才由組裝層呼叫 `clearAll()`）。

自行建立 `CooldownTracker`（例如做管理指令的 `/cooldown clear <player>`）時要注意：

- `pruneExpired()` 沒有任何自動 caller。AceLib 不會自行排程定時清理，過期紀錄會留在 map 裡，直到呼叫端自己呼叫。
- 有可清理的 API 不等於累積量自動受到限制；長期運行的伺服器仍要由呼叫端依自己的生命週期安排清理時機（例如 reload 前或定期維護時）。
- `tryAcquire(...)` 與 `pruneExpired()` 都在外層 map 的同一個 `compute`／`computeIfPresent` 內完成，共用同一把 per-player 鎖，因此清理不會讓併發寫入的冷卻被遺失。
- `clear(playerId)` 清除單一玩家的全部冷卻，`clearAll()` 清除全部；兩者都是管理用途，正常 dispatch 流程不會呼叫。

命令之外的玩家冷卻（技能、使用者自訂 key）請用 `PlayerCooldownService`，見[玩家資料與 session](player.md)。

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

自訂引數型別的完整可編譯寫法見 [`CommandV150Example`](../../examples/consumer-plugin/src/main/java/com/example/CommandV150Example.java)（外部 `com.example` 套件：裸數字百分比引數，註冊的指令在傳統與 Brigadier 兩條路徑都能執行）。

## 相關頁面

- [事件註冊](event.md)
- [平台能力](platform.md)
- [錯誤碼](../reference/error-codes.md)
