# Quick Start：讓下游插件取得 AceLib API

本頁解決下游插件如何加入 AceLib、確保載入順序，以及在啟用時取得可用 API 的問題。

## 預期結果

下游插件能編譯，`plugin.yml` 含 `depend: [AceLib]`，啟用時經 `ServicesManager` 取得 `AceLibApi.AceLibProvider`，並在使用前確認 `isReady()`。

## 1. 前置條件

| 元件 | 版本 | 說明 |
| --- | --- | --- |
| JDK | 25+ | Paper 26.1.2（目前基線）的最低需求 |
| Paper / Folia API | 26.1.2 | 目前支援基線（已驗證；26.2 尚未驗證） |
| Gradle | 9.5.1+ | 可用任意 wrapper / 版本（toolchain 會自動下載 JDK 25） |

## 2. 加入 dependency

AceLib 目前（`1.0.0` Release Candidate）**尚未發布**到外部 repository。先在 AceLib 根目錄建立本機 artifact：

```bash
# 在 AceLib 根目錄：把最新 artifact 發布到本機 Maven repository
./gradlew publishToMavenLocal
```

下游 `build.gradle.kts`：

```kotlin
repositories {
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.smile:acelib:1.0.0")
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")
}
```

> 正式發布後（未來 `v1.0.0`）會改為外部 Maven / JitPack 座標；目前
> `mavenLocal()` 是唯一可重現的解析方式。

## 3. 宣告 `depend: [AceLib]`

`plugin.yml`：

```yaml
name: MyPlugin
main: com.example.myplugin.MyPlugin
version: 1.0.0
api-version: '26.1.2'
folia-supported: true
load: POSTWORLD
depend:
  - AceLib
```

`depend` 的意義：

- **載入順序保證**：伺服器先載入並 enable AceLib，才載入下游 plugin。
- **前置檢查**：AceLib 不在 `plugins/` 或 enable 失敗時，伺服器**拒絕載入**
  下游 plugin（logs 會顯示 missing dependency）。

`depend` 不是唯一防禦：AceLib 可能「已載入但尚未 ready」或「被 disable」，
這兩種情境 `depend` 攔不住，必須在 runtime 檢查（見下節）。

## 4. 取得 `AceLibApi.AceLibProvider`

正式取得入口是 Bukkit/Paper `ServicesManager` 註冊的
**`AceLibApi.AceLibProvider`**。`ServicesManager` 是插件服務的登錄表；provider 是登錄表中的 AceLib 入口。

```java
import com.smile.acelib.AceLibApi;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class MyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
            getServer().getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);

        if (registration == null) {
            // AceLib 尚未 enable 或已 disable：depend 攔不到的 runtime 情境
            getLogger().warning("AceLib provider 未註冊；停用本 plugin。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        AceLibApi api = registration.getProvider().api();
        if (!api.isReady()) {
            // AceLib 存在但尚未 ready（例如剛 reload 或已 shutdown）
            getLogger().warning("AceLib 尚未 ready；停用本 plugin。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 之後可安全使用 api
        getLogger().info("AceLib " + api.getVersion()
            + " on " + api.getPlatform().getDisplayName());
    }
}
```

**禁止**：

- `import com.smile.acelib.AceLibPlugin` + unchecked cast（`AceLibPlugin` 是
  Internal 類別，非穩定消費者契約）。
- `AceLib.getApi()` static 呼叫（`com.smile.acelib.AceLib` 不存在）。

## 5. 使用 facade 與平台分支

`AceLibApi` 是不可變 facade（把內部實作包成穩定 API 外觀），永不回傳 null 的 service 包括：

| 方法 | 用途 | 未 ready / shutdown 時 |
| --- | --- | --- |
| `getVersion()` | AceLib 版本字串 | 仍回傳版本 |
| `getPlatform()` | `FOLIA` / `PAPER` / `UNKNOWN` | `UNKNOWN` |
| `getPlatformCapability()` | 平台能力 profile（record） | 全 false |
| `getWorldService()` | 世界操作安全 facade | `NOT_READY` / `SHUTDOWN`（操作被拒絕並帶錯誤碼） |
| `getGuiService()` | GUI 安全 facade | `NOT_READY` / `SHUTDOWN` |
| `getExternalIntegrationService()` | 外部整合查詢 facade | `NOT_READY` / `SHUTDOWN` |

平台分支範例：

```java
if (api.getPlatformCapability().regionScheduling()) {
    // Folia：操作實體 / 方塊 / 玩家必須在 region thread，走 AceLib 安全排程 API
} else if (api.getPlatformCapability().globalScheduler()) {
    // Paper：可安全使用全域 BukkitScheduler
}
```

Folia 環境下不得以 `Bukkit.getServer().getScheduler()` 作為預設路徑；
詳見 [provider 生命週期](provider-lifecycle.md#3-thread-context-與-folia-safe)。

## 6. 可編譯範例（fixture）

完整、可重現的 consumer plugin 位於
[examples/consumer-plugin/](../../examples/consumer-plugin/README.md)：

```bash
# 前置：發布 AceLib 到本機 Maven
./gradlew publishToMavenLocal

# 編譯 consumer fixture（驗證正式 provider contract）
./gradlew -p examples/consumer-plugin compileJava --console=plain

# 完整驗證（compile + docs 檢查）
./gradlew -p examples/consumer-plugin build --console=plain
```

fixture 同時證明「舊 README 教的 `AceLib.getApi()` 無法編譯」—
把範例改成 stale contract 會得到 `cannot find symbol: class AceLib`。

## 常見失敗

- provider 為 `null`：AceLib 未啟用或已停用；不要直接取 API，停用下游插件或走 fallback。
- `isReady()` 為 `false`：API facade 存在，但目前不可用；不要繼續呼叫 service。
- 編譯找不到 `AceLib`：不要使用不存在的 `AceLib.getApi()`，改用 `ServicesManager` 入口。
- Folia 發生上下文錯誤：不要以 `Bukkit.getServer().getScheduler()` 作為預設路徑，改用安全排程 API。

## 下一步

- [provider 生命週期](provider-lifecycle.md)：missing / not-ready / reload / disable 深度說明
- [相容性與發布狀態](compatibility.md)：Java 25 / 26.1.2 基線、26.2 未驗證、發布時程
- [API 分類契約](../reference/api-surface.md)：Supported / SPI / Internal 分類
- [docs 導航](../../docs/README.md)：三類受眾路徑
