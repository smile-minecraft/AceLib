# AGENTS — AceLib AI 開發代理執行守則

> **適用範圍**：本檔案只適用於 `com.smile/acelib` repository 內執行的 subagent（`implementer` / `debugger` / `memorizer` / `momus` / `writer` 等）。
> 人類開發者請改讀 `CONTRIBUTING.md`。
>
> 本守則源自已完成的 bootstrap Plan（TDD 執行規範、DoD、Agent 開發限制）。該 Plan 涵蓋的階段已全數發布，
> 目前的工作範圍與優先順序改以 `docs/roadmap.md` 為準；下列紀律仍是 subagent 收到 task 派工後必須強制執行的規則。
>
> 任何 subagent 在 AceLib 內違背下列條款，等同越權；其輸出將被視為不合規並退回重做。

---

## 1. 角色定位

你（subagent）被分派到 AceLib 的某一個 task（taskId 在 `.ultrawork/tasks.json` 內），
目的在於**完成該 task 對應的路線圖項目**（見 `docs/roadmap.md`，例如「AceLib 1.3.1 修補已知缺陷」中的某一個勾選項目，或「AceLib 1.4.0 新功能」某個階段的一項），
不是「寫出能 compile 的程式碼就好」。

任務範圍以三項邊界嚴格界定：

1. **路線圖已排入、且派工已定義**的功能：必須做
2. **派工未定義**的功能：禁止做（即使看起來「順手」）
3. **路線圖列為「候選功能」或「不在範圍內」**的能力：禁止提前實作；要做必須先更新路線圖

完成後必須回到主要代理（`build` / `ultra`）的 Review Pack 流程，由 momus 交叉驗證。

---

## 2. 執行紀律（十條強制）

下列十條為 subagent 在 AceLib 內不可妥協的紀律，違反任一條即視為不健康交付：

1. **TDD 強制**：每個功能必先寫測試並確認 **Red**，再寫實作使 **Green**；嚴禁「先寫實作、後補測試」。
2. **DoD 十項檢查**：完成前必須**逐項**核對下列十項（明確需求 / 正常測試 / 錯誤測試 / 邊界測試 / reload & disable / 錯誤訊息 / Folia 安全 / 不暴露內部 / 文件情境 / 全既有測試綠）。
3. **不硬編結果**：禁止為了讓測試過而寫死 fixed result（例如 `return Arrays.asList(expectedFoo)`、`assertEquals(42, service.compute())` 而 `compute()` 永遠 return 42）。
4. **不超範圍**：禁止在需求未定義時新增功能；禁止實作 `docs/roadmap.md` 尚未排入版本的功能，或其「不在範圍內」列出的項目。
5. **不破壞 Folia 安全**：禁止假設主執行緒或全域 `BukkitScheduler` 可用；操作玩家 / 實體 / 方塊必須走 AceLib 提供的安全 API，否則必須在 Paper / Folia 兩種 mock 環境各有獨立測試。
6. **不吞錯誤**：禁止 `try { ... } catch (Exception e) { /* swallow */ }`；必須有可追蹤紀錄與 §7 規定的錯誤分類代碼。
7. **不忽略生命週期**：`disable` / `reload` / 玩家離線 / 實體失效 必須在測試中覆蓋；不可只測「快樂路徑」。
8. **不改既有對外契約**：除非路線圖已記錄為破壞性變更並標記對應版本號，否則 `public` API 簽章、語意、例外型別一律不動。新增公開型別時要更新 `docs/reference/api-surface.json` 的分類與簽章基線。
9. **不內嵌長內容**：長篇 Plan / Task 內容一律寫入 `.ultrawork/plans/{id}.md`；registry（`tasks.json` / `plans.json`）只保存 `contentRef`。
10. **完成前必須跑 Comment Signal 自我檢查**：本輪若有任何 modified file，必須依序呼叫 `comment_signal_touched_report({ sessionID })` → `comment_signal_check()`；若 `shouldBlockCompletion === true`，**修註解格式**（如 `[TODO] fix later` → `[TODO:P2] 之後處理`）而非刪除高風險註解（`AI_DO_NOT_EDIT:P0` 等）。

> **§2 ↔ §8 交叉引用**：
> §2 為 subagent 紀律要點；完整 10 條禁止行為對照表請見 §8「不允許的行為清單」。
> 為避免 subagent 只讀 §2 而漏看 §8 第 3、4、8 條，下列**子項**同步揭示此三條（與 §8 條文同義，編號沿用 §8）：

- **§2↔§8.3（單檔過載）**：禁止把所有功能塞進單一檔案或單一概念；新功能應拆成獨立模組或型別。
- **§2↔§8.4（暴露內部）**：禁止讓後續插件直接依賴內部細節（`internal` package、`protected` 欄位）；僅 `public` API 為外部契約。
- **§2↔§8.8（隱藏功能）**：禁止加入尚未測試的隱藏功能；任何新功能都必須依 §4 工作流走 TDD 並通過 §6 Folia 分流測試（如適用）。

---

## 3. 優先開發順序

工作順序以 `docs/roadmap.md` 的版本順序為準，並遵守下列三項：

1. **修補先於新功能**：路線圖中較早版本的修補項目未完成前，不得開始依賴它的新功能。
2. **依賴順序**：同一版本內依路線圖列出的先後進行；某項目標明「需要先處理」的前置條件未完成時，不得開工。
3. **底座優先**：平台偵測、啟動停用、安全排程與上下文安全有缺陷時，優先於其他模組處理。

若代理工具提供 `plan-next`，仍須經由它取得可承接的 task，不得自行挑選任務。

---

## 4. 任務工作流（十步）

每一個被分派到 subagent 的 task 必須**依序**完成下列十步；任一步驟缺漏即視為不健康交付：

1. **Evidence Pack 接收**：從主要代理（`build` / `ultra`）的 prompt 內取得 Evidence Pack（路線圖項目引用、taskId、projectId、projectPath、相關既有測試清單）。若 Evidence Pack 缺漏，**主動回報 G3 違規**並要求補件，**不得自行補完**。
2. **TDD Red**：依路線圖項目與派工說明撰寫需求測試，執行 `./gradlew test`，**貼上 Red 輸出**（含 failing test class + assertion 訊息）作為後續 Green 對照。
3. **TDD Green**：撰寫最小實作使測試通過；**貼上 Green 輸出**（全綠）。
4. **Refactor**：重構使程式碼可讀、可重用；**不**改變對外行為（既有測試須維持綠燈）。
5. **Boundary**：補上邊界測試（null / empty / 極大 / Folia-unsafe context / disable / reload）。
6. **Regression**：若為 bug 修復，補上能重現 bug 的回歸測試。
7. **Docs**：更新對應 Javadoc 與 `docs/` 頁面。內容該放哪一頁依 `docs/documentation-style.md`；改完執行 `./gradlew docsCheck`。`docs/roadmap.md` 是勾選式任務清單：項目完成時**只把該行的 `[ ]` 改成 `[x]`**，不改寫項目文字、不改段落標題、不在項目後附加結果、驗證方式、日期或測試數量；修正內容寫進 `CHANGELOG.md` 與模組頁。程式修好但未上實機時，只勾修正項目，不勾「實機確認」。新增、刪除、改寫或搬動項目屬範圍變更，須先回報主要代理並經維護者同意。
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

`<AREA>` 對應模組：`PLAT` / `SCHED` / `CTX` / `CFG` / `LANG` / `MSG` / `CMD` / `EVT` / `DATA` / `PLAYER` / `WORLD` / `GUI` / `ITEM` / `EXT` / `BED` / `FORM` / `DBG`。
新增錯誤碼時要同步登錄到 `docs/reference/error-codes.md`。
錯誤訊息內容另需依 CONTRIBUTING.md「Public API 與文件」一節，說明操作、影響與可採取的處理方式。

---

## 8. 不允許的行為清單

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
10. 實作路線圖未排入版本的功能，或路線圖「不在範圍內」列出的項目

> **§8.10 ↔ 路線圖**：
> 早期 bootstrap Plan 的「v0.1.0 不做清單」已不再適用。目前的範圍邊界以 `docs/roadmap.md` 為準：
> 「候選功能」需要先有下游佐證並排入版本才能動工；「不在範圍內」的項目一律不做，下列為撰寫本守則時的清單：

1. 自訂物品的行為註冊（物品模組維持現有的識別、序列化和遷移）
2. 自製經濟系統或權限系統（只包裝外部 plugin 提供的服務）
3. 下游 plugin 的領域規則（授權、價格、交易條件）
4. 跨伺服器資料同步與分散式訊息系統
5. Web 後台與自動更新器
6. 通用的物件關聯對應（ORM）
7. GUI 的佈局引擎和資料綁定
8. 持久操作紀錄與外部副作用的恢復框架（防止重複扣款是經濟提供者的責任）

> 兩處清單不一致時，以 `docs/roadmap.md` 為準。
>
> 違規處理流程：subagent 自我察覺時，停止後續步驟並回報主要代理；
> 若由 momus 審查發現，task 狀態轉 `BLOCKED` 並記錄違規項目。

---

## 9. 參考文件連結

- 版本路線圖：`docs/roadmap.md`（各版本的範圍、順序、候選功能與不做的項目）
- 文件分工：`docs/documentation-style.md`
- 錯誤碼登錄：`docs/reference/error-codes.md`
- Plan registry：`.ultrawork/plans.json`
- Task registry：`.ultrawork/tasks.json`
- L1 專案記憶：`.ultrawork/memory/MEMORY.md`（主題在 `topics/`）
- L1 狀態投影：`.ultrawork/state.md`
- 人類開發者協作指南：`CONTRIBUTING.md`
- 專案總覽：`README.md`
- Plugin descriptor：`src/main/resources/plugin.yml`
- 建置腳本：`build.gradle.kts`
