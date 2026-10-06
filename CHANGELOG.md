# Changelog

AceLib 使用語意化版本。安裝與取得方式請看[如何取得 AceLib](docs/reference/release-artifacts.md)；本檔只記錄版本變更。

## [1.4.0-SNAPSHOT] - 開發中

1.4.0-SNAPSHOT 為開發中的預覽版本，尚未發布：GitHub Release 尚未建立，JitPack 尚未提供此版本的公開座標。下游在本機驗證請用 `./gradlew publishToMavenLocal` 搭配 `com.smile:acelib:1.4.0-SNAPSHOT`。已發布版本的取得方式（JitPack `com.github.smile-minecraft:AceLib:v1.3.1`、v1.3.1 GitHub Release）維持不變，見下方 `1.3.1` 節。

### 本階段內容（測試套件與測試 JAR）

- 以 Gradle `java-test-fixtures` 在單一模組內交付下游單元測試輔助：可控制時鐘（`FakeClock`）、可控制排程與實體退休事件（`FakeSafeScheduler`）、GUI 與表單標準假實作（`FakeGuiService`／`FakeFormService`，可模擬過時回應、重複回應、關閉失敗）、Provider 缺席／停用／重新取得輔助（`FakeExternalIntegrationService`），以及真實作與假實作共用的服務契約測試。
- 測試 JAR（`AceLib-<version>-test-fixtures.jar`）納入 Release workflow 的獨立選取與 SHA-256 完整性檢查；下游以座標加 `test-fixtures` 後綴引用（`testFixtures(...)` 寫法在 JitPack 解析不到）。
- 開發版本三處（`build.gradle.kts`、`plugin.yml`、`AceLibVersion.java`）與本機 consumer 座標同步為 `1.4.0-SNAPSHOT`。

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

### 本階段內容（設定的啟動、快照與型別綁定）

- 啟動四分類（`ConfigManager#startup`）：首次安裝、有效設定、損壞設定、使用後缺檔；識別依據是安裝狀態 sidecar（只在驗證成功後寫入），不是檔案是否存在。
- 保留最後驗證成功副本（`.last-good`）；損壞時原檔逐位元不動，快照依序取用副本、呼叫端後備或 null（無可用時診斷帶 `ACELIB-CFG-003`，下游據此禁用操作）。
- 整份驗證通過後一次發布不可變 `ConfigSnapshot`（深層凍結，同輪操作固定同一實例）。
- 型別綁定（`ConfigBinder`）：record／一般類別綁定，型別、範圍、列舉在載入時驗證，失敗拋 `ACELIB-CFG-007` 並帶完整欄位路徑。
- 檔案監看自動重載（`startWatching`）：無效新內容保留舊快照並診斷；自己的寫回不觸發迴圈；`reload` 不殺監看器，`close()` 徹底清理（daemon 執行緒）。
- 寫回保留註解：行級合併，只改值變了的行、只補缺的 key（含欄位說明），其餘逐位元保留；原子替換後盡力還原 POSIX 權限（已實測）。
- API 為加法性變更：新增 `StartupResult`、`ConfigSnapshot`、`ConfigBinder`（含 nested `ConfigKey`／`ConfigRange`）、`ConfigBindingException`、`ConfigChangeListener`，`ConfigManager`／`AceLibConfig` 新增方法；未變更或移除既有公開簽章，`docs/reference/api-surface-signatures.json` 已同步。

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
