# Bedrock 訊息相容性矩陣（Adventure Component × Geyser）

> 狀態：**部分已驗證（beta exploratory evidence）**。視覺特性與 Java 端互動已有使用者實機觀察；Bedrock hover 仍未驗證。
> - 視覺（`plain`／`style`／`nested`／`translatable`／`hex`／`gradient`／`rainbow`）在 Java 與 Bedrock 兩端皆為 `保留`——證據：兩張使用者截圖可見（第一張 Java、第二張 Bedrock，可由 Bedrock 觸控／鍵盤 UI 判定）。
> - Java 端 `hover-showtext` 與四種 click（`RUN_COMMAND`／`SUGGEST_COMMAND`／`OPEN_URL`／`COPY_TO_CLIPBOARD`）為 `保留`——證據：使用者以 Java 客戶端實際測試回報「基本上沒問題」。
> - Bedrock 端四種 click 為 `忽略`——證據：使用者以 Bedrock 客戶端實際點擊測試無效果；伺服器 log 的「已送出」不等於客戶端互動成功。
> - Bedrock 端 `hover-showtext` 維持 `未驗證`——無明確 tooltip／客戶端互動證據，不作成功或失敗推論。
> - 探針執行環境為 Folia `26.2-4` beta、Geyser-Spigot `2.11.2-b1232`、Floodgate `2.2.5-SNAPSHOT`；本次結果屬 beta 探索性證據，不宣稱穩定版 `26.1.2` 行為。**不以推論取代實機證據**。

## 1. 目的與範圍

本矩陣記錄 AceLib 訊息相容性探針（`examples/message-compatibility-probe`）發送的
固定 Adventure Component 案例，在 **Java 原客戶端** 與 **Bedrock 客戶端（經 Geyser
轉換）** 上的實際呈現結果。

涵蓋案例（共 12 項，對應 `CompatibilityCases.buildCatalog()` 的目錄順序）：

| # | 案例 ID | 特性 |
| - | ------- | ---- |
| 1 | `plain` | 純文字（對照基準） |
| 2 | `style` | 顏色與裝飾（紅色加粗） |
| 3 | `nested` | 巢狀 Component（兩個不同顏色子元件） |
| 4 | `hover-showtext` | HoverEvent.ShowText |
| 5 | `click-run-command` | ClickEvent.RUN_COMMAND（`/list`，無破壞性） |
| 6 | `click-suggest-command` | ClickEvent.SUGGEST_COMMAND |
| 7 | `click-open-url` | ClickEvent.OPEN_URL（公開倉庫 URL） |
| 8 | `click-copy-to-clipboard` | ClickEvent.COPY_TO_CLIPBOARD |
| 9 | `translatable` | translatable（伺服器端翻譯鍵） |
| 10 | `hex` | hex 顏色（`#ff8800`） |
| 11 | `gradient` | gradient（紅→藍，經 MiniMessage 解析） |
| 12 | `rainbow` | rainbow（經 MiniMessage 解析） |

**非目標**：本探針不實作正式 MessageService、MiniMessage parser、Bedrock fallback
renderer、LangManager 玩家 locale、AceLibApi getter，也不把任何 Component 攤平成文字。
它只負責「發送固定案例」與「記錄觀察」。

## 2. 狀態標記定義

| 標記 | 意義 |
| ---- | ---- |
| `保留` | 該特性在目標客戶端上完整呈現（顏色 / 裝飾 / 互動皆可用）。 |
| `忽略` | 該特性被目標客戶端靜默丟棄（無錯誤，但效果消失）。 |
| `部分轉換` | 部分呈現（例如顏色保留但 hover/click 失效，或 gradient 退化為單色）。 |
| `未驗證` | 尚未在實機以該客戶端觀察；結果未知。 |

## 3. 相容性矩陣

> 本節已填入使用者實機回報；未填處維持 `未驗證`，不以推論補齊。
> 伺服器 `[mprobe-send]` log 僅證明「已送出」，不等於客戶端渲染或互動成功。

| 案例 ID | Java 客戶端 | Bedrock (Geyser) | 觀察證據 / 解除條件 |
| ------- | ----------- | ---------------- | ------------------- |
| `plain` | 保留 | 保留 | 兩張使用者截圖可見（第一張 Java、第二張 Bedrock）；探針 console 已記錄 12 case 的 `[mprobe-send]` 送出，僅作發送證據。 |
| `style` | 保留 | 保留 | 同上；紅色加粗在兩張截圖皆可見。 |
| `nested` | 保留 | 保留 | 同上；Ace／Lib 巢狀顏色在兩張截圖皆可見。 |
| `hover-showtext` | 保留 | 未驗證 | Java 端：使用者以 Java 客戶端實際測試回報「基本上沒問題」；Bedrock 端：無明確 hover tooltip／互動回報，維持未驗證，需有 tooltip 或客戶端互動證據才能分類。 |
| `click-run-command` | 保留 | 忽略 | Java 端：使用者以 Java 客戶端實際測試回報「基本上沒問題」；Bedrock 端：使用者以 Bedrock 客戶端實際點擊測試無效果（伺服器 log 已送出不等於客戶端互動成功）。 |
| `click-suggest-command` | 保留 | 忽略 | 同上。 |
| `click-open-url` | 保留 | 忽略 | 同上。 |
| `click-copy-to-clipboard` | 保留 | 忽略 | 同上。 |
| `translatable` | 保留 | 保留 | 兩張使用者截圖可見「鑽石方塊」；見下方補充。 |
| `hex` | 保留 | 保留 | 兩張使用者截圖可見 `#ff8800` 橘色。 |
| `gradient` | 保留 | 保留 | 兩張使用者截圖可見紅→藍漸層。 |
| `rainbow` | 保留 | 保留 | 兩張使用者截圖可見 rainbow。 |

> **關於「預期」的說明（非觀察結果）**：Java 原客戶端對 Adventure Component 為原生
> 渲染，協定層面預期 `保留` 多數特性；Bedrock 經 Geyser 轉換，對 hover / click /
> gradient / rainbow 等特性普遍有退化。這些是**協定與過往經驗的預期**，不是本任務的
> 觀察結果；已填入的格以實機回報為準，未填處仍需依第 5 節條件補觀察。

> **translatable 補充**：本案例為 `Component.translatable("block.minecraft.diamond_block")`，本身沒有 hover 或 click event。兩張截圖已可見其顯示為「鑽石方塊」。使用者回報「鑽石方塊好像沒有反應」時：若指點擊／懸停無效果，屬預期（案例未附互動事件）；若指完全沒有文字，則與截圖證據矛盾，不得改寫為未顯示。

## 4. 探針建置、部署與發送

### 4.1 建置

探針不依賴 AceLib，單一步驟即可建置（產出 jar 位於
`examples/message-compatibility-probe/build/libs/`）：

```bash
./gradlew -p examples/message-compatibility-probe build
```

僅跑案例目錄完整性測試（TDD 錨點，驗證 12 個案例不遺漏且結構正確）：

```bash
./gradlew -p examples/message-compatibility-probe test
```

### 4.2 部署

將產出的 jar 放入 Folia 測試服的 `plugins/` 目錄後啟動（或熱載入）伺服器。
探針 `api-version: 26.1.2`、`folia-supported: true`，不依賴 AceLib。

### 4.3 發送與觀察

```
/mprobe list                     # 列出 12 個案例 id 與說明（不發送）
/mprobe send                     # 把全部案例發送給執令者本人
/mprobe send <player>            # 改發送給指定線上玩家（可對準基岩玩家觀察 Geyser）
```

- 每個案例發送前，伺服器 log 會寫入 `[mprobe-send] case=<id> target=<name> text=<plain>`，
  作為「已送出」的伺服器端證據（不等同客戶端渲染觀察）。
- 本次測試服 console 已證明探針對目標完成 12 case 的 server-side logs；此為發送證據，
  客戶端是否可見／可互動需以客戶端截圖與點擊回報為準。
- 觀察者以 **Java 客戶端** 與 **Bedrock 客戶端** 各執行一次，分別記錄每個案例的
  實際呈現，再回填第 3 節對應格。

### 4.4 安全性

- 所有案例皆為固定、無破壞性內容；`click-run-command` 只指向 `/list`（唯讀、列出線上玩家），
  不會觸發刪除、重建世界、付款或外部訊息等不可逆操作。
- 探針不呼叫任何 fresh-world / 世界刪除工具，不修改測試服世界。

## 5. 環境 blocker 與解除條件

| 項目 | 目前狀態 | 解除條件 |
| ---- | -------- | -------- |
| `folia-test-server` | **已於 Folia `26.2-4` beta 完成發送驗證**（console 可見 12 筆 `[mprobe-send]`）；`26.1.2` 穩定版待重測 | 在穩定版 Folia `26.1.2` 重跑第 4 節流程，確認結果是否與本次 beta 一致。 |
| Java 客戶端 | **已驗證**——使用者以 Java 客戶端實際測試回報「基本上沒問題」（hover 與四種 click 記為 `保留`，視覺特性由兩張截圖確認） | 無需再為本次 beta 補 Java 觀察；穩定版重測時再覆核。 |
| Bedrock 客戶端 | **部分已驗證**——視覺 `保留`（兩張截圖可見）；四種 click `忽略`（使用者以 Bedrock 客戶端實際點擊測試無效果）；hover 維持 `未驗證` | 僅剩 Bedrock `hover-showtext` 若要判定，需補 tooltip／懸停互動的客戶端證據。 |
| Geyser / Floodgate 接入 | **已接入**——Geyser-Spigot `2.11.2-b1232`、Floodgate `2.2.5-SNAPSHOT`（beta exploratory） | 穩定版環境重測時確認版本與轉換行為。 |

**下一步**：

- 若需完成最後一格：請以 Bedrock 客戶端對 `hover-showtext` 案例做懸停／tooltip 觀察，並以截圖或文字記錄是否出現 tooltip，再將第 3 節該格由 `未驗證` 更新為 `保留`／`忽略`／`部分轉換`。
- 若需宣稱穩定版相容性：請在 Folia `26.1.2` 穩定版（含對應 Geyser/Floodgate 穩定版）重跑第 4 節 12 case 發送與兩端觀察；本次結果不直接套用為穩定版結論。

## 6. 變更範圍確認

本任務只新增以下資產，未修改任何正式 API：

- `examples/message-compatibility-probe/**`（探針 plugin 與測試）
- `docs/reference/bedrock-message-compatibility-matrix.md`（本文件）

未觸及：`src/main/java/**`、`MessageService`、`LangManager`、`BedrockService`、
`AceLibApi` 或任何既有公開 API baseline。
