# 錯誤碼參考

在 server 日誌看到 `ACELIB-<AREA>-<CODE>` 時，可在這裡查代碼代表的模組與常見觸發原因。處理問題時請保留完整代碼，以及它前後的日誌內容。

錯誤碼由各模組的常數與 `diagnostics.ErrorCodeRegistry` 定義；若要修改或新增代碼，請先以原始碼為準。

## 格式

AceLib 所有對外拋出或記錄的錯誤，都攜帶 `ACELIB-<AREA>-<CODE>` 格式的分類碼，方便看日誌時對照。代碼大小寫敏感（`ACELIB-SCHED-001` 正確，`acelib-sched-001` 會被歸類為 UNKNOWN）。

## 分類（AREA）

| 分類（AREA） | 前綴 | 說明 |
| --- | --- | --- |
| `PLAT` | `ACELIB-PLAT-*` | 平台偵測 |
| `SCHED` | `ACELIB-SCHED-*` | 排程 |
| `CTX` | `ACELIB-CTX-*` | 執行緒上下文安全 |
| `CFG` | `ACELIB-CFG-*` | 設定檔 |
| `LANG` | `ACELIB-LANG-*` | 語言檔 |
| `MSG` | `ACELIB-MSG-*` | 訊息服務 |
| `CMD` | `ACELIB-CMD-*` | 指令系統 |
| `EVT` | `ACELIB-EVT-*` | 事件管理 |
| `DATA` | `ACELIB-DATA-*` | 資料儲存 |
| `PLAYER` | `ACELIB-PLAYER-*` | 玩家狀態 |
| `WORLD` | `ACELIB-WORLD-*` | 世界操作 |
| `GUI` | `ACELIB-GUI-*` | GUI |
| `ITEM` | `ACELIB-ITEM-*` | Item |
| `EXT` | `ACELIB-EXT-*` | 外部整合 |
| `BED` | `ACELIB-BED-*` | 基岩版玩家服務 |
| `FORM` | `ACELIB-FORM-*` | 表單服務 |
| `DBG` | `ACELIB-DBG-*` | 診斷模組自身 |

### 排程器（SCHED）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-SCHED-001` | 任務內部拋出例外 | 使用者的 Runnable 執行時拋錯 |
| `ACELIB-SCHED-002` | 目標玩家已離線 | 排程任務執行時玩家已離線 |
| `ACELIB-SCHED-003` | 目標實體已失效 | 排程任務執行時實體已移除／死亡 |
| `ACELIB-SCHED-004` | 目標 chunk 尚未載入 | 排程任務執行時 chunk 不可用 |
| `ACELIB-SCHED-005` | 目前平台不支援此排程模式 | Folia-only 操作在 Paper 上執行 |
| `ACELIB-SCHED-006` | 插件已停用 | `onDisable` 後所有後續任務 no-op |

### 執行緒上下文（CTX）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-CTX-001` | 執行緒上下文不安全 | 在 async thread 修改遊戲物件 |
| `ACELIB-CTX-002` | 在非同步流程後直接操作玩家／實體 | 從 main thread 送出 runAsync 後修改 |
| `ACELIB-CTX-003` | Folia 下非區域執行緒操作區域綁定物件 | Folia 上在錯誤上下文操作實體 |
| `ACELIB-CTX-004` | 平台不支援此操作 | UNKNOWN 平台上嘗試任何修改操作 |

### 設定檔（CFG）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-CFG-001` | 設定檔不存在或無法生成 | 首次啟動時預設設定寫入失敗 |
| `ACELIB-CFG-002` | 設定檔 YAML 格式錯誤 | 設定檔內容無法解析 |
| `ACELIB-CFG-003` | reload 失敗且無舊值可回退 | 設定 reload 失敗且沒有先前有效值 |
| `ACELIB-CFG-004` | 設定檔版本遷移失敗 | 設定 migration chain 中任一步驟失敗 |
| `ACELIB-CFG-005` | 必填欄位缺失 | 設定檔缺少必要欄位 |

### 訊息服務（MSG）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-MSG-001` | 訊息 key 缺失 | 查詢的 locale key 不存在 |
| `ACELIB-MSG-002` | 在不安全上下文操作玩家訊息（Folia） | Folia 下非區域執行緒傳送訊息 |
| `ACELIB-MSG-003` | 訊息格式錯誤 | LangManager 抓不到物件或格式異常 |

### 指令系統（CMD）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-CMD-001` | 缺少必要參數 | minArgs 未滿足 |
| `ACELIB-CMD-002` | 未知的子指令 | 無對應 SubCommandSpec |
| `ACELIB-CMD-003` | 沒有權限執行 | 權限檢查失敗 |
| `ACELIB-CMD-004` | 此指令僅限玩家 | console 觸發時拒絕 |
| `ACELIB-CMD-005` | 此指令僅限 console | 玩家觸發時拒絕 |
| `ACELIB-CMD-006` | 冷卻中 | 防止重複觸發 |
| `ACELIB-CMD-007` | 玩家已離線／失效 | 目標玩家離線 |
| `ACELIB-CMD-008` | 非同步指令流程失敗 | async 執行異常 |
| `ACELIB-CMD-009` | 指令註冊服務已停用 | plugin disable 後 |
| `ACELIB-CMD-010` | caller 自訂錯誤碼 | 由 caller 給 code |
| `ACELIB-CMD-011` | 玩家回覆 backend 不可用 | 無法安全派送到玩家所在 region |
| `ACELIB-CMD-012` | `/acelib` 指令綁定失敗 | `plugin.yml` 缺少 `acelib` 指令宣告 |

### 事件管理（EVT）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-EVT-001` | listener handler 內部拋例外 | 事件處理器拋錯（不影響其他 listener） |
| `ACELIB-EVT-002` | Event class 註冊到 PluginManager 失敗 | dispatch 失敗 |
| `ACELIB-EVT-003` | 重複註冊 | 已存在的 identity 重複註冊 |
| `ACELIB-EVT-004` | 插件停用 | disabled 後 register／dispatch |
| `ACELIB-EVT-005` | Folia 下 REQUIRES_REGION listener 在錯誤上下文 | Folia 上非區域執行緒觸發區域綁定 listener |
| `ACELIB-EVT-006` | 註冊事件的 plugin 尚未啟用 | Plugin 尚未完成啟用就註冊 listener |

### 資料儲存（DATA）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-DATA-001` | IO 失敗 | 檔案無法讀寫、目錄無法建立、磁碟空間不足 |
| `ACELIB-DATA-002` | 資料損壞 | 檔案格式錯誤、反序列化失敗、解碼失敗 |
| `ACELIB-DATA-003` | 索引錯誤 | key/path 為 null、空白、不合法 |
| `ACELIB-DATA-004` | 遷移失敗 | migration chain 中任一版本轉換失敗 |
| `ACELIB-DATA-005` | 儲存已關閉 | store 已 close 後仍嘗試操作 |
| `ACELIB-DATA-006` | 序列化失敗 | 型別不支援、循環參考 |
| `ACELIB-DATA-007` | 非同步逾時 | async 等待超過 deadline |
| `ACELIB-DATA-008` | 資料源不可用 | JDBC 連線拒絕、SQL 語法錯誤 |
| `ACELIB-DATA-009` | 無可用 migration | 偵測到舊版本但 chain 中無對應 from |
| `ACELIB-DATA-010` | on-disk schema 版本比 current 新 | 拒絕降版覆寫既有資料 |
| `ACELIB-DATA-011` | 非法 SQL identifier | JdbcDataStore table 名稱驗證失敗 |

### 玩家狀態（PLAYER）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-PLAYER-001` | 資料尚未就緒 | caller 在 LOADING 階段讀取 |
| `ACELIB-PLAYER-002` | 資料載入失敗 | I/O 或反序列化錯誤 |
| `ACELIB-PLAYER-003` | 資料保存失敗 | I/O 或序列化錯誤 |
| `ACELIB-PLAYER-004` | session 重複登入 | 同一 UUID 已有 active session |
| `ACELIB-PLAYER-005` | session 未找到 | caller 對未登入 UUID 操作 |
| `ACELIB-PLAYER-006` | DataStore 未初始化 | store 尚未綁定 |
| `ACELIB-PLAYER-007` | 服務已關閉 | disable/shutdown 後呼叫 join/quit |
| `ACELIB-PLAYER-008` | 內部 serial executor 終止失敗 | serial executor 異常關閉 |

### 世界操作（WORLD）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-WORLD-001` | 服務尚未啟用 | uninitialized／bind 前 |
| `ACELIB-WORLD-002` | 服務已停用 | onDisable／reload 失敗 |
| `ACELIB-WORLD-003` | 指定的 world UUID 不存在於伺服器 | world 找不到 |
| `ACELIB-WORLD-004` | 目標座標所在 chunk 未載入 | chunk 不可用 |
| `ACELIB-WORLD-005` | 目標 entity 已移除／死亡／不在線 | entity 失效 |
| `ACELIB-WORLD-006` | 目標玩家離線 | 玩家離線 |
| `ACELIB-WORLD-007` | 輸入為 null 或語意不合法 | 無效輸入 |
| `ACELIB-WORLD-008` | 目前執行緒不允許修改目標物件 | Folia 上下文違規 |
| `ACELIB-WORLD-009` | 平台不支援此操作 | UNKNOWN／缺失 capability |
| `ACELIB-WORLD-010` | 通用 operation 失敗 | 內部執行拋例外 |
| `ACELIB-WORLD-011` | 效果施展被拒絕 | chunk 未載入／target 不再有效 |
| `ACELIB-WORLD-012` | 鄰近查詢失敗 | 查詢異常 |
| `ACELIB-WORLD-013` | 通用 block 操作失敗 | 如材質不存在 |
| `ACELIB-WORLD-014` | 傳送被 Bukkit 拒絕 | `teleport()` 回傳 false |
| `ACELIB-WORLD-015` | 傳送拋例外 | CompletionStage 異常完成 |
| `ACELIB-WORLD-016` | 跨區域／玩家傳送部分完成 | 第一步成功但第二步失敗 |

### GUI（GUI）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-GUI-001` | 服務尚未啟用 | uninitialized／bind 前 |
| `ACELIB-GUI-002` | 服務已停用 | onDisable／reload 失敗 |
| `ACELIB-GUI-007` | 輸入為 null 或語意不合法 | 無效輸入 |
| `ACELIB-GUI-008` | 該玩家目前沒有 active session | session 不存在 |
| `ACELIB-GUI-009` | 該玩家已開啟 GUI，重複呼叫被拒絕 | 重複 openInventory |
| `ACELIB-GUI-010` | 玩家嘗試操作受保護 slot | slot 保護觸發 |
| `ACELIB-GUI-011` | 傳入的 generation 與持有 session 不符 | generation 不匹配 |
| `ACELIB-GUI-012` | 通用 operation 失敗 | 內部執行拋例外 |
| `ACELIB-GUI-013` | player context executor 拒絕派送 | scheduler disabled、player offline 等 |
| `ACELIB-GUI-014` | confirm/cancel 對已解決的 action 重複呼叫 | action 一次性失效後再觸發 |
| `ACELIB-GUI-015` | action token 不存在或已過期 | session 關閉／shutdown |
| `ACELIB-GUI-016` | 非同步更新請求已過時 | 舊 request 取代新 request |
| `ACELIB-GUI-017` | 非同步更新結果回來時玩家已離線 | 不得對離線玩家執行 inventory mutation |
| `ACELIB-GUI-018` | 非同步更新結果回來時 inventory 已不匹配 | link generation 不符 |

### Item（ITEM）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-ITEM-001` | 規格不合法 | null、必填欄位缺失、型別錯誤 |
| `ACELIB-ITEM-002` | 無法解析的 namespace 或 key | null、空白、不合法字元 |
| `ACELIB-ITEM-003` | 不支援的資料型別／值 | 型別錯誤、大小超出限制 |
| `ACELIB-ITEM-004` | migration 失敗 | chain 中任一版本轉換失敗 |
| `ACELIB-ITEM-005` | 反序列化失敗 | 位元組格式錯誤、無對應 schema |

### 外部整合（EXT）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-EXT-001` | 整合初始化失敗 | adapter 啟動時拋例外 |
| `ACELIB-EXT-002` | 外部插件版本不支援 | 低於需求或無法比較 |
| `ACELIB-EXT-003` | 外部插件未安裝或未啟用 | plugin 不存在或未啟用 |
| `ACELIB-EXT-004` | 整合資源清理失敗 | shutdown 時釋放失敗 |
| `ACELIB-EXT-005` | 整合服務尚未啟用 | facade NOT_READY |
| `ACELIB-EXT-006` | 整合服務已停用 | facade SHUTDOWN |

### 基岩版玩家服務（BED）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-BED-001` | 基岩服務尚未啟用 | uninitialized／bind 前，查詢或 forms() 被拒絕 |
| `ACELIB-BED-002` | 基岩服務已停用 | onDisable／reload 失敗後查詢被拒絕 |
| `ACELIB-BED-003` | 查詢輸入為 null 或語意不合法 | isBedrockPlayer / getPlayerInfo 傳入 null UUID |

### 表單服務（FORM）

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-FORM-001` | 表單服務尚未啟用 | Floodgate 缺席（綁定 absent 發送 seam）時 sendForm 被拒絕 |
| `ACELIB-FORM-002` | 表單服務已停用 | onDisable／reload 失敗後 shutdown，sendForm 被拒絕 |

### 其他

| 代碼 | 說明 | 觸發情境 |
| --- | --- | --- |
| `ACELIB-PLAT-001` | 無法識別的伺服器實作 | 平台偵測失敗 |
| `ACELIB-PLAT-004` | 伺服器實作判定失敗 | UNKNOWN 平台 warning |
| `ACELIB-LANG-001` | 訊息 key 缺失 | locale key 不存在（warning，不中斷） |
| `ACELIB-LANG-002` | 語言檔格式錯誤 | YAML 解析失敗 |
| `ACELIB-DBG-001` | 診斷模組自身錯誤 | reload 時 diagnostics 重綁失敗 |
