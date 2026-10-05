# 設定檔

> 適合要在自己 plugin 中建立、讀寫與遷移設定檔的開發者。


`ConfigManager` 管理 YAML 設定、預設值、版本與遷移。它由 consumer 自行建立，不是從 `AceLibApi` 取得。

## 建立並載入

```java
ConfigSchema schema = new ConfigSchema(
    new ConfigVersion(1, 0),
    List.of(
        new FieldSpec("locale", "zh_TW", true),
        new FieldSpec("prefix", "&7[MyPlugin] ", true)
    ));

ConfigManager config = new ConfigManager(
    this,
    "config.yml",
    schema,
    new ConfigVersion(1, 0));

config.load();
```

檔案不存在時，`load()` 會依 schema 建立預設設定。`load()` 可重複呼叫；第一次成功後，後續呼叫不會再次載入。

## 讀寫與重載

```java
Object locale = config.get("locale");
config.set("prefix", "&7[MyPlugin] ");
config.save();

boolean reloaded = config.reload();
```

必須先 `load()` 才能 `set()` 或 `save()`。`reload()` 失敗時會保留舊值，不會用部分讀取的內容覆寫目前設定。

成功的 `reload()` 會把補齊欄位、執行遷移並收斂版本之後的內容重新序列化回寫磁碟；即使磁碟上的版本與當前版本相同、內容其實沒有變動，一樣會寫回。`load()` 走的是同一條路徑，兩邊行為一致。

> **設定檔裡的註解不會保留（目前實作）**：解析後的設定只保留鍵與值，回寫時是從這些值重新輸出一份新的 YAML，原檔的註解不會寫回去。管理員寫在設定檔裡的說明文字，會在成功的 `load()` 或 `reload()` 回寫之後消失（版本較新被拒絕、或替換開始前的失敗不動目標檔；取代開始後的保證見下一節「原子寫入與暫存檔清理」）；需要長期存在的說明應放在文件或 schema 的欄位說明，不要只寫在設定檔註解裡。

## 版本較新時拒絕降版

磁碟上的 `version` 比目前的 `ConfigVersion` 新時，`load()` 會拋 `ACELIB-CFG-006` 並保持檔案逐位元不變；`reload()` 則回傳 `false`、保留舊設定且不改檔。`save()` 在未成功 `load()` 前會被擋下，因此不會把較新版本降版寫回。

## 重載失敗保留舊值並記錄原因

`reload()` 沿用回傳 `boolean` 的約定：成功回傳 `true`，失敗回傳 `false` 且已生效的設定不變。每次失敗都會以 `WARNING` 記錄原因並附上 `ACELIB-CFG-*` 代碼與檔名，可從日誌直接追查，不做靜默的 `false`。版本比較、預設值補齊與遷移的順序和 `load()` 共用同一條路徑，兩邊行為一致。

## 原子寫入與暫存檔清理

設定與語系檔寫入都走暫存檔再取代：先把內容寫到與目標檔同目錄的暫存檔，完整寫好後才取代目標檔；暫存檔與目標檔在同一目錄，不涉及跨檔案系統搬移。暫存檔建置與寫入階段失敗時目標檔逐位元不變，且暫存檔會被清掉；清理自己失敗時，清理錯誤以 `suppressed` 附在原始寫入錯誤上，兩邊原因都可在例外鏈查到。原子取代只需要父目錄可寫，不要求目標檔本身可寫。

取代時先要求 `ATOMIC_MOVE`；只有當底層明確回報不支援原子搬移時，才改用 `REPLACE_EXISTING` 重試一次。降級只是多一次機會，不是保證：取代本身仍可能失敗（此時拋 `ACELIB-CFG-001`；原子搬移的檔案系統上舊檔保留，降級取代的檔案系統上一旦取代開始後失敗，不保證舊檔完整），所以寫入流程**不保證在各平台都完成**，**原子性也不是保證**：只有在支援原子搬移的檔案系統上，取代才真的具備「要嘛整份換成新檔、要嘛完全不動」的性質。在只能降級的檔案系統上，取代是覆寫原項目，寫入期間目標檔可能短暫處於不完整狀態；這條限制無法從 API 層消除，只能靠選擇支援原子搬移的檔案系統來避免。

> **目標檔的檔案權限可能改變（尚未實測）**：取代動作把新檔案掛到原來的路徑，因此檔案權限可能改為暫存檔建立時的權限，而不是保留目標檔原有的權限。目前沒有測試或實機觀察確認實際結果，也沒有測試涵蓋權限被改動後的後果；需要固定權限的環境應自行在部署後確認，或在檔案系統層設定預設權限。

## 遷移舊設定

實作 `ConfigMigration`，提供 `fromVersion()`、`toVersion()` 與 `migrate(...)`，再於 `load()` 前註冊：

```java
config.registerMigration(new MyConfigMigration());
config.load();
```

遷移必須形成連續版本鏈。缺少必要遷移或遷移失敗時，AceLib 會保留原資料並回報 `ACELIB-CFG-*`。

多語系檔案由 `LangManager` 處理，訊息輸出方式見[訊息服務](message.md)。錯誤碼見[完整查表](../reference/error-codes.md)。

## 相關頁面

- [訊息服務](message.md)
- [資料儲存](data.md)
- [錯誤碼](../reference/error-codes.md)
