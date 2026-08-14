# 相容性、版本與發布狀態

本頁解決部署前要確認哪些 Java、伺服器與 Gradle 版本，以及目前 artifact 能否從外部取得的問題。所有版本數字以
> `build.gradle.kts`、`src/main/resources/plugin.yml`、`gradle/wrapper/` 與
> `AceLibVersion.java` 的實際值為準。

## 前置條件

部署前準備 Java 25 與 Paper／Folia 26.1.2。`1.0.0` 仍是 private repository 的 Release Candidate，外部 Maven／JitPack artifact 尚未產生。

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

## 2. 版本與發布狀態（未發布）

- 目前版本：**`1.0.0`**（Release Candidate；`build.gradle.kts` / `plugin.yml` /
  `AceLibVersion.VERSION` 三處一致，由 `verifyPublication` 與
  `PublicationConsistencyTest` 守護）。
- **尚未發布**：repository 為 private；外部 Maven / JitPack artifacts 尚未產生。
  文件與 README 一律以「未發布」與 `1.0.0` 為準。
- 目前為 `1.0.0` **Release Candidate（尚未發布）**：`v1.0.0` 正式 git 標籤與外部
  artifacts 僅在 publishing 流程完成、外部可下載後才成立。
- 本機可重現的解析方式：`./gradlew publishToMavenLocal` →
  下游 `repositories { mavenLocal() }`（見 [quickstart.md](quickstart.md)）。

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

## 5. 發布導航（Release Navigation）

發布流程由 publishing 基礎任務負責，文件層面：

- 根 [README.md](../../README.md)「取得 AceLib」章節：dependency 座標與本機驗證。
- 本頁：版本與未發布狀態（唯一維護基線處之一，與 README 互相連結）。
- JavaDoc artifact：`./gradlew javadoc` 產出；正式發布後隨 artifact 提供。

發布後（外部 artifacts 可用）需同步更新的文件位置：

1. 根 README「目前狀態」與「取得 AceLib」：改為外部 repository 座標，
   移除「尚未發布」聲明。
2. 本頁 §2：標記已發布版本。
3. consumer quickstart §2：更新 repositories 來源。

## 預期結果

版本、平台與發布狀態可從本頁及列出的 source of truth 核對；未經真實 runtime 驗證的版本不會被視為支援。

## 常見失敗

- 直接從外部 Maven／JitPack 解析 `1.0.0`：目前不會成功，先使用 `publishToMavenLocal`。
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
- **外部發布未完成**：`v1.0.0` 前不應在公開文件宣稱 Maven / JitPack 可用。
