# 在 plugin 中使用 AceLib

這份教學會建立一個以 Java 25 編譯、可在 Paper 或 Folia 26.1.2 載入的下游 plugin。

## 1. 加入 Gradle dependency

`build.gradle.kts` 的最小設定如下：

```kotlin
plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    maven("https://jitpack.io")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.github.smile-minecraft:AceLib:v1.0.0")
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")
}
```

AceLib 使用 `compileOnly`，因為執行時會由 server 的 `plugins/AceLib-1.0.0.jar` 提供。JitPack `v1.0.0` 已在不含 `mavenLocal()` 的乾淨環境解析並編譯成功。

若 Gradle 找不到 AceLib，先確認 repository URL 是 `https://jitpack.io`，座標的 group 是 `com.github.smile-minecraft`，版本包含 `v`：`v1.0.0`。

## 2. 宣告 server dependency

在你的 `src/main/resources/plugin.yml` 加入 `depend: [AceLib]`：

```yaml
name: MyPlugin
main: com.example.myplugin.MyPlugin
version: 1.0.0
api-version: '26.1.2'
folia-supported: true
depend: [AceLib]
```

伺服器會先啟用 AceLib。若 `plugins/` 中沒有 AceLib，或 AceLib 啟用失敗，你的 plugin 不會載入。

## 3. 取得 provider

在 `JavaPlugin` 的 `onEnable()` 中透過 `ServicesManager` 取得 provider：

```java
package com.example.myplugin;

import com.smile.acelib.AceLibApi;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class MyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
            getServer().getServicesManager()
                .getRegistration(AceLibApi.AceLibProvider.class);

        if (registration == null) {
            getLogger().severe("AceLib provider 未註冊；停用本 plugin。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        AceLibApi api = registration.getProvider().api();
        if (!api.isReady()) {
            getLogger().severe("AceLib 尚未就緒；停用本 plugin。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("AceLib " + api.getVersion()
            + " on " + api.getPlatform().getDisplayName());
    }
}
```

成功編譯並部署兩個 JAR 後，啟動日誌會出現 AceLib 版本與偵測到的 `Paper` 或 `Folia`。

`provider.api()` 不會回傳 `null`，但 AceLib 停用後 `isReady()` 會變成 `false`。不要把 `AceLibApi` 永久快取後假設它不會改變；完整做法請看 [Provider 生命週期](provider-lifecycle.md)。

## 常見失敗

### `Could not find com.github.smile-minecraft:AceLib:v1.0.0`

檢查是否加入 JitPack、大小寫是否為 `AceLib`，以及版本前面的 `v` 是否保留。不要改用 `com.smile:acelib:1.0.0`；那是 repository 貢獻者在本機執行 `publishToMavenLocal` 後才有的座標。

### Server 顯示 missing dependency

你的 plugin JAR 已放進 `plugins/`，但 AceLib runtime JAR 不在。管理員可依[部署指南](../operator/README.md)從原始碼建立 `build/libs/AceLib-1.0.0.jar`。

### Provider 是 `null`

AceLib 沒有成功啟用，或已經停用。查看 AceLib 啟動日誌，不要直接依賴 `AceLibPlugin` 或自行 cast plugin instance。

### `isReady()` 是 `false`

API 物件存在，但服務目前不可用。停止使用 AceLib 服務，改走你自己的降級路徑，或停用下游 plugin。

### Folia 出現 context 錯誤

玩家、實體與方塊操作必須在所屬 region 執行。不要把全域 `BukkitScheduler` 當成 Folia 的預設路徑；請從[排程](../modules/scheduler.md)與[上下文安全](../modules/context.md)開始。

## Repository 內的本機開發

若你正在修改 AceLib 本身，可先發布本機 Maven 產物，再編譯附帶的 consumer 範例：

```bash
./gradlew publishToMavenLocal
./gradlew -p examples/consumer-plugin build --no-daemon --console=plain
```

該範例使用 `mavenLocal()` 與 `com.smile:acelib:1.0.0`，只用於驗證目前 checkout 的程式碼。一般 plugin 專案請使用本頁前面的 JitPack 座標。
