# 貢獻 AceLib

這份指南給想修改 AceLib 程式碼、測試或文件的人。若你只是要在自己的 plugin 使用 AceLib，請改看[快速開始](docs/consumer/quickstart.md)。

## 開發環境

| 工具 | 版本 |
| --- | --- |
| Java | 25 |
| Gradle wrapper | 9.5.1 |
| Paper API | `26.1.2.build.72-stable` |
| MockBukkit | `4.113.1`（`mockbukkit-v26.1.2`） |
| JUnit | 5.11.0 |
| Mockito | 5.11.0 |

使用 repository 內的 `./gradlew`，不要要求貢獻者另外安裝特定 Gradle 版本。Toolchain 可自動取得 Java 25。

```bash
git clone https://github.com/smile-minecraft/AceLib.git
cd AceLib
./gradlew test --no-daemon --console=plain
```

## 開始修改前

先找到現有測試與相近的 API 用法。新功能或 bug 修正採 Red → Green → Refactor：

1. 先寫一個能重現需求或問題的測試，確認它會失敗。
2. 做最小修改讓測試通過。
3. 補上錯誤、邊界與生命週期情境，再整理程式碼。

涉及玩家、實體、方塊或 inventory 時，必須考慮 Paper 與 Folia 的差異。Folia 不能套用 Paper 的全域主執行緒假設；相關操作要回到正確 region。MockBukkit 可以測 API 與分支，但不能當作真實 Folia runtime 的替代品。

不要為了通過測試而硬編結果、吞掉例外或刪除有效斷言。也不要在無關修改中改動 public API 簽章。

## 測試與建置

```bash
# 單元與整合測試
./gradlew test --no-daemon --console=plain

# 完整建置
./gradlew clean build --no-daemon --console=plain

# JavaDoc、測試、publication 與 consumer 文件檢查
./gradlew docsCheck --rerun-tasks --no-daemon --console=plain
```

修正 bug 時應保留回歸測試。受生命週期影響的服務也要測啟用、AceLib 自己的 reload、停用，以及玩家離線或實體失效等情境。

## 驗證 consumer API

Repository 內的範例使用本機 Maven，方便它編譯目前 checkout 的 AceLib：

```bash
./gradlew publishToMavenLocal
./gradlew -p examples/consumer-plugin build --no-daemon --console=plain
```

本機座標 `com.smile:acelib:1.1.1` 不代表 Maven Central。一般使用者從 JitPack 取得 `com.github.smile-minecraft:AceLib:v1.1.1`。

## Public API 與文件

- 下游 plugin 透過 `AceLibApi.AceLibProvider` 取得 API，不應直接依賴 `AceLibPlugin`。
- Public API 的分類列在 [`docs/reference/api-surface.md`](docs/reference/api-surface.md)。該 Markdown 由 JSON 清單產生，不要手動改 generated inventory。
- Public 型別與方法要有 JavaDoc，包含參數、回傳值、例外與必要的 Folia 執行緒限制。
- 對外錯誤使用 `ACELIB-<AREA>-<CODE>`，並在訊息中說明操作、影響與可採取的處理方式。
- Markdown 的內容分工與發布更新方式見[文件寫作說明](docs/documentation-style.md)。

## Commit 與 Pull Request

Commit 建議使用英文 Conventional Commits，例如：

```text
fix(scheduler): reject work after plugin disable
docs(consumer): clarify JitPack dependency
```

Pull Request 請說明：

- 要解決的問題與採用的做法。
- 影響到的 public API、Paper/Folia 行為或資料格式。
- 實際執行的測試指令與結果。
- 尚未執行的真實 server 測試或其他限制。

不要提交 token、憑證、server 世界資料、Gradle cache 或建置輸出。
