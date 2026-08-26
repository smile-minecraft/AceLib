# 事件註冊

> 適合要以 region 安全方式註冊與移除 Bukkit 事件的插件開發者。


`SafeEventRegistry` 提供可追蹤、可移除的 Bukkit listener，並可標示 listener 是否要求 Folia region context。

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

## 選擇 listener policy

- `UNCONSTRAINED`：listener 可被任何事件上下文呼叫。若要修改 region 綁定物件，listener 自己仍須用 `SafeExecutor` 或 `SafeScheduler` 路由。
- `REQUIRES_REGION`：Folia 在非 region 執行時會略過 listener，並記錄 `ACELIB-EVT-005`。

Plugin 停用時呼叫 `events.onPluginDisable()`，解除所有 Bukkit registration。Listener 內部拋出的例外會記為 `ACELIB-EVT-001`，不會阻止其他 listener 執行。

完整錯誤代碼見[錯誤碼](../reference/error-codes.md)。

## 相關頁面

- [指令模型](command.md)
- [上下文安全](context.md)
- [錯誤碼](../reference/error-codes.md)
