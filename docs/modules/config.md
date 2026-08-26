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
