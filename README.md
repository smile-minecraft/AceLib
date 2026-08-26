# AceLib

AceLib 是給 Paper 與 Folia 插件共用的基礎函式庫。它提供安全排程、執行緒上下文、設定、訊息、指令、事件、資料、玩家狀態、世界操作、GUI、物品、外部整合與診斷 API。

目前穩定版是 **1.0.0**。repository 已公開，GitHub Release `v1.0.0` 已正式發布。

## 支援版本

| 項目 | 版本 |
| --- | --- |
| Java | 25 |
| Paper | 26.1.2 |
| Folia | 26.1.2 |

Paper 與 Folia 26.2 尚未驗證。完整限制請看[相容性說明](docs/consumer/compatibility.md)。

## 在 plugin 中加入 AceLib

將 JitPack repository 與 AceLib API 加入 `build.gradle.kts`：

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.smile-minecraft:AceLib:v1.0.0")
}
```

這個 JitPack 座標已可解析。完整且可編譯的 Gradle 設定請看[快速開始](docs/consumer/quickstart.md)。

## 設定 `plugin.yml`

你的 plugin 必須宣告 AceLib 為必要依賴：

```yaml
name: MyPlugin
main: com.example.myplugin.MyPlugin
version: 1.0.0
api-version: '26.1.2'
folia-supported: true
depend: [AceLib]
```

`depend: [AceLib]` 讓伺服器先啟用 AceLib，再啟用你的 plugin。這是下游 plugin 的設定；AceLib 自己沒有其他必要 plugin 依賴。

## 取得 API

AceLib 透過 Bukkit `ServicesManager` 提供 `AceLibApi.AceLibProvider`：

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

不要直接依賴 `AceLibPlugin`。若你的 plugin 會長時間執行，請再閱讀 [Provider 生命週期](docs/consumer/provider-lifecycle.md)，了解重載與停用時如何重新取得 API。

## 文件

- [插件開發者快速開始](docs/consumer/quickstart.md)
- [伺服器管理員指南](docs/operator/README.md)
- [貢獻者指南](docs/contributor/README.md)
- [模組指南](docs/modules/)
- [如何取得 AceLib](docs/reference/release-artifacts.md)
- [錯誤碼](docs/reference/error-codes.md)
- [變更紀錄](CHANGELOG.md)

## 重要限制

- GitHub Release `v1.0.0` 目前沒有可下載的 server plugin JAR。管理員需依[部署步驟](docs/operator/README.md)從原始碼建置。
- AceLib 不支援 Bukkit `/reload`。文件中的 AceLib reload 是函式庫自己的生命週期操作，兩者不同。
- MockBukkit 測試不能取代 Folia 真實 region scheduler 的執行驗證。
- 日誌中的對外錯誤使用 `ACELIB-<AREA>-<CODE>` 格式，可在[錯誤碼頁](docs/reference/error-codes.md)查詢。
- 基岩（Geyser/Floodgate）玩家有平台限制——聊天連結不可點擊、GUI 無法區分左右鍵，詳見[模組指南的 bedrock 頁](docs/modules/bedrock.md)。

## MIT License

AceLib 以 [MIT License](LICENSE) 發布。
