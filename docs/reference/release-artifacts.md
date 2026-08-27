# 如何取得 AceLib

> 適合要選擇正取得 AceLib 方式（JitPack、建置或本機 Maven）的開發者與管理員。


插件開發者取得的是編譯用 API；伺服器管理員需要的是可放進 `plugins/` 的 runtime JAR。兩者不是同一個安裝步驟。

## 插件開發者：從 JitPack 取得 API

Gradle repository 是 `https://jitpack.io`，`compileOnly` 座標是 `com.github.smile-minecraft:AceLib:v1.1.1`（此 checkout 的原始碼版本為 1.1.1；該座標對應 `v1.1.1` tag，在本機驗證請用 `publishToMavenLocal` 搭配 `com.smile:acelib:1.1.1`）。可直接複製的完整設定與 Paper API dependency 請看[快速開始](../consumer/quickstart.md)。

JitPack 座標 `com.github.smile-minecraft:AceLib:v1.1.1` 對應 `v1.1.1` tag，提供編譯用 API；其 artifact 命名如下：

- `AceLib-v1.1.1.jar`
- `AceLib-v1.1.1-sources.jar`
- `AceLib-v1.1.1-javadoc.jar`

主 JAR 包含 `AceLibApi`、`AceLibApi.AceLibProvider` 與 `AceLibVersion`。POM 座標為 `com.github.smile-minecraft:AceLib:v1.1.1`（對應 v1.1.1 Git tag），沒有 transitive dependencies；Gradle module metadata 要求 Java 25。

JitPack 舊的建置紀錄可能與目前可下載檔案不同，請以本頁座標與實際解析結果為準。

## 伺服器管理員：建立 plugin JAR

GitHub repository [`smile-minecraft/AceLib`](https://github.com/smile-minecraft/AceLib) 已公開。請 checkout 對應版本後從原始碼建置（`git checkout v1.1.1` 對應 v1.1.1 tag）：

```bash
git clone https://github.com/smile-minecraft/AceLib.git
cd AceLib
git checkout v1.1.1  # v1.1.1 tag
./gradlew clean build --no-daemon --console=plain
```

成功後使用：

```text
build/libs/AceLib-1.1.1.jar
```

不要把 `-sources.jar` 或 `-javadoc.jar` 放進 server。完整步驟請看[伺服器管理員指南](../operator/README.md)。

Git tag `v1.0.0` 指向 commit `cbf4a80f69c83bf3095258b42321c5b6b359f8cf`。<!-- 版本歷史 -->

## 貢獻者：發布到本機 Maven

修改 AceLib 或驗證 repository 內的 consumer 範例時，可執行：

```bash
./gradlew publishToMavenLocal
```

這會在本機提供 `com.smile:acelib:1.1.1`。它不是 Maven Central 座標，也不應作為一般 plugin 開發者的安裝方式。

## 相關頁面

- [快速開始](../consumer/quickstart.md)
- [伺服器管理員指南](../operator/README.md)
- [相容性](../consumer/compatibility.md)
