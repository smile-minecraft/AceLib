# AceLib v1 API Surface

本文件列出 AceLib 公開 API 的分類：Supported（給下游 plugin 使用）、SPI（供 adapter / backend / extension 實作者）、Internal（不允許下游依賴）。分類說明每個型別是給誰用的，不代表相容性保證；公開 API 在版本之間可能變更，變更記錄在 [CHANGELOG.md](../../CHANGELOG.md)。

> 本文件由 api-surface.json 單一來源產生，兩者必須一致；一致性由 ApiSurfaceContractTest 驗證。

## 分類政策

- Supported：給下游 plugin 使用的公開 API；版本之間可能變更，變更記錄在 CHANGELOG。
- SPI：供 adapter / backend / extension 實作者使用；文件須寫明實作者責任。
- Internal：不允許下游直接依賴；v1.0 前若維持 public 必須在 allowlist 記錄 retention 理由，收斂為 package-private 屬相容性 break 需先經 review。

## 統計

- 總數：227 個 public 頂層型別
- Supported：176
- SPI：25
- Internal：26

## 分類明細

### com.smile.acelib

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.AceLibApi` | class | Supported | 對外 API facade，是下游取得各 service 的入口。 |  | AceLibPlugin 建構；消費者經 v1 的 AceLibProvider 取得。 |
| `com.smile.acelib.AceLibPlugin` | class | Internal | Bukkit plugin main class，因 plugin.yml 要求必須 public；非穩定消費者契約，穩定入口由 v1 的 AceLibProvider 提供。 | plugin.yml main class 必須 public（Bukkit framework 反射要求）；v1 穩定入口由 AceLibProvider 提供，本類不屬消費者契約。 | Bukkit server；AceLibApi 接收其 lifecycle callback。 |
| `com.smile.acelib.AceLibVersion` | class | Supported | 對外版本常數 VERSION，與 plugin.yml / build 一致；v1 契約一部分。 |  | AceLibApi.uninitialized；DiagnosticsService。 |

### com.smile.acelib.bedrock

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.bedrock.BedrockErrorCodes` | class | Supported | 基岩服務錯誤代碼常數（ACELIB-BED-*）；與 ErrorCodeRegistry/error-codes.md 同步。 |  | BedrockService facade 與 unavailable impl。 |
| `com.smile.acelib.bedrock.BedrockPlayerInfo` | record | Supported | 基岩玩家資訊值型別（裝置/輸入/語言/連結；nested DeviceOs/InputMode/LinkState 列舉）；上游未知列舉值映射 UNKNOWN。 |  | BedrockService.getPlayerInfo；Floodgate typed seam 映射。 |
| `com.smile.acelib.bedrock.BedrockService` | interface | Supported | 基岩版玩家服務 facade（isBedrockPlayer/getPlayerInfo/forms）；缺席環境以 absent lookup 零影響。 |  | AceLibApi.getBedrockService；消費者查詢基岩玩家。 |

### com.smile.acelib.command

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.command.AceLibStatusHandler` | class | Internal | 內部 /acelib status 指令處理器，封裝 console/玩家分流與 region-safe 派送；非消費者 API。 | AceLibPlugin（com.smile.acelib）跨 package 建構註冊 /acelib status；v1 前保留 public 供既有組裝鏈使用。 | BukkitCommandBridge。 |
| `com.smile.acelib.command.ArgumentTypeFactory` | interface | SPI | 自訂引數選用 Brigadier 型別的公開 SPI；下游實作 CommandArgument 時在 brigadierType 內選用對應型別；自 AceLib 1.5.0 起公開，既有方法簽章與語意不變。 |  | CommandArgument.brigadierType；TypedCommand.toBrigadierNode；消費者自訂引數。 |
| `com.smile.acelib.command.Arguments` | class | Supported | 型別化引數工廠（玩家／離線玩家／整數／小數／時間長度／世界／列舉／材質，具解析驗證與自動補全）；v1.4.0 新增。 |  | TypedSubCommand；消費者組裝引數。 |
| `com.smile.acelib.command.BrigadierDispatch` | interface | SPI | Brigadier 執行委派回呼（來源＋重建 args 交回 dispatch，維持單一真相來源）；v1.4.0 新增。 |  | TypedCommand.toBrigadierNode；BrigadierRegistrar 內部實作。 |
| `com.smile.acelib.command.BrigadierRegistrar` | class | Supported | 型別化指令註冊器（內部 registry＋Brigadier 雙寫入；下游正式組裝入口，不再需要 plugin.yml 宣告）；v1.4.0 新增。 |  | 消費者 onEnable 註冊；AceLibPlugin 管理指令。 |
| `com.smile.acelib.command.BukkitCommandBridge` | class | Internal | Bukkit CommandExecutor 橋接內部實作；非穩定契約。 | AceLibPlugin（com.smile.acelib）跨 package 建構並 attach 到 Bukkit CommandExecutor；v1 前保留 public。 | 下游傳統路徑（plugin.yml＋attach）相容層；AceLib 自身 v1.4.0 起改用 BrigadierRegistrar。 |
| `com.smile.acelib.command.BukkitReplySink` | class | Internal | ReplySink 的 Bukkit 內部實作；消費者應使用 ReplySink 抽象而非此類。 | AceLibPlugin 跨 package 建構 ReplySink（含 nested SafeExecutorBackend）；v1 前保留 public 供既有組裝鏈使用。 | AceLibStatusHandler；指令 dispatch。 |
| `com.smile.acelib.command.BukkitSender` | class | Internal | Sender 的 Bukkit 內部實作；消費者應使用 Sender 抽象。 | v1 canonical inventory 契約要求 132 top-level types；public→package-private 屬相容性 break，v1.0 保留 public 並記錄此理由，v1.x 收斂前須先經 review。 | 指令 dispatch。 |
| `com.smile.acelib.command.CatalogMeta` | record | Supported | 指令目錄發布元資料值型別（分類、圖示、需確認子指令）；v1 穩定。 |  | CommandCatalog.publish；消費者發布指令描述。 |
| `com.smile.acelib.command.CatalogResult` | enum | Supported | 指令目錄發布結果列舉；v1 凍結常數順序。 |  | CommandCatalog.publish。 |
| `com.smile.acelib.command.CommandArgument` | interface | SPI | 型別化引數契約（解析／驗證／補全／vanilla 型別／固定選項 literal）；實作者須遵守單 token 不變條件；v1.4.0 新增。 |  | Arguments 工廠產生；TypedSubCommand 持有；消費者可自訂引數。 |
| `com.smile.acelib.command.CommandCatalog` | interface | Supported | 指令目錄服務介面，只存指令描述、不註冊不執行。 |  | 消費者發布指令描述；說明頁產生器讀取快照。 |
| `com.smile.acelib.command.CommandContext` | class | Supported | 傳遞給 SubCommand 的執行上下文（指令、參數、sender）；指令擴充契約的一部分，v1 穩定。 |  | SubCommand.execute。 |
| `com.smile.acelib.command.CommandDoc` | record | Supported | 指令純描述投影值型別（不含 handler／completer／插件實例）；v1 穩定。 |  | CommandCatalog.publish／snapshot；說明頁產生器。 |
| `com.smile.acelib.command.CommandErrorKind` | enum | Supported | 指令錯誤分類列舉，出現在 CommandException 與回覆語意中；v1 凍結常數順序。 |  | CommandException；BukkitReplySink。 |
| `com.smile.acelib.command.CommandException` | class | Supported | 指令層級例外，消費者在 SubCommand 中可拋出；v1 契約。 |  | SubCommand；CommandRegistry。 |
| `com.smile.acelib.command.CommandMessages` | interface | SPI | 指令錯誤訊息在地化契約（部分實作允許，缺 key 退回原文）；v1.4.0 新增。 |  | LocalizingReplySink；消費者可自訂訊息表。 |
| `com.smile.acelib.command.CommandRegistry` | interface | Supported | 指令註冊服務介面，消費者用來註冊 SubCommand。 |  | AceLibPlugin；消費者。 |
| `com.smile.acelib.command.CommandRegistryImpl` | class | Internal | CommandRegistry 的內部實作；非消費者 API。 | AceLibPlugin 跨 package 建構並於 onDisable 呼叫 onPluginDisable；v1 前保留 public。 | AceLibPlugin。 |
| `com.smile.acelib.command.CommandSpec` | class | Supported | 指令規格值型別（名稱、權限、描述）；註冊時使用；v1 穩定。 |  | CommandRegistry.register。 |
| `com.smile.acelib.command.CooldownTracker` | class | Supported | 指令冷卻追蹤工具類，供 SubCommandSpec 使用；public 穩定工具。 |  | SubCommandSpec；CommandRegistryImpl。 |
| `com.smile.acelib.command.DefaultCommandMessages` | class | Supported | 內建英文指令錯誤訊息（預設實作，與既有 dispatcher 文字一致）；v1.4.0 新增。 |  | TypedSubCommand 預設；MessageServiceCommandMessages 缺 key 退回。 |
| `com.smile.acelib.command.LocalizingReplySink` | class | Supported | 在地化回覆出口裝飾器（sendError 按 kind 在地化，缺 key 退回原文）；v1.4.0 新增。 |  | 消費者包裝 ReplySink。 |
| `com.smile.acelib.command.MessageServiceCommandMessages` | class | Supported | 經 message 模組查 key 的在地化轉接（key 前綴＋固定後綴）；v1.4.0 新增。 |  | 消費者自備語言檔時使用。 |
| `com.smile.acelib.command.PlayerHandle` | interface | Supported | 指令中代表玩家/來源的抽象；SubCommand 接收；v1 穩定。 |  | SubCommand；Sender。 |
| `com.smile.acelib.command.ReplySink` | interface | Supported | 指令回覆抽象（region-safe 派送）；SubCommand 接收；v1 穩定。 |  | SubCommand；BukkitReplySink 實作。 |
| `com.smile.acelib.command.Sender` | interface | Supported | 指令來源抽象（console/玩家）；SubCommand 接收；v1 穩定。 |  | SubCommand；BukkitSender 實作。 |
| `com.smile.acelib.command.SubCommand` | interface | SPI | 消費者實作的指令邏輯介面（extension point）；文件須寫明實作者責任與相容性。 |  | CommandRegistry 呼叫；消費者實作。 |
| `com.smile.acelib.command.SubCommandCompleter` | interface | SPI | 消費者實作的 tab 補全介面；extension point。 |  | CommandRegistry 呼叫補全。 |
| `com.smile.acelib.command.SubCommandSpec` | class | Supported | 子指令規格值型別（名稱、權限、冷卻）；註冊時使用；v1 穩定。 |  | CommandRegistry.register。 |
| `com.smile.acelib.command.SubDoc` | record | Supported | 子指令純描述投影值型別（不含 handler／completer）；v1 穩定。 |  | CommandDoc.subcommands；說明頁產生器。 |
| `com.smile.acelib.command.TypedCommand` | class | Supported | 型別化根指令規格（builder 組裝，轉 CommandSpec／Brigadier 節點）；v1.4.0 新增。 |  | BrigadierRegistrar.register；消費者組裝。 |
| `com.smile.acelib.command.TypedContext` | class | Supported | 型別化執行 context（已解析引數值，key 為引數實例）；v1.4.0 新增。 |  | TypedHandler.execute。 |
| `com.smile.acelib.command.TypedHandler` | interface | SPI | 型別化子指令處理器（extension point，只處理業務邏輯不碰字串）；v1.4.0 新增。 |  | TypedSubCommand；消費者實作。 |
| `com.smile.acelib.command.TypedSubCommand` | class | Supported | 型別化子指令規格（builder 組裝，轉 SubCommandSpec 相容層／Brigadier 子樹）；v1.4.0 新增。 |  | TypedCommand；消費者組裝。 |

### com.smile.acelib.config

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.config.AceLibConfig` | class | Supported | 配置綁定工廠（bind/get/unbind）與 ConfigManager/LangManager 存取；v1 穩定入口。 |  | AceLibPlugin；消費者。 |
| `com.smile.acelib.config.ConfigBindingException` | class | Supported | 設定綁定例外（ACELIB-CFG-007），攜帶完整欄位路徑；v1 契約。 |  | ConfigBinder；ConfigManager.bind。 |
| `com.smile.acelib.config.ConfigBinder` | class | Supported | 快照到 record／一般類別的綁定器（型別／範圍／列舉驗證，註解以 nested 承載）；v1 穩定。 |  | ConfigManager.bind；消費者。 |
| `com.smile.acelib.config.ConfigChangeListener` | interface | Supported | 設定檔監看回呼（自動重載成功／無效診斷）；v1 穩定。 |  | ConfigManager.startWatching；消費者實作。 |
| `com.smile.acelib.config.ConfigCrossFieldValidator` | interface | Supported | 消費者實作的跨欄位驗證規則（函式介面）；整份設定通過後才發布新快照，失敗時保留舊快照；1.5.0 新增。 |  | ConfigManager.registerCrossFieldValidator；消費者實作。 |
| `com.smile.acelib.config.ConfigException` | class | Supported | 配置載入/遷移例外；v1 契約。 |  | ConfigManager；ConfigMigration。 |
| `com.smile.acelib.config.ConfigManager` | class | Supported | 配置管理服務（載入/遷移/儲存）；v1 穩定。 |  | AceLibConfig；AceLibPlugin。 |
| `com.smile.acelib.config.ConfigMigration` | interface | SPI | 消費者實作的配置遷移介面（extension point）；寫明冪等與相容性責任。 |  | ConfigManager.registerMigration；消費者實作。 |
| `com.smile.acelib.config.ConfigMissingFileHandler` | interface | Supported | 消費者實作的缺檔攔截規則（函式介面）；只在使用後缺檔、還原最後成功副本之前執行，拋 ConfigException 即拒絕還原；1.5.0 新增。 |  | ConfigManager.registerMissingFileHandler；消費者實作。 |
| `com.smile.acelib.config.ConfigSchema` | record | Supported | 配置結構描述值型別；v1 穩定。 |  | AceLibConfig.withConfigSchema；ConfigManager。 |
| `com.smile.acelib.config.ConfigSnapshot` | class | Supported | 設定不可變快照值型別（深層凍結，同輪一致）；v1 穩定。 |  | ConfigManager.snapshot；ConfigBinder；ConfigChangeListener。 |
| `com.smile.acelib.config.ConfigVersion` | record | Supported | 配置版本值型別（major.minor），可比較；v1 凍結結構。 |  | ConfigSchema；ConfigManager；ConfigMigration。 |
| `com.smile.acelib.config.FieldSpec` | record | Supported | 配置欄位規格值型別；v1 穩定。 |  | ConfigSchema。 |
| `com.smile.acelib.config.LangManager` | class | Supported | 多語系訊息管理服務；v1 穩定。 |  | AceLibConfig；MessageService。 |
| `com.smile.acelib.config.MigrationChain` | class | Supported | 配置遷移鏈值型別，串接 ConfigMigration；v1 穩定。 |  | ConfigManager；ConfigMigration。 |
| `com.smile.acelib.config.MigrationResult` | record | Supported | 配置遷移結果值型別；v1 穩定。 |  | ConfigMigration；MigrationChain。 |
| `com.smile.acelib.config.StartupResult` | record | Supported | 設定啟動四分類結果值型別（分類＋快照＋診斷；狀態以 nested 列舉承載）；v1 穩定。 |  | ConfigManager.startup；消費者。 |

### com.smile.acelib.context

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.context.ContextCheckResult` | record | Supported | 執行緒/操作上下文檢查結果值型別；v1 穩定。 |  | ContextInspector；SafeExecutor。 |
| `com.smile.acelib.context.ContextException` | class | Supported | 上下文違規例外；v1 契約。 |  | SafeExecutor；ContextInspector。 |
| `com.smile.acelib.context.ContextInspector` | class | Supported | 執行緒上下文檢查工具（currentContext/check）；Folia-safe 基礎原語；v1 穩定。 |  | SafeExecutor；消費者。 |
| `com.smile.acelib.context.DebugMode` | class | Supported | 除錯模式開關工具；v1 穩定。 |  | DiagnosticsService；消費者。 |
| `com.smile.acelib.context.OperationType` | enum | Supported | 操作型別列舉（READ_ONLY 等）；v1 凍結常數順序。 |  | ContextInspector；ThreadContext。 |
| `com.smile.acelib.context.SafeExecutor` | class | Supported | Folia-safe 執行原語（executeAsync/executeOnRegion）；v1 穩定核心 API。 |  | 消費者；ReplySink 實作。 |
| `com.smile.acelib.context.ThreadContext` | enum | Supported | 執行緒上下文列舉（UNKNOWN 等）；v1 凍結常數順序。 |  | ContextInspector；OperationType。 |

### com.smile.acelib.data

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.data.DataMigration` | interface | SPI | 消費者實作的資料遷移介面（extension point）；寫明冪等與 rollback 責任。 |  | DataStore 遷移執行器；消費者實作。 |
| `com.smile.acelib.data.DataMigrationContext` | class | Supported | 資料遷移上下文（read/write view）；遷移過程使用；v1 穩定。 |  | DataMigration.migrate。 |
| `com.smile.acelib.data.DataStore` | interface | Supported | 資料儲存服務介面。 |  | PlayerDataService；消費者。 |
| `com.smile.acelib.data.DataStoreException` | class | Supported | 資料儲存例外；v1 契約。 |  | DataStore 實作；PlayerDataService。 |
| `com.smile.acelib.data.JdbcDataStore` | class | Internal | DataStore 的 JDBC 內部實作；非消費者 API。 | AceLibPlugin（com.smile.acelib）跨 package 組裝 DataStore；v1 前保留 public。 | AceLibPlugin 組裝。 |
| `com.smile.acelib.data.PlayerDataCodec` | interface | SPI | record 資料模型與頂層欄位之間的編解碼介面（extension point）；下游可改用別名或加密欄位映射；文件須寫明資料與模型不符時不猜、不填預設值的責任。 |  | RecordPlayerDataCodec；下游自備映射。 |
| `com.smile.acelib.data.PlayerDataConverter` | class | Supported | 既有玩家資料（舊 JSON 檔／舊 acelib_data_kv players 列）轉換為逐玩家 store 的入口；同步執行、只補缺漏、來源零刪除，並留下備份與三層校驗報告。 |  | AceLibPlugin 啟動轉換；下游自備舊資料的遷移。 |
| `com.smile.acelib.data.PlayerDataStore` | interface | SPI | 逐玩家儲存 SPI（extension point）：一位玩家一組頂層欄位、由 store 自行增量落盤；實作者須自行序列化所有操作（介面不保執行緒安全）並在 close 後拒絕操作。 |  | PlayerDataStores 工廠；SqlitePlayerDataStore；JdbcPlayerDataStore；PlayerDataService。 |
| `com.smile.acelib.data.PlayerDataStores` | class | Supported | 逐玩家 store 建立工廠（sqlite 預設／jdbc 可換 MySQL／fromDataStore 相容既有 key-value store）；下游取用 store 的唯一入口，不需依賴內部實作類別。 |  | AceLibPlugin 組裝；下游自建玩家資料儲存。 |
| `com.smile.acelib.data.JsonCodec` | interface | SPI | 消費者實作的 JSON 編解碼介面（extension point）；寫明 round-trip 白名單責任。 |  | JsonFileDataStore；消費者實作。 |
| `com.smile.acelib.data.JsonCodecImpl` | class | Internal | JsonCodec 的內部預設實作；非消費者 API。 | AceLibPlugin 與多個 data/player 測試跨 package 建構預設 codec；v1 前保留 public。 | JsonFileDataStore。 |
| `com.smile.acelib.data.JsonFileDataStore` | class | Internal | DataStore 的 JSON 檔案內部實作；非消費者 API。 | AceLibPlugin（com.smile.acelib）跨 package 組裝 DataStore；v1 前保留 public。 | AceLibPlugin。 |
| `com.smile.acelib.data.MemoryRecord` | class | Internal | Record 的記憶體內部實作；非消費者 API。 | player 模組（LockedPlayerRecord/PlayerDataService）跨 package 使用作為 Record 實作；v1 前保留 public。 | 內部 / 測試。 |
| `com.smile.acelib.data.MigrationChain` | class | Supported | 資料遷移鏈值型別，串接 DataMigration；v1 穩定。 |  | DataStore 遷移；DataMigration。 |
| `com.smile.acelib.data.MigrationResult` | record | Supported | 資料遷移結果值型別；v1 穩定。 |  | DataMigration；MigrationChain。 |
| `com.smile.acelib.data.Record` | interface | SPI | 消費者實作的資料記錄介面（extension point）；定義 path/getter 契約。 |  | DataStore；消費者實作。 |
| `com.smile.acelib.data.RecordPlayerDataCodec` | class | Supported | 以 record component 名對映頂層欄位的預設 PlayerDataCodec 實作；型別不符時以 ACELIB-DATA-002 失敗並帶出問題欄位。 |  | 下游以 record 定義玩家資料模型。 |
| `com.smile.acelib.data.SchemaVersion` | record | Supported | 資料 schema 版本值型別（major.minor）；v1 凍結結構。 |  | DataStore；MigrationResult；Record。 |

### com.smile.acelib.diagnostics

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.diagnostics.Clock` | interface | SPI | 可注入的時鐘介面（extension point）；測試/進階用途提供自訂時間來源。 |  | ErrorThrottler；DiagnosticsService 測試。 |
| `com.smile.acelib.diagnostics.DiagnosticReport` | class | Supported | 診斷報告建構/格式化工具；v1 穩定。 |  | 消費者；DiagnosticsService。 |
| `com.smile.acelib.diagnostics.DiagnosticSnapshot` | record | Supported | 診斷快照值型別；v1 穩定。 |  | DiagnosticReport；DiagnosticsService。 |
| `com.smile.acelib.diagnostics.DiagnosticsService` | class | Supported | 診斷服務（模組狀態、錯誤碼註冊、debug）；v1 穩定。 |  | AceLibPlugin；消費者。 |
| `com.smile.acelib.diagnostics.ErrorCategory` | enum | Supported | 錯誤分類列舉；v1 凍結常數順序。 |  | ErrorCodeRegistry；ErrorCodeInfo。 |
| `com.smile.acelib.diagnostics.ErrorCodeInfo` | record | Supported | 錯誤碼資訊值型別；v1 穩定。 |  | ErrorCodeRegistry。 |
| `com.smile.acelib.diagnostics.ErrorCodeRegistry` | class | Supported | 錯誤碼註冊表（唯一錯誤碼總表來源）；v1 穩定。 |  | DiagnosticsService；各模組。 |
| `com.smile.acelib.diagnostics.ErrorSummaryLine` | record | Supported | 錯誤摘要行值型別；v1 穩定。 |  | DiagnosticReport。 |
| `com.smile.acelib.diagnostics.ErrorThrottler` | class | Supported | 錯誤節流工具（tryRecord/getStats）；v1 穩定。 |  | DiagnosticsService；各模組。 |
| `com.smile.acelib.diagnostics.ModuleState` | record | Supported | 模組狀態值型別；v1 穩定。 |  | DiagnosticsService.registerModuleState。 |
| `com.smile.acelib.diagnostics.ModuleStatus` | enum | Supported | 模組狀態列舉；v1 凍結常數順序。 |  | ModuleState；DiagnosticsService。 |
| `com.smile.acelib.diagnostics.ThrottleDecision` | record | Supported | 節流決策值型別；v1 穩定。 |  | ErrorThrottler。 |
| `com.smile.acelib.diagnostics.ThrottleStats` | record | Supported | 節流統計值型別；v1 穩定。 |  | ErrorThrottler。 |

### com.smile.acelib.display

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.display.DisplayErrorCode` | class | Supported | 每玩家顯示模組錯誤碼常數（ACELIB-DISP-*）；與錯誤登錄表同步。 |  | DisplayService；DisplayResult。 |
| `com.smile.acelib.display.DisplayResult` | class | Supported | 顯示操作結果值型別，帶狀態、錯誤碼、診斷文字或全息字識別碼。 |  | DisplayService 各操作。 |
| `com.smile.acelib.display.DisplayService` | interface | Supported | 每玩家計分板、BossBar 與位置型 TextDisplay 的公開 facade；封裝排程、可見性與生命週期清理。 |  | AceLibApi.getDisplayService；下游 plugin。 |
| `com.smile.acelib.display.DisplayServiceControl` | interface | Internal | 顯示服務內部停用入口；下游不得直接依賴。 | AceLibPlugin（com.smile.acelib）跨 package 在 reload／disable 時停用 DisplayService；生命週期控制不是下游 API。 | AceLibPlugin 執行內部服務停用。 |
| `com.smile.acelib.display.DisplayState` | enum | Supported | 顯示操作結果狀態（SUCCESS／ACCEPTED／REJECTED／FAILED）。 |  | DisplayResult。 |
| `com.smile.acelib.display.Hologram` | class | Supported | 全息字不可變快照，提供識別碼、位置與文字，不暴露 Bukkit 實體。 |  | DisplayService.findHologram。 |

### com.smile.acelib.event

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.event.AceLibEvents` | class | Supported | 事件註冊工廠（create/bind/boundTo）；v1 穩定入口。 |  | AceLibPlugin；消費者。 |
| `com.smile.acelib.event.EventErrorRecord` | record | Supported | 事件錯誤記錄值型別；v1 穩定。 |  | EventErrorRecorder；SafeEventRegistry。 |
| `com.smile.acelib.event.EventErrorRecorder` | class | Supported | 事件錯誤記錄器（ring buffer）；v1 穩定工具。 |  | SafeEventRegistryImpl；消費者。 |
| `com.smile.acelib.event.EventRegistration` | record | Supported | 事件註冊結果值型別；v1 穩定。 |  | SafeEventRegistry。 |
| `com.smile.acelib.event.ListenerPolicy` | enum | Supported | 事件監聽策略列舉（Folia 約束）；v1 凍結常數順序。 |  | SafeEventListener；SafeEventRegistry。 |
| `com.smile.acelib.event.SafeEventListener` | interface | SPI | 消費者實作的 Folia-safe 事件監聽介面（extension point）；寫明 identity/thread 責任。 |  | SafeEventRegistry.register；消費者實作。 |
| `com.smile.acelib.event.SafeEventRegistry` | interface | Supported | 事件註冊服務介面。 |  | AceLibEvents；消費者。 |
| `com.smile.acelib.event.SafeEventRegistryImpl` | class | Internal | SafeEventRegistry 的內部實作；非消費者 API。 | v1 canonical inventory 契約要求 132 top-level types；public→package-private 屬相容性 break，v1.0 保留 public 並記錄此理由，v1.x 收斂前須先經 review。 | AceLibEvents。 |

### com.smile.acelib.external

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.external.AceLibPlaceholderExpansion` | class | Internal | PlaceholderAPI 子類別 expansion（自有佔位符橋接）；消費者改走 PlaceholderProvider 註冊。 | PlaceholderAPI 運行期以註冊表持有 expansion；AceLibPlugin 跨 package 建構註冊；v1 前保留 public。 | PlaceholderApiPlaceholderProvider 持有並註冊。 |
| `com.smile.acelib.external.BuildCheckProvider` | interface | SPI | 建造查詢提供者契約（位置可建造判斷）；實作者只做查詢，不得觸碰世界狀態。 |  | ExternalIntegrationService.setBuildCheckProvider；消費者實作。 |
| `com.smile.acelib.external.BuildCheckResult` | class | Supported | 建造查詢結果值型別（ALLOW／DENY／UNAVAILABLE；成功不帶 errorCode）。 |  | ExternalIntegrationService.canBuild。 |
| `com.smile.acelib.external.EconomyProvider` | interface | SPI | 經濟提供者契約（餘額／扣款／入帳）；實作者為外部包裝，不得自製帳本或去重。 |  | ExternalIntegrationService.setEconomyProvider；消費者實作。 |
| `com.smile.acelib.external.EconomyResult` | class | Supported | 經濟操作結果值型別（餘額查詢／扣款／入帳；非成功時餘額為 NaN）。 |  | ExternalIntegrationService 經濟門面。 |
| `com.smile.acelib.external.ExternalIntegrationErrorCodes` | class | Supported | 外部整合錯誤碼常數表；v1 穩定。 |  | ExternalIntegrationService；IntegrationRegistry。 |
| `com.smile.acelib.external.ExternalIntegrationService` | interface | Supported | 外部整合查詢服務介面。 |  | AceLibApi；消費者。 |
| `com.smile.acelib.external.ExternalIntegrationServiceImpl` | class | Internal | ExternalIntegrationService 的內部實作；非消費者 API。 | AceLibPlugin 跨 package 建構並取 toModuleState() 註冊 diagnostics；v1 前保留 public。 | AceLibPlugin。 |
| `com.smile.acelib.external.ExternalOperationResult` | class | Supported | 通用外部操作結果值型別（佔位符註冊／清理；成功不帶 errorCode）。 |  | ExternalIntegrationService 佔位符門面。 |
| `com.smile.acelib.external.ExternalPluginProbe` | class | Supported | 外部插件探測工具（classpath/版本）；v1 穩定。 |  | IntegrationRegistry；IntegrationAdapter。 |
| `com.smile.acelib.external.ExternalResultState` | enum | Supported | 外部操作結果狀態列舉（SUCCESS／FAILED／UNAVAILABLE；v1 凍結常數順序）。 |  | EconomyResult；PermissionResult；BuildCheckResult。 |
| `com.smile.acelib.external.FloodgateIntegrationAdapter` | class | Internal | Floodgate reflection-only 探測 adapter 與 typed provider seam 持有者；非消費者 API。 | AceLibPlugin（com.smile.acelib）跨 package 建構並讀取 typed lookup（playerLookup）；v1 前保留 public，下游不得依賴。 | AceLibPlugin.bindExternalService 註冊；bindBedrockService 讀取 lookup。 |
| `com.smile.acelib.external.IntegrationAdapter` | interface | SPI | 消費者實作的外部整合介面（extension point）；寫明冪等生命週期與相容性責任。 |  | IntegrationRegistry.register；消費者實作。 |
| `com.smile.acelib.external.IntegrationProbeResult` | record | Supported | 整合探測結果值型別；v1 穩定。 |  | ExternalPluginProbe；IntegrationAdapter；ExternalIntegrationService。 |
| `com.smile.acelib.external.IntegrationRegistry` | class | Supported | 整合介面卡註冊/查詢服務；v1 穩定。 |  | AceLibPlugin；消費者註冊 adapter。 |
| `com.smile.acelib.external.IntegrationStatus` | enum | Supported | 整合狀態列舉；v1 凍結常數順序。 |  | IntegrationProbeResult；ExternalIntegrationService。 |
| `com.smile.acelib.external.LuckPermsIntegrationAdapter` | class | Internal | LuckPerms 內建介面卡實作；消費者不應繼承，請實作 IntegrationAdapter。 | AceLibPlugin 跨 package 註冊內建 adapter；v1 前保留 public。 | AceLibPlugin 註冊內建。 |
| `com.smile.acelib.external.LuckPermsPermissionProvider` | class | Internal | LuckPerms typed 持有者（只在 AVAILABLE 後載入；缺席時不觸發外部類別載入）；消費者改走 PermissionProvider。 | 引用 LuckPerms compileOnly 型別，僅 AVAILABLE 後載入；AceLibPlugin 跨 package 建構；v1 前保留 public。 | AceLibPlugin 建構內建權限提供者。 |
| `com.smile.acelib.external.PermissionProvider` | interface | SPI | 權限提供者契約（群組／情境查詢）；實作者為外部包裝，不代做領域授權。 |  | ExternalIntegrationService.setPermissionProvider；消費者實作。 |
| `com.smile.acelib.external.PermissionResult` | class | Supported | 權限查詢結果值型別（群組／主群組／情境快照；不可用時不默認允許）。 |  | ExternalIntegrationService.getPermissionGroups。 |
| `com.smile.acelib.external.PlaceholderApiIntegrationAdapter` | class | Internal | PlaceholderAPI 內建介面卡實作；消費者不應繼承。 | AceLibPlugin 跨 package 註冊內建 adapter；v1 前保留 public。 | AceLibPlugin 註冊內建。 |
| `com.smile.acelib.external.PlaceholderApiPlaceholderProvider` | class | Internal | PlaceholderAPI typed 持有者（expansion 註冊／清理；只在 AVAILABLE 後載入）；消費者改走 PlaceholderProvider。 | 引用 PlaceholderAPI compileOnly 型別，僅 AVAILABLE 後載入；AceLibPlugin 跨 package 建構；v1 前保留 public。 | AceLibPlugin 建構內建佔位符提供者。 |
| `com.smile.acelib.external.PlaceholderHandler` | interface | SPI | 自有佔位符處理器（下游註冊鍵對應的解析回呼；player 可為 null 表非玩家請求）。 |  | ExternalIntegrationService.registerPlaceholder；消費者實作。 |
| `com.smile.acelib.external.PlaceholderProvider` | interface | SPI | 佔位符提供者契約（註冊／清理；實作者負責無殘留）。 |  | ExternalIntegrationService.setPlaceholderProvider；消費者實作。 |
| `com.smile.acelib.external.VaultEconomyProvider` | class | Internal | Vault legacy 經濟的純反射包裝（零外部 import，每次呼叫重新解析）；消費者改走 EconomyProvider。 | AceLibPlugin 跨 package 建構內建經濟提供者；v1 前保留 public。 | AceLibPlugin 建構內建經濟提供者。 |
| `com.smile.acelib.external.VaultIntegrationAdapter` | class | Internal | Vault 內建介面卡實作；消費者不應繼承。 | AceLibPlugin 跨 package 註冊內建 adapter；v1 前保留 public。 | AceLibPlugin 註冊內建。 |

### com.smile.acelib.form

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.form.FormErrorCodes` | class | Supported | 表單服務錯誤代碼常數（ACELIB-FORM-*）；與 ErrorCodeRegistry/error-codes.md 同步。 |  | FormService.FormSender.absent；FormServiceImpl。 |
| `com.smile.acelib.form.FormImage` | record | Supported | Simple 表單按鈕圖示值型別（PATH 資源包路徑／URL 網址；nested Type 列舉）；消費者提供圖示資料，Cumulus 外部型別不外洩。 |  | FormSpec.Simple.button(String, FormImage)；CumulusFormTranslator 圖示映射。 |
| `com.smile.acelib.form.FormResponse` | class | Supported | 表單回應值型別（immutable：狀態＋可選按鈕索引＋元件答案清單）；經 sendForm 三參數 overload 的 consumer 於玩家 region context 內交付。 |  | FormService.sendForm 三參數 overload；FormServiceImpl 回應派送；CumulusFormTranslator 映射產出。 |
| `com.smile.acelib.form.FormResponseStatus` | enum | Supported | 表單回應狀態語意列舉（VALID/CLOSED/INVALID）；描述玩家回應分類，回應的接收與派送機制不在本型別範圍。 |  | FormService 回應語意文件；後續回應派送以本語意為基礎。 |
| `com.smile.acelib.form.FormSendResult` | enum | Supported | 表單發送結果列舉（SENT/REJECTED）；把 Floodgate 內部 boolean 轉譯為具名遞送狀態，原始 boolean 不外洩。 |  | FormService.sendForm；FormService.FormSender.sendForm。 |
| `com.smile.acelib.form.FormService` | interface | Supported | 表單服務 facade（forProduction 工廠、sendForm 發送與三參數回應註冊、生命週期語意）；發送 seam 以 nested FormSender 隔離外部型別，回應經重新派送於玩家 region context 交付且至多一次。 |  | BedrockService.forms()。 |
| `com.smile.acelib.form.FormSpec` | class | Supported | 基岩原生表單規格 DSL（Simple/Modal/Custom sealed 階層與 Component 元件）；消費者以此描述表單，Cumulus 外部型別不外洩。 |  | FormService.sendForm；CumulusFormTranslator 窮舉翻譯。 |
| `com.smile.acelib.form.FormValue` | interface | Supported | custom 表單元件答案 sealed 介面（Text/Option/Number/Switch nested records）；label 不產值，答案依產值元件順序排列。 |  | FormResponse.values()；CumulusFormTranslator custom 回應映射。 |

### com.smile.acelib.gui

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.gui.GuiArgument` | class | Supported | GUI 開啟參數值型別（玩家/標題/保護格）；v1 穩定。 |  | GuiService.open；GuiSession。 |
| `com.smile.acelib.gui.GuiAsyncRequest` | class | Supported | GUI 非同步請求值型別；v1 穩定。 |  | GuiService；GuiSession。 |
| `com.smile.acelib.gui.GuiButton` | record | Supported | GUI 按鈕描述值型別（識別字＋點擊冷卻；點擊走專屬回呼，不以 SLOT_PROTECTED 冒充） |  | GuiView.button；GuiScope 按鈕分派。 |
| `com.smile.acelib.gui.GuiButtonClick` | record | Supported | 按鈕點擊事件快照值型別（玩家／世代／欄位／按鈕識別字／鐵砧文字）；v1 穩定。 |  | GuiScope 按鈕回呼。 |
| `com.smile.acelib.gui.GuiConfirmation` | class | Supported | GUI 確認流程值型別；v1 穩定。 |  | GuiService；GuiResult。 |
| `com.smile.acelib.gui.GuiErrorCode` | class | Supported | GUI 錯誤碼常數表；v1 穩定。 |  | GuiService；GuiResult。 |
| `com.smile.acelib.gui.GuiFlow` | class | Supported | Java GUI／基岩表單共用流程值型別（有序步驟＋起始＋結束回呼）；v1 穩定。 |  | GuiScope.openFlow／goTo。 |
| `com.smile.acelib.gui.GuiFlowStep` | record | Supported | 共用流程步驟值型別（Java 視圖＋可選基岩表單＋按鈕轉移表）；v1 穩定。 |  | GuiFlow.of；Cumulus 外部型別不外洩。 |
| `com.smile.acelib.gui.GuiInputKind` | enum | Supported | 輸入提示種類列舉（CHAT／ANVIL）；v1 凍結常數順序。 |  | GuiInputPrompt；GuiScope 輸入流程。 |
| `com.smile.acelib.gui.GuiInputPrompt` | record | Supported | 輸入提示描述值型別（種類／標題／提示／長度上限／逾時）；v1 穩定。 |  | GuiScope.promptChat／promptAnvil。 |
| `com.smile.acelib.gui.GuiInputResult` | record | Supported | 玩家輸入結果值型別（玩家／世代／種類／文字；region 內恰好一次交付）；v1 穩定。 |  | GuiScope 輸入 consumer。 |
| `com.smile.acelib.gui.GuiInputTicket` | record | Supported | 輸入票券值型別（不透明一次性票券，綁定玩家與世代）；v1 穩定。 |  | GuiScope.promptChat／submitInput。 |
| `com.smile.acelib.gui.GuiPage` | class | Supported | GUI 分頁結果值型別；v1 穩定。 |  | GuiService；GuiResult。 |
| `com.smile.acelib.gui.GuiReplacementListener` | interface | Supported | GUI 被取代通知回呼（原擁有者接 listener；v1 穩定）。 |  | GuiScope.onReplaced。 |
| `com.smile.acelib.gui.GuiResult` | class | Supported | GUI 操作結果值型別（accepted/success/rejected/failed）；v1 穩定。 |  | GuiService；GuiSession。 |
| `com.smile.acelib.gui.GuiRevalidation` | interface | Supported | 送出前重新驗證回呼（僅 SUCCESS 繼續執行 domain action；v1 穩定）。 |  | GuiScope.confirmWithRevalidation。 |
| `com.smile.acelib.gui.GuiScope` | class | Supported | 單一 plugin 的 GUI 作用域 handle（導航／按鈕／票券／輸入；共用 session 登記；close 具冪等性）。 |  | GuiScopes.create；消費者 onEnable／onDisable。 |
| `com.smile.acelib.gui.GuiScopes` | class | Supported | 插件隔離 GUI 作用域統一工廠（per-plugin 隔離建立與清理；重複建立以 ACELIB-GUI-020 拒絕）。 |  | 消費者 onEnable／onDisable；AceLibPlugin 停用分派。 |
| `com.smile.acelib.gui.GuiService` | interface | Supported | GUI 服務介面。 |  | AceLibApi；消費者。 |
| `com.smile.acelib.gui.GuiServiceControl` | interface | Internal | GUI 服務內部生命週期入口；下游不得依賴，公開 shutdown 已於 1.4.0 移除。 | AceLibPlugin（com.smile.acelib）跨 package 停用 GuiService；v1 前保留 public，下游不得依賴。 | AceLibPlugin 跨 package 執行內部停用。 |
| `com.smile.acelib.gui.GuiSession` | class | Supported | GUI 會話值型別；v1 穩定。 |  | GuiService；GuiResult；GuiArgument。 |
| `com.smile.acelib.gui.GuiState` | enum | Supported | GUI 狀態列舉；v1 凍結常數順序（只能追加）。 |  | GuiResult；GuiSession。 |
| `com.smile.acelib.gui.GuiView` | class | Supported | GUI 視圖描述值型別（種類／標題／格數／放行欄位／按鈕；預設全擋；v1 穩定）。 |  | GuiScope 導航；下游 renderer 讀取。 |

### com.smile.acelib.item

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.item.AceItemFactory` | class | Supported | 物品工廠（create/identify/metadata）；v1 穩定。 |  | 消費者。 |
| `com.smile.acelib.item.ItemErrorCode` | class | Supported | 物品錯誤碼常數表；v1 穩定。 |  | AceItemFactory；ItemMigration。 |
| `com.smile.acelib.item.ItemException` | class | Supported | 物品操作例外；v1 契約。 |  | AceItemFactory；ItemMigration。 |
| `com.smile.acelib.item.ItemIdentity` | record | Supported | 物品識別值型別（namespace:key@major.minor）；v1 穩定。 |  | AceItemFactory；ItemMigration。 |
| `com.smile.acelib.item.ItemMigration` | interface | SPI | 消費者實作的物品遷移介面（extension point）；寫明冪等與 rollback 責任。 |  | ItemMigrationChain；消費者實作。 |
| `com.smile.acelib.item.ItemMigrationChain` | class | Supported | 物品遷移鏈值型別，串接 ItemMigration；v1 穩定。 |  | AceItemFactory；ItemMigration。 |
| `com.smile.acelib.item.ItemMigrationContext` | interface | SPI | 物品遷移上下文介面（extension point）；傳遞給 ItemMigration。 |  | ItemMigration.migrate；消費者實作。 |
| `com.smile.acelib.item.ItemMigrationResult` | record | Supported | 物品遷移結果值型別；v1 穩定。 |  | ItemMigration；ItemMigrationChain。 |
| `com.smile.acelib.item.ItemSchemaVersion` | record | Supported | 物品 schema 版本值型別（major.minor）；v1 凍結結構。 |  | ItemMigration；ItemMigrationResult。 |

### com.smile.acelib.lifecycle

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.lifecycle.LifecycleHost` | interface | Supported | 下游模組生命週期宿主；以 owner 批次驗證相依、排序啟用與反向清理，reload 失敗明確記錄結果並區分可重試與 fail-closed。 |  | AceLibApi.getLifecycleHost；下游 plugin 註冊與撤銷模組。 |
| `com.smile.acelib.lifecycle.LifecycleModule` | record | Supported | 下游模組宣告值型別（穩定 id、相依 id 與啟用回呼）；啟用交回 handle 前的部分資源由模組自行清理。 |  | LifecycleHost.register；下游 plugin 宣告模組。 |
| `com.smile.acelib.lifecycle.LifecycleResult` | record | Supported | 生命週期操作不可變結果（SUCCESS／REJECTED／FAILED、宿主狀態與結構化問題）。 |  | LifecycleHost；下游 plugin 處理註冊、撤銷與 reload 結果。 |

### com.smile.acelib.message

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.message.BedrockFallbackStyle` | enum | Supported | 基岩降級風格列舉（HINTS 預設提示風格、PLAIN_TEXT 整體攤平純文字）；v1.5.0 新增。 |  | MessageService *WithFallbackResult 風格多載；消費者選擇降級風格。 |
| `com.smile.acelib.message.DetailedRender` | record | Supported | 帶狀態的單次渲染結果值型別（RenderedMessage＋RenderStatus＋可用語系＋enriched 診斷）；v1.5.0 新增。 |  | MessageService.renderDetailed。 |
| `com.smile.acelib.message.FormText` | class | Supported | Adventure Component → 基岩表單可安全顯示字串的靜態渲染入口（click/hover 移除、hex 降 16 色、translatable 解析、換行 §r、長度截斷）；下游同一份語系餵聊天與表單。 |  | 消費者；MessageService.formatFormText。 |
| `com.smile.acelib.message.FormTextOptions` | record | Supported | 表單文字渲染選項值型別（click 提示開關、可見字元上限、locale；defaults 為 false/0/null）。 |  | FormText.render；MessageService.formatFormText。 |
| `com.smile.acelib.message.MessageLabel` | record | Supported | 顯示標籤值型別（穩定程式識別字 id＋渲染顯示文字 text；缺 key 時文字退回 id）。 |  | MessageScope.label；GUI 按鈕／表單選項顯示。 |
| `com.smile.acelib.message.MessageScope` | class | Supported | 單一 plugin 的訊息作用域 handle（專屬 LangManager＋MessageService；解析器語系發送／共用渲染／顯示標籤；close 具冪等性）。 |  | MessageScopes.create；消費者 onEnable／onDisable。 |
| `com.smile.acelib.message.MessageScopes` | class | Supported | 插件作用域訊息服務統一工廠（per-plugin 隔離建立與清理；重複建立以 ACELIB-MSG-006 拒絕）。 |  | 消費者 onEnable／onDisable。 |
| `com.smile.acelib.message.MessageService` | class | Supported | 訊息格式化/發送服務；v1 穩定。 |  | 消費者；LangManager。 |
| `com.smile.acelib.message.PlayerLocaleResolver` | interface | Supported | 玩家語系解析器（可替換；預設跟隨 Player.locale；不強制偏好儲存方式）。 |  | MessageScope 解析器發送；下游自訂語系來源。 |
| `com.smile.acelib.message.RenderStatus` | enum | Supported | 單次渲染狀態分類列舉（OK／LOCALE_NOT_LOADED／KEY_MISSING／RENDER_FAILED）；v1.5.0 新增。 |  | DetailedRender.status；MessageService.renderDetailed。 |
| `com.smile.acelib.message.RenderedMessage` | record | Supported | 單次渲染結果（component／text／formText 三視圖＋缺 key／渲染失敗診斷；聊天／ActionBar／GUI／表單共用）。 |  | MessageService.render；MessageScope 共用發送。 |
| `com.smile.acelib.message.SendResult` | record | Supported | 單次發送結果值型別（是否送達、是否確實套用基岩降級）；v1.5.0 新增。 |  | MessageService *WithFallbackResult 入口。 |

### com.smile.acelib.platform

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.platform.Platform` | enum | Supported | 平台列舉（FOLIA/PAPER/UNKNOWN）；v1 凍結常數順序。 |  | PlatformDetector；PlatformCapability；AceLibApi。 |
| `com.smile.acelib.platform.PlatformCapability` | record | Supported | 平台能力 profile 值型別；v1 穩定。 |  | PlatformDetector；AceLibApi；SafeExecutor。 |
| `com.smile.acelib.platform.PlatformDetector` | class | Supported | 平台偵測工具（detect/detectCapability）；v1 穩定。 |  | AceLibPlugin；AceLibApi。 |

### com.smile.acelib.player

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.player.PlayerCooldownService` | class | Supported | 玩家冷卻服務；v1 穩定。 |  | 消費者；PlayerDataService。 |
| `com.smile.acelib.player.PlayerDataReadyListener` | interface | SPI | 資料就緒回呼（extension point）：session 轉為 READY 後通知一次，於 I/O executor 執行緒呼叫（非主執行緒／非 region），故不是 Bukkit Event；實作者須自行把後續操作送回正確上下文。 |  | PlayerDataService。 |
| `com.smile.acelib.player.PlayerDataService` | class | Supported | 玩家資料/會話服務；v1 穩定。 |  | 消費者；AceLibPlugin。 |
| `com.smile.acelib.player.PlayerSession` | class | Supported | 玩家會話值型別；v1 穩定。 |  | PlayerDataService；PlayerSessionRegistry。 |
| `com.smile.acelib.player.PlayerSessionRegistry` | class | Supported | 玩家會話註冊/追蹤服務；v1 穩定。 |  | PlayerDataService。 |
| `com.smile.acelib.player.PlayerSessionState` | enum | Supported | 玩家會話狀態列舉；v1 凍結常數順序。 |  | PlayerSession。 |
| `com.smile.acelib.player.PlayerStateException` | class | Supported | 玩家會話狀態例外；v1 契約。 |  | PlayerSession；PlayerDataService。 |

### com.smile.acelib.scheduler

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.scheduler.AceLibScheduler` | class | Supported | 排程器工廠（create/bind/boundTo）；v1 穩定入口。 |  | AceLibPlugin；消費者。 |
| `com.smile.acelib.scheduler.SafeScheduler` | interface | Supported | Folia-safe 排程服務介面。 |  | AceLibScheduler；消費者。 |
| `com.smile.acelib.scheduler.SafeSchedulerImpl` | class | Internal | SafeScheduler 的內部實作；非消費者 API。 | SafeExecutor（context）、DiagnosticsService（diagnostics）與 AceLibPlugin 跨 package 依賴型別與 getRecorder/bindScheduler；v1 前保留 public。 | AceLibScheduler。 |
| `com.smile.acelib.scheduler.ScheduledTask` | interface | Supported | 排程任務控制介面（cancel/isCancelled）；v1 穩定。 |  | SafeScheduler；SafeExecutor。 |
| `com.smile.acelib.scheduler.TaskErrorRecord` | record | Supported | 排程錯誤記錄值型別；v1 穩定。 |  | TaskErrorRecorder；SafeScheduler。 |
| `com.smile.acelib.scheduler.TaskErrorRecorder` | class | Supported | 排程錯誤記錄器；v1 穩定工具。 |  | SafeSchedulerImpl；消費者。 |
| `com.smile.acelib.scheduler.TaskOutcome` | enum | Supported | 排程動作終態分類列舉；v1 凍結常數順序。 |  | TaskResult；TaskTicket。 |
| `com.smile.acelib.scheduler.TaskResult` | record | Supported | 排程動作終態值型別；v1 穩定。 |  | TaskTicket；TaskScope。 |
| `com.smile.acelib.scheduler.TaskScope` | interface | Supported | 玩家／實體作用域任務群組介面。 |  | SafeScheduler；消費者。 |
| `com.smile.acelib.scheduler.TaskTicket` | interface | Supported | 可觀察終態的任務票據介面；v1 穩定。 |  | TaskScope；消費者。 |
| `com.smile.acelib.scheduler.TaskType` | enum | Supported | 排程任務型別列舉；v1 凍結常數順序。 |  | SafeScheduler；ScheduledTask。 |

### com.smile.acelib.world

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.world.BlockResult` | class | Supported | 區塊操作結果值型別；v1 穩定。 |  | WorldService；WorldBackendResult。 |
| `com.smile.acelib.world.BukkitWorldBackend` | class | Internal | WorldBackend 的 Bukkit/Folia 內部實作；非消費者 API。 | AceLibPlugin 跨 package 建構 WorldBackend 注入 WorldServiceImpl；v1 前保留 public。 | WorldServiceImpl。 |
| `com.smile.acelib.world.EntityReference` | record | Supported | 實體參考值型別；v1 穩定。 |  | WorldService；EntityResult；NearbyQueryResult。 |
| `com.smile.acelib.world.EntityResult` | class | Supported | 實體操作結果值型別；v1 穩定。 |  | WorldService；WorldBackendResult。 |
| `com.smile.acelib.world.LocationSnapshot` | record | Supported | 位置快照值型別（不可變）；v1 穩定。 |  | WorldService；BlockResult；EntityResult 等。 |
| `com.smile.acelib.world.NearbyQueryResult` | class | Supported | 附近查詢結果值型別；v1 穩定。 |  | WorldService。 |
| `com.smile.acelib.world.TeleportResult` | class | Supported | 傳送結果值型別；v1 穩定。 |  | WorldService。 |
| `com.smile.acelib.world.WorldBackend` | interface | SPI | 消費者實作的 world 後端介面（extension point）；寫明 region/thread 與失敗語意責任。 |  | WorldServiceImpl；消費者實作。 |
| `com.smile.acelib.world.WorldBackendResult` | class | Supported | world 後端結果值型別（WorldBackend 回傳）；v1 穩定。 |  | WorldBackend；WorldServiceImpl。 |
| `com.smile.acelib.world.WorldErrorCode` | class | Supported | world 錯誤碼常數表；v1 穩定。 |  | WorldService；WorldResult。 |
| `com.smile.acelib.world.WorldResult` | class | Supported | world 操作結果基類值型別；v1 穩定。 |  | WorldService；WorldBackendResult。 |
| `com.smile.acelib.world.WorldService` | interface | Supported | world 操作服務介面。 |  | AceLibApi；消費者。 |
| `com.smile.acelib.world.WorldServiceImpl` | class | Internal | WorldService 的內部實作；非消費者 API。 | AceLibPlugin 與 AceLibApi 跨 package 建構/型別依賴；v1 前保留 public。 | AceLibPlugin。 |
| `com.smile.acelib.world.WorldServiceUnavailableImpl` | class | Internal | WorldService 的不可用 facade 內部實作（NOT_READY/SHUTDOWN）；非消費者 API。 | AceLibApi（com.smile.acelib）跨 package 建構 NOT_READY/SHUTDOWN facade；v1 前保留 public。 | AceLibApi（uninitialized/shutDown）。 |
| `com.smile.acelib.world.WorldState` | enum | Supported | world 操作狀態列舉；v1 凍結常數順序。 |  | WorldResult；BlockResult 等。 |
