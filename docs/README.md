# AceLib 文件中心

本頁解決「不知道該從哪份文件開始」的問題。依照要完成的工作選擇 Consumer、Contributor 或 Operator 路徑。

## 讀完本頁後

可直接前往對應指南：

- 用 AceLib 寫插件 → **Consumer**
- 修改 AceLib 原始碼、文件或測試 → **Contributor**
- 部署 AceLib 系插件並診斷問題 → **Operator**

## 三條操作路徑

| 目標 | 指南內容 | 入口 |
| --- | --- | --- |
| 插件開發者（下游） | 快速接入、查 API、解讀錯誤與平台差異 | [docs/consumer/README.md](consumer/README.md) |
| 修改 AceLib 的開發者 | 修改、測試、JavaDoc 與文件規則 | [docs/contributor/README.md](contributor/README.md) |
| 伺服器管理員 | 部署、診斷、讀錯誤碼、跑 smoke | [docs/operator/README.md](operator/README.md) |

## 閱讀前先知道的詞

- **API surface**：公開類別的分類清單，說明哪些名稱可穩定使用。`Supported` 是穩定 API；`SPI`（Service Provider Interface）是留給外部實作者的擴充介面；`Internal` 是內部實作，下游不得直接依賴。
- **adapter**：把外部插件或另一種資料來源接到 AceLib 介面的實作物件。

## 參考資料與唯一來源

每類參考資料只有一個「唯一來源（single source of truth）」，其他文件都只連結或做鏡像，不各自複製維護，避免內容漂移。

| 參考資料 | 唯一來源 | 說明 |
| --- | --- | --- |
| API 分類（Supported / SPI / Internal） | [docs/reference/api-surface.json](reference/api-surface.json) | [docs/reference/api-surface.md](reference/api-surface.md) 由 JSON 產生，兩者由 `ApiSurfaceContractTest` 驗證一致；JSON 是唯一可編輯來源 |
| 錯誤代碼（`ACELIB-<AREA>-<CODE>`） | 各模組 `*ErrorCodes` 常數類與 `diagnostics.ErrorCodeRegistry` | 根 `README.md` 的錯誤表是人類可讀鏡像 |
| 平台能力（`PlatformCapability`） | `src/main/java/com/smile/acelib/platform/PlatformCapability.java` | 根 `README.md` 的平台能力表是說明鏡像 |
| 建置／發布 metadata | `build.gradle.kts`、`src/main/resources/plugin.yml` | POM URL/SCM 由 `PublicationConsistencyTest` 守護 |
| 文件寫作規範 | [docs/documentation-style.md](documentation-style.md) | 全 repo 文件與 JavaDoc 的寫作契約 |

> 原則：需要引用上面的內容時，**連結**到唯一來源，不要複製一份自己維護。

## 下一步

- 插件開發 → [Consumer 指南](consumer/README.md)
- 修改專案 → [Contributor 指南](contributor/README.md)
- 伺服器部署 → [Operator 指南](operator/README.md)
- 查模組用法 → [模組指南](modules/)
