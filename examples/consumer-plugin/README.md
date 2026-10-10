# 可編譯的 consumer plugin 範例

這個目錄是一個獨立 Gradle 專案，示範下游 plugin 如何：

- 在 `plugin.yml` 宣告 `depend: [AceLib]`。
- 透過 Bukkit `ServicesManager` 取得 `AceLibApi.AceLibProvider`。
- 檢查 `api().isReady()`，再使用 API。
- 使用公開 lifecycle API 註冊帶依賴的模組，並處理缺依賴與循環的結構化拒絕。
- reload 後透過動態 provider 取得新 facade；停用時只撤銷本 plugin 擁有的模組。
- 依 Paper 或 Folia 的平台能力選擇操作路徑。

一般 plugin 專案請先看[快速開始](../../docs/consumer/quickstart.md)。已發布版本使用 JitPack `com.github.smile-minecraft:AceLib:v1.4.0`（對應 `v1.4.0` tag）；本範例驗證中的本機版本為 `com.smile:acelib:1.4.0`（含 `com.smile:acelib:1.4.0:test-fixtures` 測試輔助，見下方）。

## 在 AceLib repository 內編譯

本範例刻意使用 `mavenLocal()` 與 `com.smile:acelib:1.4.0`，以便驗證你目前修改中的 AceLib。先從 repository 根目錄發布本機產物，再建置範例：

```bash
./gradlew publishToMavenLocal
./gradlew -p examples/consumer-plugin build --no-daemon --console=plain
```

成功後會產生：

```text
examples/consumer-plugin/build/libs/acelib-consumer-quickstart-1.0.0-SNAPSHOT.jar
```

這個 JAR 只用於範例驗證，不會發布。`mavenLocal()` 也不是一般使用者取得 AceLib 的唯一方式。

只執行文件與範例契約檢查：

```bash
./gradlew -p examples/consumer-plugin verifyConsumerDocs --no-daemon --console=plain
```

主要檔案：

- [`QuickStartPlugin.java`](src/main/java/com/example/acelibconsumer/QuickStartPlugin.java)
- [`plugin.yml`](src/main/resources/plugin.yml)
- [`build.gradle.kts`](build.gradle.kts)

## 開發中的新 API 範例

以下五個範例示範尚未發布的新 API，只用公開 API，對應的模組頁各有一句入口說明：

- [`CommandV150Example.java`](src/main/java/com/example/CommandV150Example.java)：以解析函式與補全函式建立自訂百分比引數。
- [`GuiV150Example.java`](src/main/java/com/example/GuiV150Example.java)：以字元遮罩組商店視圖，購買按鈕一併給物品。
- [`MessageV150Example.java`](src/main/java/com/example/MessageV150Example.java)：分辨渲染失敗原因、拿發送結果、選攤平風格與輸出純文字。
- [`ConfigV150Example.java`](src/main/java/com/example/ConfigV150Example.java)：世代判斷、數值讀取、跨欄位規則與缺檔攔截。
- [`PlayerStoreV150Example.java`](src/main/java/com/example/PlayerStoreV150Example.java)：條件寫入、讀檢查套用與離線讀取；可照抄的路徑是 sqlite 後端。
