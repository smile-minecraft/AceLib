# AceLib

> **Folia-first base library for Smile Minecraft plugins**
>
> Paper 26.1.2 / Folia 26.1.2 / Java 25

---

## 設計理念

AceLib 是 [Smile](https://github.com/smile-minecraft) 系列 Minecraft 插件的共用基礎函式庫，
採 **Folia-first** 策略設計：

- **正確性優先於相容性**：Folia 的 regionized 排程與事件模型在 API 層面與 Paper 不同，
  本函式庫在所有 platform 偵測、API facade 設計上以 Folia 為標的行為，Paper 為相容路徑。
- **不可降級到 Paper-only 思維**：在 Folia 環境下錯誤的 API（例如全域 `BukkitScheduler`）
  不會被允許透過 AceLib 暴露；插件作者須明確選擇 Folia / Paper 分支。
- **平台透明**：插件透過 `AceLibApi.getPlatform()` 取得當前 platform，自行決定分支邏輯。

---

## 環境需求

| 元件          | 版本                  | 取得方式                                                  |
| ------------- | --------------------- | --------------------------------------------------------- |
| JDK           | **25+**               | 由 `foojay-resolver-convention` plugin 自動下載           |
| Paper         | **26.1.2+**           | https://papermc.io/downloads/paper                        |
| Folia         | **26.1.2+**           | https://github.com/PaperMC/Folia/releases                 |
| Gradle        | 8.10+（隨附 `gradlew`） | —                                                        |
| MockBukkit    | 4.113.1（測試）       | 由 `build.gradle.kts` 引入                                |

> Java 25 是 Paper 26.1+ 的最低需求。本專案透過
> [`foojay-resolver-convention`](https://github.com/gradle/foojay-toolchains)
> plugin 自動下載缺少的 JDK toolchain，無需手動安裝對應版本。

---

## Build 指令

```bash
# 編譯 + 打包 jar（產出 build/libs/AceLib-0.1.0-SNAPSHOT.jar）
./gradlew build

# 跑單元測試（產出 build/reports/tests/test/index.html）
./gradlew test

# 重新測試（清除快取後跑一次）
./gradlew clean test
```

> **首次 build 須連網**：Gradle 會下載 Java 25 toolchain（foojay）與
> 依賴（paper-api 26.1.2.build.72-stable、MockBukkit 4.113.1、JUnit 5.11.0）。

---

## API 範例

```java
import com.smile.acelib.AceLib;
import com.smile.acelib.AceLibApi;
import com.smile.acelib.platform.Platform;
import org.bukkit.plugin.java.JavaPlugin;

public class MyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // 取得不可變的 API facade
        AceLibApi api = AceLib.getApi();

        getLogger().info("AceLib " + api.getVersion()
            + " on " + api.getPlatform().getDisplayName());

        // 依平台分流：Folia 走 RegionizedServer，Paper 走 BukkitScheduler
        if (api.getPlatform() == Platform.FOLIA) {
            // Folia-specific：RegionizedServer.getGlobalRegionScheduler() 等
        } else if (api.getPlatform() == Platform.PAPER) {
            // Paper 環境：getServer().getScheduler() 等
        }

        // 重新偵測平台並重新建立 API 實例（hot reload 場景）
        api.reload();
    }
}
```

`AceLibApi` 是不可變（immutable）facade，提供：

- `getVersion()` — 取得 AceLib 版本字串
- `getPlatform()` — 取得當前 `Platform` 列舉（`FOLIA` / `PAPER` / `UNKNOWN`）
- `isReady()` — 反映底層 plugin 的啟用狀態
- `reload()` — 委派給 plugin 重新偵測平台

---

## Folia / Paper 行為差異

`com.smile.acelib.platform.PlatformDetector` 透過 classpath reflection 判定當前平台，
**不依賴 Bukkit runtime API**，因此可在純 classpath 隔離的單元測試中運作。

判定順序：

1. 若能找到 `io.papermc.paper.threadedregions.RegionizedServer` → **Folia**
2. 否則若能找到 `org.bukkit.Bukkit` → **Paper / CraftBukkit 相容**
3. 否則 → **Unknown**

| Platform   | displayName | 典型環境                                |
| ---------- | ----------- | --------------------------------------- |
| `FOLIA`    | `Folia`     | PaperMC/Folia 服務端（regionized）     |
| `PAPER`    | `Paper`     | Paper / Paper 相容衍生（Purpur 等）     |
| `UNKNOWN`  | `Unknown`   | 純 classpath 測試 / 未對應的環境        |

---

## 測試策略

本專案所有 production 邏輯（`AceLibPlugin` 生命週期、`PlatformDetector` 探測等）
皆配備 JUnit 5 單元測試：

- **5 個 `PlatformDetectorTest`**：用 `ClassLoader` 子類隔離真實 classpath，
  覆蓋 FOLIA / PAPER / UNKNOWN 三種情境。
- **11 個 `AceLibPluginTest`**：以 MockBukkit 4.x 模擬 Bukkit 環境，
  覆蓋 `onEnable` / `onDisable` / `reload` 冪等性與邊界條件。

```bash
./gradlew test      # 跑測試
./gradlew test --info   # 含測試 stdout
```

---

## 開發計劃狀態

**目前為 v0.1.0-SNAPSHOT 骨架（Phase 0 完成）**。

| Phase | 範疇                                 | 狀態      |
| ----- | ------------------------------------ | --------- |
| 0     | 專案基礎（build / plugin.yml / API facade / Platform 偵測） | ✅ 完成   |
| 1     | Lifecycle Manager（hot reload / 優雅關閉）              | ⏳ 規劃中 |
| 2     | Scheduler Wrapper（Folia / Paper 統一介面）            | ⏳ 規劃中 |
| 3     | Event Bus（region-aware event dispatcher）             | ⏳ 規劃中 |
| 4~13  | Logger、Config、Metrics、Command、I18N 等模組          | ⏳ 規劃中 |
| 14    | 發版 / 文件收尾                                       | ⏳ 規劃中 |

> 詳細計劃屬內部文件，不對外公開。

---

## 授權

本專案以 **MIT License** 發布 — 詳見 [LICENSE](LICENSE) 檔案。

```
MIT License
Copyright (c) 2026 Smile
```
