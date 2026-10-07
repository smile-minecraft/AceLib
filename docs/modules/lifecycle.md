# 生命週期宿主

> 適合要讓 AceLib 安排下游模組的啟用順序、停用順序與 reload 重建流程的開發者。

ready 的 `AceLibApi` 永遠提供非 null 的 `LifecycleHost`：

```java
LifecycleHost host = api.getLifecycleHost();
LifecycleResult result = host.register(this, modules);
```

`uninitialized()` 或未接到實際 plugin 的 facade 會提供 `NOT_READY` 宿主；此時註冊會回傳結構化拒絕結果。下游只呼叫 `register`／`unregister`，不得呼叫 `AceLibApi.ready(...)` 或依賴 `AceLibPlugin`。

## 宣告模組與相依

每個 `LifecycleModule` 宣告一個穩定 id、一組 `dependsOn` 與啟用回呼。id 在同一個 AceLib 宿主內唯一，建議使用 `<plugin-name>:<module-name>`；不同 plugin 的模組可以互相依賴。

```java
String prefix = getName().toLowerCase(Locale.ROOT);
LifecycleModule storage = new LifecycleModule(
    prefix + ":storage", Set.of(), context -> {
        openStorage();
        return this::closeStorage;
    });
LifecycleModule commands = new LifecycleModule(
    prefix + ":commands", Set.of(storage.id()), context -> {
        registerCommands();
        return this::unregisterCommands;
    });

LifecycleResult result = host.register(this, List.of(commands, storage));
if (!result.isSuccess()) {
    result.problems().forEach(problem -> getLogger().warning(
        "[" + problem.code().code() + "] " + problem.message()
            + " " + problem.relatedModuleIds()));
}
```

宿主會先把本批與已註冊模組合併驗證，再依圖啟用；輸入順序不決定啟用順序。彼此無相依的模組會依 id 排序，令結果可重現。缺依賴、重複 id、循環會一次列入 `LifecycleResult.problems()`，在任何 `enable` 回呼執行前整批拒絕。每個問題包含 `LifecycleResult.Code`、模組 id 與相關 id；錯誤代碼見[錯誤碼表](../reference/error-codes.md#生命週期宿主life)。

如果某模組的 `enable` 在交回 handle 前失敗，該回呼尚未交給宿主管理的資源必須自行清理。已成功啟用的模組則由宿主按反向拓樸呼叫 handle；補償成功時整批不會留在宿主圖中。若 handle 清理也失敗，宿主保留待清理 handle、回傳 `CLOSE_FAILED` 並進入 `FAILED`，不宣稱已完整清理。

## 所有權與撤銷

宿主只擁有 `enable` 交回的 `LifecycleModule.Handle`。下游仍自行管理 config watcher、event registry、scheduler、GUI scope 等沒有放進 handle 的資源；不要把外部資源的擁有權模糊地交給 AceLib。

```java
@Override
public void onDisable() {
    LifecycleResult result = apiProvider.api().getLifecycleHost().unregister(this);
    if (!result.isSuccess()) {
        result.problems().forEach(problem -> getLogger().warning(
            "[" + problem.code().code() + "] " + problem.message()));
    }
}
```

`unregister(owner)` 只撤銷該 plugin 自己的模組。若其他 plugin 的已啟用模組直接或間接依賴它，結果會以 `ACTIVE_DEPENDENTS` 列出相依者並拒絕拆除。每個 owner 停用時 AceLib 也會自動嘗試撤銷；在 `onDisable` 明確呼叫可讓清理結果由下游處理。重複撤銷沒有已註冊模組的 owner 是安全 no-op。

## 內建服務與宿主的接線邊界

這裡的「接線」是讓下游模組的 handle 參與 AceLib 既有 reload 交易：`beginReload()` 先反向停用下游模組，核心服務完成提交並發布新 facade 後才由 `finishReload()` 重建。若核心 rollback 可安全重試，宿主保留模組宣告、把已關閉 handle 標記為未啟用並回到可重入的 `READY`；`lastResult()` 仍記錄失敗，且新註冊會暫時拒絕，直到後續 reload 成功。核心無法安全重試或下游重建失敗時宿主維持 `FAILED`，不把未完成的重建回報為成功。這**不表示**把 AceLib 自己的十項內建服務轉成 `LifecycleModule` 圖節點；內建服務仍由 `AceLibPlugin` 的既有 reload orchestration 擁有與清理。表中的 `FormService` 隨 `BedrockService` 一起計入，不另算一項。

| 內建服務 | reload 行為 | 零殘留驗證出處 |
| --- | --- | --- |
| `SafeSchedulerImpl` | 舊 scheduler 先停用，新 scheduler 只在核心提交路徑接任；失敗時 diagnostics 明確降級。 | `AceLibPluginTest#reload_rebindsDiagnosticsBindings`、`ReloadServiceReleaseTest#reload_dispatchFollowsNewCanonicalScheduler` |
| `DiagnosticsService` | 保留同一個 service reference，重綁新平台資料與 scheduler；提交前失敗會還原 metadata snapshot，並清楚標記失敗。 | `AceLibPluginTest#reload_rebindsDiagnosticsBindings`、`#reload_diagnosticsRebindFailure_restoresMetadataSnapshot` |
| Brigadier 管理指令 | reload 保留既有 registrar 與指令節點，handler 透過動態來源讀取最新 diagnostics，不重複註冊。 | `AceLibStatusCommandTest#reload_commandStillWorks` |
| `CommandCatalog` | 成功與失敗 reload 都保留同一目錄、內容與 revision；owner 停用時才撤下其描述。 | `CommandCatalogLifecycleTest#reloadSuccess_retainsCatalog`、`#reloadFailure_retainsCatalog`、`#catalogListener_reinstallSafeAndCleanOnDisable` |
| `PlayerDataService` | 舊服務先 flush 並停用，舊 pre-login listener 撤除；flush 完成後才關閉 plugin 自建 executor 與舊 store，再建新服務並重接在線玩家。 | `ReloadServiceReleaseTest#reload_onlinePlayerRejoined_dataQuitUsable`、`#reload_oldPlayerIoExecutorShutdown`、`#reload_consecutiveReloads_ioThreadsDoNotGrow`、`AceLibPluginPlayerStoreLifecycleTest` |
| `WorldService` | 舊實例先 unbind/shutdown，新實例在新 scheduler 接任後才建立；舊 reference 轉為停用語意。 | `ReloadServiceReleaseTest#reload_oldWorldServiceShutdown_newReady` |
| `GuiService` | 先撤銷舊 listener 並 shutdown 舊 session，再以新 scheduler 建立服務；不保留舊 listener 或舊 READY facade。 | `ReloadServiceReleaseTest#reload_oldGuiListenerUnregistered_onlyNewRemains`、`#reload_oldGuiServiceShutdown_newReady`、`AceLibPluginGuiServiceIntegrationTest` |
| `DisplayService` | 解除 quit listener 並清除本服務追蹤的顯示，再以新 scheduler 建立服務；不清理其他 plugin 的顯示。 | `DisplayServiceLifecycleTest#shutdown_rejectsAndClearsOwn`、`ReloadServiceReleaseTest#reload_oldGuiListenerUnregistered_onlyNewRemains` 的 plugin listener 數量檢查 |
| `ExternalIntegrationService` | 舊服務先 shutdown，再初始化新 adapter；Phase D 後段失敗時補償新服務並替換為 shutdown facade。 | `AceLibPluginExternalIntegrationTest#reload_replacesService_oldShutdown_newRegistered`、`#reload_playerShutdownFailureCompensatesNewIntegrationsAndOldForm` |
| `BedrockService`（含 `FormService`） | 依新 external adapter 重建；shutdown 會連帶關閉表單生命週期，清除 pending response 並忽略遲到回應。 | `AceLibPluginBedrockIntegrationTest#reload_rebindsNewReadyService_oldInstanceRejectsWithShutdownCode`、`BedrockServiceFormLifecycleTest#shutdownClearsPendingFormAndIgnoresLateResponse`、`AceLibPluginExternalIntegrationTest#reload_playerShutdownFailureCompensatesNewIntegrationsAndOldForm` |

這十項的 handle 不是宿主圖節點。下游交給宿主的 handle 則由宿主在核心 reload 前反向關閉，提交後依拓樸重建；可重試的核心失敗會保留宣告但不重複關閉已停用的 handle，新註冊要等 reload 重試成功才接受。啟用補償失敗會保留待清理 handle 並標記 `FAILED`，不宣稱已清乾淨。對應測試：`LifecycleHostImplTest#reloadRebuildsModulesAgainstUpdatedProvider`、`#reloadRebuildFailureCompensatesAndRemainsFailed`、`#register_rollbackFailureMarksHostFailedAndRetainsHandle`、`AceLibPluginTest#reload_retryableCoreFailureRebuildsDormantModulesOnRetry`。

## reload 與動態 facade

AceLib reload 開始拆舊核心服務前，會先反向停用所有下游 handle；核心提交成功後，宿主再依拓樸重建模組。`LifecycleModule.Context` 提供動態 `AceLibApi.AceLibProvider`，模組應保存 provider，並在需要時重新呼叫 `api()`，不要長期保存一次取得的 `AceLibApi` 或 service reference：

```java
LifecycleModule module = new LifecycleModule(prefix + ":feature", Set.of(), context -> {
    if (!context.apiProvider().api().isReady()) {
        throw new IllegalStateException("AceLib is not ready");
    }
    registerFeature(context.apiProvider());
    return () -> unregisterFeature();
});

private void registerFeature(AceLibApi.AceLibProvider provider) {
    WorldService world = provider.api().getWorldService();
    // 以目前 facade 的 service 建立本模組資源
}
```

`AceLibApi.reload()` 仍維持原本的 `void` 簽章；要確認 reload 結果，檢查 `host.status()` 與 `host.lastResult()`。可安全重試的核心 rollback 會回報 `lastResult().outcome() == FAILED`，但宿主狀態回到 `READY` 以便下一次 reload；舊模組 handle 已關閉，需等重試成功後才重新啟用。核心 fail-closed 或核心已提交但下游模組重建失敗時宿主維持 `FAILED`；後一種情況會依反向拓樸補償已重建模組，核心新世代不會假裝回滾成舊世代。`FAILED` 下仍可 `unregister(owner)` 釋放該 owner 自己的 handle，但不會讓宿主恢復可用。

### Paper 與 Folia

宿主不替模組回呼重新排程；`enable` 與 handle `close` 在呼叫 lifecycle API 的執行緒執行。兩者也會在宿主的同步鎖內呼叫；回呼若等待另一執行緒，而該執行緒又呼叫同一宿主 API，可能形成死鎖，應避免同步等待。模組若觸碰玩家、實體、世界或 GUI，必須沿用 AceLib 對應的安全 API／擁有者上下文；Folia 下不得假設全域主執行緒。宿主只計算相依順序與管理 handle，Paper 與 Folia 不另走不同圖規則。

## consumer 範例

可編譯範例位於 [`examples/consumer-plugin`](../../examples/consumer-plugin/README.md)：包含缺依賴／循環的拒絕處理、模組依賴註冊、reload 後從 provider 取得新 facade，以及停用時只撤銷自身模組。

## 相關頁面

- [核心 API](core.md)
- [安全排程](scheduler.md)
- [GUI](gui.md)
- [錯誤碼](../reference/error-codes.md)
