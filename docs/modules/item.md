# 自訂物品

> 適合要建立、辨識與遷移自訂物品的插件開發者。


`AceItemFactory` 可建立帶識別資料的 `ItemStack`，讀寫 gameplay/display tag，並支援序列化與 schema 遷移。Factory 依 plugin namespace 隔離 PDC key。

## 目錄

- [建立與辨識](#建立與辨識)
- [保存與還原](#保存與還原)
- [遷移舊物品](#遷移舊物品)
- [執行緒限制](#執行緒限制)

## 建立與辨識

```java
AceItemFactory factory = AceItemFactory.create("myplugin");
ItemIdentity identity = new ItemIdentity("myplugin", "golden_sword", 1, 0);

ItemStack stack = factory.create(
    AceItemFactory.ItemSpec.builder()
        .identity(identity)
        .material(Material.GOLDEN_SWORD)
        .amount(1)
        .displayName("黃金之劍")
        .build());

boolean belongsToFactory = factory.identify(stack);
```

辨識依 Persistent Data Container 中的 namespace、key 與版本，不依賴 display name。

## 保存與還原

```java
byte[] bytes = factory.serialize(stack);
ItemStack restored = factory.deserialize(bytes).orElseThrow();
```

`create` 與 `deserialize` 都會回傳新的 `ItemStack`。呼叫端可以修改回傳物件，不會改到 factory 內部狀態。

## 遷移舊物品

將多個 `ItemMigration` 加入 `ItemMigrationChain`，再呼叫：

```java
ItemMigrationResult result = factory.migrate(
    stack,
    new ItemSchemaVersion(1, 1),
    chain);
```

遷移成功後會原地更新傳入的 `ItemStack`。任何步驟失敗時會回復原本 ItemMeta，並以 `ACELIB-ITEM-004` 回報。

## 執行緒限制

Factory 本身沒有可變狀態，但 `ItemStack`、`ItemMeta` 與 PDC 都是 Bukkit 可變物件。不要把 inventory 或世界中的物品帶到任意背景執行緒讀寫。Paper 使用主執行緒；Folia 要遵守持有該 inventory 或世界物件的 region。

完整代碼見[錯誤碼](../reference/error-codes.md)。

## 相關頁面

- [資料儲存](data.md)
- [世界操作](world.md)
- [錯誤碼](../reference/error-codes.md)
