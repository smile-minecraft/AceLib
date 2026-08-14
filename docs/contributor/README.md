# 修改、測試與撰寫 JavaDoc

本頁解決在 AceLib repo 內修改程式碼、文件或測試時「要改哪裡、怎麼驗證」的問題。

## 前置條件

- JDK 25。
- 已具備基本 Java、Gradle、Paper／Folia 插件開發經驗。
- 已讀 [CONTRIBUTING.md](../../CONTRIBUTING.md) 的修改與測試規則。

## 最短成功路徑

1. 先確認修改範圍與對外 API 分類；[API surface](../reference/api-surface.md) 是公開型別分類參考。
2. 先寫能描述行為的測試，再執行測試確認 Red。
3. 做最小修改，執行目標測試確認 Green。
4. 補正常、錯誤、邊界與生命週期測試；涉及玩家、實體或方塊時，同時檢查 Paper／Folia 上下文。
5. 修改 JavaDoc 或 Markdown 前，先讀 [文件寫作規範](../documentation-style.md)。

## JavaDoc 與文件規則

- public 型別與方法要有用途摘要；參數、回傳值與可能拋出的例外要有對應標記。
- `doclint` 是產生 JavaDoc 時檢查註解完整性的工具；`./gradlew javadoc` 會啟用它。
- 第一次出現的 Folia、SPI、Internal、facade 等術語要附白話說明。
- 保留 Java symbol、CLI 與 `ACELIB-*` 錯誤碼原樣；不要把未確認內容寫成規範。
- 不要在 JavaDoc、source comment 或文件加入一次性工作追蹤編號與進度敘事。

## 驗證指令

```bash
./gradlew test
./gradlew javadoc
./gradlew docsCheck --rerun-tasks --no-daemon --console=plain
```

下游契約與文件連結可再執行：

```bash
./gradlew -p examples/consumer-plugin build --no-daemon --console=plain
```

## 預期結果

測試、JavaDoc 與文件品質檢查均完成，且沒有改變既有 public API/signature、版本基線或 Folia safe-context 限制。

## 常見失敗

- JavaDoc 失敗：先檢查 `@param`、`@return`、`@throws` 與泛型／HTML 標記。
- consumer 編譯失敗：確認使用 `AceLibApi.AceLibProvider`，不要依賴 `AceLibPlugin`。
- Folia 測試失敗：確認玩家、實體、方塊操作回到正確 region context。

## 下一步

- [文件寫作規範](../documentation-style.md)
- [API surface](../reference/api-surface.md)
- [CONTRIBUTING.md](../../CONTRIBUTING.md)
