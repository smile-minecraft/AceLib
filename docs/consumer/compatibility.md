# 相容性、版本與發布狀態

本頁解決部署前要確認哪些 Java、伺服器與 Gradle 版本，以及目前 artifact 能否從外部取得的問題。所有版本數字以
> `build.gradle.kts`、`src/main/resources/plugin.yml`、`gradle/wrapper/` 與
> `AceLibVersion.java` 的實際值為準。

## 前置條件

部署前準備 Java 25 與 Paper／Folia 26.1.2。repository 已公開，GitHub `v1.0.0` Release 已建立；JitPack tag 與 Maven publication 的可用性需分開判讀。

## 1. 支援基線（已確認）

| 項目 | 值 | 來源 |
| --- | --- | --- |
| JDK | Java 25+ | `build.gradle.kts` toolchain |
| Paper API | `26.1.2.build.72-stable` | `build.gradle.kts` `compileOnly` |
| MockBukkit（測試） | `4.113.1`（`mockbukkit-v26.1.2`） | `build.gradle.kts` |
| Gradle | `9.5.1` | `gradle/wrapper/gradle-wrapper.properties` |
| plugin `api-version` | `26.1.2` | `src/main/resources/plugin.yml` |
| `folia-supported` | `true` | `src/main/resources/plugin.yml` |
| `load` | `POSTWORLD` | `src/main/resources/plugin.yml` |

## 2. 版本與發布狀態

- 目前版本：**`1.0.0`**（GitHub Release 已建立；`build.gradle.kts` / `plugin.yml` /
  `AceLibVersion.VERSION` 三處一致，由 `verifyPublication` 與
  `PublicationConsistencyTest` 守護）。
- **GitHub**：repository 已公開，`v1.0.0` GitHub Release 已建立；tag 指向含 Gradle wrapper 修復的 commit。
- **本機 Maven**：`./gradlew publishToMavenLocal` 後，`com.smile:acelib:1.0.0` 可由
  `repositories { mavenLocal() }` 解析；此狀態不代表 Maven Central 已發布。
- **JitPack commit**：`com.github.smile-minecraft:AceLib:cbf4a80` status 為 `ok`；
  consumer fixture 以 dependency substitution 指向該 commit，並在乾淨 Gradle user home 完成 build／`verifyConsumerDocs`。
- **JitPack tag**：`com.github.smile-minecraft:AceLib:v1.0.0` 目前仍為 `Error`，
  原因是服務端快取舊 commit `9b8e55d`；刪除舊失敗 build 或服務端介入後才可重新驗證。

## 3. 平台支援

| 平台 | 狀態 | 說明 |
| --- | --- | --- |
| Paper 26.1.2 | 支援（測試覆蓋 + smoke harness） | `PlatformDetectorTest`、MockBukkit 路徑 |
| Folia 26.1.2 | 支援（測試覆蓋；真實 runtime 依 smoke harness） | regionized 排程路徑 |
| Folia / Paper 26.2 | **尚未驗證** | 不得寫成 supported；需真實 runtime 驗證後再更新本頁 |

## 4. 驗證與測試

| 層級 | 指令 | 驗證內容 |
| --- | --- | --- |
| 單元 / 整合測試 | `./gradlew test` | lifecycle、platform、provider、command、smoke script 等 |
| JavaDoc | `./gradlew javadoc` | doclint 啟用，public API 文件品質 |
| 完整 build | `./gradlew build` | compile + test + jar + 文件驗證 |
| consumer fixture | `./gradlew -p examples/consumer-plugin build` | 下游可依 README 編譯正式 provider contract |
| 發布驗證 | `./gradlew publishToMavenLocal` + `verifyPublication` | artifact 四件套與版本一致性 |
| 真實伺服器 smoke | `./scripts/smoke-server.sh paper\|folia` | Paper / Folia 真實 runtime（需 server jar） |

> 「`./gradlew test` 通過」不等於「已在 Paper / Folia 真實啟動驗證」；
> 後者需要 smoke harness 或手動部署。

## 5. 取得方式與發布限制

發布資訊分為 GitHub Release、JitPack commit artifact 與本機 Maven publication：

- 根 [README.md](../../README.md)「取得 AceLib」章節：dependency 座標與本機驗證。
- 本頁：版本、GitHub、JitPack 與 Maven 取得限制。
- JavaDoc：`./gradlew javadoc` 產出；不宣稱 Maven Central 或 JitPack tag 已提供 JavaDoc artifact。

目前同步狀態：

1. 根 README 與本頁已標示 GitHub Release、JitPack commit 與 JitPack tag 狀態。
2. consumer quickstart §2 已分開列出 `com.smile:acelib:1.0.0` 與 `cbf4a80`。
3. JitPack tag 待服務端刪除舊失敗 build 或支援介入後重新驗證。

## 預期結果

版本、平台與發布狀態可從本頁及列出的 source of truth 核對；未經真實 runtime 驗證的版本不會被視為支援。

## 常見失敗

- 直接解析 JitPack `v1.0.0` tag：目前為 `Error`，先使用本機 Maven 或 `cbf4a80` commit artifact。
- 將 `com.smile:acelib:1.0.0` 當成 Maven Central artifact：目前僅本機 Maven publication 可驗證。
- 將 Paper／Folia `26.2` 當成已支援：目前尚未驗證。
- 只跑 `./gradlew test` 就宣稱真實伺服器已驗證：真實 runtime 仍需 smoke harness 或手動部署。

## 下一步

- 下游接入：查看 [Quick Start](quickstart.md)。
- 管理員部署：查看 [Operator 指南](../operator/README.md)。
- 重新驗證發布內容：執行 `./gradlew publishToMavenLocal` 與 `verifyPublication`。

## 6. 限制與風險

- **26.2 未驗證**：升級 Paper / Folia 前需先完成真實 runtime 驗證。
- **Folia 真實 runtime**：MockBukkit 不提供 Folia entity scheduler API，
  regionized 排程路徑需在 Folia 26.1.2 runtime 驗證（smoke harness）。
- **JitPack tag 重建待處理**：`v1.0.0` tag 因服務端快取 `9b8e55d` 仍為 `Error`；`cbf4a80` commit artifact 已驗證。
- **Maven Central**：未宣稱 `com.smile:acelib:1.0.0` 已發布至 Maven Central。
