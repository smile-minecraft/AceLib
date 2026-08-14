# 用 AceLib 寫插件

本頁解決下游插件如何接入 AceLib、取得 API，並在 Paper／Folia 上正確處理生命週期的問題。

## 預期結果

完成後，下游插件會宣告 `depend: [AceLib]`，透過 `ServicesManager` 取得 `AceLibApi.AceLibProvider`，檢查 `isReady()`，並依平台能力選擇安全的操作路徑。

## 前置條件

- **Java 25** 開發環境（AceLib 的 Gradle 也會自動下載 toolchain）。
- **AceLib 取得方式**：本機使用 `com.smile:acelib:1.0.0`；JitPack 驗證使用 commit `cbf4a80`。`v1.0.0` tag 仍待服務端重建。
- 基本 Bukkit／Paper 插件開發經驗。

## 最短成功路徑

1. [Quick Start](quickstart.md)：加入 dependency、宣告 `depend: [AceLib]`，並取得 provider。
2. [Provider 生命週期](provider-lifecycle.md)：處理 missing、not-ready、reload 與 disable。
3. [相容性與發布狀態](compatibility.md)：確認 Java 25、Paper／Folia 26.1.2 與 GitHub／JitPack／Maven 取得限制。

最短可工作的程式碼片段（重點）：

```java
RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
    getServer().getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);
if (registration == null || !registration.getProvider().api().isReady()) {
    getServer().getPluginManager().disablePlugin(this); // AceLib 不在／還沒 ready
    return;
}
AceLibApi api = registration.getProvider().api();
```

## 取得 AceLib

GitHub repository 已公開，`v1.0.0` GitHub Release 已建立。下游 dependency 有兩條已確認路徑：

1. 本機 Maven：執行 `./gradlew publishToMavenLocal`，使用 `com.smile:acelib:1.0.0` 與 `mavenLocal()`。
2. JitPack commit：使用 `com.github.smile-minecraft:AceLib:cbf4a80`。此 commit status 為 `ok`；consumer fixture 以 dependency substitution 指向該 commit，並在乾淨 Gradle user home 完成 build／`verifyConsumerDocs`。

JitPack `v1.0.0` tag 目前仍為 `Error`，服務端快取舊 commit `9b8e55d`；刪除舊失敗 build 或服務端介入後才可重新驗證。不得將 `com.github.smile-minecraft:AceLib:v1.0.0` 寫成已成功，也不宣稱 Maven Central 已發布。

## 常見失敗與處理位置

- **編譯失敗／API 找不到**：確認使用 `AceLibApi.AceLibProvider`，不要使用不存在的 `AceLib.getApi()`，也不要直接依賴 `AceLibPlugin`（內部類別）。見 [Quick Start §禁止事項](quickstart.md#4-取得-acelibapiacelibprovider)。
- **執行期拿不到 API**：看 [Provider 生命週期](provider-lifecycle.md) 的四種狀態表。
- **日誌裡的 `ACELIB-*` 錯誤碼**：對照根 `README.md` 的 [錯誤分類與代碼參考](../../README.md#錯誤分類與代碼)，或各模組指南的「錯誤碼」小節。
- **平台行為不同**：看根 `README.md` 的 [平台差異](../../README.md#平台差異folia-與-paper) 與各模組指南的「Folia／執行緒契約」。

## API 參考

- 分類契約（Supported / SPI / Internal）：[../reference/api-surface.md](../reference/api-surface.md)（唯一來源是 [../reference/api-surface.json](../reference/api-surface.json)）。
- JavaDoc：隨發布 artifact 提供，或本機 `./gradlew javadoc` 產出。
- 各模組怎麼用：見 [../modules/](../modules/)。

## 下一步

- [Quick Start](quickstart.md) — 將 AceLib 接入下游插件
- [Provider 生命週期](provider-lifecycle.md) — 四種狀態與 Folia 安全規則
- [相容性與發布狀態](compatibility.md) — 基線與 GitHub／JitPack／Maven 狀態
- 回文件中心 → [../README.md](../README.md)
