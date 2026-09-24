# 相容性

> 適合在建置或升級前確認 Java、Paper／Folia 與 Gradle 基線的開發者與管理員。


AceLib 1.2.2 採用 Java 25，Paper 與 Folia 26.1.2、26.2 皆為正式支援（SUPPORTED）；未列出的版本皆為 UNVERIFIED（尚未驗證）。詳細的版本相容矩陣（JSON 格式）見 [runtime-compatibility-matrix.json](../reference/runtime-compatibility-matrix.json)。其中 **SUPPORTED** 為正式支援、**UNVERIFIED** 為尚未驗證。

| 項目 | 版本或設定 | 用途 |
| --- | --- | --- |
| Java toolchain | 25 | 編譯 AceLib 與下游範例 |
| Paper API | `26.1.2.build.72-stable` | 編譯期 API |
| Paper server | 26.1.2、26.2 | 已採用版本 |
| Folia server | 26.1.2、26.2 | 已採用版本 |
| Gradle wrapper | 9.5.1 | 修改 AceLib repository 時使用 |
| `api-version` | `26.1.2` | AceLib 的 `plugin.yml` metadata |
| `folia-supported` | `true` | AceLib 的 `plugin.yml` metadata |
| `load` | `POSTWORLD` | AceLib 的載入階段 |

## 基岩版支援（Geyser/Floodgate）

AceLib 透過 Floodgate 偵測基岩版玩家並傳送原生表單；相關 API 見[基岩版玩家模組](../modules/bedrock.md)與[表單模組](../modules/form.md)。

下列為編譯期對照版本（compileOnly 鎖定於 `build.gradle.kts`），運行期使用 server 上實際安裝的 Floodgate plugin：

| 項目 | 版本或設定 | 用途 |
| --- | --- | --- |
| Floodgate API | `2.2.5-SNAPSHOT`（unique snapshot `2.2.5-20260809.110940-20`） | 基岩玩家偵測，compileOnly |
| Geyser common | `2.2.1-20240128.225244-3` | DeviceOs / InputMode / LinkedPlayer 等型別，compileOnly |
| Cumulus | `1.1.2` | 表單模型翻譯層，compileOnly |
| Floodgate 最低版本 | `2.2.0` | 低於此版本判定 `VERSION_UNSUPPORTED` |

## 限制與注意事項

- Paper 與 Folia 26.1.2、26.2（含 Folia 26.2-4 上的真人基岩玩家實測）皆為正式支援（**SUPPORTED**）：Paper 26.2-120 與 Folia 26.2-7 通過了啟動 smoke 與執行期能力閘驗證，Folia 26.2-4 另外通過了狀態、排程、上下文、訊息 fallback 檢查與真人基岩玩家的四項實測。其他未在矩陣列出的未來版本皆為 **UNVERIFIED**（尚未驗證）。詳見[執行期相容矩陣](../reference/runtime-compatibility-matrix.json)。
- MockBukkit 可測試 Paper API 與部分平台分支，但不能取代 Folia 真實 region scheduler 的實際運作。涉及 region 的功能仍需在 Folia 伺服器上驗證。
- Bukkit `/reload` 不受支援。AceLib 文件中提到的 reload 是函式庫自己的生命週期操作。
- Geyser 位於 proxy 的架構已在[管理員指南](../operator/README.md)說明部署條件，但目前僅驗證過單機後端路徑，尚未在實際 proxy 環境驗證。

### 26.2 驗證歷程

v1.1.0 的基岩功能曾在 Folia 26.2-4 上針對單一組合做過實機驗證（Floodgate 2.2.5-SNAPSHOT b140 + Geyser-Spigot 2.11.2-b1232，含真人基岩客戶端的表單操作；詳見 CHANGELOG 的 1.1.0 紀錄）。1.2.0 起，Folia 26.2-4 與 Paper 26.2-120、Folia 26.2-7 已在獨立測試環境完成啟動驗證，當時標為 VERIFIED-BETA（已驗證的測試版）；1.2.1 起三者皆升為正式支援（SUPPORTED，見[執行期相容矩陣](../reference/runtime-compatibility-matrix.json)）。排程、上下文與其他模組在 26.2 上的細部驗證狀態以矩陣為準。評估是否升級時，仍請先在獨立的測試伺服器上驗證。

版本數字可在 `build.gradle.kts`、`gradle/wrapper/gradle-wrapper.properties` 與 `src/main/resources/plugin.yml` 核對。取得 JitPack API 或 server JAR 的方式請看[如何取得 AceLib](../reference/release-artifacts.md)。

## 相關頁面

- [快速開始](quickstart.md)
- [伺服器管理員指南](../operator/README.md)
- [如何取得 AceLib](../reference/release-artifacts.md)
