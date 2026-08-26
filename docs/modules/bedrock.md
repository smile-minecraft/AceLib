# 偵測基岩版玩家

AceLib 透過 Floodgate 判斷連線玩家是否來自基岩版（Geyser），並提供裝置、輸入模式與語言等資訊。先從 ready 的 `AceLibApi` 取得 `BedrockService`：

```java
import com.smile.acelib.AceLibApi;
import com.smile.acelib.bedrock.BedrockService;

// api 為 ready 的 AceLibApi（取得方式見根 README 的「取得 API」）
BedrockService bedrock = api.getBedrockService();
```

## 判斷是否為基岩玩家

`isBedrockPlayer(UUID)` 回傳 `boolean`：

```java
if (bedrock.isBedrockPlayer(player.getUniqueId())) {
    // 這是經 Geyser/Floodgate 連線的基岩版玩家
}
```

要進一步讀取資訊，用 `getPlayerInfo(UUID)`，回傳 `Optional<BedrockPlayerInfo>`：

```java
import com.smile.acelib.bedrock.BedrockPlayerInfo;

bedrock.getPlayerInfo(player.getUniqueId()).ifPresent(info -> {
    BedrockPlayerInfo.DeviceOs device = info.deviceOs();   // 未知上游值為 UNKNOWN
    BedrockPlayerInfo.InputMode input = info.inputMode();  // 未知上游值為 UNKNOWN
    String language = info.languageCode();                 // 例如 "zh_TW"
    BedrockPlayerInfo.LinkState link = info.linkState();   // LINKED 或 UNLINKED
    String linkedName = info.linkedUsername();             // 未連結時為 null
});
```

`BedrockPlayerInfo` 的裝置與輸入列舉會鏡射 Floodgate 的常數；上游日後新增未知的裝置或輸入型別時，AceLib 一律映射為 `UNKNOWN`，不拋例外。這表示舊版插件在 Floodgate 升級後也不會因為遇到陌生列舉而壞掉。

## Floodgate 沒安裝時

Floodgate 未安裝時，`BedrockService` 仍然可用，只是查詢會安全回傳「不是基岩玩家」：

- `isBedrockPlayer(...)` 回 `false`
- `getPlayerInfo(...)` 回 `Optional.empty()`

這是一種零影響的綁定：你不需要寫 `if (floodgate 存在)` 之類的防禦程式碼，套件在純 Java 版伺服器上一樣能正常啟用與運作。

## 查詢 Floodgate 整合狀態（進階）

若你需要確認 Floodgate 本身是否就緒，可以走外部整合服務：

```java
import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.IntegrationProbeResult;

ExternalIntegrationService external = api.getExternalIntegrationService();
IntegrationProbeResult floodgate = external.getStatus("floodgate");
// 未安裝時 status() 為 INIT_FAILED，reason() 會記錄底層探測結果（例如未安裝）
```

日常要判斷「某個玩家是不是基岩玩家」，請直接用 `isBedrockPlayer`，不要去解析上面這段原始整合狀態字串——整合狀態代表的是 Floodgate plugin 本身的可用性，與單一玩家的身分是兩件事。

## 基岩玩家的平台限制

基岩版用戶端有官方記載的限制（Geyser 目前限制）：

- 聊天中的連結無法點擊。
- 背包 GUI 無法區分左鍵與右鍵。

因此，涉及危險操作（例如刪除資料、花費資源）時，建議走確認流程而不是直接執行；需要文字輸入時，建議改用基岩原生表單而非依賴聊天輸入，見[表單模組](form.md)。

## 錯誤碼

基岩服務相關錯誤見[錯誤碼頁](../reference/error-codes.md#基岩版玩家服務bed)的 `ACELIB-BED-*` 段落。
