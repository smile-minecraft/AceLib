# 安全事件系統

本頁解決如何註冊可移除的事件 listener，以及如何標示 Folia region context 要求的問題。契約唯一來源是
> `src/main/java/com/smile/acelib/event/**` source 與 tests；
> 本頁為導覽鏡像，不複製完整 JavaDoc。

## 前置條件與最短路徑

以自身 plugin 建立 `SafeEventRegistry`，註冊 `SafeEventListener`，需要時保存 `EventRegistration` 以解除註冊。

## 預期結果

listener 可安全註冊與移除；停用時 `HandlerList` 不殘留 listener；`REQUIRES_REGION` 在錯誤上下文會略過並記錄 `ACELIB-EVT-005`。

## 1. 取得方式

透過 `AceLibEvents` factory 建立 `SafeEventRegistry`：

```java
import com.smile.acelib.event.AceLibEvents;
import com.smile.acelib.event.SafeEventRegistry;

// 推薦：從 AceLibPlugin 建立並自動綁定 lifecycle
SafeEventRegistry events = AceLibEvents.create(plugin);

// 或手動組合三個參數
SafeEventRegistry events2 = AceLibEvents.create(plugin, platform, capability);
```

- `create(AceLibPlugin)` 建立後自動呼叫 `bind(...)`；`plugin.onDisable()`
  會自動呼叫 `unbind(plugin)` 觸發 `SafeEventRegistry.onPluginDisable()`，
  確保 Bukkit `HandlerList` 不殘留 listener。
- `boundTo(plugin)` 查詢 plugin 對應的已綁定 registry；未綁定回 null。

## 2. 最小安全範例

```java
import com.smile.acelib.event.EventRegistration;
import com.smile.acelib.event.ListenerPolicy;
import com.smile.acelib.event.SafeEventListener;
import org.bukkit.event.player.PlayerJoinEvent;

// 註冊一般 listener（非一次性）
EventRegistration<PlayerJoinEvent> reg = events.register(
    PlayerJoinEvent.class,
    new SafeEventListener<>() {
        @Override public void onEvent(PlayerJoinEvent event) {
            // listener 內部拋例外會被 registry 攔截並記錄 ACELIB-EVT-001
        }
        @Override public Class<PlayerJoinEvent> eventType() {
            return PlayerJoinEvent.class;
        }
        @Override public ListenerPolicy policy() {
            return ListenerPolicy.UNCONSTRAINED; // 或 REQUIRES_REGION
        }
    });

// 解除註冊（用先前回傳的 handle；已不在 registry 時為 no-op）
events.unregister(reg);

// 一次性 listener：觸發一次後自動解除
events.registerOneShot(PlayerJoinEvent.class, listener);
```

## 3. Folia／執行緒契約與生命週期

- listener 以 `ListenerPolicy` 標記是否需要 region-bound context：
  - `UNCONSTRAINED`（預設）：接受任何 thread/context；Folia 下需要
    region-bound mutate 時由 listener 作者自行經 `SafeExecutor` 路由。
  - `REQUIRES_REGION`：Folia 環境下非 region thread 呼叫會被略過並記錄
    `ACELIB-EVT-005`；Paper / UNKNOWN 環境下等同 `UNCONSTRAINED`。
- 重複註冊（同 `SafeEventListener.identity()`）→ 回傳原 registration 並
  記錄 `ACELIB-EVT-003`。
- listener 內部拋錯會被攔截、記錄 `ACELIB-EVT-001`，不影響其他 listener
  或 AceLib 本身。
- `onPluginDisable()`：解除所有 Bukkit `HandlerList` 註冊、清空 tracked
  registration；之後 register 仍回傳 handle 但 listener 不會被 dispatch
  （記錄 `ACELIB-EVT-004`）。重複呼叫不丟例外（idempotent）。
- 所有 public 方法 thread-safe，可在多 region 並行環境使用。

## 常見失敗與錯誤碼

- `ACELIB-EVT-001` listener handler 內部拋 exception
- `ACELIB-EVT-002` Event class 註冊到 PluginManager 失敗（dispatch 失敗）
- `ACELIB-EVT-003` 重複註冊（已存在的 identity）
- `ACELIB-EVT-004` 插件停用（disabled 後 register / dispatch）
- `ACELIB-EVT-005` Folia 環境下 REQUIRES_REGION listener 在錯誤 context
- `ACELIB-EVT-006` Host plugin 尚未 enabled（library 不會主動 `enablePlugin`）

## 查核來源

- 介面：`SafeEventRegistry`；SPI：`SafeEventListener`
- 型別：`AceLibEvents`、`EventRegistration`、`EventErrorRecord`、
  `EventErrorRecorder`、`ListenerPolicy`（`SafeEventRegistryImpl` 為 Internal）
- 測試：`src/test/java/com/smile/acelib/event/SafeEventRegistryImplTest.java`、
  `EventErrorRecordTest.java`、`EventErrorRecorderTest.java`、
  `HostPluginNotEnabledTest.java`、`Phase7LifecycleIntegrationTest.java`
  （`Phase7LifecycleIntegrationTest` 涵蓋 event 模組的生命週期整合。）
- 下一步：[docs/modules/context.md](context.md)（SafeExecutor 派送）、
  [docs/consumer/quickstart.md](../consumer/quickstart.md)
