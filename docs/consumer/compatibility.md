# 相容性

AceLib 1.0.0 採用 Java 25，目標伺服器版本是 Paper 與 Folia 26.1.2。

| 項目 | 版本或設定 | 用途 |
| --- | --- | --- |
| Java toolchain | 25 | 編譯 AceLib 與下游範例 |
| Paper API | `26.1.2.build.72-stable` | 編譯期 API |
| Paper server | 26.1.2 | 已採用版本 |
| Folia server | 26.1.2 | 已採用版本 |
| Gradle wrapper | 9.5.1 | 修改 AceLib repository 時使用 |
| `api-version` | `26.1.2` | AceLib 的 `plugin.yml` metadata |
| `folia-supported` | `true` | AceLib 的 `plugin.yml` metadata |
| `load` | `POSTWORLD` | AceLib 的載入階段 |

## 尚未驗證的範圍

- Paper 與 Folia 26.2 尚未驗證，不應直接視為支援版本。
- MockBukkit 可測試 Paper API 與部分平台分支，但不能取代 Folia 真實 region scheduler runtime。涉及 region 的功能仍需在 Folia server 上驗證。
- Bukkit `/reload` 不受支援。AceLib 文件提到的 reload 是函式庫自己的生命週期操作。

版本數字可在 `build.gradle.kts`、`gradle/wrapper/gradle-wrapper.properties` 與 `src/main/resources/plugin.yml` 核對。取得 JitPack API 或 server JAR 的方式請看[如何取得 AceLib](../reference/release-artifacts.md)。
