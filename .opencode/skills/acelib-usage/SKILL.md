---
name: acelib-usage
description: AceLib consumer plugin development and testing. Use when adding AceLib to a Paper/Folia plugin, configuring plugin.yml, retrieving AceLibApi, handling lifecycle or Folia safety, deploying a consumer to the test server, or testing AceLib commands.
---

# AceLib 使用指南

協助插件開發者正確加入、使用與驗證 AceLib。這份 skill 只描述公開 consumer 契約；不要讓下游 plugin 依賴 AceLib 的內部實作。

## 已確認的發布與相容性

- 公開穩定版本：`1.0.0`
- Java：25
- Paper：26.1.2
- Folia：26.1.2
- Paper/Folia 26.2：尚未驗證
- Server plugin JAR：`AceLib-1.0.0.jar`
- 公開下載：https://github.com/smile-minecraft/AceLib/releases/download/v1.0.0/AceLib-1.0.0.jar
- 編譯座標：`com.github.smile-minecraft:AceLib:v1.0.0`
- `com.smile:acelib:1.0.0` 只供 repository 貢獻者使用 `mavenLocal()`，不代表 Maven Central。

伺服器部署與本機 RCON 操作請搭配 `test-server-operations` skill；不要在這份 skill 內重複保存 RCON 密碼。

## Gradle 設定

一般下游 plugin 使用 JitPack，不要依賴 `mavenLocal()`：

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

AceLib 使用 `compileOnly`，因為執行時由伺服器的 `plugins/AceLib-1.0.0.jar` 提供。不要把 AceLib classes 打包進下游 plugin，避免重複 class 與版本衝突。

## plugin.yml

下游 plugin 必須要求 AceLib 先載入：

```yaml
name: MyPlugin
main: com.example.myplugin.MyPlugin
version: 1.0.0
api-version: '26.1.2'
folia-supported: true
depend: [AceLib]
```

`depend: [AceLib]` 是下游 plugin 的設定，不是要修改 AceLib 自己的 `plugin.yml`。

## 取得公開 API

透過 Bukkit `ServicesManager` 取得 `AceLibApi.AceLibProvider`，不要直接 cast `AceLibPlugin`、使用 static singleton，或引用 internal implementation：

```java
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
```

常用公開資訊包括 `getVersion()`、`getPlatform()` 與 `isReady()`；需要其他功能時，以 `AceLibApi` 公開方法與模組文件為準。

## Lifecycle 規則

- `getRegistration(...)` 可能回傳 `null`，例如 AceLib 尚未啟用或已解除註冊。
- `provider.api()` 契約本身不回傳 `null`，但 shutdown 後取得的 facade 會讓 `isReady()` 回傳 `false`。
- 不要永久快取 `AceLibApi` 並假設它永遠有效；在長生命週期流程中重新取得 provider，並在使用服務前檢查 `isReady()`。
- AceLib 自己的 `api.reload()` 是函式庫生命週期操作，不等於 Bukkit `/reload`；不要用 Bukkit `/reload` 測試。
- reload 不提供成功／失敗 boolean；不要依賴 internal class 推測結果。

## Folia 安全

- `AceLibApi.AceLibProvider.api()` 可跨 thread 安全呼叫，但各項玩家、實體、方塊與世界操作仍必須遵守所屬 region 的執行緒上下文。
- 不要把 Bukkit global scheduler 當成 Folia 預設路徑。
- 需要操作遊戲物件時，使用 AceLib 提供的安全排程／上下文 API，或明確安排到正確的 Folia region context。
- 任何對外錯誤或 log 應保留 `ACELIB-<AREA>-<CODE>` 分類碼，並依錯誤碼文件處理。

## 測試與部署流程

1. 先以 Java 25 編譯 consumer，使用 JitPack 座標；不要以 `mavenLocal()` 假裝公開依賴可用。
2. 確認 consumer JAR 的 `plugin.yml` 有 `depend: [AceLib]`。
3. 依 `test-server-operations` skill 停服、備份、部署 AceLib 與 consumer JAR，再重啟。
4. 先讀取啟動 log，確認兩個 plugin 都出現 `Loading`／`Enabling`，並沒有 `Could not load plugin`、`Exception` 或 `ERROR`。
5. 若要測試管理指令，透過本機 RCON 執行 `list` 或 `acelib status`；RCON 必須先通過認證，指令不加 `/`。
6. 把 RCON 回應與 server log 一起保存，不能只因 port 開啟就宣稱插件測試成功。

## 常見錯誤

- `Could not find com.github.smile-minecraft:AceLib:v1.0.0`：確認有 `https://jitpack.io`、大小寫正確，版本保留前面的 `v`。
- Server 顯示 missing dependency：確認 AceLib JAR 位於伺服器 `plugins/`，且 consumer `plugin.yml` 宣告 `depend: [AceLib]`。
- Provider 是 `null`：AceLib 尚未成功啟用或已停用；先查 log，不要自行 cast plugin instance。
- `isReady()` 是 `false`：停止使用 AceLib 服務，走降級路徑或停用下游 plugin。
- Folia context 錯誤：檢查 region scheduler 與物件所屬 context，不要用延遲或吞錯誤掩蓋問題。

## 完成前檢查

- [ ] 依賴使用公開 JitPack 座標，未依賴 `mavenLocal()`。
- [ ] `plugin.yml` 有 `depend: [AceLib]`。
- [ ] API 透過 `ServicesManager` 取得，沒有依賴 `AceLibPlugin` 或 internal class。
- [ ] 使用服務前檢查 provider 與 `isReady()`。
- [ ] 玩家／實體／世界操作符合 Folia context 要求。
- [ ] 測試服 log 與 RCON 回應都已核對。
