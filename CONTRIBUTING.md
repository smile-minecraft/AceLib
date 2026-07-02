# CONTRIBUTING — AceLib 開發協作指南

> 適用對象：在 AceLib repository 內貢獻程式碼、文件或測試的人類開發者。
> Subagent 請改讀 `AGENTS.md`（另一份給 AI 代理的執行守則）。
>
> 本檔內容對應 Plan §二十二（TDD 執行規範）、§二十三（DoD）、§二十四（Agent 開發限制）、
> §二十五（最終驗收）與 §二十六（優先開發順序），並轉譯為可在程式開發過程中逐項打勾的 checklist。

---

## 1. 專案定位

AceLib 是 `com.smile` 組織下 Minecraft 插件的基礎函式庫，提供排程、設定、訊息、指令、事件、
玩家狀態、資料儲存、世界操作、GUI、Item、外部整合與除錯等可重用能力。Folia-first 設計，
Paper 為相容路徑；後續插件透過 `AceLibApi` 取得不可變 facade，依 `Platform` 列舉分流，
不應再直接依賴 `BukkitScheduler` 等 Folia-unsafe API。

| 元件       | 版本                       |
| ---------- | -------------------------- |
| JDK        | 25                         |
| Gradle     | 8.10（`gradlew` wrapper）  |
| Paper API  | 26.1.2.build.72-stable     |
| Folia API  | 26.1.2                     |
| JUnit      | 5.11.0                     |
| MockBukkit | 4.113.1                    |
| Mockito    | 5.11.0                     |

---

## 2. 開發紀律（強制）

### 2.1 TDD 七步（Plan §二十二）

每一個新功能或 bug 修復都必須依下列順序推進，跳步視同違規：

- [ ] 步驟 1：撰寫描述「行為」的需求測試，**不預設內部類別或方法名**
- [ ] 步驟 2：執行測試，**確認 Red**；若一開始就通過代表測試沒捕捉需求
- [ ] 步驟 3：實作最小功能，**達成 Green**
- [ ] 步驟 4：重構（不改變對外行為）
- [ ] 步驟 5：補上**邊界測試**（玩家離線、停用、reload、資料失敗、不安全上下文）
- [ ] 步驟 6：補上**回歸測試**（任何 bug 修復都留下對應測試）
- [ ] 步驟 7：更新需求文件（先更新需求與驗收，再繼續開發）

### 2.2 DoD 十項（Plan §二十三）

完成一個功能之前，**逐項**核對下列清單；未通過者一律視為未完成：

- [ ] DoD-1：該功能有明確的需求描述（對應到 Plan 章節或 task section）
- [ ] DoD-2：有**正常情境**測試
- [ ] DoD-3：有**錯誤情境**測試（拒絕無效輸入、不安全上下文）
- [ ] DoD-4：有至少一個**邊界條件**測試（null、空集合、零、極大值）
- [ ] DoD-5：若功能受生命週期影響，有 **reload / disable** 測試
- [ ] DoD-6：有**錯誤訊息或錯誤分類代碼**（見 §4 錯誤訊息規範）
- [ ] DoD-7：未破壞 Folia 安全原則（見 §5 平台分流）
- [ ] DoD-8：未讓後續插件依賴內部實作細節（不暴露非 public 類型）
- [ ] DoD-9：對應文件含使用情境說明（README / Javadoc / 範例）
- [ ] DoD-10：`./gradlew test` 全部既有測試**維持綠燈**

### 2.3 Agent 開發限制（Plan §二十四）

下列十項屬於「**禁止**」條款，違者需 revert：

- [ ] 禁止為了快速通過測試而**硬編固定結果**（hard-coded fixed result）
- [ ] 禁止在需求尚未定義時**新增功能**（no speculative features）
- [ ] 禁止把所有功能塞進**單一檔案或單一概念**中
- [ ] 禁止讓後續插件**直接依賴內部細節**（internal package、protected 欄位）
- [ ] 禁止在**非同步流程完成後直接操作**玩家、實體、世界（未回到 region context）
- [ ] 禁止忽略 plugin **disable / reload / 玩家離線**情境
- [ ] 禁止**吞掉錯誤**（silent catch + 空實作 / no-op）
- [ ] 禁止加入**尚未測試的隱藏功能**
- [ ] 禁止用 Paper **主執行緒假設**覆蓋 Folia 需求
- [ ] 禁止在 v0.1.0 第一版加入**大型框架功能**（GUI 框架、ORM、分散式系統等）

> **§2.3 ↔ Plan §三 (2) v0.1.0 不做清單（Q2 補強）**：
> 上一條之「大型框架功能」對應 Plan §三 (2) 明列之十項，
> 任一項出現於 v0.1.0 第一版皆視為違規；完整清單如下：

1. 完整大型 GUI 框架
2. 自製經濟系統
3. 自製權限系統
4. 跨服資料同步
5. Web 後台
6. 自動更新器
7. 複雜 ORM
8. 大型命令語法框架
9. Redis、RabbitMQ 或其他分散式系統
10. 過度抽象的插件框架設計

---

## 3. 優先開發順序（Plan §二十六）

| 優先級 | 範疇                                                 | 對應 Phase              |
| ------ | ---------------------------------------------------- | ----------------------- |
| 一     | 平台偵測、啟動停用、安全排程、上下文安全（Folia-safe 底座） | Phase 0~3               |
| 二     | 設定檔、訊息、指令、事件（基本功能建置）             | Phase 4~7               |
| 三     | 資料儲存與玩家狀態（玩法 / 經濟 / 任務 / 統計）      | Phase 8~9               |
| 四     | 世界操作、GUI、Item、外部整合（核心穩定後逐步加入）  | Phase 10~13             |

> 實作順序應由實際插件需求驅動，不可一次把所有可能用到的功能塞進 AceLib。

---

## 4. commit / branch / PR 規範

### 4.1 Commit 訊息

採 **Conventional Commits** 風格，以英文撰寫，建議長度 ≤ 72 字 subject：

```text
<type>(<scope>): <subject>

<body>

<footer>
```

常用 type：`feat` / `fix` / `refactor` / `test` / `docs` / `chore` / `perf`。
scope 採 phase 編號或模組名，例如 `feat(phase-1): add PlatformDetector for Folia classpath`。
breaking change 須於 footer 標 `BREAKING CHANGE:` 並對應到 Plan §二十一版本號。

### 4.2 Branch 命名

- 功能分支：`phase-N-<slug>`，例如 `phase-2-safe-scheduler`
- 里程碑分支：`milestone-vX.Y.Z-<slug>`，例如 `milestone-v0.1.0-core-safety`
- 修復分支：`fix-<ticket>-<slug>`（若有對應 task 編號則帶上）

### 4.3 Pull Request

PR 必須：

- 綁定 **task ID**（`.opencode/memory/tasks.json` 內的 `taskId`）
- 綁定 **plan ID**（`acelib-bootstrap-plan-20260701-001`）
- 在描述中引用對應的 Plan 章節（§五～§二十一）與 DoD checklist 編號
- 隨 PR 附上 `./gradlew test` 的綠燈輸出（Red→Green 證據）
- 通過 momus 審查後才能 merge（見 AGENTS.md 的 Review Pack 流程）

---

## 5. 測試規範

### 5.1 Red-Green-Refactor 強制

- 嚴禁「先寫實作、後補測試」；commit 紀錄若呈現「實作在前、測試在後」需還原順序
- bug 修復必須先寫失敗回歸測試，再修正實作
- 重構 PR 不得夾帶行為變更；若同時需要變更行為，請拆成兩個 PR

### 5.2 MockBukkit vs classpath 隔離測試

| 情境                              | 推薦方式                                | 原因                                                                 |
| --------------------------------- | --------------------------------------- | -------------------------------------------------------------------- |
| 測試 `JavaPlugin` 生命週期         | MockBukkit 4.113.1                      | 模擬 `onEnable` / `onDisable` / `reload` 真實 server 行為             |
| 測試 platform reflection 探測     | `ClassLoader` 子類隔離 classpath        | 不依賴 Bukkit runtime，可在純 JVM 跑（見 `PlatformDetectorTest`）     |
| 測試 service / utility / config    | JUnit 5 + Mockito 5.11.0                 | 純 Java 邏輯，避免 MockBukkit 開銷                                   |
| 測試 region-aware / Folia 行為     | 在 MockBukkit 環境顯式 mock region context | Paper 與 Folia 行為分流必須有兩組獨立測試（見 AGENTS.md §6）         |

---

## 6. 錯誤訊息規範（Plan §四 (5)）

任何對外拋出或記錄的錯誤訊息必須包含下列**五要件**：

1. **發生在哪個功能區域**（模組 / 套件 / 類別）
2. **發生時正在處理什麼操作**（哪個 API、哪個輸入）
3. **對伺服器管理員的影響**（資料未存、玩家被踢、任務殘留等）
4. **建議檢查方向**（檢查設定、檢查權限、檢查插件版本）
5. **錯誤分類代碼**（見下方格式）

### 錯誤分類代碼格式

```text
ACELIB-<AREA>-<CODE>
```

範例：

- `ACELIB-SCHED-001`：排程器無法取得玩家 region context
- `ACELIB-CTX-002`：在不安全執行緒嘗試操作實體
- `ACELIB-CFG-003`：設定檔載入失敗且無舊值可回退
- `ACELIB-PLAT-004`：無法識別的伺服器實作

`<AREA>` 建議對應 Plan phase：`PLAT` / `SCHED` / `CTX` / `CFG` / `MSG` / `CMD` / `EVT` / `DATA` / `PLAYER` / `WORLD` / `GUI` / `ITEM` / `EXT` / `DBG`。

> 完整 AREA 代碼表將於 Phase 14（除錯 / 診斷）正式定型；目前請依模組自然語意挑選。

---

## 7. 參考文件連結

- Plan 全本：`.opencode/plans/acelib-bootstrap-plan-20260701-001.md`
- Plan registry：`.opencode/memory/plans.json`（planId 索引）
- Task registry：`.opencode/memory/tasks.json`（task 編號與 owner 對應）
- Subagent 執行守則：`AGENTS.md`（本 repo 根目錄）
- 專案總覽：`README.md`
- 授權條款：[LICENSE](LICENSE)
