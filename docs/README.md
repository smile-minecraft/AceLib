# AceLib 文件

> 適合不確定該先看哪份文件的讀者，依任務選路。


依你現在要做的事選一條路，不必先讀完所有頁面。

## 目錄

- [寫 plugin](#寫-plugin)
- [管理 server](#管理-server)
- [修改 AceLib](#修改-acelib)
- [查資料](#查資料)

## 寫 plugin

先看 [插件開發者快速開始](consumer/quickstart.md)，把 AceLib 加入 Gradle、設定 `plugin.yml`，再取得 `AceLibApi`。

接著可依需求閱讀：

- [Provider 生命週期](consumer/provider-lifecycle.md)：處理 AceLib 尚未就緒、重載或停用。
- [模組指南](modules/)：排程、設定、資料、GUI 等 API 用法。
- [相容性](consumer/compatibility.md)：確認 Java、Paper 與 Folia 版本。

## 管理 server

[伺服器管理員指南](operator/README.md) 說明如何從原始碼建立 plugin JAR、部署到 Paper 或 Folia，並用 `/acelib status` 檢查啟動結果。

## 修改 AceLib

[貢獻者指南](contributor/README.md) 列出開發環境、測試指令、文件檢查與本機 Maven 用法。送出修改前也請閱讀根目錄的 [CONTRIBUTING.md](../CONTRIBUTING.md)。

## 查資料

- [如何取得 AceLib](reference/release-artifacts.md)
- [錯誤碼](reference/error-codes.md)
- [公開 API 分類](reference/api-surface.md)
- [版本變更](../CHANGELOG.md)

## 相關頁面

- [插件開發者快速開始](consumer/quickstart.md)
- [伺服器管理員指南](operator/README.md)
- [貢獻者指南](contributor/README.md)
