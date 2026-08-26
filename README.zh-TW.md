[English](README.md) · 繁體中文

# AceLib

AceLib 是給 Paper 與 Folia 插件共用的基礎函式庫。它提供安全排程、執行緒上下文、設定、訊息、指令、事件、資料、玩家狀態、世界操作、GUI、物品、外部整合與診斷 API。

目前版本是 **1.1.0**（新增基岩版玩家支援）。repository 已公開，`v1.1.0` 已作為 [GitHub Release](https://github.com/smile-minecraft/AceLib/releases/tag/v1.1.0) 發布；歷史版本詳情見 CHANGELOG。

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
    compileOnly("com.github.smile-minecraft:AceLib:v1.1.0")
}
```

這個 JitPack 座標已可解析。完整且可編譯的 Gradle 設定請看[快速開始](docs/consumer/quickstart.md)。

## 設定 `plugin.yml`

你的 plugin 必須宣告 AceLib 為必要依賴：

```yaml
name: MyPlugin
main: com.example.myplugin.MyPlugin
version: 1.1.0
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

| 任務分組 | 文件 | 用途 |
| --- | --- | --- |
| 入門 | [插件開發者快速開始](docs/consumer/quickstart.md) | 第一次整合 AceLib——設定 Gradle、宣告依賴並取得 `AceLibProvider` |
| 入門 | [如何取得 AceLib](docs/reference/release-artifacts.md) | 確認 repository 已公開狀態並取得 JitPack 座標 `com.github.smile-minecraft:AceLib:v1.1.0` |
| 日常整合 | [模組指南](docs/modules/) | 按子系統查詢——排程、上下文、設定、訊息、指令、事件、資料、玩家、世界、GUI、物品與外部整合 |
| 日常整合 | [Provider 生命週期](docs/consumer/provider-lifecycle.md) | 正確處理重載與停用，適合長時間執行的插件 |
| 日常整合 | [錯誤碼](docs/reference/error-codes.md) | 查詢 `ACELIB-<AREA>-<CODE>` 涵義與訊息五要件 |
| 部署維運 | [伺服器管理員指南](docs/operator/README.md) | 從原始碼建置伺服器 jar 並完成部署 |
| 部署維運 | [相容性說明](docs/consumer/compatibility.md) | 確認已驗證基線（Java 25／Paper 26.1.2／Folia 26.1.2）與 26.2 尚未支援原因 |
| 參考 | [貢獻者指南](docs/contributor/README.md) | 貢獻流程、驗證門禁與文件風格 |
| 參考 | [變更紀錄](CHANGELOG.md) | 版本歷史與更新指引 |
| 參考 | [AI 檢索索引 (llms.txt)](llms.txt) | 給 AI Agent 的完整文件索引 |

## 重要限制

- GitHub Release 沒有可下載的 server plugin JAR。管理員需依[部署步驟](docs/operator/README.md)從原始碼建置 `AceLib-1.1.0.jar`。
- AceLib 不支援 Bukkit `/reload`。文件中的 AceLib reload 是函式庫自己的生命週期操作，兩者不同。
- MockBukkit 測試不能取代 Folia 真實 region scheduler 的執行驗證。
- 日誌中的對外錯誤使用 `ACELIB-<AREA>-<CODE>` 格式，可在[錯誤碼頁](docs/reference/error-codes.md)查詢。
- 基岩（Geyser/Floodgate）玩家有平台限制——聊天連結不可點擊、GUI 無法區分左右鍵，詳見[模組指南的 bedrock 頁](docs/modules/bedrock.md)。

## MIT License

AceLib 以 [MIT License](LICENSE) 發布。
