# 外部整合門面探針

部署到 Paper／Folia 測試服，實測外部整合門面四類業務在真實外部 plugin
環境下的行為：經濟（Vault legacy）、權限（LuckPerms）、佔位符
（PlaceholderAPI）、建造查詢（通用 SPI，本期無外部 adapter）。

## 部署

1. 在 AceLib 根目錄執行 `./gradlew publishToMavenLocal`
2. `./gradlew -p examples/external-integration-probe build`
3. 把 `build/libs/external-integration-probe-*.jar` 放進測試服 `plugins/`
   並重啟（Folia 不支援熱重載）

前置：測試服建議安裝 VaultUnlocked、LuckPerms、PlaceholderAPI
（缺席亦可：探針會輸出明確的 `UNAVAILABLE` 列，正好驗收缺席路徑）。

## 執行

遊戲內執行 `/extprobe`（需 op）。console 執行亦可，但玩家相關門面會
明確標示跳過（console 無玩家對象）。

## 預期輸出（console＋執行者皆可見）

```text
[status] module=AVAILABLE vault=AVAILABLE luckperms=AVAILABLE placeholderapi=AVAILABLE
[economy] SUCCESS balance=1000.0
[permission] SUCCESS primary=admin groups=admin,default
[placeholder] register SUCCESS; unregister SUCCESS（無殘留）
[build] UNAVAILABLE code=ACELIB-EXT-... detail=no build-check provider is registered
```

- 外部 plugin 缺席時對應欄位為 `INIT_FAILED`／`UNAVAILABLE`（明確不可用，
  不得默認允許、不得視為成功）。
- `[build]` 在無區域保護外掛的測試服上預期為 `UNAVAILABLE`：建造查詢
  本期只提供通用 SPI，不綁任何外部 adapter。
- `[placeholder]` 為註冊→清理往返：`unregister` 非成功會如實輸出，
  表示有殘留疑慮，需進一步檢查。

## 清理

探針註冊的 `extprobe` 測試鍵在每次執行結束時立即清理；`onDisable`
不需額外動作（AceLib 停用時會清理內建提供者的佔位符註冊）。
移除探針 jar 並重啟即完全清除。
