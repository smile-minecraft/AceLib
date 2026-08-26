# Changelog

AceLib 使用語意化版本。安裝與取得方式請看[如何取得 AceLib](docs/reference/release-artifacts.md)；本檔只記錄版本變更。

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
