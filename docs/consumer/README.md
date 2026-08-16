# 用 AceLib 寫 plugin

第一次接入 AceLib，直接從[快速開始](quickstart.md)做起。那一頁包含唯一一份完整的 Gradle、`plugin.yml` 與 provider 範例。

AceLib 不提供 static singleton。你的 plugin 要透過 Bukkit `ServicesManager` 取得 `AceLibApi.AceLibProvider`，呼叫 `api()`，並在使用服務前檢查 `isReady()`。

完成快速開始後，依需要閱讀：

- [Provider 生命週期](provider-lifecycle.md)：AceLib 啟用、重載與停用時，consumer 應如何處理。
- [相容性](compatibility.md)：Java、Paper、Folia 與 Gradle 版本。
- [模組指南](../modules/)：排程、設定、資料、世界、GUI 等 API。
- [公開 API 分類](../reference/api-surface.md)：哪些類別可由下游 plugin 使用，哪些只供擴充或內部使用。
- [錯誤碼](../reference/error-codes.md)：查詢日誌中的 `ACELIB-*` 代碼。

如果你只想看一個可編譯專案，開啟 [`examples/consumer-plugin`](../../examples/consumer-plugin/README.md)。該範例在 repository 內使用本機 Maven 產物做驗證；一般 plugin 專案仍應依快速開始使用 JitPack。
