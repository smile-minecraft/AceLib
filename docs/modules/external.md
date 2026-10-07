# 外部 plugin 整合

> 適合要查詢外部插件整合狀態、使用經濟／權限／佔位符／建造查詢門面，
> 或替換提供者的開發者。

AceLib 可查詢 Vault、PlaceholderAPI、LuckPerms 與 Floodgate 是否存在、已啟用且版本可接受。從 ready 的 API 取得服務：

Floodgate 整合同時餵養 BedrockService（基岩玩家偵測與表單發送）；詳見[基岩版玩家模組](bedrock.md)。

```java
ExternalIntegrationService external = api.getExternalIntegrationService();
IntegrationProbeResult result = external.getStatus("vault");
```

查詢結果可能是：

```java
switch (result.status()) {
    case AVAILABLE -> {
        // AceLib 判定此整合目前可用
    }
    case NOT_INSTALLED -> {
        // Server 沒有該 plugin
    }
    case NOT_ENABLED -> {
        // 已安裝但未啟用
    }
    case VERSION_UNSUPPORTED -> {
        // 版本不符合需求或無法比較
    }
    case INIT_FAILED -> {
        // 探測或初始化失敗
    }
}
```

`AVAILABLE` 是目前 server 的探測結果，不是永久保證。外部 plugin 可能在生命週期中停用；使用前仍應重新查詢，並準備沒有整合時的行為。

四個內建 adapter 彼此獨立，初始化與清理維持 registry 的註冊順序；這不代表一般模組相依。下游只有在 lifecycle module 明確宣告依賴時，才由生命週期宿主依拓樸順序啟用、反向停用。

AceLib 使用 reflection 探測，不要求這些外部 API 一定存在於 classpath。若你要直接呼叫 Vault、PlaceholderAPI 或 LuckPerms API，仍需在自己的 plugin 宣告相應 dependency，並遵守對方的文件。

AceLib 尚未就緒或已停用時，查詢會回 `INIT_FAILED` 與不可用原因。完整 `ACELIB-EXT-*` 說明見[錯誤碼](../reference/error-codes.md)。

## 業務門面（只包裝，不自製）

狀態查詢之外，AceLib 另提供四類業務門面，皆只包裝外部提供者：

- 經濟：`getBalance` 查餘額、`withdraw` 扣款、`deposit` 入帳（內建為 Vault legacy 經濟的純反射包裝）。
- 權限：`getPermissionGroups` 查主要群組、所屬群組與當前情境（內建為 LuckPerms 包裝）。
- 佔位符：`registerPlaceholder`／`unregisterPlaceholder` 註冊下游自有佔位符（內建為 PlaceholderAPI expansion 子類別）。
- 建造查詢：`canBuild` 查玩家能否在某位置建造（通用 SPI，本期不內建任何區域保護 adapter；無提供者時一律回不可用）。

四類結果皆為「狀態＋錯誤代碼＋訊息」：成功不帶錯誤代碼；提供者缺席／停用時回 `UNAVAILABLE`；提供者存在但操作失敗時回 `FAILED`。不可用不得被解讀為成功：經濟餘額為 `NaN`（不等於 0）、建造查詢一律不允許、權限群組為空（不得做授權判斷）。

金流語意：扣款／入帳在提供者回成功之後、餘額取回失敗時，門面會以一次餘額重讀回補；重讀亦失敗才回 `FAILED`，並明示交易可能已完成——呼叫端請勿盲目重試（重試可能造成重複扣款）。

```java
EconomyResult balance = external.getBalance(player);
if (!balance.isSuccess()) {
    // UNAVAILABLE 或 FAILED：走降級路徑，不得把 NaN 當成 0
    return;
}
double amount = balance.balance();
```

```java
BuildCheckResult check = external.canBuild(playerId, "world", 10, 64, -5);
if (!check.isSuccess() || !check.allowed()) {
    // 不可用或被拒絕：一律視為不可建造
    return;
}
```

呼叫端可在非同步執行緒呼叫這四類門面；提供者端可能有 I/O，本門面不觸碰世界／實體狀態。位置查詢的資料（玩家識別＋世界名稱＋座標）由呼叫端提供。

AceLib 不自製經濟或權限系統，不代做領域授權、價格或交易條件，也不做扣款去重或持久操作紀錄；重複呼叫的後果由提供者語意決定。

## 替換提供者

下游可以 AceLib 自有 SPI 型別（`EconomyProvider`／`PermissionProvider`／`PlaceholderProvider`／`BuildCheckProvider`，皆不含外部型別）替換內建提供者；清除後恢復內建。服務停用、reload 或被替換時，舊引用即被丟棄（佔位符註冊會被清理，不殘留），之後的呼叫重新解析，不會讀到舊提供者。

```java
external.setBuildCheckProvider(
    (playerId, worldName, x, y, z) -> BuildCheckResult.success(true, "my-region-ok"));
```

## 相關頁面

- [基岩版玩家](bedrock.md)
- [診斷](diagnostics.md)
- [錯誤碼](../reference/error-codes.md)
