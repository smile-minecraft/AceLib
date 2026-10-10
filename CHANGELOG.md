# Changelog

AceLib 使用語意化版本。安裝與取得方式請看[如何取得 AceLib](docs/reference/release-artifacts.md)；本檔只記錄版本變更。

## [1.5.0] - 未發布

### 修補內容（第二階段 行為缺陷）

- 設定繫結的 `double` 欄位一律拒絕 `NaN` 與正負無限大，不論有無 `@ConfigRange`；錯誤沿用 `ACELIB-CFG-007` 並帶完整欄位路徑，有範圍時訊息一併帶出允許範圍。有限值的範圍語意不變。
- 行為變更：`MessageService` 純文字視圖（`RenderedMessage.text()`／`format()`，含字串發送多載）與富文字視圖共用安全替換，變數值裡的 `<...>` 會先跳脫（`MiniMessage.escapeTags`）再套用模板，模板本身的標記不受影響。玩家名稱或自訂文字帶 `<...>` 時純文字輸出與 1.4.0 不同（原文→跳脫字面）。
- `render(key, vars, locale)` 的 `message.prefix` 改取指定語系（缺時退回預設語系），此前固定取全域目前語系。`formatConsole` 維持原文不跳脫。
- 修正 `PlayerDataStores.fromDataStore(...)` 轉接的失敗回復：`applyChanges`／`deletePlayer` 在 `save()` 失敗時把記憶體樹、欄位存在性與 `revision` 還原為操作前並以 `ACELIB-DATA-008` 回報（還原失敗掛 `suppressed`）；缺席刪除改為不觸發 `save()` 的 no-op；寫入改以點分隔路徑存純值（原先存入 `Record` 節點會被 `ACELIB-DATA-006` 拒絕）。`revision` 僅對同一轉接實例有效，不持久化；不承諾跨行程原子性，亦不對 delegate 已部分落盤做逆轉。還原以整節點深拷貝寫回，保留頂層 null、空節點與字面點號鍵。

### 新增內容（第三階段 型別化指令的引數）

- 下游可以實作自己的引數型別：`ArgumentTypeFactory` 公開為 SPI，`CommandArgument.custom` 只需解析函式與補全函式即可建立引數（自訂引數為單 token 開放式引數，Brigadier 型別固定為 `stringWord`）；consumer 範例新增外部套件實作。
- 新增 BigDecimal 精確數值引數（Arguments.bigDecimal）：以 BigDecimal 解析不經 double 中轉，範圍端點包含，小數位上限依輸入 scale 檢查，科學記號一律拒絕；非法值回 ACELIB-CMD-015。
- 型別化指令支援省略引數與重複引數：尾段引數可宣告預設值（省略時依執行者計算一次），最後一個引數可重複（零個起，handler 以不可變 List 取值）；傳統與 Brigadier 兩條路徑行為一致。
- 新增 Arguments.dynamic(name, optionsSupplier)：由供應函式提供選項的動態選項引數，每次解析與補全重新取值，執行期增刪立刻反映；大小寫不敏感、回傳宣告形式，非法值走 ACELIB-CMD-015。
- CommandErrorKind.ASYNC_EXECUTION_FAILED 改名為 EXECUTION_FAILED，錯誤碼 ACELIB-CMD-008 不變。舊名已移除，參照舊名的程式需改名後重新編譯。
- 修正 CommandMessages.localize 缺少 INVALID_ARGUMENT 分支：型別化引數解析失敗（固定選項、列舉、數值、自訂、動態選項等）此前在 presentation 層落到預設分支回空字串、退回拋出點原文；現在依 kind + vars 以 invalidArgument(arg, value, reason) 重算，缺 key 才退回原文。
- 子指令支援別名：TypedSubCommand.builder("ban").aliases("b")；別名在傳統路徑與 Brigadier 路徑都視為主名（同一 handler、同一冷卻 key；help 只列主名）。比較一律小寫；與主名或彼此衝突在建構時以 IllegalArgumentException 拒絕。

### 新增內容（第四階段 訊息、設定與資料的新增介面）

- 訊息渲染現在可以分辨失敗原因：`MessageService#renderDetailed` 回傳 `DetailedRender`，以 `RenderStatus` 區分語系尚未載入（`LOCALE_NOT_LOADED`，整條查找鏈皆無內容）、缺 key（`KEY_MISSING`）與渲染失敗（`RENDER_FAILED`）。缺 key 的診斷帶完整 key 與可用語系；`RenderedMessage` 本身的內容、診斷字串與記錄維持不變。
- 發送訊息新增回傳結果的入口：`sendChatWithFallbackResult`、`sendActionBarWithFallbackResult`、`sendTitleWithFallbackResult`、`broadcastWithFallbackResult` 回傳 `SendResult`（是否送達、是否確實套用基岩降級）。舊的 void 入口改為委派，行為不變；`fallbackApplied` 只代表降級已套用到送出的 Component，不代表客戶端已經看見。
- 基岩降級新增 `BedrockFallbackStyle.PLAIN_TEXT` 攤平選項（整體轉純文字）；預設仍為 `HINTS` 提示風格。
- 新增 `MessageService#formatPlain`：給 console 與外部頻道用的純文字輸出，去除全部 MiniMessage 標記只留可讀文字，可選是否帶 `message.prefix`（預設不帶）。
- 行為變更（破壞性）：`ConfigSnapshot.getInt(path, default)` 改為嚴格轉換：小數、NaN／無限大、超出 int 範圍的數字一律拋 `ACELIB-CFG-007`（訊息含完整路徑），不再靜默截斷或溢位，也不再回傳預設值。缺值、null 與非數字值的預設值語意不變。過去依賴截斷的寫法請改用 `getDouble` 再自行取整，或改走 `bind()` 的範圍約束一次驗證。
- `ConfigSnapshot` 新增 `getLong(path, default)`／`getDouble(path, default)`：`getLong` 對小數與溢位拋 `ACELIB-CFG-007`；`getDouble` 拒絕 NaN 與正負無限大（例如 YAML 的 `.nan`／`.inf`）；缺值、null 與非數字值回傳預設值。與 `ConfigBinder.bind` 的數值轉換同規則。
- `ConfigSnapshot` 新增世代與內容相等：`generation()` 為獨立 metadata，`ConfigManager` 每次成功發布（`load`／`reload`／`startup`）世代 +1（同內容也 +1），失敗不發布新世代；`equals`／`hashCode` 只比較深層內容，世代不參與。
- 設定繫結支援 Map<String, T>、Set<T> 與物件清單（元素依宣告型別驗證，錯誤帶完整元素路徑）；新增跨欄位驗證入口 registerCrossFieldValidator，規則失敗時不發布新快照、不推進世代。
- 使用後缺檔可在還原前攔截：新增 `ConfigManager#registerMissingFileHandler`，規則只在檔案不存在且曾經成功載入過時、於還原最後成功副本之前執行；正常回傳即照現行流程還原，拋 `ConfigException` 即拒絕（不寫入目標檔、不產生預設檔、不發布新快照、不推進世代）。`startup()` 回傳 `MISSING_AFTER_USE`（快照取記憶體舊快照 → 呼叫端後備 → null，診斷帶該例外的錯誤碼與訊息），`load()` 原樣拋出。首次安裝不觸發；最後成功副本版本較新時的 `ACELIB-CFG-006` 拒絕不受影響。拒絕用的錯誤碼由下游自行決定，不新增錯誤碼。

## [1.4.0] - 2026-10-08

v1.4.0 新增生命週期宿主、每玩家顯示、測試套件與測試 JAR、任務完成語意與作用域、事件處理完成後的操作、插件作用域訊息與在地化、插件隔離介面流程與元件、設定啟動快照與型別綁定、型別化指令框架、逐玩家儲存與玩家資料模型，以及外部整合門面；本版以 GitHub Release 發布，提供可下載的 `AceLib-1.4.0.jar` 與 `AceLib-1.4.0-test-fixtures.jar`，管理員可直接下載，或從 `v1.4.0` tag 以 `./gradlew clean build --no-daemon --console=plain` 建置取得。開發者可從 JitPack（`com.github.smile-minecraft:AceLib:v1.4.0`）取得。

### 本階段內容（生命週期宿主）

- 新增 `AceLibApi.getLifecycleHost()` 與公開 lifecycle 模組宣告：依賴圖在啟用前一次驗證，缺依賴、重複 id 與循環會結構化拒絕；有效模組依拓樸啟用、反向停用。
- 批次啟用失敗會反向清理已交付 handle；reload 先停用下游模組，核心提交後再拓樸重建。可安全重試的核心 rollback 保留模組宣告並允許再次 reload；核心 fail-closed 或模組重建失敗則明確維持 `FAILED`，不假稱跨世代完整回滾。
- 新增 `ACELIB-LIFE-001`～`ACELIB-LIFE-009`；Bedrock shutdown 同步清理表單 pending response，Phase D 未提交的 external／bedrock 服務會補償釋放，並拒絕 reload 重入。
- 新增[生命週期宿主指南](docs/modules/lifecycle.md)及 consumer 範例；新公開型別、API surface 與簽章基線同步列於 reference 文件。

### 本階段內容（每玩家顯示）

- 新增 `DisplayService`：每位玩家有獨立計分板與 BossBar，另提供固定位置的原生 `TextDisplay` 全息字；呼叫結果區分已完成、已接受派送、拒絕與失敗。
- 顯示更新依目標玩家、位置或實體的擁有者上下文派送。計分板清理只在玩家仍使用本服務建立的計分板時還原主板，不覆蓋其他 plugin 後來設定的計分板；shutdown 取消未執行的變更。
- 全息字建立時預設不可見且不持久化；關閉時的實體清理走原生 entity scheduler，避免被 SafeScheduler 的停用取消路徑攔截。disable 時平台不保證已排程 callback 執行，限制與 Paper／Folia 實機探針見[顯示模組頁](docs/modules/display.md)。
- 新增錯誤碼 `ACELIB-DISP-001`～`ACELIB-DISP-007`。公開 API surface 與簽章基準已同步；MockBukkit 4.113.1 的 `TextDisplayMock` 未實作 `setVisibleByDefault`，因此後端安全預設由 seam 測試保護、真實顯示行為由實機探針驗證。

### 本階段內容（測試套件與測試 JAR）

- 以 Gradle `java-test-fixtures` 在單一模組內交付下游單元測試輔助：可控制時鐘（`FakeClock`）、可控制排程與實體退休事件（`FakeSafeScheduler`）、GUI 與表單標準假實作（`FakeGuiService`／`FakeFormService`，可模擬過時回應、重複回應、關閉失敗）、Provider 缺席／停用／重新取得輔助（`FakeExternalIntegrationService`），以及真實作與假實作共用的服務契約測試。
- 測試 JAR（`AceLib-<version>-test-fixtures.jar`）納入 Release workflow 的獨立選取與 SHA-256 完整性檢查；下游以座標加 `test-fixtures` 後綴引用（`testFixtures(...)` 寫法在 JitPack 解析不到）。
- 開發版本三處（`build.gradle.kts`、`plugin.yml`、`AceLibVersion.java`）與本機 consumer 座標同步為 `1.4.0`。

### 本階段內容（任務完成語意與作用域）

- 排程結果區分「已接受排程」與「動作完成」：`SafeScheduler#scopeFor(Player/Entity)` 建立玩家／實體作用域群組，派送回傳 `TaskTicket`（沿用 `ScheduledTask` 句柄語意，另以 `TaskResult`／`TaskOutcome` 攜帶完成、失敗、取消、拒派四種終態，支援等待與串接，終態只完成一次）。
- 玩家退服、實體退休、plugin 停用時群組任務自動取消並通知呼叫端；退服後不執行使用者程式。
- `TaskScope#pipeline` 把讀取、背景計算、回玩家／實體所在執行緒回覆串成單一流程（Folia 下回覆跟著跨區後的玩家）；任一階段失敗、取消或拒派即為整條流程的終態。

### 本階段內容（事件處理完成後的操作）
- 延後傳送（`WorldService#teleportPlayerDeferred`）：排到事件處理後的 tick，在玩家所在執行緒傳送，完成時確認玩家真的到達目的地（同世界＋每軸誤差在容差內，預設 0.5 格、可覆寫）；平台回報成功但位置被還原時回報 `FAILED + ACELIB-WORLD-018`（診斷含期望與實際位置）。
- 通用延後操作（`WorldService#deferForPlayer`）：同樣的延後方式可用在傳送以外的操作，終態語意重用排程作用域的 `TaskTicket`。
- 新增錯誤碼 `ACELIB-WORLD-017`（延後派送無法安排）與 `ACELIB-WORLD-018`（到達確認失敗）。
- 傳送後端改為真正的非同步呼叫（`Entity#teleportAsync`，`keepPassengers` 以 `RETAIN_PASSENGERS` 旗標表達）：移除過去永遠生效的同步 fallback；目標 chunk 未載入時改由平台非同步語意處理。呼叫端本就以 future 等待結果，無需改動。

### 本階段內容（插件作用域的訊息與在地化）

- 插件作用域訊息服務（`MessageScopes`／`MessageScope`）：每 plugin 在 `onEnable` 建立、`onDisable` 關閉自己的作用域；同 key 各自隔離，不跨 plugin 讀文案或清理他人資源；重複建立與關閉後使用以 `ACELIB-MSG-006` 拒絕。
- 玩家語系解析器（`PlayerLocaleResolver`）可替換：預設跟隨 `Player.locale()`；偏好存在哪裡不做規定，下游可用自己的資料庫實作；解析失敗退回預設語系並記錄 `ACELIB-MSG-003`，不中斷發送。
- 磁碟自訂文案優先，缺 key 讀 plugin JAR 內建資源（`lang/<locale>.yml`）；升級以 `syncMissingBuiltinKeys` 只補新 key（保註解合併、不覆寫管理員修改、具冪等性）。
- 聊天、ActionBar、GUI 與表單共用同一份渲染結果（`MessageService#render` → `RenderedMessage`：component／text／formText 三視圖一次產出）；顯示標籤與程式識別字以 `MessageLabel`（id＋text，文字取無 prefix 且 MiniMessage 已解析的表單安全字串視圖）分離，缺 key 時文字退回 id。
- 缺 key（`ACELIB-MSG-001`）與渲染失敗（`ACELIB-MSG-003`）在渲染結果的 `missing`／`diagnosis` 中可診斷；既有 `format`／`formatComponent` 改走同一管線，單語系下輸出與重構前等價。
- 行為變更（刻意修正，多語系服注意）：`formatFormText(key, vars, locale)` 指定非全域語系時，現在按該語系分層讀模板（舊版固定讀全域模板）；`render(locale)` 的 `message.prefix` 仍取自全域語系。單語系服無差異。
- 原始碼相容性說明：新增 `RenderedMessage` 發送多載後，字面 `null` 呼叫（`sendChat(p, null)`、`broadcast(null)`）在原始碼層歧義，需加明確轉型（例如 `(Component) null`）；二進位相容，簽章只增不減（與既有 `Locale`／`FormTextOptions` 多載歧義提醒一致）。
- 新增錯誤碼 `ACELIB-MSG-006`（訊息作用域生命週期違規）。
- API 為加法性變更：新增 `MessageScopes`、`MessageScope`、`PlayerLocaleResolver`、`RenderedMessage`、`MessageLabel`，`LangManager` 新增 `syncMissingBuiltinKeys`，`MessageService` 新增 `render` 與 `RenderedMessage` 發送多載；未變更或移除既有公開簽章，`docs/reference/api-surface-signatures.json` 已同步。

### 本階段內容（插件隔離的介面流程與元件）

- 插件隔離 GUI 作用域（`GuiScopes`／`GuiScope`）：每 plugin 在 `onEnable` 建立、`onDisable` 關閉自己的 handle，只能操作自己開的 GUI；底層共用同一份 session 登記；新 GUI 取代其他 plugin 的 GUI 時原擁有者收到取代通知。跨 plugin 操作回 `NOT_OWNER`（`ACELIB-GUI-019`）。
- 破壞性變更（路線圖待決事項第一項已決定）：公開 `GuiService` 介面上的全服務 `shutdown()` 移除；內部停用改走內部生命週期 `GuiServiceControl`（下游不得依賴），結束 GUI 請關自己的作用域。`getListener()` 保留。除此刪除外未變更或移除既有公開簽章，`docs/reference/api-surface-signatures.json` 已精準同步。遷移說明見 `docs/consumer/provider-lifecycle.md`。
- 五導航（`open`／`push`／`replace`／`back`／`close`）：每次切換畫面開啟新 session（generation 遞增），舊代操作回 `GENERATION_MISMATCH`；無歷史 `back` 回 `NO_PREVIOUS_VIEW`（`ACELIB-GUI-023`）；reload 後以 `reopen` 用目前視圖重開。
- 共用流程（`GuiFlow`／`GuiFlowStep`）：同一份步驟同時描述 Java 視圖與基岩表單；基岩回應按轉移表自動推進（`VALID`）、關閉結束（`CLOSED`）、無效停留（`INVALID`）、過時忽略；需要讀 custom 元件答案時直接用 `FormService`（流程不做資料綁定）。
- 一次性確認票券：`confirm`／`cancel` 競爭只解決一次（後到回 `ACTION_ALREADY_RESOLVED`）；送出前重新驗證（`confirmWithRevalidation`，僅 `SUCCESS` 執行 domain action，失敗自動取消且不執行，驗證器抛例外視為失敗）。
- 元件：按鈕專屬回呼（禁止拿 `SLOT_PROTECTED` 當按鈕訊號）、欄位預設全擋只開放指定欄位、按鈕冷卻（冷卻中回 `COOLDOWN_ACTIVE`，`ACELIB-GUI-021`）、分頁（`GuiPage` 既有，CONTENT／EMPTY／LOADING／ERROR）、聊天輸入與鐵砧輸入（一次性票券，超長可重試，逾時／關閉／退服／停用失效）。
- 玩家退服自動清理 session、票券與輸入；任一下游 plugin 停用自動關閉其作用域。新增錯誤碼 `ACELIB-GUI-019`～`ACELIB-GUI-023`。
- API 為加法性變更（除上述已決定的 shutdown 移除）：新增 `GuiScope`、`GuiScopes`、`GuiView`、`GuiButton`、`GuiButtonClick`、`GuiFlow`、`GuiFlowStep`、`GuiInputKind`、`GuiInputPrompt`、`GuiInputTicket`、`GuiInputResult`、`GuiRevalidation`、`GuiReplacementListener`（Supported）與 `GuiServiceControl`（Internal）；`docs/reference/api-surface*.json`／`.md` 已同步。

### 本階段內容（設定的啟動、快照與型別綁定）

- 啟動四分類（`ConfigManager#startup`）：首次安裝、有效設定、損壞設定、使用後缺檔；識別依據是安裝狀態 sidecar（只在驗證成功後寫入），不是檔案是否存在。
- 保留最後驗證成功副本（`.last-good`）；損壞時原檔逐位元不動，快照依序取用副本、呼叫端後備或 null（無可用時診斷帶 `ACELIB-CFG-003`，下游據此禁用操作）。
- 整份驗證通過後一次發布不可變 `ConfigSnapshot`（深層凍結，同輪操作固定同一實例）。
- 型別綁定（`ConfigBinder`）：record／一般類別綁定，型別、範圍、列舉在載入時驗證，失敗拋 `ACELIB-CFG-007` 並帶完整欄位路徑。
- 檔案監看自動重載（`startWatching`）：無效新內容保留舊快照並診斷；自己的寫回不觸發迴圈；`reload` 不殺監看器，`close()` 徹底清理（daemon 執行緒）。
- 寫回保留註解：行級合併，只改值變了的行、只補缺的 key（含欄位說明），其餘逐位元保留；原子替換後盡力還原 POSIX 權限（已實測）。
- API 為加法性變更：新增 `StartupResult`、`ConfigSnapshot`、`ConfigBinder`（含 nested `ConfigKey`／`ConfigRange`）、`ConfigBindingException`、`ConfigChangeListener`，`ConfigManager`／`AceLibConfig` 新增方法；未變更或移除既有公開簽章，`docs/reference/api-surface-signatures.json` 已同步。

### 本階段內容（型別化的指令框架）

- 以 Paper Brigadier 註冊取代 `plugin.yml` 的指令宣告需求：`BrigadierRegistrar` 在 `onEnable` 期間經 `LifecycleEvents.COMMANDS` 掛載註冊器，平台在命令同步時機執行實際註冊。`plugin.yml` 不再需要 `commands` 區塊（AceLib 自身的 `/acelib` 已遷移，`acelib.admin` 權限節點保留）。
- 型別化組裝入口：`TypedCommand`／`TypedSubCommand` builder 與 `Arguments` 引數工廠。同一個 builder 產出兩種註冊形式——`SubCommandSpec` 相容層保留原樣，既有以 `plugin.yml` 宣告＋bridge attach 的插件不受影響。
- 八種型別化引數各有解析、驗證與自動補全：玩家、離線玩家、有界整數、有界小數、時間長度、世界、列舉、固定字串選項、材質。解析失敗一律以 `ACELIB-CMD-015`（玩家離線沿用既有的 `ACELIB-CMD-007`）回覆，不 wrap、不截斷、不靜默降級。
- 固定選項（列舉、固定字串）在 Brigadier 樹中編譯為 literal 分支；開放式引數送 vanilla 引數型別給客戶端先行驗證。原本預期 literal 分支是基岩版（Geyser）看得見補全的結構，但 2026-10-08 真人基岩客戶端實測（Folia 26.2-7＋Geyser 2.11.3-b1247）顯示基岩端建議列不顯示——literal 分支（`parse-mode`、`parse-fixed`、`trade`）與玩家引數（`parse`）皆然。歸因是 Geyser 官方 Current Limitations 的 Unfixable 條目（Bedrock 不送聊天／指令 UI 輸入封包）；執行與回應不受影響（`/cprobe parse-mode buy` 成功並回 `thread=Folia Region Scheduler Thread #0`）。樣本範圍：四個子指令解析正確（參數數 1／1／2／1），見模組頁補全支援矩陣。時間長度語法與 vanilla time 一致（`100`／`1t`／`1.5s`／`1d`，回傳 ticks），兩端一致拒絕 `h`／`m` 單位以免出現「客戶端擋、伺服器放」的分歧。
- 錯誤在地化：`CommandMessages` 契約搭配 `DefaultCommandMessages`（內建英文）、`MessageServiceCommandMessages`（經 message 模組查 key）與 `LocalizingReplySink`（presentation 層裝飾器，缺 key 退回例外原文）。說明與補全依權限過濾，冷卻沿用既有 `CooldownTracker`，未重造冷卻。
- 生命週期：註冊只在 `onEnable` 呼叫一次，reload 不重建、不重複註冊；同名重複註冊原子拒絕且不殘留半註冊；`shutdown()` 與 plugin disable 後殘留 dispatch 一律回 `ACELIB-CMD-009`。平台未提供取消單一指令註冊的 API，因此 `unregister` 只清本地簿記與內部 registry，平台側節點等 plugin disable 才移除（已於模組頁與 Javadoc 標明）。
- 實機驗證工具：新增 `examples/command-compatibility-probe`，涵蓋 42 個可重跑案例（解析／錯誤／補全／生命週期）與執行緒紀錄，供 Paper／Folia 實測。
- **基岩補全實測結論**：2026-10-08 真人基岩客戶端實測完成（Folia 26.2-7＋Geyser 2.11.3-b1247，玩家 `.linoQsmile`）：基岩端建議列不顯示（literal 分支與玩家引數皆然），執行與回應不受影響。模組頁的基岩版補全矩陣已改記實測結果；未逐項實測之型別（world／material／duration／offlinePlayer／intArg／doubleArg）標為推論自同一平台限制。Java 機器人的 tab 請求走 Java 協議，不能替代基岩客戶端的觀察。

### 本階段內容（逐玩家儲存與玩家資料模型）

- 逐玩家儲存 SPI `PlayerDataStore`：一位玩家一組頂層欄位，由 store 自己負責增量落盤。`PlayerDataStores` 工廠提供三種後端——`sqlite(Path, SchemaVersion)`（預設，單一檔案承載全部玩家資料）、`jdbc(DataSource, SchemaVersion)`（MySQL／MariaDB，`DataSource` 生命週期由呼叫端管理）、`fromDataStore(DataStore)`（遷移期間沿用既有 key-value store）。`owner` 與玩家 UUID、欄位名一起構成主鍵，多個下游可在同一張表各自存放資料而不互相覆寫。
- 預設 SQLite 後端以 WAL 模式開啟、`synchronous=FULL`（每次交易提交都 fsync）、`busy_timeout=5000`；需要 `org.xerial:sqlite-jdbc`，由 `plugin.yml` 的 `libraries:` 在啟動時下載，缺席時以 `ACELIB-DATA-012` 失敗且不影響 AceLib 其他模組。函式庫使用者須自行提供該 driver。
- 逐玩家表 `acelib_player_data` 以 `(player_uuid, owner, field_hash)` 為主鍵，`field_hash` 為欄位名的 SHA-256 hex，主鍵最壞 1352 bytes（utf8mb4 下遠低於 InnoDB 3072 上限），完整欄位名另存於 `field`。MySQL／MariaDB 的 `payload` 使用上限 16,777,215 bytes 的 `MEDIUMTEXT`，SQLite 使用 `TEXT`。`applyChanges(...)` 只寫有變動的欄位：同值 upsert 不產生寫入也不推進 `revision`；欄位移除以 `FieldChange.deletion(...)` 明確刪除，不留孤兒列；整批在單一交易內套用，任一筆失敗即整批 rollback。實際變動的新列從 `revision=1` 開始，後續每次寫入加一；同一批次重複指定欄位時最後一筆為準且只推進一次。upsert 先 `INSERT`，主鍵衝突時再 `UPDATE`，避免 InnoDB `REPEATABLE READ` 下 DELETE-then-INSERT 的 gap-lock 死結，不依賴 vendor 專屬語法。
- 資料模型編解碼 SPI `PlayerDataCodec<T>` 與內建 `RecordPlayerDataCodec`（以 record component 名對映頂層欄位）。資料與模型不符時 `decode` 以 `ACELIB-DATA-002` 失敗並帶出問題欄位，不猜、不填預設值；欄位單純不存在則依型別取預設值。
- `PlayerDataService` 新增逐玩家 store 建構路徑：登入前預載資料、session 轉為 `READY` 後以 `PlayerDataReadyListener` 通知一次（SPI 而非 Bukkit Event，因 I/O executor 既非主執行緒也非 region 執行緒；單一 listener 失敗只記 `ACELIB-PLAYER-009`）。`getOfflineData(uuid)` 可讀離線玩家資料，不需要 active session。另有四參數建構子可自訂單批 flush 的等待上限，逾時語意與既有版本一致（`ACELIB-PLAYER-008`，不等於保存成功）。
- 定期保存週期性失敗不再把 dirty 標記為已清除：落盤確認後才推進已保存序號，失敗批次完整保留供下個週期或 quit／shutdown 重試。異常終止的資料遺失上限為一個保存週期內的變更量，已以獨立 JVM + `SIGKILL` 的 `scripts/player-store-crash-test.sh` 實測（32 次變更中讀回 29 筆、遺失 3 筆，週期 500ms，落在單週期預算內）。
- 既有資料一次性轉換：舊 `player-data.json` 與舊 `acelib_data_kv` 的 `players` 列可轉為逐玩家 store，只補目標缺少的玩家，來源檔與來源資料表零修改零刪除，因此中斷重跑安全、轉換後新發生的資料永遠勝出。三層校驗（筆數／內容雜湊／逐玩家完整性）以「從 store 讀回」重算，SQLite 與 MySQL 同一套成立；只比對本次匯入玩家的完整內容，已存在玩家只檢查仍有資料，避免現場更新後重跑誤報 `ACELIB-DATA-013`。轉換失敗以 `ACELIB-PLAYER-006` 記錄並停用玩家資料服務；來源與備份保留，修復後可重跑，plugin 其他功能仍可啟動。
- 真 DB 驗證擴充：`PlayerStoreCompatSuite`（逐玩家隔離、增量 upsert 與 revision、70,000 字元 payload 往返、欄位／玩家刪除、六組並行變動、交易失敗 rollback、舊資料轉換保留其他 store）與既有 `JdbcCompatSuite` 共用 `scripts/jdbc-mysql-compat.sh`，在 mysql:8.4 與 mariadb:11.4 臨時容器上執行；Gradle test 端以 `PlayerStoreCompatGatedTest` 在環境變數齊備時才跑，CI 不依賴 Docker。
- **修正**：`PlayerDataService` 曾在 `store.save()` 落盤前就推進已保存序號，保存失敗時 dirty 被清掉、變更永不重試。改為逐玩家寫入回傳序號、整批寫完且落盤確認後才推進。
- **修正**：reload 成功路徑與 `reload(INCOMPATIBLE)` 降級路徑原本未關閉舊 store，SQLite 連線會洩漏。現兩處都在舊服務 shutdown（已完成 flush）之後、bind 新服務之前關閉舊 store。
- **審查修正**：離線資料讀取不再修改活躍玩家差分快取；整批寫入全部成功後才推進保存序號，文件同步更正；轉換結果計數只包含實際有欄位寫入的玩家。
- 玩家資料於 `AsyncPlayerPreLoginEvent` 透過既有 session 狀態機登入前預載；join 接手成功預載而不重複讀取，失敗則回退原 join 載入。登入未完成時的預載在 30 秒後清理，reload／disable 解除 listener 與清理排程。
- **未實測項目**：Paper／Folia 實機的 join／reload／disable、SQLite 落盤與 MySQL 後端尚未在實際伺服器上驗收；真 MySQL／MariaDB 相容驗證目前在 macOS 臨時容器完成，需於目標部署環境重跑確認。

### 修補內容（實機驗證回合 2）

- `WorldArgument` 解析加入維度鍵 fallback：依序嘗試 legacy Bukkit 世界名 → 大小寫不敏感掃描 → 維度鍵（`NamespacedKey.fromString(raw, null)` 後 `Bukkit.getWorld(key)`），裸名以 `minecraft` 命名空間解讀。
  - 修正前：世界引數**任何輸入都無法解析**。Brigadier 樹送給客戶端的是 vanilla world 型別，只接受維度鍵（`overworld`／`minecraft:overworld`），而 `Bukkit.getWorld(String)` 認的是 legacy Bukkit 名（主世界為 `world`）。兩套命名對主世界不相交，造成 `world` 被 vanilla 拒（`Unknown dimension 'minecraft:world'`），`overworld`／`minecraft:overworld` 通過 vanilla 卻在解析階段被拒（`invalid value for <world>: 'overworld' (unknown world)`）。
  - 修正後：兩種命名皆可解析出主世界，兩條執行路徑的輸入集合一致。維度鍵為小寫規範形式，`NamespacedKey.fromString` 對含大寫的輸入回 null，因此一併以小寫重試以維持大小寫不敏感語意。
  - 補全行為不變（仍回傳 Bukkit 世界名）；既有的「精確名 → 大小寫不敏感掃描」順序保留。
- 指令相容性探針的回報改為直接取自已解析值的實型別（不再從 usage token 推測），並補上 `PlayerHandle` 分支：在線玩家不再印出 `BukkitSender$BukkitPlayerHandle@<hash>` 與 `type=?`，改為玩家名與 `type=PlayerHandle`。

### 修補內容（實機驗證回合 1）

- `BukkitReplySink` 對玩家回覆加入「同執行緒 inline」快路徑：呼叫當下已擁有該玩家 region 時（`Bukkit.isOwnedByCurrentRegion`；Paper 主執行緒恆 owned、Folia 為對應 region 執行緒）直接送達，不再經 region 派送。
  - 修正前：下游 plugin 的 handler 在 dispatch 執行緒呼叫 `ctx.reply(...)` 會被 backend 以 `ACELIB-CMD-011` 拒絕，訊息完全送不到玩家（實機於 Folia 觀察到 8 筆）。根因是 backend 只認 `AceLibPlugin` owner，與呼叫當下是否已在正確執行緒無關。
  - 修正後：在 dispatch 執行緒的回覆（含 `CommandException` 錯誤提示）可直接送達，下游不需是 `AceLibPlugin` 也不需自行安排 region 派送。
  - 安全下限不變：未擁有（或擁有權判斷失敗，fail-closed）時仍走 backend 派送；非 `AceLibPlugin` owner 的跨執行緒回覆仍以 `ACELIB-CMD-011` 拒絕，不退縮為 inline 送達。
  - 判斷所用 API 已對照 Paper 與 MoonRise 原始碼：`CraftServer.isOwnedByCurrentRegion(Entity)` 委派 `TickThread.isTickThreadFor(entity)`，Paper 上為 `isTickThread()`（主執行緒為 `TickThread`），Folia 由 MoonRise 覆寫為真實 region 擁有權判斷。
- API 為加法性變更：新增 `Arguments`、`TypedCommand`、`TypedSubCommand`、`TypedContext`、`BrigadierRegistrar`、`CommandArgument`、`TypedHandler`、`BrigadierDispatch`、`CommandMessages`、`DefaultCommandMessages`、`MessageServiceCommandMessages`、`LocalizingReplySink`，`CommandErrorKind` 新增 `INVALID_ARGUMENT`；`AceLibPlugin` 新增 `getCommandRegistry()`。未變更或移除既有公開簽章，`docs/reference/api-surface-signatures.json` 已同步（純加法 +135／−0）。

### 本階段內容（外部整合門面）

- 四類業務門面只包裝、不自製：經濟（Vault legacy 純反射，零外部 import）／權限（LuckPerms compileOnly typed 持有者）／佔位符（PlaceholderAPI compileOnly 子類別 expansion）／建造查詢（通用 SPI，本期無外部 adapter）。缺席、停用或不支援時回明確 `UNAVAILABLE` 結果（不得默認允許、不得視為成功）；提供者每次呼叫重新解析，不跨停用快取，停用後注入被拒絕且查詢不可用。
- 新增公開型別（`docs/reference/api-surface.*` 已同步：211 個頂層型別＝Supported 162／SPI 24／Internal 21；簽章基線純加法）：結果值型別 `EconomyResult`／`PermissionResult`／`BuildCheckResult`／`ExternalOperationResult`／`ExternalResultState`（成功不帶 errorCode，非成功餘額為 NaN）；提供者契約 SPI `EconomyProvider`／`PermissionProvider`／`PlaceholderProvider`／`PlaceholderHandler`／`BuildCheckProvider`（下游可替換，內建 adapter 於 AVAILABLE 時為預設）；內建實作 Internal `VaultEconomyProvider`／`LuckPermsPermissionProvider`／`PlaceholderApiPlaceholderProvider`／`AceLibPlaceholderExpansion`（LP／PAPI 型別集中於僅 AVAILABLE 後載入的持有者，缺席時類別可安全載入）。
- Vault `EconomyResponse` 以 `transactionSuccess()==false` 為明確失敗（錯誤訊息取自 `getErrorMessage()`），不視為成功；LuckPerms 以 `UserManager#getUser` 取快取使用者（null 即查無，不做阻塞載入），群組經 `resolveDistinctInheritedNodes` 過濾 `InheritanceNode#getGroupName`，情境經 `ContextManager#getQueryOptions` 快照；佔位符註冊／清理（unregister、reload、disable）不殘留。
- 依賴變更：`net.luckperms:api:5.5`（compileOnly＋test，Maven Central，MIT）／`me.clip:placeholderapi:2.12.3`（compileOnly＋test，repo.helpch.at，GPL v3），`gradle/verification-metadata.xml` 已同步 checksum。
- `examples/external-integration-probe` 最小探針：`/extprobe` 依序呼叫四類門面並輸出（經濟只讀餘額、佔位符註冊後立即清理、建造查詢預期 UNAVAILABLE），供實機驗收。
- **未實測項目**：VaultUnlocked 2.20.2／LuckPerms 5.5.71／PAPI 2.12.3／AceEconomy 2.2.0 的 legacy 相容性與探針輸出尚未在實際伺服器上驗收，由主代理執行。

### 本階段新增錯誤碼

- `ACELIB-EXT-007`：經濟提供者不可用（Vault 缺席／停用／服務未啟用）。
- `ACELIB-EXT-008`：經濟操作失敗（提供者回失敗、回 null 或呼叫拋例外）。
- `ACELIB-EXT-009`：權限提供者不可用（LuckPerms 缺席／停用／服務未啟用）。
- `ACELIB-EXT-010`：權限查詢失敗（查無玩家、回 null 或呼叫拋例外）。
- `ACELIB-EXT-011`：佔位符提供者不可用（PlaceholderAPI 缺席／停用／服務未啟用）。
- `ACELIB-EXT-012`：佔位符操作失敗（識別重複、底層拒絕、回 null 或呼叫拋例外）。
- `ACELIB-EXT-013`：建造查詢不可用（無區域保護提供者／服務未啟用）。
- `ACELIB-EXT-014`：建造查詢失敗（提供者回 null 或呼叫拋例外）。
- `ACELIB-CMD-015`：引數值非法（型別化引數解析失敗：非數字、整數／長度溢位、超出宣告範圍、時間長度溢位、未知世界／材質／選項，或從未上線的玩家名稱）。

## [1.3.1] - 2026-10-06

v1.3.1 是修補版，修正排程、資料、事件、冷卻、設定、世界、生命週期與玩家資料的已知缺陷；本版以 GitHub Release 發布，提供可下載的 `AceLib-1.3.1.jar`，管理員可直接下載，或從 `v1.3.1` tag 以 `./gradlew clean build --no-daemon --console=plain` 建置取得。開發者可從 JitPack（`com.github.smile-minecraft:AceLib:v1.3.1`）取得。

### 修補內容

- 排程：轉發平台任務 handle，取消與實體退休通知真正生效；一次性任務即時解除追蹤；位置排程只讀取載入狀態，未載入時記 `ACELIB-SCHED-004`；停用與派送競態下晚回的 handle 仍會取消。
- 資料：巢狀 `remove` 走訪修正；含 `null` 的資料可正常快照存檔；JSON store 關閉時寫入失敗留下紀錄；遷移完整取代舊內容；寫入失敗清理暫存檔（清理失敗掛 suppressed）；migration 在巢狀節點隔離寫入；`getRecord` 對空白路徑回報 `ACELIB-DATA-003`；暫存檔在取代前先強制落盤；JDBC store 主鍵改用雜湊，舊表於初始化時升級。
- 事件：父類事件註冊能收到子類事件；同一型別重複註冊不再重複觸發。
- 冷卻：並行呼叫原子化；過期紀錄可清理。
- 設定與語系：reload 失敗記錄；設定檔寫入原子化；設定檔版本較新時拒絕降版；缺訊息 key 只警告一次；不存在的語系檔不再每次讀磁碟。
- 世界：鄰近實體查詢改用範圍 API；未實作的效果明確回報不支援。
- 生命週期與上下文：reload 先解除舊 GUI／世界服務與 listener 再重建；`SafeExecutor` 共用 plugin 排程器。
- 玩家資料：reload 後為線上玩家補建 session；載入與保存失敗記錄錯誤碼；快速重連與保存失敗後可重建 session；停用時批次寫回後再一次性保存。
- GUI：確認操作的回呼在離開鎖後執行，並行 confirm／cancel 不再被回呼阻塞。

### 版本與限制

- API 為加法性變更：新增 `PlayerCooldownService#pruneExpired()` 與 `CooldownTracker#pruneExpired()`（冷卻過期清理）；未變更或移除既有公開簽章，`docs/reference/api-surface-signatures.json` 已同步。
- API surface 文件調整：改為宣告公開 API 得於版本之間變更；不再使用 v1 永久相容的表述（見 `docs/reference/api-surface.md`）。
- 本版修補經真實 MySQL 8.4 與 MariaDB 11.4 驗證資料層；Folia 26.2-7 與 Paper 26.2-120 完成實機確認。

## [1.3.0] - 2026-09-28

v1.3.0 以 GitHub Release 發布，提供可下載的 `AceLib-1.3.0.jar`；管理員可直接下載，或從 `v1.3.0` tag 以 `./gradlew clean build --no-daemon --console=plain` 建置取得。開發者如需驗證目前原始碼，請用 `./gradlew publishToMavenLocal` 取得本機座標 `com.smile:acelib:1.3.0`。

### 新增功能

- 表單按鈕圖示（`FormImage`）：Simple 表單按鈕可帶 PATH（資源包路徑）或 URL 圖示；圖示轉換失敗時該按鈕退回純文字並記錄 `ACELIB-FORM-003`，表單其餘部分不受影響。
- 表單文字轉換（`FormText`／`FormTextOptions`，`MessageService.formatFormText`）：Adventure Component 轉為基岩表單可安全顯示的字串；渲染失敗時退回純文字並記錄 `ACELIB-MSG-005`。
- 指令目錄（`CommandCatalog`）：只存描述的共用目錄，不註冊、不執行；跨擁有者同名並存時記錄 `ACELIB-CMD-013`，服務不可用時發布被拒並記錄 `ACELIB-CMD-014`。

### 新增錯誤碼

- `ACELIB-FORM-003`、`ACELIB-MSG-005`、`ACELIB-CMD-013`、`ACELIB-CMD-014`（定義見 `docs/reference/error-codes.md`）。

## [1.2.2] - 2026-09-24

v1.2.2 是修補版，修正 GUI 關窗時的世代判定問題；本版以 GitHub Release 發布，提供可下載的 `AceLib-1.2.2.jar`，管理員可直接下載，或從 `v1.2.2` tag 以 `./gradlew clean build --no-daemon --console=plain` 建置取得。

### 修補內容

- 修正 GUI 關窗事件在世代切換期間可能錯誤判定為目前世代的問題。
- 本版未變更任何既有公開 API 的簽章或語意。

## [1.2.1] - 2026-09-14

v1.2.1 以 GitHub Release 發布，提供可下載的 `AceLib-1.2.1.jar`；管理員可直接下載，或從 `v1.2.1` tag 以 `./gradlew clean build --no-daemon --console=plain` 建置取得。

### 主要功能

- Paper 與 Folia 26.2 升為正式支援（SUPPORTED）：Paper 26.2-120 與 Folia 26.2-7 通過了啟動 smoke 與執行期能力閘驗證；Folia 26.2-4 另外通過了狀態、排程、上下文、訊息 fallback 檢查與真人基岩玩家的四項實測。
- 執行期驗證矩陣納入 26.2：在上述三個 26.2 build 上啟動不再輸出 `ACELIB-PLAT-009` UNVERIFIED 警告。
- 相容性文件、執行期相容矩陣與 consumer fixture 同步更新。

### 版本與限制

- 本版為 1.2.0 之上的加法性更新：未變更任何既有公開 API 語意或簽章。
- `api-version` 維持 `26.1.2`，Java 25；正式支援為 Paper 26.1.2-72、Folia 26.1.2-8、Paper 26.2-120、Folia 26.2-7、Folia 26.2-4，其他未列出的未來版本皆為 UNVERIFIED（尚未驗證）。
- 機器可讀的執行期相容矩陣見 `docs/reference/runtime-compatibility-matrix.json`。

## [1.2.0] - 2026-08-28

v1.2.0 以 GitHub Release 發布，提供可下載的 `AceLib-1.2.0.jar`；管理員可直接下載，或仍可從 `v1.2.0` tag 以 `./gradlew clean build --no-daemon --console=plain` 建置取得。

### 主要功能

- Adventure 4 / 5 雙版本相容：同一份 production JAR 在 Adventure 4.26.1（Paper 26.1.2 攜帶）與 5.2.0 下經 bytecode gate 與 isolated classloader 驗證 binary compatible，基岩 click fallback 在兩版本皆可用。
- 執行期能力閘（capability gate）：新增 `CompatibilityGate` / `CapabilityProbe` / `RuntimeFingerprint` / `CompatibilityStatus`，在啟用期間確認 Adventure / Bedrock 路徑可用性，並以錯誤代碼 `ACELIB-PLAT-004` 等回報未識別實作。
- 排程器後端分流（`SchedulerBackend` seam）：抽取 `PaperSchedulerBackend` / `FoliaSchedulerBackend`，由 `SafeSchedulerImpl` 依平台選擇，避免 Folia 環境誤用全域 `BukkitScheduler`。
- CI 雙版本相容矩陣：新增 `compatibility-nightly.yml` 與 `ci.yml` 矩陣，覆蓋 Paper / Folia 26.1.2 與 26.2 系列。
- 26.2 系列標為 VERIFIED-BETA（已驗證的測試版）：Paper 26.2-120 與 Folia 26.2-7 已在獨立測試環境完成啟動驗證；Folia 26.2-4 另外通過了狀態、排程、上下文、訊息 fallback 與真人基岩玩家的四項實測，但由於上游仍為 beta，尚未列入正式支援。

### 版本與限制

- 本版為 1.1.2 之上的加法性更新：未變更任何既有公開 API 語意或簽章。
- 正式支援（SUPPORTED）仍以 26.1.2 為準（Paper 26.1.2-72、Folia 26.1.2-8）；26.2 系列為 VERIFIED-BETA（已驗證的測試版），其他未列出的未來版本皆為 UNVERIFIED（尚未驗證）。
- 機器可讀的執行期相容矩陣見 `docs/reference/runtime-compatibility-matrix.json`。

## [1.1.2] - 2026-08-27

v1.1.2 以與 1.1.1 相同的 GitHub Release 方式發布（Release 沒有 binary asset）；請從公開 repository 建置取得。

### 主要功能

- 公開 `MessageService` 的 Bedrock 注入建構子：`MessageService(JavaPlugin, LangManager, BedrockService)` 由 package-private 提升為 `public` Supported API。下游插件可從 `AceLibApi#getBedrockService()` 取得 facade 後注入，使四個 `*WithFallback` 方法對基岩玩家啟用 click 降級；此前該建構子不可見，導致下游永遠不降級。

### 版本與限制

- 本版為 1.1.1 之上的加法性更新：僅加寬 API 可見性（package-private → public），未變更任何既有公開 API 語意或簽章，亦未新增 `AceLibApi.getMessageService(...)` 工廠。
- 注入 `BedrockService.forUnavailable(...)` 時，`MessageService` 會安全捕捉其拋出的 `IllegalStateException` 並退回原始 Component，不降級、不中斷（與既有 2 參數建構子行為一致）。
- API surface baseline（`docs/reference/api-surface-signatures.json`）已同步新增該公開建構子條目；`ApiSurfaceSignatureContractTest` 通過。

## [1.1.1] - 2026-08-27

v1.1.1 以與 1.1.0 相同的 GitHub Release 方式發布（Release 沒有 binary asset）；請從公開 repository 建置取得。

### 主要功能

- 訊息 API 擴充：新增 Adventure Component 支援，訊息可攜帶格式化元件；提供 per-locale 查詢，依玩家語言回傳對應訊息。
- 基岩版安全降級：基岩玩家不支援可點擊元件時，自動退回純文字 fallback，不拋例外、不遺失訊息內容。
- 新增相容性矩陣與探測（probe），協助下游 plugin 在啟用期間確認 Adventure / Bedrock 路徑可用性。

### 版本與限制

- 本版為 1.1.0 之上的加法性更新，未變更既有公開 API 語意或簽章。
- 基岩 click fallback 的限制與 1.1.0 相同：聊天連結不可點擊、GUI 無法區分左右鍵。

## [1.1.0] - 2026-08-26

`v1.1.0` 以 GitHub Release 發布，repository 已公開；Release 本身沒有 binary asset。

### 主要功能

- 基岩版玩家支援：透過 Floodgate 偵測基岩玩家，查詢裝置、輸入方式、語言與連結資訊（`BedrockService`）；未知列舉值回報 `UNKNOWN` 不拋例外，Floodgate 缺席時零影響。
- 基岩原生表單：`FormSpec` DSL 支援 Simple / Modal / Custom 三種表單；送出結果明確區分「Floodgate 已接受」（SENT/REJECTED）與玩家回應（VALID/CLOSED/INVALID）。
- 表單回應安全派送：不論上游從哪個執行緒回呼，一律重新派送到玩家 region context；有效結果最多執行一次，離線／關閉／過期／reload／disable 時執行零次。
- 新錯誤分類 `ACELIB-BED-*` 與 `ACELIB-FORM-*`；公開 API 擴充為 143 個頂層型別（111 Supported + 12 SPI + 20 Internal）。

### 版本與限制

- 編譯期鎖定 floodgate api `2.2.5-SNAPSHOT`（unique snapshot `2.2.5-20260809.110940-20`）並啟用 Gradle dependency verification；整合最低門檻 2.2.0。
- 實機驗證組合：Floodgate 2.2.5-SNAPSHOT b140 + Geyser-Spigot 2.11.2-b1232 on Folia 26.2-4（含真人基岩客戶端表單操作驗收）。
- 修復外部整合探測使用伺服器 classloader 導致實機上外部整合永遠無法啟用的 v1.0.0 缺陷；`plugin.yml` softdepend 擴充為 floodgate / Vault / LuckPerms / PlaceholderAPI。
- Cumulus/Floodgate 型別不出現在任何公開簽章；transfer 指令與訊息互動降級不在本版範圍。

## [1.0.0] - 2026-08-14

`v1.0.0` 已作為正式 GitHub Release 發布，repository 已公開。Release 本身沒有 binary asset。

### 主要功能

- 透過 Bukkit `ServicesManager` 提供 `AceLibApi.AceLibProvider`，並處理啟用、內部重載與停用。
- 支援 Paper 與 Folia 的平台能力偵測、安全排程與執行緒上下文檢查。
- 提供設定、訊息、指令、事件、資料儲存與玩家狀態 API。
- 提供世界操作、GUI、自訂物品與外部 plugin 狀態查詢。
- 提供 `/acelib status`、診斷快照、錯誤節流與 `ACELIB-<AREA>-<CODE>` 錯誤分類。
- 附帶可編譯的下游 plugin 範例，以及 Paper/Folia smoke 測試腳本。

### 版本與限制

- 使用 Java 25、Paper API `26.1.2.build.72-stable` 與 Gradle wrapper 9.5.1。
- 採用的 server 版本是 Paper 與 Folia 26.1.2；26.2 尚未驗證。
- AceLib 不支援 Bukkit `/reload`。AceLib API 中的 reload 是函式庫自己的生命週期操作。
- MockBukkit 無法代替 Folia 真實 region scheduler runtime 驗證。
- JitPack `com.github.smile-minecraft:AceLib:v1.0.0` 已可解析；repository 內的 `com.smile:acelib:1.0.0` 只供本機 Maven 開發。

## [0.5.0] - 歷史里程碑

這一節記錄 `0.5.0-SNAPSHOT` 當時的 GA candidate，不代表目前的發布狀態。

當時已完成平台偵測、生命週期、provider、安全排程、上下文檢查，以及設定、訊息、指令、事件、資料、玩家、世界、GUI、物品、外部整合與診斷模組。當時 repository 仍是 private，外部 Maven 與 JitPack artifact 尚未發布；這些狀態已由 1.0.0 的公開 repository、正式 Release 與可用 JitPack 座標取代。

當時未包含大型 GUI 框架、自製經濟或權限系統、跨服資料同步、Web 後台、自動更新器、複雜 ORM、大型命令框架與分散式訊息系統。
