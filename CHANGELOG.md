# Changelog

本檔記錄 AceLib 各版本的顯著變更。版本號遵循語意化版本（SemVer）；
目前 `1.0.0` 為 **Release Candidate（尚未發布）**，外部 Maven / JitPack artifacts 尚未產生。

> **未發布邊界**：repository 仍為 private；`v1.0.0` 正式 git 標籤與外部發布
> 僅在 publishing 流程完成、外部 artifacts 可下載後才成立。本檔與
> [README](README.md)、[相容性與發布狀態](docs/consumer/compatibility.md)
> 一律以「未發布」與 `1.0.0` 為準。

---

## [1.0.0] - 2026-08-14（Release Candidate，未發布）

本版本將 `0.5.0-SNAPSHOT` 封存為歷史里程碑，並以最小範圍同步為 `1.0.0` RC。
**未變更任何 runtime 行為、public API 簽章、API surface、Java 25 / Paper / Folia 基線、
checker policy 或 CI docsCheck。** 僅調整版本來源、consumer fixture 座標與現行文件文字。

### 重大能力

`1.0.0` 提供完整的 Folia-first 基礎能力（皆由測試覆蓋）：

- **平台偵測**：`PlatformDetector` 透過 classpath reflection 判定 Folia / Paper / Unknown。
- **生命週期管理**：`AceLibPlugin` 的 `onEnable` / `onDisable` / reload 冪等性。
- **對外 provider**：`AceLibApi.AceLibProvider` 經 Bukkit `ServicesManager` 註冊，
  reload 反映新 facade、disable 解除註冊並切換 shutdown facade。
- **安全排程器**：`SafeSchedulerImpl` 統一 Folia `RegionizedServer` 與 Paper
  `BukkitScheduler`，避免在 Folia 環境誤用全域 scheduler。
- **執行緒上下文安全**：`ContextInspector` 阻擋在不安全執行緒操作實體。
- **設定 / 訊息 / 指令 / 事件 / 玩家狀態 / 診斷**：提供對應的服務、生命週期與錯誤回報。
- **世界操作**：`WorldService` 提供位置、方塊、實體、傳送與錯誤回報。
- **GUI 與 Item**：`GuiService` / `AceItemFactory` 提供基礎 GUI、按鈕、分頁、
  確認、自訂物品與序列化。
- **外部整合**：`ExternalIntegrationService` 探測 Vault / PlaceholderAPI / LuckPerms
  狀態，提供三態安全 facade。
- **正式服診斷**：`DiagnosticsService` 提供統一診斷報告、錯誤節流、模組狀態追蹤
  與 `/acelib status` 管理指令。
- **錯誤分類**：所有對外錯誤攜帶 `ACELIB-<AREA>-<CODE>` 格式分類代碼。
- **除錯模式**：`DebugMode` 支援 system property / 動態切換 / 快取。
- **雙路徑 smoke harness**：`scripts/smoke-server.sh` 提供 Paper 與 Folia 路徑驗證。

### 對外 API / Provider / 生命週期

- 取得入口：`AceLibApi.AceLibProvider`（Bukkit `ServicesManager` 註冊），
  下游 plugin 以 `depend: [AceLib]` 保證載入順序。
- 座標：`com.smile:acelib:1.0.0`（Maven publication；本機可 `publishToMavenLocal`）。
- 生命週期：enable / disable / reload 三態安全 facade；disable 後資源釋放、
  provider 切換為 shutdown facade，呼叫方會收到明確拒絕而非 NPE。
- 錯誤契約：對外錯誤均攜帶 `ACELIB-<AREA>-<CODE>` 分類代碼，不吞錯。

### 平台基線（Folia / Paper / Java）

- **Java 25**：Paper 26.1 系列最低需求；透過 Foojay JDK Resolver Convention 自動下載 toolchain。
- **Paper / Folia 26.1.2（已驗證基線）**：MockBukkit 4.113.1 對齊 paper-api 26.1.2。
- **26.2 尚未驗證**：不得視為 supported，詳見相容性文件。
- **Gradle 9.5.1**（隨附 `gradlew`）。

### 驗證狀態

下列驗證在本 RC 均為 Green（本機可重現）：

- `./gradlew test`：全部既有單元測試通過（含 API surface / signature / docs coverage）。
- `./gradlew docsCheck`：Javadoc（doclint 啟用）、publication、consumer docs、API tests 聚合通過。
- `./gradlew build`：編譯 + 打包 `build/libs/AceLib-1.0.0.jar` 通過。
- `./gradlew publishToMavenLocal` + `verifyPublication`：四類 artifact
  （jar / pom / sources / javadoc）產出，POM 座標與三處版本來源一致。
- `./gradlew -p examples/consumer-plugin build`：consumer fixture 以 `mavenLocal()`
  解析 `com.smile:acelib:1.0.0` 並編譯通過，`verifyConsumerDocs` 通過。

### 已知限制

- **Folia 真實 runtime**：MockBukkit 不提供 Folia entity scheduler API，
  Folia regionized 排程的真實執行路徑需在 Folia 26.1.2 runtime 上驗證。
- **Vault 整合**：以 reflection-only 探測，未在真實 Vault 安裝環境驗證完整流程。
- **大型伺服器負載**：錯誤節流（window-based 策略）在高並發場景的效能影響尚未量化。
- **跨版本相容**：目前基線為 Paper / Folia 26.1.2，與未來版本的相容性未經驗證
  （26.2 未驗證，不得視為 supported）。
- **`/reload` 支援**：AceLib 的 reload 僅指內部交易式 reload，不支援 Bukkit `/reload`
  指令；使用 Bukkit `/reload` 可能導致狀態不一致。

### 未發布邊界（Release Candidate）

- repository 仍為 **private**；外部 Maven / JitPack artifacts **尚未產生**。
- 未建立 git tag / release，未執行 push，未對外公開 repository 或外部發布。
- `v1.0.0` 正式 git 標籤與外部發布僅在 publishing 流程完成後成立。
- 本 RC 僅保證本機可重現的 artifact 與 consumer 驗證；不宣稱外部 artifact 存在。

---

## [0.5.0] - 歷史里程碑（保留）

> 本段落為歷史紀錄，對應 `0.5.0-SNAPSHOT` 時期的 GA candidate 範圍，**保留不變**，
> 不應誤讀為 `1.0.0`。

### 範圍與限制

本里程碑（v0.5.0）已完成的核心能力（皆由測試覆蓋）：

- **平台偵測**：`PlatformDetector` 透過 classpath reflection 判定 Folia / Paper / Unknown。
- **Lifecycle Manager**：`AceLibPlugin` 的 `onEnable` / `onDisable` / reload 冪等性。
- **對外 provider**：`AceLibApi.AceLibProvider` 經 Bukkit `ServicesManager` 註冊，
  reload 反映新 facade、disable 解除註冊並切換 shutdown facade。
- **安全排程器**：`SafeSchedulerImpl` 統一 Folia `RegionizedServer` 與 Paper
  `BukkitScheduler`，避免在 Folia 環境誤用全域 scheduler。
- **執行緒上下文安全**：`ContextInspector` 阻擋在不安全執行緒操作實體。
- **設定檔 / 訊息系統 / 指令系統 / 事件管理 / 玩家狀態 / 診斷**：提供對應的服務與錯誤回報。
- **世界操作**：`WorldService` 提供位置、方塊、實體、傳送與錯誤回報。
- **GUI 與 Item**：`GuiService` / `AceItemFactory` 提供基礎 GUI、按鈕、分頁、
  確認、自訂物品與序列化。
- **外部整合**：`ExternalIntegrationService` 探測 Vault / PlaceholderAPI / LuckPerms
  狀態，提供三態安全 facade。
- **正式服診斷**：`DiagnosticsService` 提供統一診斷報告、錯誤節流、模組狀態追蹤
  與 `/acelib status` 管理指令。
- **錯誤分類**：所有對外錯誤攜帶 `ACELIB-<AREA>-<CODE>` 格式分類代碼。
- **除錯模式**：`DebugMode` 支援 system property / 動態切換 / 快取。
- **Paper 與 Folia 雙路徑 smoke harness**：`scripts/smoke-server.sh`。

### GA Candidate 限制與未驗證風險（歷史）

本版本（v0.5.0-SNAPSHOT）為 GA candidate，下列項目尚未完全驗證：

1. **Folia 真實 runtime**：MockBukkit 不提供 Folia entity scheduler API，
   Folia regionized 排程的真實執行路徑需在 Folia 26.1.2 runtime 上驗證。
2. **Vault 整合**：Vault 整合以 reflection-only 探測，未在真實 Vault 安裝環境
   驗證完整流程（如 economy / permission 子系統）。
3. **大型伺服器負載**：錯誤節流（`ErrorThrottler`）的 window-based 策略
   在高並發場景下的效能影響尚未量化。
4. **跨版本相容**：目前基線為 Paper 26.1.2 / Folia 26.1.2，與未來版本的
   相容性未經驗證（26.2 未驗證，不得視為 supported）。
5. **`/reload` 支援**：AceLib 的 reload 僅指內部交易式 reload，不支援
   Bukkit `/reload` 指令；使用 Bukkit `/reload` 可能導致狀態不一致。
6. **外部發布**：目前 repository 為 private，外部 Maven / JitPack artifacts
   尚未發布；`v1.0.0` 標籤僅在發布流程完成後成立。

**未實作（v0.5.0 範圍外）**：完整大型 GUI 框架、自製經濟系統、自製權限系統、
跨服資料同步、Web 後台、自動更新器、複雜 ORM、大型命令語法框架、
分散式訊息佇列、過度抽象的插件框架設計。
