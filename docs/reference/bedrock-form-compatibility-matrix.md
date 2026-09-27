# Bedrock 表單相容性矩陣（FormSpec × Geyser）

> 狀態：**部分已驗證（Paper 除外）**。Folia 26.2 與 Folia 26.1.2 的真人觀察
> 已完成（見第 3.1／3.3 節）；Paper 26.1.2 經使用者決定不做，
> 對應格維持 `未驗證`。
> 任何格子在取得第 5 節所述的實際觀察證據之前，不得改寫為其他狀態。

> 伺服器 `[fprobe-send]` log 只證明「已送出」（Floodgate 已接受遞送），
> 不等於客戶端渲染或點擊成功；`[fprobe-response]` 才是玩家實際回應的證據。
> 不得以送出日誌代替客戶端顯示的觀察，也不得以推論補齊任何格子。

## 1. 目的與範圍

本矩陣記錄 AceLib 表單相容性探針（`examples/form-compatibility-probe`）經
`FormService` 發送的固定 `FormSpec` 案例，在 **Bedrock 客戶端（經 Geyser
轉換）** 上的實際呈現與互動結果。

涵蓋案例（共 11 項，對應 `FormProbeCases.buildCatalog()` 的目錄順序）：

| # | 案例 ID | 特性 |
| - | ------- | ---- |
| 1 | `icon-path-item` | PATH 圖示（物品貼圖 `textures/items/diamond_sword`） |
| 2 | `icon-path-block` | PATH 圖示（方塊貼圖 `textures/blocks/diamond_block`） |
| 3 | `icon-url` | URL 圖示（https 示例圖片） |
| 4 | `icon-broken-fallback` | 錯誤路徑：中間按鈕圖示不存在；圖示空白但三顆按鈕仍可點且索引正確 |
| 5 | `text-hex-downgrade` | FormText hex 降級（`#ff8800` 降為 16 色） |
| 6 | `text-gradient-downgrade` | FormText gradient 降級（紅→藍，經 MiniMessage 解析） |
| 7 | `text-decoration-stripped` | FormText 裝飾清理（底線與刪除線移除、粗體斜體保留） |
| 8 | `translatable-with-fallback` | translatable 有 fallback（未註冊鍵顯示備援文字） |
| 9 | `translatable-key-only` | translatable 無 fallback（未註冊鍵保留 key） |
| 10 | `multiline` | 多行文字（換行保留、每行補 `§r`、跨行樣式延續） |
| 11 | `overlong-truncation` | 超長文字（完整可見字元計數截斷並補省略號，上限 60） |

**非目標**：本探針不實作正式表單業務邏輯、Modal／Custom 表單種類、
`CommandCatalog` 的表單顯示（本版不含），也不把任何 Component 攤平成文字。
它只負責「發送固定案例」與「記錄觀察」。Modal 與 Custom 種類的相容性
不在本輪範圍；若後續需要，新增案例時同步擴充本矩陣。

## 2. 狀態標記定義

| 標記 | 意義 |
| ---- | ---- |
| `保留` | 該特性在目標客戶端上完整呈現（圖示可見／文字樣式正確／點擊索引正確）。 |
| `忽略` | 該特性被目標客戶端靜默丟棄（無錯誤，但效果消失）。 |
| `部分轉換` | 部分呈現（例如圖示空白但按鈕仍可點、索引正確；或顏色降級但文字可讀）。 |
| `未驗證` | 尚未在實機以該客戶端觀察；結果未知。 |

## 3. 相容性矩陣

> 本節按三個目標環境各列一張結果表（3.1 Folia 26.2、3.2 Paper 26.1.2、
> 3.3 Folia 26.1.2）。Folia 26.2 與 Folia 26.1.2 已各自獨立完成真人觀察；
> Paper 26.1.2 經使用者決定不做。兩個 Folia 環境各自獨立觀察，
> 不以 3.1 推論 3.3，也不以任一環境推論 Paper。
> 所有結果都以「真人基岩客戶端觀察」為準；伺服器日誌只佐證「已送出」與
> 「點擊索引」，不等同顯示結果。

### 3.1 Folia 26.2（已驗證）

環境：伺服器 Folia 26.2-7、Geyser 2.11.3-b1247、Floodgate 2.2.5-SNAPSHOT、
客戶端 Bedrock 26.51、觀察日期 2026-09-28。受測探針為修正後的版本
（`icon-url` 改用實測 PNG、`translatable-with-fallback` 改用 `.fallback(...)`；
兩處初次不如預期的追查見下方附註）。

| 案例 ID | 結果 | 觀察證據 |
| ------- | ---- | -------- |
| `icon-path-item` | 保留 | 真人基岩客戶端觀察：物品貼圖圖示正常顯示。 |
| `icon-path-block` | 保留 | 真人基岩客戶端觀察：方塊貼圖圖示正常顯示。 |
| `icon-url` | 保留 | 真人基岩客戶端觀察：https 圖片正常顯示。附註：初次觀察未顯示，追查後確認是探針當時指向保留示範網域 `example.com`（回傳網頁而非圖片）所致，不是 AceLib 缺陷；探針改用實測為 PNG 的公開網址並重新送出（SENT）後重測正常。 |
| `icon-broken-fallback` | 部分轉換 | 真人基岩客戶端觀察：第二顆按鈕圖示空白，但三顆按鈕皆可點；伺服器日誌三次點擊索引為 0／1／2（僅佐證點擊索引與已送出），證明圖示遺失不造成按鈕錯位。 |
| `text-hex-downgrade` | 保留 | 真人基岩客戶端觀察：顏色降級後可讀、無亂碼。 |
| `text-gradient-downgrade` | 保留 | 真人基岩客戶端觀察：漸層降級後可讀、無亂碼。 |
| `text-decoration-stripped` | 保留 | 真人基岩客戶端觀察：底線與刪除線消失、粗體與斜體保留。 |
| `translatable-with-fallback` | 保留 | 真人基岩客戶端觀察：顯示備援文字。附註：初次觀察顯示鍵名，追查後確認是探針誤把文字以 translation 參數傳入、根本沒有設定 fallback 所致，不是 AceLib 缺陷；改用 `.fallback(...)` 並補上「渲染輸出必須包含備援文字」的測試、重新送出（SENT）後重測正常。 |
| `translatable-key-only` | 保留 | 真人基岩客戶端觀察：顯示鍵名；依本矩陣定義，fallback 與 key 皆為允許結果。 |
| `multiline` | 保留 | 真人基岩客戶端觀察：三行顯示與換行正確。 |
| `overlong-truncation` | 保留 | 真人基岩客戶端觀察：截斷並補省略號、表單正常開啟。 |

> Geyser 版本註記：本次驗收期間 Geyser 由 2.11.2-b1232 更新為 2.11.3-b1247。
> 原因：客戶端 26.51 超出舊版支援範圍，舊版直接拒絕連線；新版經官方公布
> SHA-256 校驗通過後安裝，更新後連線正常。舊版已備份保留。

### 3.2 Paper 26.1.2（未驗證：使用者決定不做）

> 本表全部為 `未驗證`，且**不會再補做**：經使用者決定，本環境不進行真人觀察
>（此環境需另建伺服器；使用者選擇只做兩個 Folia 環境並以既有工具切換版本，
> 先前為它建立的暫存伺服器目錄已刪除）。以下每列的解除條件僅說明「若要補做
> 需要什麼」，不表示將會執行；任何人不得把該格改為其他狀態，
> 也不得以 3.1／3.3 節結果推論本環境。

| 案例 ID | Bedrock (Geyser) | 觀察證據 / 解除條件 |
| ------- | ---------------- | ------------------- |
| `icon-path-item` | 未驗證 | 需真人基岩客戶端截圖證明物品圖示可見；若不可見，記 `忽略` 並附截圖。 |
| `icon-path-block` | 未驗證 | 需真人基岩客戶端截圖證明方塊圖示可見；若不可見，記 `忽略` 並附截圖。 |
| `icon-url` | 未驗證 | 需真人基岩客戶端截圖證明 https 圖示可見（含網路可達時的載入狀態）；若空白，記 `忽略` 並附截圖與當時網路狀態。 |
| `icon-broken-fallback` | 未驗證 | 需真人依序點擊三顆按鈕：中間圖示應空白但三顆皆可點，且 `[fprobe-response]` 的 `clickedButton` 依序為 0／1／2；三項全滿足記 `部分轉換`，任一失敗記 `忽略` 並說明。 |
| `text-hex-downgrade` | 未驗證 | 需真人基岩客戶端截圖證明文字以近似 16 色顯示且可讀；截圖即證據。 |
| `text-gradient-downgrade` | 未驗證 | 需真人基岩客戶端截圖證明漸層已降級顯示且文字可讀；截圖即證據。 |
| `text-decoration-stripped` | 未驗證 | 需真人基岩客戶端截圖證明底線／刪除線消失且粗體斜體保留；截圖即證據。 |
| `translatable-with-fallback` | 未驗證 | 需真人基岩客戶端截圖或文字記錄證明顯示 fallback 備援文字（非 key）；fallback 與 key 皆視為允許結果，記錄實際顯示者。 |
| `translatable-key-only` | 未驗證 | 需真人基岩客戶端截圖或文字記錄證明顯示 key；fallback 與 key 皆視為允許結果，記錄實際顯示者。 |
| `multiline` | 未驗證 | 需真人基岩客戶端截圖證明三行皆顯示、換行正確、無樣式污染；截圖即證據。 |
| `overlong-truncation` | 未驗證 | 需真人基岩客戶端截圖證明超長文字被截斷並補省略號、表單可正常開啟；截圖即證據。 |

### 3.3 Folia 26.1.2（已驗證）

環境：伺服器 Folia 26.1.2-8、Geyser 2.11.3-b1247、Floodgate 2.2.5-SNAPSHOT、
客戶端 Bedrock 26.51、觀察日期 2026-09-28。AceLib 為 1.3.0-SNAPSHOT
（`enabled on Folia`，`regionScheduling=true, globalScheduler=true`），
探針已啟用，Geyser 已在 UDP 19132 啟動，日誌 `SEVERE|ERROR` 計數為 0。
本環境以全新世界啟動；切換版本前既有世界已備份到
`worlds-backup-before-folia-26.1.2-20260928`。另裝 ViaVersion 5.12.0 與
ViaBackwards 5.12.0（Geyser 2.11.3 對較舊 Java 版本需要協定橋接）；
Geyser 啟動時對 ViaVersion 版本發出過「版本過舊」的警告，但實際連線成功
（玩家正常登入與登出），不影響結果。

| 案例 ID | 結果 | 觀察證據 |
| ------- | ---- | -------- |
| `icon-path-item` | 保留 | 真人基岩客戶端觀察：物品貼圖圖示正常顯示（使用者回報全部正常）。 |
| `icon-path-block` | 保留 | 真人基岩客戶端觀察：方塊貼圖圖示正常顯示（使用者回報全部正常）。 |
| `icon-url` | 保留 | 真人基岩客戶端觀察：https 圖片正常顯示（使用者回報全部正常）。 |
| `icon-broken-fallback` | 部分轉換 | 真人基岩客戶端觀察：第二顆按鈕圖示空白，但三顆按鈕皆可點；伺服器日誌三次點擊索引為 0／1／2（僅佐證點擊索引與已送出），另有一次 `CLOSED` 未點按鈕即關閉（僅記錄關閉事實，不解讀為意圖）。 |
| `text-hex-downgrade` | 保留 | 真人基岩客戶端觀察：顏色降級後可讀、無亂碼（使用者回報全部正常）。 |
| `text-gradient-downgrade` | 保留 | 真人基岩客戶端觀察：漸層降級後可讀、無亂碼（使用者回報全部正常）。 |
| `text-decoration-stripped` | 保留 | 真人基岩客戶端觀察：底線與刪除線消失、粗體與斜體保留（使用者回報全部正常）。 |
| `translatable-with-fallback` | 保留 | 真人基岩客戶端觀察：顯示備援文字（使用者回報全部正常）。 |
| `translatable-key-only` | 保留 | 真人基岩客戶端觀察：顯示鍵名；依本矩陣定義，fallback 與 key 皆為允許結果。 |
| `multiline` | 保留 | 真人基岩客戶端觀察：三行顯示與換行正確（使用者回報全部正常）。 |
| `overlong-truncation` | 保留 | 真人基岩客戶端觀察：截斷並補省略號、表單正常開啟（使用者回報全部正常）。 |

> **關於「預期」的說明（非觀察結果）**：PATH 圖示依賴客戶端資源包內容，
> 不存在的路徑預期顯示空白；URL 圖示依賴客戶端網路；FormText 的降級規則
>（hex／gradient 降 16 色、底線刪除線移除、換行補 `§r`、截斷計數）是產品
> 單元測試已覆蓋的轉換規則，不是客戶端觀察結果。這些是**預期**，
> 已填入的格一律以實機回報為準，未填處仍需依第 5 節條件補觀察。

## 4. 探針建置、部署與發送

### 4.1 建置

探針以 mavenLocal 解析 AceLib（與 consumer-plugin 相同慣例），需要兩步
（產出 jar 位於 `examples/form-compatibility-probe/build/libs/`）：

```bash
./gradlew publishToMavenLocal
./gradlew -p examples/form-compatibility-probe build
```

僅跑案例目錄完整性測試（TDD 錨點，驗證 11 個案例不遺漏、識別碼唯一穩定、
每個案例可建構合法規格、按鈕順序與索引對應）：

```bash
./gradlew -p examples/form-compatibility-probe test
```

### 4.2 部署

將 AceLib jar 與探針產出的 jar 同時放入 Folia 測試服的 `plugins/` 目錄後
啟動（或熱載入）伺服器。探針 `api-version: 26.1.2`、`folia-supported: true`，
並以 `depend: [AceLib]` 保證 AceLib 先載入；AceLib 缺席或未就緒時探針拒絕發送。

### 4.3 發送與觀察

```
/fprobe list                     # 列出 11 個案例 id 與說明（不發送）
/fprobe send                     # 把全部案例依序發送給執令者本人
/fprobe send <player>            # 改發送給指定線上玩家（可對準基岩玩家觀察 Geyser）
/fprobe send <player> <caseId>   # 只發送指定識別碼的那一個案例（識別碼不分大小寫）
```

- **建議的觀察方式是一次只送一個案例**（例如
  `/fprobe send <玩家名稱> icon-url`）：表單為彈出式介面，
  連續發送多個表單會互相取代，不利逐案截圖觀察。客戶端面對連續多個表單的
  實際行為未經觀察確認，因此不要依賴「一次送全部」做逐案記錄。
- 識別碼不存在時，探針會回覆明確錯誤並列出可用識別碼（或提示使用
  `/fprobe list`），且不會發送任何表單。

- 每個案例發送後，伺服器 log 會寫入
  `[fprobe-send] case=<id> target=<name> buttons=<n> result=<SENT|REJECTED>`，
  作為「已送出」的伺服器端證據（不等同客戶端渲染觀察；`REJECTED` 表示
  未產生任何遞送，例如目標不是基岩玩家）。
- 玩家每次回應，伺服器 log 會寫入
  `[fprobe-response] case=<id> player=<name> <response>`；
  simple 表單的 `clickedButton` 可直接對照案例的按鈕順序，
  是「點擊索引與按鈕順序對應」的人工驗證依據。
- 觀察者以 **Bedrock 客戶端** 對每個案例記錄實際呈現（截圖）與點擊結果，
  再回填第 3 節對應格。表單為 Bedrock 原生 UI，無 Java 客戶端對照欄。

### 4.3.1 連線前提（觀察者照著做）

- 基岩客戶端連線走 Geyser 的 Bedrock 埠（UDP，預設 19132）。
  Geyser 綁定於 `0.0.0.0`，觀察者需以測試服所在主機的區網位址加該埠連線；
  **觀察者的客戶端必須與該主機在同一可達網段**（或經由可達的通道），
  否則需先處理網路可達性。本文件不斷言觀察者的實際可達性，
  請使用者自行確認其基岩客戶端能否連上該主機。
- 該測試服的 Java 埠只綁在本機位址，因此遠端只能以基岩客戶端經 Geyser 連線，
  不能以 Java 客戶端連入；這不影響本矩陣的觀察方式。
- Floodgate 已設定且伺服器為非正版驗證模式，基岩玩家可經 Floodgate 取得身分。
- **同一時間只有一個環境可以提供該 Bedrock 埠**，因此多個環境的真人觀察
  必須分次進行；本矩陣的三個目標環境（Folia 26.2、Paper 26.1.2、
  Folia 26.1.2）需逐一安排。
- 觀察流程建議：以基岩客戶端連線後，於遊戲中執行
  `/fprobe send <自己的玩家名稱>`，或由主控台執行 `/fprobe send <玩家名稱>`；
  逐案截圖並記錄；`icon-broken-fallback` 需依序點擊三顆按鈕，
  並以伺服器日誌的 `[fprobe-response]` 確認索引為 0／1／2。
- 再次強調：log 的 `[fprobe-send]` 只證明已送出，
  **不等於**客戶端顯示正確。

### 4.4 安全性

- 所有案例皆為固定、無破壞性內容；點擊只記錄 `[fprobe-response]` log，
  不會觸發刪除、重建世界、付款或外部訊息等不可逆操作。
- 探針不呼叫任何 fresh-world / 世界刪除工具，不修改測試服世界。

## 5. 環境 blocker 與解除條件

| 項目 | 目前狀態 | 解除條件 |
| ---- | -------- | -------- |
| `folia-test-server` | Folia 26.2 與 Folia 26.1.2 皆已完成真人觀察（見第 3.1／3.3 節）；Paper 26.1.2 不做 | Paper 26.1.2 若日後要補做，需另建伺服器（含 Geyser 與 Floodgate；同一時間只有一個環境能提供 Bedrock 埠），再依第 4 節執行並觀察。 |
| Bedrock 客戶端（真人） | 兩台 Folia 已驗證（Bedrock 26.51）；Paper 26.1.2 不做 | 不需再為本輪補觀察；若日後補做 Paper，需真人逐案觀察並回填第 3.2 節。 |
| Geyser / Floodgate 接入 | 兩台 Folia 皆為 Geyser 2.11.3-b1247、floodgate 2.2.5-SNAPSHOT；26.1.2 環境另有 ViaVersion 5.12.0 與 ViaBackwards 5.12.0；轉譯行為不下結論 | 同上。 |
| 四服組合（Paper／Folia × 26.1.2／26.2） | 伺服器端生命週期已驗證（2026-09-27 五條 smoke 全 exit 0，見第 6 節）；客戶端顯示兩台 Folia 已驗證，Paper 26.1.2 不做 | 同上。 |

**環境欄位（Folia 26.2，已驗證）**：伺服器版本：Folia 26.2-7；
Geyser 版本：Geyser-Spigot 2.11.3-b1247（驗收期間由 2.11.2-b1232 更新，
原因見第 3.1 節）；Floodgate 版本：2.2.5-SNAPSHOT；
Bedrock 客戶端版本：26.51；觀察日期：2026-09-28。

**環境欄位（Folia 26.1.2，已驗證）**：伺服器版本：Folia 26.1.2-8；
Geyser 版本：Geyser-Spigot 2.11.3-b1247；Floodgate 版本：2.2.5-SNAPSHOT；
Bedrock 客戶端版本：26.51；觀察日期：2026-09-28。
本環境以全新世界啟動（切換前既有世界已備份）；另裝 ViaVersion 5.12.0 與
ViaBackwards 5.12.0（協定橋接用，皆經官方 SHA-256 校驗）；
Geyser 曾警告 ViaVersion 版本過舊，但連線成功，不影響結果。

**下一步**：

- Paper 26.1.2 經使用者決定不做；若日後要補，需另建伺服器並重走第 4 節流程。
- translatable 兩案的 fallback／key 皆視為允許結果，記錄實際顯示者即可。

## 6. 伺服器端生命週期驗證結果（非客戶端顯示證據）

> 本節記錄 2026-09-27 實際執行並逐條核對日誌的五條伺服器生命週期煙霧驗證。
> 這些結果證明的是「該版本伺服器能載入、啟用、乾淨停用本版 jar，且能力偵測正確」，
> **不證明任何表單在基岩客戶端上的顯示結果，也不證明 Geyser／Floodgate
> 的實際轉譯行為**。第 3.2 節維持 `未驗證`（使用者決定不做），不受本節影響。

受測產物：`build/libs/AceLib-1.3.0-SNAPSHOT.jar`，
SHA-256 `bc4c6d57a680c81ce7b1b1fc078a036e6dcfd4988939729ccef85383c57d7d9d`。

五條執行的共同判定標準（皆滿足）：exit 0；日誌含
`Loading server plugin AceLib v1.3.0-SNAPSHOT`、
`AceLib 1.3.0-SNAPSHOT enabled on <Paper|Folia>`、
`Disabling AceLib v1.3.0-SNAPSHOT`、`AceLib disabled`；
server stdout 的 SEVERE／ERROR 計數為 0。

| # | 組合 | 指令 | 結果 |
| - | ---- | ---- | ---- |
| 1 | Folia 26.1.2-8 | `SERVER_JAR=<測試服>/versions/26.1.2/folia-26.1.2-8.jar scripts/smoke-server.sh folia --timeout 240` | exit 0；能力偵測 `regionScheduling=true, globalScheduler=true` |
| 2 | Folia 26.2-7 | `SERVER_JAR=<測試服>/versions/26.2/folia-26.2-7.jar scripts/smoke-server.sh folia --timeout 240` | exit 0 |
| 3 | Folia 26.2-4 | `SERVER_JAR=<測試服>/versions/26.2/folia-26.2-4.jar scripts/smoke-server.sh folia --timeout 240` | exit 0 |
| 4 | Paper 26.1.2-72 | `scripts/smoke-server.sh paper --download --timeout 300` | exit 0；下載後 SHA-256 校驗通過才啟動；能力偵測 `regionScheduling=false, globalScheduler=true` |
| 5 | Paper 26.2-120 | `scripts/smoke-server.sh paper --version 26.2 --build 120 --sha256 2d1a4c3e5152171c3ef327a8ae10baeae44d6c13b4620bc2578cc15ea6c6ab47 --download --timeout 300` | exit 0；校驗通過才啟動 |

註：`<測試服>` 指本地 Folia 測試服路徑（本機以 `SERVER_JAR` 直接提供既有
jar，未走下載路徑）；兩條 Paper 走 `--download`，皆顯示「下載並校驗完成」
後才啟動（fail-closed）。本輪為本機以腳本執行，未確認 CI 是否曾執行；
workflow 為手動觸發，見第 5 節。

### 6.1 部署就緒驗證（共用 Folia 測試服，2026-09-27）

> 本小節記錄主代理在共用 Folia 測試服實際部署、啟動並核對主控台與 RCON
> 輸出的部署就緒驗證。這一節證明的是「本版 jar 與探針能在真實 Folia 26.2
> 伺服器上部署、載入、啟用，指令可用，且既有的下游插件仍能運作」；
> 它不證明任何表單在基岩客戶端上的顯示，也不證明 Geyser 的轉譯行為；
> 第 3.2 節維持 `未驗證`（使用者決定不做）。

- 環境：共用 Folia 測試服，啟動後版本為 26.2-7（與執行環境矩陣已記載的
  Folia 26.2-7 條目一致），三個世界都在；驗證後測試服已停止。
- 部署：`AceLib-1.3.0-SNAPSHOT.jar`
 （SHA-256 `bc4c6d57a680c81ce7b1b1fc078a036e6dcfd4988939729ccef85383c57d7d9d`）
  與 `acelib-form-compatibility-probe-1.0.0-SNAPSHOT.jar`；
  原有 `AceLib-1.2.2.jar` 已備份為 `.backup-before-1.3.0-20260927`
  （不再被載入）。
- 載入與啟用：主控台完整序列 `Loading` → `Enabling` → `enabled on Folia`；
  能力偵測為 `regionScheduling=true, globalScheduler=true, bukkitApi=true,
  foliaThreadedRegionsApi=true`。表單探針與既有的訊息探針、
  `AceLibBedrockTest`、`AceLibQualProbe` 在同一次啟動中被載入；
  探針的 `Loading`／`Enabling` 皆出現，`/fprobe` 可註冊。
- 指令驗證（RCON）：`/fprobe list` 正確列出 11 個案例與人類可讀說明，
  內容與探針的案例定義一致；`/fprobe send` 在無玩家時回覆明確用法訊息
  （console 執行 send 必須指定線上玩家）；`/acelib status` 回報
  Version 1.3.0-SNAPSHOT、Platform Folia、Ready true、模組狀態
  （scheduler／integration／world／compatibility 為 READY，
  lang 與 config 為 NOT_INITIALIZED，屬既有階段性狀態）與
  `Errors: (no errors)`。
- 環境版本（部署當時）：Geyser-Spigot 2.11.2（橫幅顯示 2.11.2-b1232）、
  floodgate 2.2.5-SNAPSHOT（b140-8780fa4）；驗收期間 Geyser 已更新為
  2.11.3-b1247（見第 3.1 節）。
- 下游相容性觀察：測試服上的 AceEconomy 2.2.0 在同一次啟動中正常載入與啟用，
  未出現與 AceLib 相關的錯誤；此為真實環境下的觀察，不是對未來版本的保證。

探針的使用前置條件：實際觀察顯示結果必須執行 `/fprobe send <player>`，
且目標玩家須為已連線的真人基岩客戶端（經 Geyser）；無玩家時 `send`
只回覆用法訊息，不產生任何發送證據。

## 7. 變更範圍確認

本文件建立時只新增以下資產，未修改任何正式 API：

- `examples/form-compatibility-probe/**`（探針 plugin 與測試）
- `docs/reference/bedrock-form-compatibility-matrix.md`（本文件）

記錄實際結果階段（2026-09-27）另更新：本文件第 6 節（伺服器端結果）、
第 5 節四服組合列（僅伺服器端狀態），以及
`docs/reference/runtime-compatibility-matrix.json` 的 `generatedAt`
與五個 runtime 的 `evidence` 附加語（既有敘述保留）。

部署就緒記錄階段（2026-09-27）另更新：本文件第 6.1 節（測試服部署與指令驗證）、
第 5 節測試服／Geyser／環境欄位列（僅已實測的伺服器端版本事實）。

Folia 26.2 觀察結果記錄（2026-09-28）：本文件第 3 節重構為三環境表、
第 3.1 節填入 11 格實際觀察（含兩項初次不如預期的追查附註）、
第 5 節 blocker 與環境欄位同步、第 6／6.1 節的「第 3 節維持未驗證」敘述
修正為第 3.2／3.3 節。第 3.2／3.3 節 22 格維持 `未驗證`，未觸及。

Folia 26.1.2 觀察結果記錄（2026-09-28）：第 3.3 節填入 11 格實際觀察與環境欄位
（含 ViaVersion／全新世界／備份註記）；第 3.2 節標示使用者決定不做；
第 5 節 blocker 同步；第 3 節引言與第 6／6.1 節的未驗證敘述同步為僅第 3.2 節。

未觸及：`src/main/java/**`、既有的
`docs/reference/bedrock-message-compatibility-matrix.md`、
版本字串、`docs/reference/api-surface*`、
`runtime-compatibility-matrix.json` 的 `platform`／`version`／`status`、
`bedrock`、`adventure`、`minBaseline`、`java`、`libraryVersion`。
