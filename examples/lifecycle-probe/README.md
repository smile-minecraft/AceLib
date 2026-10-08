# Lifecycle reload 實機探針

`/lprobe` 透過 AceLib 公開 provider 呼叫 `AceLibApi.reload()`，並註冊一個由
`LifecycleHost` 管理的探針模組。此探針只記錄 lifecycle 啟用、關閉與公開結果，
不引用 AceLib internal 型別。

## 建置

```bash
# 在 AceLib 根目錄發布本機產物
./gradlew publishToMavenLocal
# 建置探針 plugin
./gradlew -p examples/lifecycle-probe build
```

JAR 位於 `examples/lifecycle-probe/build/libs/acelib-lifecycle-probe-1.0.0-SNAPSHOT.jar`。
探針不加入根專案的 `docsCheck` 或 `compatibilityCheck`。

## 部署與操作

把探針 JAR 放入 Paper 或 Folia 測試服的 `plugins/`，確認部署的是待驗證的 AceLib
候選 JAR，再重啟伺服器。外掛宣告 `depend: [AceLib]` 與 `folia-supported: true`；
Folia 不支援熱載入，替換 JAR 後必須重啟。

以具權限的玩家或伺服器 console 執行：

```text
/lprobe status
/lprobe reload
```

`reload` 可連續呼叫兩次。每次會在全域執行區同步呼叫公開的 `api.reload()`，並在
console 記錄 `/lprobe reload #N return=void`、呼叫後的 `apiReady`、`lifecycleStatus`
與 `LifecycleHost.lastResult()`。`AceLibApi.reload()` 的公開回傳型別是 `void`，因此
探針如實記錄 `return=void`；是否成功以 lifecycle 結構化結果觀察，不以 `isReady()`
假裝成 reload 成敗值。

## 觀察點

- 啟用後出現 `lprobe module enabled`。
- 每次成功重建的順序應為 `lprobe module closed`、AceLib 核心 reload 記錄、
  `lprobe module enabled`；第二次呼叫應再次出現這組模組生命週期記錄。
- `/lprobe reload` 的輸出包含 `return=void`、`apiReady`、宿主狀態及最近一次
  lifecycle 結果。若失敗，`lastResult` 會保留失敗結果與問題代碼；應同時檢查
  AceLib 核心記錄，不能只看 `apiReady` 判定成功。
- `/lprobe status` 顯示目前 `apiReady`、`lifecycleStatus` 與 `lastResult`。
- 停用探針時應看到 `lprobe module closed`，以及 unregister 結果。

探針命令以 `lprobe` 權限保護，預設授予 OP。移除探針 JAR 並重啟即可清理。
