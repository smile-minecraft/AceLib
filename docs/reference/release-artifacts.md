# 如何取得 AceLib

> 適合要選擇正取得 AceLib 方式（JitPack、建置或本機 Maven）的開發者與管理員。


插件開發者取得的是編譯用 API；伺服器管理員需要的是可放進 `plugins/` 的 runtime JAR。兩者不是同一個安裝步驟。

## 插件開發者：從 JitPack 取得 API

Gradle repository 是 `https://jitpack.io`，`compileOnly` 座標是 `com.github.smile-minecraft:AceLib:v1.4.0`（此 checkout 的原始碼版本為 1.4.0；該座標對應 `v1.4.0` tag，在本機驗證請用 `publishToMavenLocal` 搭配 `com.smile:acelib:1.4.0`）。可直接複製的完整設定與 Paper API dependency 請看[快速開始](../consumer/quickstart.md)。

JitPack 座標 `com.github.smile-minecraft:AceLib:v1.4.0` 對應 `v1.4.0` tag，提供編譯用 API；其 artifact 命名如下：

- `AceLib-v1.4.0.jar`
- `AceLib-v1.4.0-sources.jar`
- `AceLib-v1.4.0-javadoc.jar`

主 JAR 包含 `AceLibApi`、`AceLibApi.AceLibProvider` 與 `AceLibVersion`。POM 座標為 `com.github.smile-minecraft:AceLib:v1.4.0`（對應 v1.4.0 Git tag），沒有 transitive dependencies；Gradle module metadata 要求 Java 25。

JitPack 舊的建置紀錄可能與目前可下載檔案不同，請以本頁座標與實際解析結果為準。

## 插件開發者：從 JitPack 取得測試輔助

單元測試用的假實作與服務契約放在獨立的 test-fixtures JAR（`AceLib-<version>-test-fixtures.jar`）。引用方式是在版本後面加上 `test-fixtures` 後綴（classifier）：

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.smile-minecraft:AceLib:v1.4.0")
    testImplementation("com.github.smile-minecraft:AceLib:v1.4.0")
    testImplementation("com.github.smile-minecraft:AceLib:v1.4.0:test-fixtures")
}
```

後綴座標只提供 test-fixtures 單一檔案，main API 仍由上面的 `compileOnly` 提供；`compileOnly` 不進測試路徑，測試以 `testImplementation` 同時取得 main（編譯加運行）與 fixtures，三者缺一不可。不要用 Gradle 的 `testFixtures(...)` 寫法引用 JitPack（例如 `testFixtures("com.github.smile-minecraft:AceLib:9ac06a16d0")` 在 JitPack 解析不到）；後綴寫法才解析得到。

範圍說明：上面範例的 main 與 fixtures 取同一版本（`v1.4.0`），不要長期混用不同版本的 main 與 fixtures。後綴寫法經實際解析驗證（曾以 `9ac06a16d0` 可行性實驗 jar 證明後綴引用可解析；該實驗 jar 只含一個 throwaway 類別，不含下方型別表）。

下方是 AceLib 1.4.0 fixtures 的型別表（完整行為見各型別 Javadoc）：

| 用途 | 型別 |
| --- | --- |
| 可控制的時鐘 | `com.smile.acelib.testing.FakeClock` |
| 可控制的排程、實體退休事件 | `com.smile.acelib.testing.FakeSafeScheduler` |
| GUI 標準假實作（過時回應、重複回應、關閉失敗） | `com.smile.acelib.gui.FakeGuiService` |
| 表單標準假實作（過時回應、重複回應、關閉回應） | `com.smile.acelib.testing.FakeFormService` |
| Provider 缺席、停用、重新取得 | `com.smile.acelib.testing.FakeExternalIntegrationService` |
| 真實作與假實作共用的服務契約 | `com.smile.acelib.testing.contracts` 下的 `GuiServiceContract`、`FormServiceContract`、`SafeSchedulerContract`、`ExternalIntegrationContract`（繼承後分別接上真實作與假實作） |

失敗模擬對照：

| 想測的情境 | 做法 |
| --- | --- |
| 過時回應 | 舊 generation 調用（GUI）／未知 token 投遞（表單，`deliverResponse` 回 false） |
| 重複回應 | 同一票券／token 調用第二次（回 `ACTION_ALREADY_RESOLVED`／計為丟棄，callback 只執行一次） |
| 關閉失敗 | `FakeGuiService.failNextClose()`（假實作獨有注入：回 `FAILED + OPERATION_FAILED`，session 保留可重試；production 的關閉一律成功，沒有關閉失敗路徑）；表單以 `CLOSED` 回應投遞重現玩家關閉 |
| Provider 缺席／停用／重新取得 | 未知 id（`INIT_FAILED`）／`NOT_ENABLED`／移除後重新註冊（恢復 `AVAILABLE`） |
| 玩家離線／實體退休／chunk 未載入 | 離線標記／`retire(entity)`／`setChunkLoaded(location, false)`（分別記 `SCHED-002`／`SCHED-003`／`SCHED-004`） |

## 伺服器管理員：取得 plugin JAR

GitHub repository [`smile-minecraft/AceLib`](https://github.com/smile-minecraft/AceLib) 的 v1.4.0 Release 提供可直接放入 `plugins/` 的 runtime asset：

```text
https://github.com/smile-minecraft/AceLib/releases/download/v1.4.0/AceLib-1.4.0.jar
```

下載後請驗證 SHA-256：

```bash
shasum -a 256 AceLib-1.4.0.jar
```

以下雜湊與 v1.4.0 GitHub Release 附件一致（2026-10-08 發布後下載比對），`AceLib-1.4.0.jar` 的 SHA-256：

```text
80c5274b1d2ab1ed528f49b7878f58ad8dfb110492da59277b6aacc7b4e3185b
```

同一 Release 另有 `AceLib-1.4.0-test-fixtures.jar` 附件（下游單元測試用，不放進 server），以下雜湊與 v1.4.0 GitHub Release 附件一致（2026-10-08 發布後下載比對）：

```text
6c12c677c6beed17b4893050c42f547bfcf588496631673b52b557de3748a85f
```

下載後請同樣驗證：

```bash
shasum -a 256 AceLib-1.4.0-test-fixtures.jar
```

不要把 `-sources.jar`、`-javadoc.jar` 或 `-test-fixtures.jar` 放進 server（測試輔助只給下游單元測試用，不是 server 插件）。若 Release asset 暫時無法取得，才 checkout 對應版本後從原始碼建置（`git checkout v1.4.0` 對應 v1.4.0 tag）：

```bash
git clone https://github.com/smile-minecraft/AceLib.git
cd AceLib
git checkout v1.4.0  # v1.4.0 tag
./gradlew clean build --no-daemon --console=plain
```

成功後使用：

```text
build/libs/AceLib-1.4.0.jar
```

完整步驟請看[伺服器管理員指南](../operator/README.md)。

Git tag `v1.0.0` 指向 commit `cbf4a80f69c83bf3095258b42321c5b6b359f8cf`。<!-- 版本歷史 -->

## 貢獻者：發布到本機 Maven

修改 AceLib 或驗證 repository 內的 consumer 範例時，可執行：

```bash
./gradlew publishToMavenLocal
```

這會在本機提供 `com.smile:acelib:1.4.0`（含 `com.smile:acelib:1.4.0:test-fixtures` 測試輔助）。它不是 Maven Central 座標，也不應作為一般 plugin 開發者的安裝方式。

## 相關頁面

- [快速開始](../consumer/quickstart.md)
- [伺服器管理員指南](../operator/README.md)
- [相容性](../consumer/compatibility.md)
