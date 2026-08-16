# 外部 plugin 狀態

AceLib 可查詢 Vault、PlaceholderAPI 與 LuckPerms 是否存在、已啟用且版本可接受。從 ready 的 API 取得服務：

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

AceLib 使用 reflection 探測，不要求這些外部 API 一定存在於 classpath。若你要直接呼叫 Vault、PlaceholderAPI 或 LuckPerms API，仍需在自己的 plugin 宣告相應 dependency，並遵守對方的文件。

AceLib 尚未就緒或已停用時，查詢會回 `INIT_FAILED` 與不可用原因。完整 `ACELIB-EXT-*` 說明見[錯誤碼](../reference/error-codes.md)。
