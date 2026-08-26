# 模組指南

AceLib 各模組的 API 與使用限制。先從 ready 的 `AceLibApi` 取得對應服務，再呼叫模組方法。

- [核心 API](core.md) — 取得 API、就緒狀態與版本資訊。
- [平台能力](platform.md) — 判斷 Paper / Folia 與可用能力。
- [執行緒上下文安全](context.md) — 在正確執行緒或 region 操作遊戲物件。
- [Paper 與 Folia 的安全排程](scheduler.md) — 跨平台安全排程任務。
- [設定檔](config.md) — 讀寫與 reload 設定。
- [訊息服務](message.md) — 多語系訊息傳送。
- [指令模型](command.md) — 註冊與處理指令。
- [事件註冊](event.md) — 監聽與分發事件。
- [資料儲存](data.md) — 持久化玩家與模組資料。
- [玩家資料與 session](player.md) — 玩家狀態與 session 管理。
- [世界操作](world.md) — 方塊、實體與傳送。
- [GUI](gui.md) — 安全開啟與操作 inventory GUI。
- [自訂物品](item.md) — 建立與序列化自訂物品。
- [外部 plugin 狀態](external.md) — 查詢 Vault、PlaceholderAPI、LuckPerms、Floodgate。
- [診斷](diagnostics.md) — 模組狀態與錯誤碼查詢。
- [基岩版玩家](bedrock.md) — 偵測基岩版玩家與其裝置／輸入／語言資訊。
- [表單](form.md) — 傳送基岩原生表單並接收回應。
