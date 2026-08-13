# AceLib

> **Folia-first base library for Smile Minecraft plugins**
>
> Paper 26.1.2 / Folia 26.1.2 / Java 25

---

## 設計理念

AceLib 是 Smile 系列 Minecraft 插件的共用基礎函式庫（隸屬於 github.com/smile-minecraft 組織），
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
> Foojay JDK Resolver Convention 插件（github.com/gradle/foojay-toolchains）
> plugin 自動下載缺少的 JDK toolchain，無需手動安裝對應版本。

---

## Build 指令

```bash
# 編譯 + 打包 jar（產出 build/libs/AceLib-0.5.0-SNAPSHOT.jar）
./gradlew build

# 跑 unit verification 後檢視 `build/reports/` 內附 HTML report
./gradlew test

# 重新跑 unit verification（先清除快取再執行一次）
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

本專案 production 邏輯（`AceLibPlugin` 生命週期、`PlatformDetector` 探測、
`/acelib status` 命令系統、Smoke harness 等）皆配備 JUnit 5 自動化測試，
並用 Gradle build jar 內 `plugin.yml` 對 build artifact 做 descriptor 契約驗證。

### Unit / Integration（跑得動、可重現、無外部依賴）

- **`PlatformDetectorTest`**：用 `ClassLoader` 子類隔離真實 classpath，
  覆蓋 FOLIA / PAPER / UNKNOWN 三種情境。
- **`AceLibPluginTest`**：以 MockBukkit 4.x 模擬 Bukkit 環境，
  覆蓋 `onEnable` / `onDisable` / `reload` 冪等性與邊界條件。
- **`AceLibStatusCommandTest`**：以 MockBukkit 4.x 模擬完整 plugin lifecycle，
  驗證 `/acelib status` 的指令註冊、權限檢查、tab complete、disable 卸載。
- **`SmokeScriptTest`**（v0.1.0）：用 `ProcessBuilder` spawn `bash` 驗證
  `scripts/smoke-server.sh` 的 syntax、`--help`、無效平台、缺 `SERVER_JAR`
  拒絕、以及 strict mode 設定。
- **`PluginDescriptorJarTest`**（v0.1.0）：開啟 `build/libs/AceLib-*.jar`
  並用 SnakeYAML 解析 `plugin.yml`，驗證 `name` / `main` / `folia-supported` /
  `commands.acelib` / `permissions.acelib.admin` 等 build artifact 契約。

### 跑全部測試

```bash
./gradlew test                                     # 全套
./gradlew test --tests 'com.smile.acelib.milestone.*'   # 只跑 v0.1.0 milestone
./gradlew test --tests 'com.smile.acelib.command.AceLibStatusCommandTest*'
./gradlew test --info                              # 含詳細 stdout
```

### 跑 milestone smoke script 測試的子集

```bash
./gradlew test --tests 'com.smile.acelib.milestone.SmokeScriptTest*'
./gradlew test --tests 'com.smile.acelib.milestone.PluginDescriptorJarTest*'
```

> 注意：`PluginDescriptorJarTest` 會在 `@BeforeAll` 掃 `build/libs/`；
> 若 jar 不存在測試會 fail；先跑一次 `./gradlew jar` 或 `./gradlew build` 即可。

---

## 伺服器 smoke 操作（v0.1.0 milestone）

`scripts/smoke-server.sh` 是 AceLib 在 Paper / Folia 兩個正式支援路徑下的
runtime smoke harness：在隔離 temporary runtime 目錄內啟動真實服務端，
驗證 AceLib jar 被載入、plugin 印出 `AceLib <version> enabled on <platform>`、
然後送 stop 乾淨退出。

### 必要前置

- **JDK 25**（已由 Gradle toolchain 自動下載）
- **真實 server jar**：必須由呼叫端提供（推薦直接從
  [PaperMC Downloads](https://papermc.io/downloads/paper) 或
  [Folia Releases](https://github.com/PaperMC/Folia/releases) 下載對應版本）

> 此腳本**預設不下載**任何 binary，避免在 CI / 開發者機器偷偷拉數百 MB。
> 只有顯式傳 `--download` 才會走預先驗證過的固定 URL + SHA-256 路徑
> （Paper 26.1.2 build 72 / Folia 26.1.2 build 8）。

### 用法

```bash
# 最常見：本地已有 server jar
SERVER_JAR=/path/to/paper-26.1.2.jar \
    ./scripts/smoke-server.sh paper

# Folia 環境
SERVER_JAR=/path/to/folia-26.1.2.jar \
    ./scripts/smoke-server.sh folia

# 沒 server jar 但願意讓腳本下載並校驗（需網路）
./scripts/smoke-server.sh folia --download

# 看說明
./scripts/smoke-server.sh --help
```

腳本會：

1. 建 `mktemp -d` runtime，把 `build/libs/AceLib-*.jar` 複製進 `plugins/`，寫
   `eula=true`。
2. 以 `java -jar <server>` 啟動服務端，FIFO 餵 stdin。
3. 等 plugin log 出現 `AceLib <version> enabled on <Paper|Folia>` 才送 `stop`。
4. 服務端退出後驗證 plugin log 有 disable 紀錄、trap 清掉 runtime。
5. 任一步失敗：印最後 200 行 server log、trap 清掉 runtime、exit 非零。

### 真實 smoke 並沒有「自動化」

腳本測試（`SmokeScriptTest`）只覆蓋 **可離線驗證**的路徑（語法檢查、
`--help`、無效平台、缺 `SERVER_JAR`），不啟動真實 Minecraft 服務端；
要跑真正的兩平台 smoke 必須手動或由 CI 呼叫，請確保：

- **網路**（用 `--download` 時）或**本機已有對應 server jar**
- **CPU / 記憶體** 足夠跑 JVM（腳本預設 `-Xms512m -Xmx1024m`）
- **預期耗時**：每個平台約 30~90 秒（受 startup / stop 速度影響）

不要把「`./gradlew test` 通過」誤讀為「在 Paper / Folia 上已實際啟動並驗證」；
前者只證明合約與 script 健全，後者需要真的跑 smoke harness。

---

## `/acelib status` 使用（v0.1.0）

`/acelib status` 是 v0.1.0 milestone 暴露給伺服器管理員的最小診斷指令，
由 `plugin.yml` 內 `commands.acelib`（aliases: `alib`）宣告，需要
`acelib.admin` 權限節點（預設只給 op）。

```
/acelib status
```

執行後 sender 會收到一段結構化報告：

- **Version**：AceLib 版本字串（與 `build.gradle.kts` 的 `version` 同步）
- **Platform**：當前平台（`Paper` / `Folia`）
- **Ready**：`true` 表示 `onEnable` 已成功完成
- **Modules**：核心模組摘要（scheduler / config / context / message 等）
- **Errors**：自啟動以來的錯誤統計

沒有權限時玩家會收到 plugin.yml 內定義的 `permission-message`；console 預設
擁有 op 等級權限，可以直接執行。tab complete 會列出 `status` 子指令。

---

## v0.1.0 範圍與限制

本里程碑已完成的核心能力（皆由測試覆蓋）：

- **平台偵測**：`PlatformDetector` 透過 classpath reflection 判定 Folia / Paper / Unknown。
- **Lifecycle Manager**：`AceLibPlugin` 的 `onEnable` / `onDisable` / reload 冪等性。
- **安全排程器**：`SafeSchedulerImpl` 統一 Folia `RegionizedServer` 與 Paper
  `BukkitScheduler`，避免在 Folia 環境誤用全域 scheduler。
- **執行緒上下文安全**：`ContextSafetyGuard` 阻擋在不安全執行緒操作實體。
- **設定檔 / 訊息系統 / 指令系統 / 事件管理 / 玩家狀態 / 診斷**：依
  `plan/acelib-bootstrap-plan-20260701-001` Phase 4–9 範圍完成。
- **`/acelib status` 管理指令**：見上一節。
- **Paper 與 Folia 雙路徑 smoke harness**：`scripts/smoke-server.sh`。

**未實作（v0.1.0 範圍外）**：完整大型 GUI 框架、自製經濟系統、自製權限系統、
跨服資料同步、Web 後台、自動更新器、複雜 ORM、大型命令語法框架、
 分散式訊息佇列、過度抽象的插件框架設計。詳細清單與理由見 Plan §三 (2)。

---

## 授權

本專案以 **MIT License** 發布 — 詳見 [LICENSE](LICENSE) 檔案。

```
MIT License
Copyright (c) 2026 Smile
```
