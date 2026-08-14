# 世界操作

本頁解決如何以不可變 snapshot 查詢世界、操作方塊與傳送玩家，並正確處理 Folia 執行緒限制的問題。契約唯一來源是
> `src/main/java/com/smile/acelib/world/**` source 與 tests；
> 本頁為導覽鏡像，不複製完整 JavaDoc。

## 前置條件與最短路徑

先從 ready 的 `AceLibApi` 取得 `WorldService`，以 `LocationSnapshot` 或 `EntityReference` 描述目標，再在正確的 Paper main thread 或 Folia region thread 呼叫。

## 預期結果

查詢與操作回傳 `WorldResult`；不存在的世界、未載入 chunk、失效實體或錯誤上下文會回傳明確狀態與 `ACELIB-WORLD-*`。

## 1. 取得方式

正式取得入口是 `AceLibApi.getWorldService()`（見
[docs/consumer/provider-lifecycle.md](../consumer/provider-lifecycle.md)）：

```java
import com.smile.acelib.AceLibApi;
import com.smile.acelib.world.WorldService;
import org.bukkit.plugin.RegisteredServiceProvider;

// 以下位於 JavaPlugin 子類別（例如 onEnable 內），getServer() 為 JavaPlugin 方法
RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
    getServer().getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);
if (registration == null) {
    // AceLib 尚未 enable 或已 disable：depend 攔不到的 runtime 情境；
    // 停用自身 plugin 或走 fallback，不要硬取 API
    getServer().getPluginManager().disablePlugin(this);
    return;
}
AceLibApi api = registration.getProvider().api(); // provider.api() 永不為 null
if (!api.isReady()) {
    // AceLib 存在但尚未 ready（例如 reload 失敗或已 shutdown）
    getServer().getPluginManager().disablePlugin(this);
    return;
}
WorldService world = api.getWorldService();        // 永不為 null
```

- 未啟用（`uninitialized`）或已停用（`shutDown`）時，回傳的是
  `WorldServiceUnavailableImpl`：任何操作回 `REJECTED + ACELIB-WORLD-001/002`，
  不丟例外（null 輸入除外）。
- `world` 與 `gui`、`external` 一樣是「安全 facade 三態」：query 永遠不回 null。

## 2. 最小安全範例

```java
import com.smile.acelib.AceLibApi;
import com.smile.acelib.world.BlockResult;
import com.smile.acelib.world.LocationSnapshot;
import com.smile.acelib.world.WorldService;
import java.util.UUID;
import org.bukkit.plugin.RegisteredServiceProvider;

// 與 §1「取得方式」相同的完整 lookup（位於 JavaPlugin 子類別，例如 onEnable 內）
RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
    getServer().getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);
if (registration == null) {
    getServer().getPluginManager().disablePlugin(this); // AceLib 尚未 enable 或已 disable
    return;
}
AceLibApi api = registration.getProvider().api(); // provider.api() 永不為 null
if (!api.isReady()) {
    getServer().getPluginManager().disablePlugin(this); // AceLib 存在但尚未 ready
    return;
}
WorldService world = api.getWorldService();      // 永不為 null

UUID worldId = UUID.fromString("00000000-0000-0000-0000-000000000000");   // 以實際世界 UUID 取代
UUID playerUuid = UUID.fromString("11111111-1111-1111-1111-111111111111"); // 以實際玩家 UUID 取代

// 讀取方塊：以不可變 snapshot 表達目標，不直接傳 Location/World
LocationSnapshot at = LocationSnapshot.of(worldId, 100, 64, -200);
BlockResult result = world.readBlock(at);
if (result.isSuccess()) {
    // result.blockKey() 例如 "STONE"
}

// 非同步傳送：CompletionStage 必定完成，不得假設立即完成
LocationSnapshot target = LocationSnapshot.of(worldId, 200, 64, -100);
world.teleportPlayer(playerUuid, target, false)
    .thenAccept(teleportResult -> {
        // SUCCESS / REJECTED / FAILED / CANCELLED / PARTIAL
    });
```

## 3. Folia／執行緒契約

- **輸入只用 `LocationSnapshot` / `EntityReference`**：不可傳入
  `World` / `Location` / `Entity` / `Player` 的 mutable reference。
- 方塊與實體 mutate 操作必須在目標所屬 region thread（Folia）或主執行緒
  （Paper）執行。facade **不會**自動派送到 region thread：`WorldServiceImpl`
  直接呼叫 backend，在呼叫端執行緒立即執行；呼叫端必須先確保自己已位於
  正確 context（Folia 下用既有 `SafeScheduler.runAtLocation` / `runForEntity` /
  `runForPlayer` 排程，或已位於目標 region thread；Paper 下在主執行緒）。
- 不得以全域 `Bukkit.getScheduler()` 作為預設路徑；執行緒切換應由呼叫端
  或既有 `SafeScheduler` 安排，不要期待 facade 代為切換。
- Teleport 一律非同步：透過 `CompletionStage<TeleportResult>` 等待最終結果，
  跨 region／玩家部分完成回 `PARTIAL`（`ACELIB-WORLD-016`）。
- 每次呼叫執行前重新驗證目標；失敗回結果而非丟例外（null 輸入丟
  `IllegalArgumentException` 帶 `ACELIB-WORLD-007`）。

## 4. 生命週期與 nullability

| 情境 | 行為 |
| --- | --- |
| 未啟用（enable 前） | `REJECTED + NOT_READY`（`ACELIB-WORLD-001`） |
| 已停用（disable 後） | `REJECTED + SHUTDOWN`（`ACELIB-WORLD-002`）；in-flight teleport 自動回 `CANCELLED` |
| world 不存在 | `REJECTED + WORLD_NOT_FOUND`（`ACELIB-WORLD-003`） |
| chunk 未載入 | `REJECTED + CHUNK_UNLOADED`（`ACELIB-WORLD-004`） |
| entity 已移除／死亡 | `REJECTED + ENTITY_GONE`（`ACELIB-WORLD-005`） |
| 玩家離線 | `REJECTED + PLAYER_OFFLINE`（`ACELIB-WORLD-006`） |
| 輸入 null／radius <= 0 | 丟 `IllegalArgumentException`（`ACELIB-WORLD-007`） |

`WorldResult` 是 sealed abstract class 為基底的 result hierarchy：
`BlockResult` / `EntityResult` / `TeleportResult` / `NearbyQueryResult` 皆為
final class 子類別（非 record）。基底與子類別欄位皆 final、無 setter，
`NearbyQueryResult` 清單以 `List.copyOf` 防禦性複製，持有的
`LocationSnapshot` / `EntityReference` 是 record：實例建構後不可變，可安全
共享。`state()` 對應 `WorldState`（`SUCCESS` / `REJECTED` / `CANCELLED` /
`PARTIAL` / `FAILED`）。

## 常見失敗與錯誤碼

全部錯誤代碼見 `WorldErrorCode`（`ACELIB-WORLD-001` ~ `ACELIB-WORLD-016`），
例如：

- `ACELIB-WORLD-008 CONTEXT_UNSAFE`：當前執行緒不允許 mutate 目標（Folia 上下文違規）
- `ACELIB-WORLD-014 TELEPORT_REJECTED`：Bukkit 回傳 false
- `ACELIB-WORLD-015 TELEPORT_EXCEPTION`：傳送拋例外

## 查核來源

- 介面：`WorldService`、`WorldBackend`（SPI）
- 型別：`LocationSnapshot`、`EntityReference`、`WorldResult`、`WorldState`、
  `WorldErrorCode`
- 測試：`src/test/java/com/smile/acelib/world/WorldServiceImplTest.java`、
  `WorldServiceFacadeLookupTest.java`、`AceLibPluginWorldServiceIntegrationTest.java`
- 下一步：[docs/consumer/quickstart.md](../consumer/quickstart.md#5-使用-facade-與平台分支)
