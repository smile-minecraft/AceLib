# 事件註冊

> 適合要以 region 安全方式註冊與移除 Bukkit 事件的插件開發者。


`SafeEventRegistry` 提供可追蹤、可移除的 Bukkit listener，並可標示 listener 是否要求 Folia region context。

## 目錄

- [建立 registry](#建立-registry)
- [註冊與移除](#註冊與移除)
- [一次性 listener 的實際語意](#一次性-listener-的實際語意)
- [沒有操作提交成功後的回呼](#沒有操作提交成功後的回呼)
- [解除與重新註冊的呼叫端責任](#解除與重新註冊的呼叫端責任)
- [選擇 listener policy](#選擇-listener-policy)
- [相關頁面](#相關頁面)

## 建立 registry

下游 plugin 應使用接收 `JavaPlugin`、`Platform` 與 `PlatformCapability` 的 factory：

```java
SafeEventRegistry events = AceLibEvents.create(
    this,
    api.getPlatform(),
    api.getPlatformCapability());
```

接收 `AceLibPlugin` 的 overload 是 AceLib 自己的組裝路徑，consumer 不應使用。

## 註冊與移除

```java
EventRegistration<PlayerJoinEvent> registration = events.register(
    PlayerJoinEvent.class,
    new SafeEventListener<>() {
        @Override
        public void onEvent(PlayerJoinEvent event) {
            // 處理事件
        }

        @Override
        public Class<PlayerJoinEvent> eventType() {
            return PlayerJoinEvent.class;
        }

        @Override
        public ListenerPolicy policy() {
            return ListenerPolicy.UNCONSTRAINED;
        }
    });

events.unregister(registration);
```

一次性 listener 可用 `registerOneShot(...)`，觸發後會自動解除。

## 一次性 listener 的實際語意

`registerOneShot(...)` 保證的是「在同一次 dispatch 中被呼叫過之後，從 listener 清單移除」，不是「整個伺服器生命週期只被呼叫一次」。

移除動作發生在 listener 被呼叫之後、該次 dispatch 的迴圈結束時。因此下列兩種情況下，同一個一次性 listener 仍可能被呼叫第二次：

- 同一種事件在兩個執行緒並行 dispatch。兩邊都可能在對方移除之前讀到 listener 清單。
- listener 內部重入。listener 在自己的 `onEvent` 裡同步觸發同一種事件時，內層 dispatch 看到的仍是尚未移除的清單。

需要嚴格一次的語意時，請在 listener 內部自行保護（例如以 `AtomicBoolean` 記錄是否已執行），不要依賴 `registerOneShot(...)` 本身擋住重入。

## 沒有操作提交成功後的回呼

`SafeEventRegistry` 只提供註冊、移除與查詢（`register`／`registerOneShot`／`unregister`／`unregisterAll`／`getRecentErrors`）；沒有「操作提交成功後」的回呼。需要在事件處理完成後執行的後續動作，請直接寫在 listener 的 `onEvent` 本體內，由下游自行組合，不要等待框架提供提交回呼。

## 解除與重新註冊的呼叫端責任

`unregisterAll()` 與 `onPluginDisable()` 會依序清空 listener 清單、內部索引與 bridge 註冊狀態，最後才呼叫 Bukkit 的 `HandlerList.unregisterAll(...)` 解除 bridge。

`register(...)` 沒有與這兩個方法共用同一把鎖。若呼叫端讓 `register(...)` 與 `unregisterAll()` 同時執行，`register(...)` 可能把 entry 加進一個即將被 `unregisterAll()` 丟棄的 `RegistrationList` 參考，結果是 handle 正常回傳、listener 卻不會再被 dispatch，也不會記錄任何錯誤。

因此註冊與解除不應並行：需要批次換掉整組 listener 時，先完成 `unregisterAll()` 再進行新一輪 `register(...)`。

`onPluginDisable()` 會先標記 disabled，之後才開始清理，所以它之後才進入的 `register(...)` 只回傳 handle 並記錄 `ACELIB-EVT-004`，不會靜默失效。已經在清理之前進入的 `register(...)` 則仍有上述視窗；停用流程應在自己的生命週期裡先停止新的註冊請求。

## 選擇 listener policy

- `UNCONSTRAINED`：listener 可被任何事件上下文呼叫。若要修改 region 綁定物件，listener 自己仍須用 `SafeExecutor` 或 `SafeScheduler` 路由。
- `REQUIRES_REGION`：Folia 在非 region 執行時會略過 listener，並記錄 `ACELIB-EVT-005`。

Plugin 停用時呼叫 `events.onPluginDisable()`，解除所有 Bukkit registration。Listener 內部拋出的例外會記為 `ACELIB-EVT-001`，不會阻止其他 listener 執行。

完整錯誤代碼見[錯誤碼](../reference/error-codes.md)。

## 相關頁面

- [指令模型](command.md)
- [上下文安全](context.md)
- [錯誤碼](../reference/error-codes.md)
