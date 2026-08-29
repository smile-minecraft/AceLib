# 可編譯的 consumer plugin 範例

這個目錄是一個獨立 Gradle 專案，示範下游 plugin 如何：

- 在 `plugin.yml` 宣告 `depend: [AceLib]`。
- 透過 Bukkit `ServicesManager` 取得 `AceLibApi.AceLibProvider`。
- 檢查 `api().isReady()`，再使用 API。
- 依 Paper 或 Folia 的平台能力選擇操作路徑。

一般 plugin 專案請先看[快速開始](../../docs/consumer/quickstart.md)，並使用 JitPack `com.github.smile-minecraft:AceLib:v1.2.0`。

## 在 AceLib repository 內編譯

本範例刻意使用 `mavenLocal()` 與 `com.smile:acelib:1.2.0`，以便驗證你目前修改中的 AceLib。先從 repository 根目錄發布本機產物，再建置範例：

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
