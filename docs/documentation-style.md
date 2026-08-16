# 文件怎麼分工

AceLib 的文件依讀者正在做的事拆分。新增內容前，先把資訊放到負責的頁面，其他頁只留一句摘要與連結。

| 內容 | 更新位置 |
| --- | --- |
| 專案介紹、穩定版與第一個範例 | 根目錄 `README.md` |
| 插件開發者的完整安裝步驟 | `docs/consumer/quickstart.md` |
| Provider 啟用、重載與停用 | `docs/consumer/provider-lifecycle.md` |
| Java、Paper、Folia 與 Gradle 版本 | `docs/consumer/compatibility.md` |
| JitPack、GitHub Release、server JAR 與本機 Maven | `docs/reference/release-artifacts.md` |
| Server 部署與 `/acelib status` | `docs/operator/README.md` |
| Repository 建置、測試與文件檢查 | `docs/contributor/README.md` 與 `CONTRIBUTING.md` |
| 各模組 API 與使用限制 | `docs/modules/*.md` |
| 完整錯誤碼 | `docs/reference/error-codes.md` |
| 版本歷史 | `CHANGELOG.md` |

`docs/reference/api-surface.md` 由 `docs/reference/api-surface.json` 產生。修改公開 API 分類時，依專案既有產生流程更新，不要直接編輯 generated Markdown。

## 寫法

- 標題直接寫讀者要做的事，例如「建立 runtime JAR」或「Provider 停用時」。
- 先給可執行的步驟，再補充原因與限制。
- 指令和程式碼要能複製；placeholder 使用容易辨認的值，例如 `/path/to/server`。
- 保留 API、Paper、Folia、Gradle 等必要名稱，其餘盡量使用自然的台灣繁中。
- 不在公開文件放工作追蹤編號、代理流程、驗收紀錄或一次性的測試數量。
- 「必須」「不得」「預設」等說法要能從程式、設定、測試或官方文件核對。

## JavaDoc

Public 型別與方法需說明用途，並補齊適用的 `@param`、`@return`、`@throws`。若 Paper 與 Folia 行為不同，JavaDoc 要寫清楚呼叫端需在哪個執行緒或 region 操作。

產生 JavaDoc 時會啟用 doclint：

```bash
./gradlew javadoc --no-daemon --console=plain
```

## 發布新版本時

1. 在[相容性頁](consumer/compatibility.md)更新實際採用與驗證過的版本。
2. 在[如何取得 AceLib](reference/release-artifacts.md)更新 JitPack、Release asset 與 server JAR 取得方式。
3. 在[快速開始](consumer/quickstart.md)更新唯一一份完整 consumer dependency。
4. 更新根 README 的短摘要與 `CHANGELOG.md`。
5. 執行文件檢查：

```bash
./gradlew docsCheck --rerun-tasks --no-daemon --console=plain
```

不要把相同發布狀態複製到每一個模組頁。模組頁只在 API 或限制真的改變時更新。
