# AceLib v1 API Surface

本文件定義 AceLib v1.0 公開 API 的分類契約：Supported（v1 承諾相容）、SPI（供 adapter / backend / extension 實作者）、Internal（不允許下游依賴）。

> 本文件由 api-surface.json 單一來源產生，兩者必須一致；一致性由 ApiSurfaceContractTest 驗證。

## 分類政策

- Supported：v1.0 起承諾 source/binary compatibility。
- SPI：供 adapter / backend / extension 實作者使用；文件須寫明實作者責任與相容性承諾。
- Internal：不允許下游直接依賴；v1.0 前若維持 public 必須在 allowlist 記錄 retention 理由，收斂為 package-private 屬相容性 break 需先經 review。

## 統計

- 總數：143 個 public 頂層型別
- Supported：111
- SPI：12
- Internal：20

## 分類明細

### com.smile.acelib

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.AceLibApi` | class | Supported | 對外 API facade；v1 承諾 source/binary 相容，是下游取得各 service 的入口。 |  | AceLibPlugin 建構；消費者經 v1 的 AceLibProvider 取得。 |
| `com.smile.acelib.AceLibPlugin` | class | Internal | Bukkit plugin main class，因 plugin.yml 要求必須 public；非穩定消費者契約，穩定入口由 v1 的 AceLibProvider 提供。 | plugin.yml main class 必須 public（Bukkit framework 反射要求）；v1 穩定入口由 AceLibProvider 提供，本類不屬消費者契約。 | Bukkit server；AceLibApi 接收其 lifecycle callback。 |
| `com.smile.acelib.AceLibVersion` | class | Supported | 對外版本常數 VERSION，與 plugin.yml / build 一致；v1 契約一部分。 |  | AceLibApi.uninitialized；DiagnosticsService。 |

### com.smile.acelib.bedrock

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.bedrock.BedrockErrorCodes` | class | Supported | 基岩服務錯誤代碼常數（ACELIB-BED-*）；與 ErrorCodeRegistry/error-codes.md 同步。 |  | BedrockService facade 與 unavailable impl。 |
| `com.smile.acelib.bedrock.BedrockPlayerInfo` | record | Supported | 基岩玩家資訊值型別（裝置/輸入/語言/連結；nested DeviceOs/InputMode/LinkState 列舉）；上游未知列舉值映射 UNKNOWN。 |  | BedrockService.getPlayerInfo；Floodgate typed seam 映射。 |
| `com.smile.acelib.bedrock.BedrockService` | interface | Supported | 基岩版玩家服務 facade（isBedrockPlayer/getPlayerInfo/forms）；v1 承諾相容，缺席環境以 absent lookup 零影響。 |  | AceLibApi.getBedrockService；消費者查詢基岩玩家。 |

### com.smile.acelib.command

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.command.AceLibStatusHandler` | class | Internal | 內部 /acelib status 指令處理器，封裝 console/玩家分流與 region-safe 派送；非消費者 API。 | AceLibPlugin（com.smile.acelib）跨 package 建構註冊 /acelib status；v1 前保留 public 供既有組裝鏈使用。 | BukkitCommandBridge。 |
| `com.smile.acelib.command.BukkitCommandBridge` | class | Internal | Bukkit CommandExecutor 橋接內部實作；非穩定契約。 | AceLibPlugin（com.smile.acelib）跨 package 建構並 attach 到 Bukkit CommandExecutor；v1 前保留 public。 | AceLibPlugin 註冊指令。 |
| `com.smile.acelib.command.BukkitReplySink` | class | Internal | ReplySink 的 Bukkit 內部實作；消費者應使用 ReplySink 抽象而非此類。 | AceLibPlugin 跨 package 建構 ReplySink（含 nested SafeExecutorBackend）；v1 前保留 public 供既有組裝鏈使用。 | AceLibStatusHandler；指令 dispatch。 |
| `com.smile.acelib.command.BukkitSender` | class | Internal | Sender 的 Bukkit 內部實作；消費者應使用 Sender 抽象。 | v1 canonical inventory 契約要求 132 top-level types；public→package-private 屬相容性 break，v1.0 保留 public 並記錄此理由，v1.x 收斂前須先經 review。 | 指令 dispatch。 |
| `com.smile.acelib.command.CommandContext` | class | Supported | 傳遞給 SubCommand 的執行上下文（指令、參數、sender）；指令擴充契約的一部分，v1 穩定。 |  | SubCommand.execute。 |
| `com.smile.acelib.command.CommandErrorKind` | enum | Supported | 指令錯誤分類列舉，出現在 CommandException 與回覆語意中；v1 凍結常數順序。 |  | CommandException；BukkitReplySink。 |
| `com.smile.acelib.command.CommandException` | class | Supported | 指令層級例外，消費者在 SubCommand 中可拋出；v1 契約。 |  | SubCommand；CommandRegistry。 |
| `com.smile.acelib.command.CommandRegistry` | interface | Supported | 指令註冊服務介面，消費者用來註冊 SubCommand；v1 承諾相容。 |  | AceLibPlugin；消費者。 |
| `com.smile.acelib.command.CommandRegistryImpl` | class | Internal | CommandRegistry 的內部實作；非消費者 API。 | AceLibPlugin 跨 package 建構並於 onDisable 呼叫 onPluginDisable；v1 前保留 public。 | AceLibPlugin。 |
| `com.smile.acelib.command.CommandSpec` | class | Supported | 指令規格值型別（名稱、權限、描述）；註冊時使用；v1 穩定。 |  | CommandRegistry.register。 |
| `com.smile.acelib.command.CooldownTracker` | class | Supported | 指令冷卻追蹤工具類，供 SubCommandSpec 使用；public 穩定工具。 |  | SubCommandSpec；CommandRegistryImpl。 |
| `com.smile.acelib.command.PlayerHandle` | interface | Supported | 指令中代表玩家/來源的抽象；SubCommand 接收；v1 穩定。 |  | SubCommand；Sender。 |
| `com.smile.acelib.command.ReplySink` | interface | Supported | 指令回覆抽象（region-safe 派送）；SubCommand 接收；v1 穩定。 |  | SubCommand；BukkitReplySink 實作。 |
| `com.smile.acelib.command.Sender` | interface | Supported | 指令來源抽象（console/玩家）；SubCommand 接收；v1 穩定。 |  | SubCommand；BukkitSender 實作。 |
| `com.smile.acelib.command.SubCommand` | interface | SPI | 消費者實作的指令邏輯介面（extension point）；文件須寫明實作者責任與相容性。 |  | CommandRegistry 呼叫；消費者實作。 |
| `com.smile.acelib.command.SubCommandCompleter` | interface | SPI | 消費者實作的 tab 補全介面；extension point。 |  | CommandRegistry 呼叫補全。 |
| `com.smile.acelib.command.SubCommandSpec` | class | Supported | 子指令規格值型別（名稱、權限、冷卻）；註冊時使用；v1 穩定。 |  | CommandRegistry.register。 |

### com.smile.acelib.config

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.config.AceLibConfig` | class | Supported | 配置綁定工廠（bind/get/unbind）與 ConfigManager/LangManager 存取；v1 穩定入口。 |  | AceLibPlugin；消費者。 |
| `com.smile.acelib.config.ConfigException` | class | Supported | 配置載入/遷移例外；v1 契約。 |  | ConfigManager；ConfigMigration。 |
| `com.smile.acelib.config.ConfigManager` | class | Supported | 配置管理服務（載入/遷移/儲存）；v1 穩定。 |  | AceLibConfig；AceLibPlugin。 |
| `com.smile.acelib.config.ConfigMigration` | interface | SPI | 消費者實作的配置遷移介面（extension point）；寫明冪等與相容性責任。 |  | ConfigManager.registerMigration；消費者實作。 |
| `com.smile.acelib.config.ConfigSchema` | record | Supported | 配置結構描述值型別；v1 穩定。 |  | AceLibConfig.withConfigSchema；ConfigManager。 |
| `com.smile.acelib.config.ConfigVersion` | record | Supported | 配置版本值型別（major.minor），可比較；v1 凍結結構。 |  | ConfigSchema；ConfigManager；ConfigMigration。 |
| `com.smile.acelib.config.FieldSpec` | record | Supported | 配置欄位規格值型別；v1 穩定。 |  | ConfigSchema。 |
| `com.smile.acelib.config.LangManager` | class | Supported | 多語系訊息管理服務；v1 穩定。 |  | AceLibConfig；MessageService。 |
| `com.smile.acelib.config.MigrationChain` | class | Supported | 配置遷移鏈值型別，串接 ConfigMigration；v1 穩定。 |  | ConfigManager；ConfigMigration。 |
| `com.smile.acelib.config.MigrationResult` | record | Supported | 配置遷移結果值型別；v1 穩定。 |  | ConfigMigration；MigrationChain。 |

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
| `com.smile.acelib.data.DataStore` | interface | Supported | 資料儲存服務介面；v1 承諾相容。 |  | PlayerDataService；消費者。 |
| `com.smile.acelib.data.DataStoreException` | class | Supported | 資料儲存例外；v1 契約。 |  | DataStore 實作；PlayerDataService。 |
| `com.smile.acelib.data.JdbcDataStore` | class | Internal | DataStore 的 JDBC 內部實作；非消費者 API。 | AceLibPlugin（com.smile.acelib）跨 package 組裝 DataStore；v1 前保留 public。 | AceLibPlugin 組裝。 |
| `com.smile.acelib.data.JsonCodec` | interface | SPI | 消費者實作的 JSON 編解碼介面（extension point）；寫明 round-trip 白名單責任。 |  | JsonFileDataStore；消費者實作。 |
| `com.smile.acelib.data.JsonCodecImpl` | class | Internal | JsonCodec 的內部預設實作；非消費者 API。 | AceLibPlugin 與多個 data/player 測試跨 package 建構預設 codec；v1 前保留 public。 | JsonFileDataStore。 |
| `com.smile.acelib.data.JsonFileDataStore` | class | Internal | DataStore 的 JSON 檔案內部實作；非消費者 API。 | AceLibPlugin（com.smile.acelib）跨 package 組裝 DataStore；v1 前保留 public。 | AceLibPlugin。 |
| `com.smile.acelib.data.MemoryRecord` | class | Internal | Record 的記憶體內部實作；非消費者 API。 | player 模組（LockedPlayerRecord/PlayerDataService）跨 package 使用作為 Record 實作；v1 前保留 public。 | 內部 / 測試。 |
| `com.smile.acelib.data.MigrationChain` | class | Supported | 資料遷移鏈值型別，串接 DataMigration；v1 穩定。 |  | DataStore 遷移；DataMigration。 |
| `com.smile.acelib.data.MigrationResult` | record | Supported | 資料遷移結果值型別；v1 穩定。 |  | DataMigration；MigrationChain。 |
| `com.smile.acelib.data.Record` | interface | SPI | 消費者實作的資料記錄介面（extension point）；定義 path/getter 契約。 |  | DataStore；消費者實作。 |
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

### com.smile.acelib.event

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.event.AceLibEvents` | class | Supported | 事件註冊工廠（create/bind/boundTo）；v1 穩定入口。 |  | AceLibPlugin；消費者。 |
| `com.smile.acelib.event.EventErrorRecord` | record | Supported | 事件錯誤記錄值型別；v1 穩定。 |  | EventErrorRecorder；SafeEventRegistry。 |
| `com.smile.acelib.event.EventErrorRecorder` | class | Supported | 事件錯誤記錄器（ring buffer）；v1 穩定工具。 |  | SafeEventRegistryImpl；消費者。 |
| `com.smile.acelib.event.EventRegistration` | record | Supported | 事件註冊結果值型別；v1 穩定。 |  | SafeEventRegistry。 |
| `com.smile.acelib.event.ListenerPolicy` | enum | Supported | 事件監聽策略列舉（Folia 約束）；v1 凍結常數順序。 |  | SafeEventListener；SafeEventRegistry。 |
| `com.smile.acelib.event.SafeEventListener` | interface | SPI | 消費者實作的 Folia-safe 事件監聽介面（extension point）；寫明 identity/thread 責任。 |  | SafeEventRegistry.register；消費者實作。 |
| `com.smile.acelib.event.SafeEventRegistry` | interface | Supported | 事件註冊服務介面；v1 承諾相容。 |  | AceLibEvents；消費者。 |
| `com.smile.acelib.event.SafeEventRegistryImpl` | class | Internal | SafeEventRegistry 的內部實作；非消費者 API。 | v1 canonical inventory 契約要求 132 top-level types；public→package-private 屬相容性 break，v1.0 保留 public 並記錄此理由，v1.x 收斂前須先經 review。 | AceLibEvents。 |

### com.smile.acelib.external

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.external.ExternalIntegrationErrorCodes` | class | Supported | 外部整合錯誤碼常數表；v1 穩定。 |  | ExternalIntegrationService；IntegrationRegistry。 |
| `com.smile.acelib.external.ExternalIntegrationService` | interface | Supported | 外部整合查詢服務介面；v1 承諾相容。 |  | AceLibApi；消費者。 |
| `com.smile.acelib.external.ExternalIntegrationServiceImpl` | class | Internal | ExternalIntegrationService 的內部實作；非消費者 API。 | AceLibPlugin 跨 package 建構並取 toModuleState() 註冊 diagnostics；v1 前保留 public。 | AceLibPlugin。 |
| `com.smile.acelib.external.ExternalPluginProbe` | class | Supported | 外部插件探測工具（classpath/版本）；v1 穩定。 |  | IntegrationRegistry；IntegrationAdapter。 |
| `com.smile.acelib.external.FloodgateIntegrationAdapter` | class | Internal | Floodgate reflection-only 探測 adapter 與 typed provider seam 持有者；非消費者 API。 | AceLibPlugin（com.smile.acelib）跨 package 建構並讀取 typed lookup（playerLookup）；v1 前保留 public，下游不得依賴。 | AceLibPlugin.bindExternalService 註冊；bindBedrockService 讀取 lookup。 |
| `com.smile.acelib.external.IntegrationAdapter` | interface | SPI | 消費者實作的外部整合介面（extension point）；寫明冪等生命週期與相容性責任。 |  | IntegrationRegistry.register；消費者實作。 |
| `com.smile.acelib.external.IntegrationProbeResult` | record | Supported | 整合探測結果值型別；v1 穩定。 |  | ExternalPluginProbe；IntegrationAdapter；ExternalIntegrationService。 |
| `com.smile.acelib.external.IntegrationRegistry` | class | Supported | 整合介面卡註冊/查詢服務；v1 穩定。 |  | AceLibPlugin；消費者註冊 adapter。 |
| `com.smile.acelib.external.IntegrationStatus` | enum | Supported | 整合狀態列舉；v1 凍結常數順序。 |  | IntegrationProbeResult；ExternalIntegrationService。 |
| `com.smile.acelib.external.LuckPermsIntegrationAdapter` | class | Internal | LuckPerms 內建介面卡實作；消費者不應繼承，請實作 IntegrationAdapter。 | AceLibPlugin 跨 package 註冊內建 adapter；v1 前保留 public。 | AceLibPlugin 註冊內建。 |
| `com.smile.acelib.external.PlaceholderApiIntegrationAdapter` | class | Internal | PlaceholderAPI 內建介面卡實作；消費者不應繼承。 | AceLibPlugin 跨 package 註冊內建 adapter；v1 前保留 public。 | AceLibPlugin 註冊內建。 |
| `com.smile.acelib.external.VaultIntegrationAdapter` | class | Internal | Vault 內建介面卡實作；消費者不應繼承。 | AceLibPlugin 跨 package 註冊內建 adapter；v1 前保留 public。 | AceLibPlugin 註冊內建。 |

### com.smile.acelib.form

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.form.FormErrorCodes` | class | Supported | 表單服務錯誤代碼常數（ACELIB-FORM-*）；與 ErrorCodeRegistry/error-codes.md 同步。 |  | FormService.FormSender.absent；FormServiceImpl。 |
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
| `com.smile.acelib.gui.GuiConfirmation` | class | Supported | GUI 確認流程值型別；v1 穩定。 |  | GuiService；GuiResult。 |
| `com.smile.acelib.gui.GuiErrorCode` | class | Supported | GUI 錯誤碼常數表；v1 穩定。 |  | GuiService；GuiResult。 |
| `com.smile.acelib.gui.GuiPage` | class | Supported | GUI 分頁結果值型別；v1 穩定。 |  | GuiService；GuiResult。 |
| `com.smile.acelib.gui.GuiResult` | class | Supported | GUI 操作結果值型別（accepted/success/rejected/failed）；v1 穩定。 |  | GuiService；GuiSession。 |
| `com.smile.acelib.gui.GuiService` | interface | Supported | GUI 服務介面；v1 承諾相容。 |  | AceLibApi；消費者。 |
| `com.smile.acelib.gui.GuiSession` | class | Supported | GUI 會話值型別；v1 穩定。 |  | GuiService；GuiResult；GuiArgument。 |
| `com.smile.acelib.gui.GuiState` | enum | Supported | GUI 狀態列舉；v1 凍結常數順序（只能追加）。 |  | GuiResult；GuiSession。 |

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

### com.smile.acelib.message

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.message.MessageService` | class | Supported | 訊息格式化/發送服務；v1 穩定。 |  | 消費者；LangManager。 |

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
| `com.smile.acelib.player.PlayerDataService` | class | Supported | 玩家資料/會話服務；v1 穩定。 |  | 消費者；AceLibPlugin。 |
| `com.smile.acelib.player.PlayerSession` | class | Supported | 玩家會話值型別；v1 穩定。 |  | PlayerDataService；PlayerSessionRegistry。 |
| `com.smile.acelib.player.PlayerSessionRegistry` | class | Supported | 玩家會話註冊/追蹤服務；v1 穩定。 |  | PlayerDataService。 |
| `com.smile.acelib.player.PlayerSessionState` | enum | Supported | 玩家會話狀態列舉；v1 凍結常數順序。 |  | PlayerSession。 |
| `com.smile.acelib.player.PlayerStateException` | class | Supported | 玩家會話狀態例外；v1 契約。 |  | PlayerSession；PlayerDataService。 |

### com.smile.acelib.scheduler

| Type | Kind | Classification | Reason | Retention | Main callers |
| --- | --- | --- | --- | --- | --- |
| `com.smile.acelib.scheduler.AceLibScheduler` | class | Supported | 排程器工廠（create/bind/boundTo）；v1 穩定入口。 |  | AceLibPlugin；消費者。 |
| `com.smile.acelib.scheduler.SafeScheduler` | interface | Supported | Folia-safe 排程服務介面；v1 承諾相容。 |  | AceLibScheduler；消費者。 |
| `com.smile.acelib.scheduler.SafeSchedulerImpl` | class | Internal | SafeScheduler 的內部實作；非消費者 API。 | SafeExecutor（context）、DiagnosticsService（diagnostics）與 AceLibPlugin 跨 package 依賴型別與 getRecorder/bindScheduler；v1 前保留 public。 | AceLibScheduler。 |
| `com.smile.acelib.scheduler.ScheduledTask` | interface | Supported | 排程任務控制介面（cancel/isCancelled）；v1 穩定。 |  | SafeScheduler；SafeExecutor。 |
| `com.smile.acelib.scheduler.TaskErrorRecord` | record | Supported | 排程錯誤記錄值型別；v1 穩定。 |  | TaskErrorRecorder；SafeScheduler。 |
| `com.smile.acelib.scheduler.TaskErrorRecorder` | class | Supported | 排程錯誤記錄器；v1 穩定工具。 |  | SafeSchedulerImpl；消費者。 |
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
| `com.smile.acelib.world.WorldService` | interface | Supported | world 操作服務介面；v1 承諾相容。 |  | AceLibApi；消費者。 |
| `com.smile.acelib.world.WorldServiceImpl` | class | Internal | WorldService 的內部實作；非消費者 API。 | AceLibPlugin 與 AceLibApi 跨 package 建構/型別依賴；v1 前保留 public。 | AceLibPlugin。 |
| `com.smile.acelib.world.WorldServiceUnavailableImpl` | class | Internal | WorldService 的不可用 facade 內部實作（NOT_READY/SHUTDOWN）；非消費者 API。 | AceLibApi（com.smile.acelib）跨 package 建構 NOT_READY/SHUTDOWN facade；v1 前保留 public。 | AceLibApi（uninitialized/shutDown）。 |
| `com.smile.acelib.world.WorldState` | enum | Supported | world 操作狀態列舉；v1 凍結常數順序。 |  | WorldResult；BlockResult 等。 |
