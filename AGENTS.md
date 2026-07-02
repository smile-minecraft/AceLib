# AGENTS — AceLib AI 開發代理執行守則

> **適用範圍**：本檔案只適用於 `com.smile/acelib` repository 內執行的 subagent（`implementer` / `debugger` / `memorizer` / `momus` / `writer` 等）。
> 人類開發者請改讀 `CONTRIBUTING.md`。
>
> 本守則對應 Plan §二十二（TDD 執行規範）、§二十三（DoD）、§二十四（Agent 開發限制）、
> §二十六（優先開發順序）與 §二十七（總結），並轉譯為 subagent 在收到 task 派工後必須強制執行的紀律。
>
> 任何 subagent 在 AceLib 內違背下列條款，等同越權；其輸出將被視為不合規並退回重做。

---

## 1. 角色定位

你（subagent）被分派到 AceLib 的某一個 task（taskId 在 `.opencode/memory/tasks.json` 內），
目的在於**完成該 task 對應的 Plan section**（例如 `phase-2-safe-scheduler`），
不是「寫出能 compile 的程式碼就好」。

任務範圍以三項邊界嚴格界定：

1. **Plan 已定義**的功能：必須做
2. **Plan 未定義**的功能：禁止做（即使看起來「順手」）
3. **Plan 已標記為 v0.1.0 不做**的能力：見 Plan §三 (2)，禁止提前實作

完成後必須回到主要代理（`build` / `ultra`）的 Review Pack 流程，由 momus 交叉驗證。

---

## 2. 執行紀律（十條強制）

下列十條為 subagent 在 AceLib 內不可妥協的紀律，違反任一條即視為不健康交付：

1. **TDD 強制**：每個功能必先寫測試並確認 **Red**，再寫實作使 **Green**；嚴禁「先寫實作、後補測試」。
2. **DoD 十項檢查**：完成前必須**逐項**核對 Plan §二十三（明確需求 / 正常測試 / 錯誤測試 / 邊界測試 / reload & disable / 錯誤訊息 / Folia 安全 / 不暴露內部 / 文件情境 / 全既有測試綠）。
3. **不硬編結果**：禁止為了讓測試過而寫死 fixed result（例如 `return Arrays.asList(expectedFoo)`、`assertEquals(42, service.compute())` 而 `compute()` 永遠 return 42）。
4. **不超範圍**：禁止在需求未定義時新增功能；禁止在 v0.1.0 加入 GUI 框架、ORM、分散式系統等大型框架。
5. **不破壞 Folia 安全**：禁止假設主執行緒或全域 `BukkitScheduler` 可用；操作玩家 / 實體 / 方塊必須走 AceLib 提供的安全 API，否則必須在 Paper / Folia 兩種 mock 環境各有獨立測試。
6. **不吞錯誤**：禁止 `try { ... } catch (Exception e) { /* swallow */ }`；必須有可追蹤紀錄與 §4 規定的錯誤分類代碼。
7. **不忽略生命週期**：`disable` / `reload` / 玩家離線 / 實體失效 必須在測試中覆蓋；不可只測「快樂路徑」。
8. **不改既有對外契約**：除非 Plan 已記錄為破壞性變更並標記對應版本號（見 Plan §二十一），否則 `public` API 簽章、語意、例外型別一律不動。
9. **不內嵌長內容**：長篇 Plan / Task 內容一律寫入 `.opencode/plans/{id}.md`；registry（`tasks.json` / `plans.json`）只保存 `contentRef`。
10. **完成前必須跑 Comment Signal 自我檢查**：本輪若有任何 modified file，必須依序呼叫 `comment_signal_touched_report({ sessionID })` → `comment_signal_check()`；若 `shouldBlockCompletion === true`，**修註解格式**（如 `[TODO] fix later` → `[TODO:P2] 之後處理`）而非刪除高風險註解（`AI_DO_NOT_EDIT:P0` 等）。

> **§2 ↔ §8 交叉引用（Q1 補強）**：
> §2 為 subagent 紀律要點；Plan §二十四 完整 10 條禁止行為對照表請見 §8「不允許的行為清單」。
> 為避免 subagent 只讀 §2 而漏看 §二十四 第 3、4、8 條，下列**子項**同步揭示此三條（與 §8 條文同義，編號沿用 §二十四）：

- **§2↔§二十四.3（單檔過載）**：禁止把所有功能塞進單一檔案或單一概念；模組應依 Plan §二十六優先級拆分（見 §3 優先開發順序表格）。
- **§2↔§二十四.4（暴露內部）**：禁止讓後續插件直接依賴內部細節（`internal` package、`protected` 欄位）；僅 `public` API 為外部契約。
- **§2↔§二十四.8（隱藏功能）**：禁止加入尚未測試的隱藏功能；任何新功能都必須依 §4 工作流走 TDD 並通過 §6 Folia 分流測試（如適用）。

---

## 3. 優先開發順序（Plan §二十六）

依下列四個優先級推進；上一優先級未穩定前，不得跨級開發：

| 優先級 | 範疇                                                 | 對應 Phase          |
| ------ | ---------------------------------------------------- | ------------------- |
| 一     | 平台偵測、啟動停用、安全排程、上下文安全（Folia-safe 底座） | Phase 0~3           |
| 二     | 設定檔、訊息、指令、事件（基本功能建置）             | Phase 4~7           |
| 三     | 資料儲存與玩家狀態（玩法 / 經濟 / 任務 / 統計）      | Phase 8~9           |
| 四     | 世界操作、GUI、Item、外部整合（核心穩定後逐步加入）  | Phase 10~13         |

當前可承接的 task 範圍可透過 `plan-next({ planId: "acelib-bootstrap-plan-20260701-001" })` 取得，
不得繞過 plan-next 直接挑選任務。

---

## 4. 任務工作流（十步）

每一個被分派到 subagent 的 task 必須**依序**完成下列十步；任一步驟缺漏即視為不健康交付：

1. **Evidence Pack 接收**：從主要代理（`build` / `ultra`）的 prompt 內取得 Evidence Pack（Plan section 引用、taskId、projectId、projectPath、相關既有測試清單）。若 Evidence Pack 缺漏，**主動回報 G3 違規**並要求補件，**不得自行補完**。
2. **TDD Red**：依 Plan section 撰寫需求測試，執行 `./gradlew test`，**貼上 Red 輸出**（含 failing test class + assertion 訊息）作為後續 Green 對照。
3. **TDD Green**：撰寫最小實作使測試通過；**貼上 Green 輸出**（全綠）。
4. **Refactor**：重構使程式碼可讀、可重用；**不**改變對外行為（既有測試須維持綠燈）。
5. **Boundary**：補上邊界測試（null / empty / 極大 / Folia-unsafe context / disable / reload）。
6. **Regression**：若為 bug 修復，補上能重現 bug 的回歸測試。
7. **Docs**：更新對應 Javadoc / README 段落（注意：`README.md` 修正屬 `milestone-v0.1.0-core-safety` task 範圍，**未取得授權前不得擅動**）。
8. **Review Pack**：整理五段式 Review Pack（Context & Impact / TDD Evidence / Key Diffs & Logic / Known Risks / Momus Action Items）回傳主要代理。
9. **momus 審查**：由主要代理委派 momus 交叉驗證；subagent 不得跳過此步。若 momus 要求修改，依其回饋修補後重新提交。
10. **task-state-sync 結案**：由主要代理呼叫 `task-state-sync` 將 task 轉為 `REVIEWING` / `ARCHIVING` / `COMPLETED`，並產出 `memoryReceiptId` 通過 G5 門禁。

> 步驟 8~10 由主要代理執行，subagent 負責產出步驟 1~7 的完整證據。

---

## 5. 單元測試強制

每個 production 公開 API（即 `public` 方法、`public` class）**至少**需配備：

- **1 個正常情境測試**：典型輸入 → 預期輸出
- **1 個錯誤情境測試**：無效輸入 → 預期例外 / 預期拒絕
- **1 個邊界情境測試**：null、空集合、零、極大、不安全上下文

若該 API 涉及**狀態**或**生命週期**，另需補：

- reload 測試（重複呼叫不應留下殘留）
- disable 測試（停用後資源釋放）

若該 API 在 Paper 與 Folia 行為不同，依 §6 規定分組測試。

測試風格請參考現有範例：

- `src/test/java/com/smile/acelib/AceLibPluginTest.java`（MockBukkit 生命週期）
- `src/test/java/com/smile/acelib/platform/PlatformDetectorTest.java`（classpath 隔離探測）

---

## 6. Folia vs Paper 行為分流規則

若同一 API 在 Paper 與 Folia 行為不同，**必須**有兩組獨立測試：

- **Paper 環境 mock**：在 MockBukkit 環境執行，斷言走 Paper 路徑
- **Folia 環境 mock**：在 MockBukkit 環境顯式注入 region context，斷言走 Folia 路徑

判斷分流依據：`com.smile.acelib.platform.PlatformDetector`（見 `src/main/java/com/smile/acelib/platform/`）。
**禁止**在 production code 內使用 `Bukkit.getServer().getScheduler()` 等 Folia-unsafe 全域 API
作為預設路徑；必須在 Folia 環境下改走 `RegionizedServer` 路徑或拋出 `ACELIB-SCHED-001` 錯誤代碼。

---

## 7. 錯誤代碼格式

所有對外拋出或記錄的錯誤必須攜帶 `ACELIB-<AREA>-<CODE>` 格式的分類代碼：

```text
ACELIB-<AREA>-<CODE>
```

範例：

- `ACELIB-SCHED-001`：排程器無法取得玩家 region context
- `ACELIB-CTX-002`：在不安全執行緒嘗試操作實體
- `ACELIB-CFG-003`：設定檔載入失敗且無舊值可回退
- `ACELIB-PLAT-004`：無法識別的伺服器實作

`<AREA>` 對應 Plan phase：`PLAT` / `SCHED` / `CTX` / `CFG` / `MSG` / `CMD` / `EVT` / `DATA` / `PLAYER` / `WORLD` / `GUI` / `ITEM` / `EXT` / `DBG`。
錯誤訊息內容另需包含 CONTRIBUTING.md §6 規定的「五要件」。

---

## 8. 不允許的行為清單（對應 Plan §二十四）

下列十項行為**禁止**執行；違者需 revert 並重新派工：

1. 為了快速通過測試而硬編固定結果
2. 在需求尚未定義時新增功能（speculative feature）
3. 把所有功能塞進單一檔案或單一概念
4. 讓後續插件直接依賴內部細節（`internal` package、`protected` 欄位）
5. 在非同步流程完成後直接操作玩家、實體、世界
6. 忽略 plugin disable / reload / 玩家離線情境
7. 吞掉錯誤（silent catch + 空實作）
8. 加入尚未測試的隱藏功能
9. 用 Paper 主執行緒假設覆蓋 Folia 需求
10. 在 v0.1.0 第一版加入大型框架功能

> **§8.10 ↔ Plan §三 (2) v0.1.0 不做清單（Q2 補強）**：
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

> 違規處理流程：subagent 自我察覺時，停止後續步驟並回報主要代理；
> 若由 momus 審查發現，task 狀態轉 `BLOCKED` 並記錄違規項目。

---

## 9. 參考文件連結

- Plan 全本：`.opencode/plans/acelib-bootstrap-plan-20260701-001.md`（§五～§二十一為各 phase 需求；§二十二～§二十六為本守則原始條文）
- Plan registry：`.opencode/memory/plans.json`
- Task registry：`.opencode/memory/tasks.json`
- L1 專案記憶：`.opencode/memory/project.md`
- L1 狀態投影：`.opencode/memory/state.md`
- 人類開發者協作指南：`CONTRIBUTING.md`
- 專案總覽：`README.md`
- Plugin descriptor：`src/main/resources/plugin.yml`
- 建置腳本：`build.gradle.kts`
