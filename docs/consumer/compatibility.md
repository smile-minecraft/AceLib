# 相容性

> 適合在建置或升級前確認 Java、Paper／Folia 與 Gradle 基線的開發者與管理員。


AceLib 1.1.2 採用 Java 25，目標伺服器版本是 Paper 與 Folia 26.1.2。

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

## 基岩版支援（Geyser/Floodgate）

AceLib 透過 Floodgate 偵測基岩版玩家並傳送原生表單；相關 API 見[基岩版玩家模組](../modules/bedrock.md)與[表單模組](../modules/form.md)。

下列為編譯期對照版本（compileOnly 鎖定於 `build.gradle.kts`），運行期使用 server 上實際安裝的 Floodgate plugin：

| 項目 | 版本或設定 | 用途 |
| --- | --- | --- |
| Floodgate API | `2.2.5-SNAPSHOT`（unique snapshot `2.2.5-20260809.110940-20`） | 基岩玩家偵測，compileOnly |
| Geyser common | `2.2.1-20240128.225244-3` | DeviceOs / InputMode / LinkedPlayer 等型別，compileOnly |
| Cumulus | `1.1.2` | 表單模型翻譯層，compileOnly |
| Floodgate 最低版本 | `2.2.0` | 低於此版本判定 `VERSION_UNSUPPORTED` |

## 尚未驗證的範圍

- Paper 與 Folia 26.2 尚未全面驗證，不應直接視為支援版本。AceLib 的支援聲明以 Paper 與 Folia 26.1.2 為準。
- MockBukkit 可測試 Paper API 與部分平台分支，但不能取代 Folia 真實 region scheduler runtime。涉及 region 的功能仍需在 Folia server 上驗證。
- Bukkit `/reload` 不受支援。AceLib 文件提到的 reload 是函式庫自己的生命週期操作。
- Geyser 位於 proxy 的架構已於[管理員指南](../operator/README.md)說明部署條件，但本地僅驗證單機後端路徑，未在實際 proxy 環境驗證。

### 關於 Folia 26.2 的個案驗證紀錄

v1.1.0 的基岩功能曾在 Folia 26.2-4 上做過單一功能的實機驗證（Floodgate 2.2.5-SNAPSHOT b140 + Geyser-Spigot 2.11.2-b1232，含真人基岩客戶端的表單操作；詳見 CHANGELOG 的 1.1.0 紀錄）。這是特定組合下針對基岩表單功能的驗證紀錄，不代表 AceLib 已完整支援 Folia 26.2 平台：排程、上下文與其他模組在 26.2 上仍未驗證。評估升級時，仍應以 26.1.2 為支援基準，並先在獨立測試 server 驗證。

版本數字可在 `build.gradle.kts`、`gradle/wrapper/gradle-wrapper.properties` 與 `src/main/resources/plugin.yml` 核對。取得 JitPack API 或 server JAR 的方式請看[如何取得 AceLib](../reference/release-artifacts.md)。

## 相關頁面

- [快速開始](quickstart.md)
- [伺服器管理員指南](../operator/README.md)
- [如何取得 AceLib](../reference/release-artifacts.md)
