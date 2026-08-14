# AceLib 文件與 JavaDoc 寫作規範

本頁解決在 AceLib 新增或修改文件、JavaDoc 時，如何維持術語、結構與可驗證性的問題。它是 repository 內文件寫作的共同參考。

## 文件的最短成功路徑

每份面向開發者的主要頁面依序回答：

1. **目的**：這頁解決什麼問題，讀者完成後能做什麼。
2. **前置條件**：需要的版本、權限、檔案或環境。
3. **操作步驟**：一條最短可工作的路徑；命令前後說明用途。
4. **預期結果**：成功時可觀察到的狀態、輸出或檔案。
5. **常見問題**：列出已確認的失敗原因、錯誤碼與處理方式。
6. **下一步**：連到下一個讀者任務，不用空泛結語。

段落保持短小。範例必須可複製、不含 secret；placeholder 要清楚，例如 `<plugin-name>`。規範性用語必須能回到 source、測試、CLI help、設定或官方文件。

## JavaDoc 契約

下列規則適用於 `src/main/java` 的 public 型別與方法；`doclint` 是產生 JavaDoc 時檢查註解完整性的工具。

1. 每個 public `class`、`interface`、`enum`、`record` 都要有型別摘要，第一句說明用途，並標示 `Supported`、`SPI` 或 `Internal` 分類。
2. 每個 public 方法都要有簡短摘要，描述「做什麼」，不要只描述實作方式。
3. 每個參數使用 `@param`；有回傳值使用 `@return`；可能拋出的例外使用 `@throws`。
4. 例外說明要包含對應的 `ACELIB-<AREA>-<CODE>` 與觸發情境。
5. 若 Paper 與 Folia 行為不同，要說明平台分流與 region context 限制，不得假設只有 Paper 主執行緒。
6. 繼承或執行緒契約需要補充實作者／呼叫端責任時，使用 `@implSpec`。
7. 說明文字使用台灣繁中；Java symbol、API 名稱與錯誤碼保留原樣。
8. 不在 JavaDoc、source comment 或檔案 header 寫入一次性工作編號、session 進度或階段紀錄。
9. 不硬編容易漂移的版本；版本應引用 `AceLibVersion.VERSION` 或對應 source of truth。`Internal` 型別要明確說明下游不得直接依賴；`SPI` 要說明實作者責任與相容性。

## 術語表

| 英文／原始詞 | 採用繁中 | 第一次出現時的白話說明 |
| --- | --- | --- |
| scheduler / scheduling | 排程 | 在指定執行緒或時間執行工作 |
| entity | 實體 | Minecraft 世界中的生物或其他可互動物件 |
| block | 方塊 | 世界中的方塊資料或方塊物件 |
| region（Folia） | 區域 | Folia 負責一組世界物件的執行範圍與執行緒 |
| thread context | 執行緒上下文 | 目前程式是否在可安全操作目標物件的執行緒 |
| facade | API 外觀／facade | 把內部實作包起來的對外入口 |
| lifecycle | 生命週期 | enable、disable 與 reload 的狀態變化 |
| migration | 遷移 | 將舊版本資料轉換成新格式 |
| serialization / deserialization | 序列化／反序列化 | 在物件與可儲存資料之間轉換 |
| diagnostics | 診斷 | 用來確認服務狀態與錯誤的資訊 |
| extension point | 擴充點 | 由外部實作者提供行為的介面 |
| SPI | SPI | Service Provider Interface，留給外部實作者的擴充介面 |
| Internal | Internal | AceLib 內部類別，不是下游穩定契約 |
| doclint | doclint | JavaDoc 產生時檢查標記與註解完整性的工具 |

## 參考資料的唯一來源

每類 reference 只保留一個可編輯來源；其他文件應連結到來源，不要複製後分別維護。

| 內容 | 唯一來源 |
| --- | --- |
| API 分類 | [docs/reference/api-surface.json](reference/api-surface.json)；Markdown 由 JSON 產生 |
| 錯誤代碼 | 各模組 `*ErrorCodes` 常數與 `diagnostics.ErrorCodeRegistry` |
| 平台能力 | `src/main/java/com/smile/acelib/platform/PlatformCapability.java` |
| 建置與發布 metadata | `build.gradle.kts`、`src/main/resources/plugin.yml` |
| 文件寫作規範 | 本頁 |

需要更新內容時，先修改唯一來源，再更新導覽或人類可讀鏡像。不要手動修改由 JSON 產生的 API 分類資料。
