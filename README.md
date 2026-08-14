# AceLib

> AceLib 是 Smile 系列 Minecraft 插件共用的基礎函式庫。目前版本為 `1.0.0` Release Candidate，尚未對外發布；repository 仍為 private。
>
> 支援 **Paper 26.1.2 / Folia 26.1.2**，需要 **Java 25**。

---

## 先找到需要的操作

依照目標選擇入口：

| 讀者 | 要完成的工作 | 直接看 |
| --- | --- | --- |
| 插件開發者 | 用 AceLib 寫自己的插件 | 下面「快速開始」→ [docs/consumer/](docs/consumer/README.md) |
| 貢獻者 | 改 AceLib 原始碼、文件或測試 | [docs/contributor/](docs/contributor/README.md) |
| 伺服器管理員 | 部署、診斷、讀錯誤碼 | [docs/operator/](docs/operator/README.md) |

---

## 這頁解決什麼問題

本頁提供三條最短路徑：下游插件接入 AceLib、伺服器管理員確認部署狀態，以及貢獻者找到測試與文件規則。AceLib 將平台偵測、安全排程、玩家／世界操作、設定、訊息、指令、事件與診斷整理成可重用 API。

第一次接觸時，以下術語最重要：

- **提供者（provider）**：AceLib 啟動後註冊在 Bukkit `ServicesManager` 的入口物件。`ServicesManager` 是插件互相提供服務的登錄表；下游插件從 provider 取得 `AceLibApi`。
- **對外門面（facade）**：`AceLibApi` 把內部實作包起來，只提供下游可使用的方法。下游程式只應依賴這個 API，不直接碰 AceLib 內部類別。
- **Folia region**：Folia 將世界分成多個區域，每個區域有自己的執行緒。玩家、實體與方塊必須在所屬 region 執行緒上操作。
- **生命週期（lifecycle）**：插件的 enable、disable 與 AceLib 內部 reload。API 會反映目前是否 ready。

關鍵設計：**Folia 優先**。AceLib 的 API 從一開始就按 Folia 的規則設計，在 Paper 上則走相容路徑；可避免在 Folia 上誤用 Paper 專有的全域排程。

---

## 快速開始（將 AceLib 接入下游插件）

預期結果：下游插件能編譯，並在啟用時取得 AceLib API。

開始前要有：

- **Java 25**（開發機或 CI 有 JDK 25；AceLib 的 Gradle 也會自動下載）。
- **AceLib 本機 artifact**：因為 `1.0.0` 還沒發布到外部倉庫，先在本機產生一份（見下方「取得 AceLib」）。
- 下游插件的 `plugin.yml` 宣告 `depend: [AceLib]`，保證 AceLib 先載入。

### 第一步：宣告載入順序

`plugin.yml` 裡加 `depend`：

```yaml
name: MyPlugin
main: com.example.myplugin.MyPlugin
api-version: '26.1.2'
folia-supported: true
depend:
  - AceLib
```

`depend` 做兩件事：保證 **載入順序**（AceLib 先啟用，下游插件才啟用），以及 **前置檢查**（AceLib 不在 `plugins/` 或啟用失敗時，伺服器會拒絕載入下游插件）。但它擋不住「AceLib 已載入卻還沒 ready／已經被停用」這兩種執行期狀況，因此呼叫端仍需在程式裡檢查（見下一步）。

### 第二步：從服務註冊表拿到 provider

在 `onEnable()` 裡，透過 Bukkit 的服務註冊表取得 AceLib 的 provider，**不要** `import com.smile.acelib.AceLibPlugin`、**不要**用不存在的 `AceLib.getApi()`：

```java
import com.smile.acelib.AceLibApi;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class MyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // 1. 從服務註冊表取得 AceLib 的 provider（AceLib 啟用後才會註冊）
        RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
            getServer().getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);

        // 2. 拿不到：AceLib 尚未啟用或已停用（depend 擋不住的執行期狀況）
        if (registration == null) {
            getLogger().warning("AceLib provider 未註冊；停用本插件。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. api() 永不回傳 null，但停用後 isReady() 會是 false，必須檢查
        AceLibApi api = registration.getProvider().api();
        if (!api.isReady()) {
            getLogger().warning("AceLib 尚未 ready；停用本插件。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("AceLib " + api.getVersion()
            + " on " + api.getPlatform().getDisplayName());
    }
}
```

`api.isReady()` 反映 AceLib 的目前狀態：`true` 表示已啟用且可用；`false` 表示尚未啟用或已停用。即使 `api()` 不為 null，**停用後呼叫服務仍會被拒絕**，所以每次要用之前都檢查 `isReady()`。

### 第三步：用 facade 操作，並依平台分流

`AceLibApi` 提供這些永不為 null 的方法：

| 方法 | 用途 |
| --- | --- |
| `getVersion()` | AceLib 版本字串（與 `plugin.yml` 一致） |
| `getPlatform()` | 目前平台：`FOLIA` / `PAPER` / `UNKNOWN` |
| `getPlatformCapability()` | 平台能力（Folia 區域排程／Paper 全域排程等） |
| `getWorldService()` / `getGuiService()` / `getExternalIntegrationService()` | 各項安全功能（停用時操作會被拒絕並帶錯誤碼） |

依平台能力選擇排程路徑：

```java
if (api.getPlatformCapability().regionScheduling()) {
    // Folia：操作玩家／實體／方塊必須在該區域的執行緒，走 AceLib 安全排程 API
} else if (api.getPlatformCapability().globalScheduler()) {
    // Paper：可用全域 BukkitScheduler
}
```

完整的可編譯範例在 [examples/consumer-plugin/](examples/consumer-plugin/README.md)。更細的生命週期與錯誤處理看 [docs/consumer/quickstart.md](docs/consumer/quickstart.md) 與 [docs/consumer/provider-lifecycle.md](docs/consumer/provider-lifecycle.md)。

---

## 環境需求

| 元件 | 版本 | 備註 |
| --- | --- | --- |
| JDK | **25+** | Paper 26.1 系列最低需求；AceLib 用 Foojay JDK Resolver 自動下載 toolchain |
| Paper | **26.1.2**（已驗證基線） | https://papermc.io/downloads/paper |
| Folia | **26.1.2**（已驗證基線） | https://github.com/PaperMC/Folia/releases |
| Gradle | 9.5.1（隨附 `gradlew`） | 不需要手動安裝 |
| MockBukkit | 4.113.1（測試用） | 由 `build.gradle.kts` 引入 |

> Java 25 是 Paper 26.1 系列（目前基線 26.1.2）的最低需求。本專案透過 Foojay JDK Resolver Convention 插件自動下載缺少的 JDK，不用手動安裝。
>
> **支援基線是 Paper / Folia 26.1.2**：`26.2` 還沒在真實執行環境驗證過，不能當成已支援。

---

## 取得 AceLib（本機，因為還沒發布）

下游插件的座標是 **`com.smile:acelib:1.0.0`**。目前 artifact 還沒發布到外部倉庫，本機驗證流程如下（也是 [consumer fixture](examples/consumer-plugin/README.md) 用的流程）：

```bash
# 1. 在 AceLib 根目錄，把最新 artifact 發布到本機 Maven repository
./gradlew publishToMavenLocal

# 2. 下游專案的 repositories 加入 mavenLocal()
repositories {
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
}

# 3. dependencies 宣告（runtime 由伺服器提供，compileOnly 即可）
dependencies {
    compileOnly("com.smile:acelib:1.0.0")
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")
}
```

`1.0.0` 為 Release Candidate、**尚未發布**：repository 仍為 private，外部 Maven / JitPack artifact 還沒產生。本文件一律以「未發布」與 `1.0.0` 為準；`v1.0.0` 正式 git 標籤與外部發布，只會在發布流程完成後才成立。

---

## 平台差異：Folia 與 Paper

`com.smile.acelib.platform.PlatformDetector` 透過 classpath 反射判定目前平台（**不**依賴 Bukkit 執行期 API，所以能在純 classpath 隔離的單元測試裡運作）。判定順序：

1. 找到 `io.papermc.paper.threadedregions.RegionizedServer` → **Folia**
2. 否則找到 `org.bukkit.Bukkit` → **Paper / CraftBukkit 相容**
3. 否則 → **Unknown**

| 平台 | 顯示名 | 典型環境 |
| --- | --- | --- |
| `FOLIA` | `Folia` | PaperMC/Folia 服務端（區域化） |
| `PAPER` | `Paper` | Paper / Paper 相容衍生（Purpur 等） |
| `UNKNOWN` | `Unknown` | 純 classpath 測試 / 未對應的環境 |

### Folia 環境的限制

在 Folia 上，這些操作受限制：

1. **不能用全域 BukkitScheduler 當預設路徑**：必須走 `RegionizedServer` 路徑，或 AceLib 提供的安全 API。
2. **執行緒上下文**：操作玩家、實體、方塊必須在該區域的執行緒上做；非區域執行緒的修改會觸發 `ACELIB-CTX-003`。
3. **事件監聽**：標記 `REQUIRES_REGION` 的 listener 必須在區域執行緒被觸發，否則會被略過並記錄 `ACELIB-EVT-005`。
4. **訊息傳送**：`Player#sendMessage` 必須在區域上下文執行，否則觸發 `ACELIB-MSG-002`。

平台能力（`PlatformCapability`，一個不可變的資料物件）描述目前平台能做什麼：

| 能力 | Folia | Paper | Unknown |
| --- | --- | --- | --- |
| `regionScheduling`（區域排程） | `true` | `false` | `false` |
| `globalScheduler`（全域排程） | `true` | `true` | `false` |
| `bukkitApi` | `true` | `true` | `false` |
| `foliaThreadedRegionsApi` | `true` | `false` | `false` |

---

## 診斷與錯誤碼

### `/acelib status` 指令（給管理員）

`/acelib status` 是給伺服器管理員看的診斷指令（權限 `acelib.admin`，預設只有 op 能執行；console 預設有權限）。它印出版本、平台、各模組狀態與錯誤統計。

```
=== AceLib Diagnostics Report ===
Version: 1.0.0
Platform: Folia
Capability: globalScheduler=true, bukkitApi=true
Ready: true
Modules:
  scheduler: READY - tracked=3
  config: READY - bound to config
Errors:
  (no errors)
```

模組狀態意義：`READY`（已綁定可運作）、`NOT_INITIALIZED`（尚未綁定）、`UNAVAILABLE`（綁定失敗／依賴缺失）、`FAILED`（運作中異常）、`DEGRADED`（部分可用、走備案）。

### 錯誤分類與代碼

AceLib 所有對外拋出或記錄的錯誤，都帶 `ACELIB-<AREA>-<CODE>` 格式的分類碼，方便看日誌時對照。代碼大小寫敏感（`ACELIB-SCHED-001` 正確，`acelib-sched-001` 會被歸類為 UNKNOWN）。

> 這張表是**人類可讀鏡像**；唯一可編輯來源是各模組的 `*ErrorCodes` 常數類與 `diagnostics.ErrorCodeRegistry`。改錯誤碼要以原始碼為準。

| 分類（AREA） | 前綴 | 說明 |
| --- | --- | --- |
| `PLAT` | `ACELIB-PLAT-*` | 平台偵測 |
| `SCHED` | `ACELIB-SCHED-*` | 排程 |
| `CTX` | `ACELIB-CTX-*` | 執行緒上下文安全 |
| `CFG` | `ACELIB-CFG-*` | 設定檔 |
| `LANG` | `ACELIB-LANG-*` | 語言檔 |
| `MSG` | `ACELIB-MSG-*` | 訊息服務 |
| `CMD` | `ACELIB-CMD-*` | 指令系統 |
| `EVT` | `ACELIB-EVT-*` | 事件管理 |
| `DATA` | `ACELIB-DATA-*` | 資料儲存 |
| `PLAYER` | `ACELIB-PLAYER-*` | 玩家狀態 |
| `WORLD` | `ACELIB-WORLD-*` | 世界操作 |
| `GUI` | `ACELIB-GUI-*` | GUI |
| `ITEM` | `ACELIB-ITEM-*` | Item |
| `EXT` | `ACELIB-EXT-*` | 外部整合 |
| `DBG` | `ACELIB-DBG-*` | 診斷模組自身 |

#### 排程器（SCHED）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-SCHED-001` | 任務內部拋出例外 | 使用者的 Runnable 執行時拋錯 |
| `ACELIB-SCHED-002` | 目標玩家已離線 | 排程任務執行時玩家已離線 |
| `ACELIB-SCHED-003` | 目標實體已失效 | 排程任務執行時實體已移除／死亡 |
| `ACELIB-SCHED-004` | 目標 chunk 尚未載入 | 排程任務執行時 chunk 不可用 |
| `ACELIB-SCHED-005` | 目前平台不支援此排程模式 | Folia-only 操作在 Paper 上執行 |
| `ACELIB-SCHED-006` | 插件已停用 | `onDisable` 後所有後續任務 no-op |

#### 執行緒上下文（CTX）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-CTX-001` | 執行緒上下文不安全 | 在 async thread 修改遊戲物件 |
| `ACELIB-CTX-002` | 在非同步流程後直接操作玩家／實體 | 從 main thread 送出 runAsync 後修改 |
| `ACELIB-CTX-003` | Folia 下非區域執行緒操作區域綁定物件 | Folia 上在錯誤上下文操作實體 |
| `ACELIB-CTX-004` | 平台不支援此操作 | UNKNOWN 平台上嘗試任何修改操作 |

#### 設定檔（CFG）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-CFG-001` | 設定檔不存在或無法生成 | 首次啟動時預設設定寫入失敗 |
| `ACELIB-CFG-002` | 設定檔 YAML 格式錯誤 | 設定檔內容無法解析 |
| `ACELIB-CFG-003` | reload 失敗且無舊值可回退 | 設定 reload 失敗且沒有先前有效值 |
| `ACELIB-CFG-004` | 設定檔版本遷移失敗 | 設定 migration chain 中任一步驟失敗 |
| `ACELIB-CFG-005` | 必填欄位缺失 | 設定檔缺少必要欄位 |

#### 訊息服務（MSG）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-MSG-001` | 訊息 key 缺失 | 查詢的 locale key 不存在 |
| `ACELIB-MSG-002` | 在不安全上下文操作玩家訊息（Folia） | Folia 下非區域執行緒傳送訊息 |
| `ACELIB-MSG-003` | 訊息格式錯誤 | LangManager 抓不到物件或格式異常 |

#### 指令系統（CMD）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-CMD-001` | 缺少必要參數 | minArgs 未滿足 |
| `ACELIB-CMD-002` | 未知的子指令 | 無對應 SubCommandSpec |
| `ACELIB-CMD-003` | 沒有權限執行 | 權限檢查失敗 |
| `ACELIB-CMD-004` | 此指令僅限玩家 | console 觸發時拒絕 |
| `ACELIB-CMD-005` | 此指令僅限 console | 玩家觸發時拒絕 |
| `ACELIB-CMD-006` | 冷卻中 | 防止重複觸發 |
| `ACELIB-CMD-007` | 玩家已離線／失效 | 目標玩家離線 |
| `ACELIB-CMD-008` | 非同步指令流程失敗 | async 執行異常 |
| `ACELIB-CMD-009` | 指令註冊服務已停用 | plugin disable 後 |
| `ACELIB-CMD-010` | caller 自訂錯誤碼 | 由 caller 給 code |
| `ACELIB-CMD-011` | 玩家回覆 backend 不可用 | 非 AceLib owner 無法區域安全派送 |

#### 事件管理（EVT）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-EVT-001` | listener handler 內部拋例外 | 事件處理器拋錯（不影響其他 listener） |
| `ACELIB-EVT-002` | Event class 註冊到 PluginManager 失敗 | dispatch 失敗 |
| `ACELIB-EVT-003` | 重複註冊 | 已存在的 identity 重複註冊 |
| `ACELIB-EVT-004` | 插件停用 | disabled 後 register／dispatch |
| `ACELIB-EVT-005` | Folia 下 REQUIRES_REGION listener 在錯誤上下文 | Folia 上非區域執行緒觸發區域綁定 listener |
| `ACELIB-EVT-006` | Host plugin 尚未 enabled | 註冊時 owner plugin 尚未啟用 |

#### 資料儲存（DATA）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-DATA-001` | IO 失敗 | 檔案無法讀寫、目錄無法建立、磁碟空間不足 |
| `ACELIB-DATA-002` | 資料損壞 | 檔案格式錯誤、反序列化失敗、解碼失敗 |
| `ACELIB-DATA-003` | 索引錯誤 | key/path 為 null、空白、不合法 |
| `ACELIB-DATA-004` | 遷移失敗 | migration chain 中任一版本轉換失敗 |
| `ACELIB-DATA-005` | 儲存已關閉 | store 已 close 後仍嘗試操作 |
| `ACELIB-DATA-006` | 序列化失敗 | 型別不支援、循環參考 |
| `ACELIB-DATA-007` | 非同步逾時 | async 等待超過 deadline |
| `ACELIB-DATA-008` | 資料源不可用 | JDBC 連線拒絕、SQL 語法錯誤 |
| `ACELIB-DATA-009` | 無可用 migration | 偵測到舊版本但 chain 中無對應 from |
| `ACELIB-DATA-010` | on-disk schema 版本比 current 新 | 拒絕降版覆寫既有資料 |
| `ACELIB-DATA-011` | 非法 SQL identifier | JdbcDataStore table 名稱驗證失敗 |

#### 玩家狀態（PLAYER）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-PLAYER-001` | 資料尚未就緒 | caller 在 LOADING 階段讀取 |
| `ACELIB-PLAYER-002` | 資料載入失敗 | I/O 或反序列化錯誤 |
| `ACELIB-PLAYER-003` | 資料保存失敗 | I/O 或序列化錯誤 |
| `ACELIB-PLAYER-004` | session 重複登入 | 同一 UUID 已有 active session |
| `ACELIB-PLAYER-005` | session 未找到 | caller 對未登入 UUID 操作 |
| `ACELIB-PLAYER-006` | DataStore 未初始化 | store 尚未綁定 |
| `ACELIB-PLAYER-007` | 服務已關閉 | disable/shutdown 後呼叫 join/quit |
| `ACELIB-PLAYER-008` | 內部 serial executor 終止失敗 | serial executor 異常關閉 |

#### 世界操作（WORLD）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-WORLD-001` | 服務尚未啟用 | uninitialized／bind 前 |
| `ACELIB-WORLD-002` | 服務已停用 | onDisable／reload 失敗 |
| `ACELIB-WORLD-003` | 指定的 world UUID 不存在於伺服器 | world 找不到 |
| `ACELIB-WORLD-004` | 目標座標所在 chunk 未載入 | chunk 不可用 |
| `ACELIB-WORLD-005` | 目標 entity 已移除／死亡／不在線 | entity 失效 |
| `ACELIB-WORLD-006` | 目標玩家離線 | 玩家離線 |
| `ACELIB-WORLD-007` | 輸入為 null 或語意不合法 | 無效輸入 |
| `ACELIB-WORLD-008` | 目前執行緒不允許修改目標物件 | Folia 上下文違規 |
| `ACELIB-WORLD-009` | 平台不支援此操作 | UNKNOWN／缺失 capability |
| `ACELIB-WORLD-010` | 通用 operation 失敗 | 內部執行拋例外 |
| `ACELIB-WORLD-011` | 效果施展被拒絕 | chunk 未載入／target 不再有效 |
| `ACELIB-WORLD-012` | 鄰近查詢失敗 | 查詢異常 |
| `ACELIB-WORLD-013` | 通用 block 操作失敗 | 如材質不存在 |
| `ACELIB-WORLD-014` | 傳送被 Bukkit 拒絕 | `teleport()` 回傳 false |
| `ACELIB-WORLD-015` | 傳送拋例外 | CompletionStage 異常完成 |
| `ACELIB-WORLD-016` | 跨區域／玩家傳送部分完成 | 第一步成功但第二步失敗 |

#### GUI（GUI）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-GUI-001` | 服務尚未啟用 | uninitialized／bind 前 |
| `ACELIB-GUI-002` | 服務已停用 | onDisable／reload 失敗 |
| `ACELIB-GUI-007` | 輸入為 null 或語意不合法 | 無效輸入 |
| `ACELIB-GUI-008` | 該玩家目前沒有 active session | session 不存在 |
| `ACELIB-GUI-009` | 該玩家已開啟 GUI，重複呼叫被拒絕 | 重複 openInventory |
| `ACELIB-GUI-010` | 玩家嘗試操作受保護 slot | slot 保護觸發 |
| `ACELIB-GUI-011` | 傳入的 generation 與持有 session 不符 | generation 不匹配 |
| `ACELIB-GUI-012` | 通用 operation 失敗 | 內部執行拋例外 |
| `ACELIB-GUI-013` | player context executor 拒絕派送 | scheduler disabled、player offline 等 |
| `ACELIB-GUI-014` | confirm/cancel 對已解決的 action 重複呼叫 | action 一次性失效後再觸發 |
| `ACELIB-GUI-015` | action token 不存在或已過期 | session 關閉／shutdown |
| `ACELIB-GUI-016` | 非同步更新請求已過時 | 舊 request 取代新 request |
| `ACELIB-GUI-017` | 非同步更新結果回來時玩家已離線 | 不得對離線玩家執行 inventory mutation |
| `ACELIB-GUI-018` | 非同步更新結果回來時 inventory 已不匹配 | link generation 不符 |

#### Item（ITEM）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-ITEM-001` | 規格不合法 | null、必填欄位缺失、型別錯誤 |
| `ACELIB-ITEM-002` | 無法解析的 namespace 或 key | null、空白、不合法字元 |
| `ACELIB-ITEM-003` | 不支援的資料型別／值 | 型別錯誤、大小超出限制 |
| `ACELIB-ITEM-004` | migration 失敗 | chain 中任一版本轉換失敗 |
| `ACELIB-ITEM-005` | 反序列化失敗 | 位元組格式錯誤、無對應 schema |

#### 外部整合（EXT）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-EXT-001` | 整合初始化失敗 | adapter 啟動時拋例外 |
| `ACELIB-EXT-002` | 外部插件版本不支援 | 低於需求或無法比較 |
| `ACELIB-EXT-003` | 外部插件未安裝或未啟用 | plugin 不存在或未啟用 |
| `ACELIB-EXT-004` | 整合資源清理失敗 | shutdown 時釋放失敗 |
| `ACELIB-EXT-005` | 整合服務尚未啟用 | facade NOT_READY |
| `ACELIB-EXT-006` | 整合服務已停用 | facade SHUTDOWN |

#### 其他

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-PLAT-001` | 無法識別的伺服器實作 | 平台偵測失敗 |
| `ACELIB-PLAT-004` | 伺服器實作判定失敗 | UNKNOWN 平台 warning |
| `ACELIB-LANG-001` | 訊息 key 缺失 | locale key 不存在（warning，不中斷） |
| `ACELIB-LANG-002` | 語言檔格式錯誤 | YAML 解析失敗 |
| `ACELIB-DBG-001` | 診斷模組自身錯誤 | reload 時 diagnostics 重綁失敗 |

---

## 給貢獻者

想改 AceLib 原始碼、文件或測試？看 [docs/contributor/README.md](docs/contributor/README.md)：那裡有修改規則、測試指令，以及 JavaDoc 寫作規範（含 **doclint**——編譯 Javadoc 時檢查註解是否完整的工具——的相關要求）。

## 給管理員

要部署、跑 smoke、看診斷？看 [docs/operator/README.md](docs/operator/README.md)。重點：

- 啟動後用 `/acelib status` 確認 Platform 正確偵測為 Folia 或 Paper。
- 監控日誌裡的 `ACELIB-` 錯誤碼，特別是 `ACELIB-SCHED-005`（平台不支援）與 `ACELIB-CTX-003`（Folia 上下文違規）。
- AceLib 的 reload 只指內部交易式重載，**不**支援 Bukkit `/reload` 指令；用 Bukkit `/reload` 可能導致狀態不一致。

---

## 限制與未發布狀態

- **`1.0.0` 還沒發布**：repository 為 private，外部 Maven / JitPack artifact 尚未產生；`v1.0.0` 正式標籤與外部發布只會在發布流程完成後成立。
- **Folia 真實執行環境**：MockBukkit 不提供 Folia entity scheduler API，Folia 區域化排程的真實路徑需在 Folia 26.1.2 執行環境上驗證（用 smoke harness）。
- **Vault 整合**：以 reflection-only 方式探測，還沒在真實 Vault 安裝環境驗證完整流程。
- **`26.2` 尚未驗證**：升級 Paper / Folia 前要先做真實執行環境驗證，不能當成已支援。
- **Bukkit `/reload`**：AceLib 不支援，可能導致狀態不一致。

歷史里程碑 `v0.5.0` 的能力範圍仍保留於 [CHANGELOG.md](CHANGELOG.md)；`1.0.0` 是把 `0.5.0-SNAPSHOT` 封存為歷史、以最小範圍同步為 RC，**未改變任何 runtime 行為、public API 簽章、API 範圍或基線**。

---

## 授權

本專案以 **MIT License** 發布 — 詳見 [LICENSE](LICENSE)。

```
MIT License
Copyright (c) 2026 Smile
```
