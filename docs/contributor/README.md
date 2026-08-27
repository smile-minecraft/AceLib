# 修改 AceLib

> 適合要修改 AceLib 原始碼並跑通建置、測試與文件門禁的貢獻者。


AceLib 使用 Java 25 與 Gradle wrapper 9.5.1。先安裝 Git；JDK 可由 Gradle toolchain 下載，也可自行準備 Java 25。

## 目錄

- [建立開發環境](#建立開發環境)
- [常用檢查](#常用檢查)
- [驗證下游 plugin 範例](#驗證下游-plugin-範例)
- [修改公開 API 或文件](#修改公開-api-或文件)

## 建立開發環境

```bash
git clone https://github.com/smile-minecraft/AceLib.git
cd AceLib
./gradlew test --no-daemon --console=plain
```

測試成功後，再開始修改。程式碼、測試與文件的協作規則在根目錄的 [CONTRIBUTING.md](../../CONTRIBUTING.md)。

## 常用檢查

```bash
# 單元與整合測試
./gradlew test --no-daemon --console=plain

# JavaDoc、測試、publication 與 consumer 文件檢查
./gradlew docsCheck --rerun-tasks --no-daemon --console=plain

# 完整編譯與打包
./gradlew clean build --no-daemon --console=plain
```

`docsCheck` 會連帶執行測試、JavaDoc doclint、Maven publication 檢查，以及 repository 內的 consumer 範例。

## 驗證下游 plugin 範例

範例專案使用本機 Maven 產物，讓它能編譯你目前 checkout 的 AceLib，而不是遠端 release：

```bash
./gradlew publishToMavenLocal
./gradlew -p examples/consumer-plugin build --no-daemon --console=plain
```

本機座標是 `com.smile:acelib:1.1.2`（此 checkout 的原始碼版本為 1.1.2）。這只供 repository 開發與測試，不代表 Maven Central 已發布。一般 plugin 開發者使用的是 JitPack `com.github.smile-minecraft:AceLib:v1.1.2`（對應 `v1.1.2` tag；在本機驗證請用 `publishToMavenLocal`）。

只檢查 Markdown 連結、anchor、版本文字與 consumer 範例契約時，可執行：

```bash
./gradlew -p examples/consumer-plugin verifyConsumerDocs --no-daemon --console=plain
```

## 修改公開 API 或文件

- 新功能與 bug 修正先寫能失敗的測試，再做最小實作。
- 玩家、實體、方塊與 inventory 的操作要同時考慮 Paper 與 Folia；MockBukkit 不能取代真實 Folia runtime。
- 公開型別分類列在 [API surface](../reference/api-surface.md)。該頁由 `api-surface.json` 產生，不要手動改 generated inventory。
- Markdown 的分工與 release 更新位置請看[文件寫作說明](../documentation-style.md)。
- 公開 JavaDoc 必須通過 doclint；錯誤訊息使用 `ACELIB-<AREA>-<CODE>` 格式。

## 相關頁面

- [文件怎麼分工](../documentation-style.md)
- [模組指南](../modules/README.md)
- [錯誤碼](../reference/error-codes.md)
