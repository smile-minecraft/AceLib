# 自訂物品與資料遷移

本頁解決如何建立可辨識的自訂物品、序列化／反序列化，以及以 migration 更新舊資料的問題。契約唯一來源是
> `src/main/java/com/smile/acelib/item/**` source 與 tests；
> 本頁為導覽鏡像，不複製完整 JavaDoc。

## 前置條件與最短路徑

以 plugin namespace 建立 `AceItemFactory`，建立 `ItemSpec` 後呼叫 `create`；需要保存時使用 `serialize` 與 `deserialize`。

## 預期結果

每次建立或反序列化都得到新的 `ItemStack`；migration 全部成功才 commit，失敗會 rollback 並回報 `ACELIB-ITEM-004`。

## 1. 取得方式

`AceItemFactory` 是獨立 factory，不需要經 `AceLibApi`：

```java
import com.smile.acelib.item.AceItemFactory;
import com.smile.acelib.item.ItemIdentity;

AceItemFactory factory = AceItemFactory.create("myplugin"); // namespace 前綴
```

每個 factory 綁定一個 namespace，作為 PDC key 的前綴，隔離不同 plugin 的 tag；
不同 factory 即使 key 字串重疊也不會互相誤判。

## 2. 最小安全範例

```java
import com.smile.acelib.item.AceItemFactory;
import com.smile.acelib.item.ItemIdentity;
import org.bukkit.Material;

AceItemFactory factory = AceItemFactory.create("myplugin");
ItemIdentity id = new ItemIdentity("myplugin", "golden_sword", 1, 0);

// 建立：回傳「新的獨立 ItemStack」，caller 可自由修改
org.bukkit.inventory.ItemStack stack = factory.create(
    AceItemFactory.ItemSpec.builder()
        .identity(id)
        .material(Material.GOLDEN_SWORD)
        .amount(1)
        .displayName("黃金之劍")
        .build());

// 辨識：依 PDC 三欄位（namespace/key/major/minor），不依賴 display name
boolean mine = factory.identify(stack);

// 序列化：可寫入任意 byte store
byte[] bytes = factory.serialize(stack);

// 反序列化：回傳新的 ItemStack
org.bukkit.inventory.ItemStack restored =
    factory.deserialize(bytes).orElseThrow();
```

## 3. Ownership 與 Mutability

- 所有 factory 方法回傳「新物件」：caller 修改回傳的 `ItemStack` 不影響
  factory 內部狀態。
- `serialize` 回傳位元組陣列（含物種／amount／meta／全部自訂 PDC／lore）；
  `deserialize` 回傳新的 `ItemStack`。
- **`migrate` 對輸入 `ItemStack` 做原地修改（若 chain 成功）**：執行前 clone
  `ItemMeta` 為工作複本；任一 migration 失敗會 rollback（`setItemMeta(backup)`），
  輸入資料保持原樣，並拋 `ItemException`（`ACELIB-ITEM-004`）。
- tag 分層：`gameplayTag`（參與遊戲邏輯）與 `displayTag`（僅顯示）分開；
  `readDisplayInt` 讀不到寫成 gameplay 的 int（反之亦然）。

## 4. Schema Migration（SPI）

`ItemMigration` 是 SPI：實作者提供 `fromVersion()` → `toVersion()` 與
`migrate(ItemMigrationContext)`；多個 migration 以 `ItemMigrationChain` 串接：

```java
ItemMigrationChain chain = new ItemMigrationChain()
    .add(new ItemMigration() {
        public ItemSchemaVersion fromVersion() { return new ItemSchemaVersion(1, 0); }
        public ItemSchemaVersion toVersion()   { return new ItemSchemaVersion(1, 1); }
        public void migrate(ItemMigrationContext ctx) {
            // 讀取舊資料（readXxx）→ 轉換 → 寫入工作複本（writeXxx）
            ctx.writeVersion(new ItemSchemaVersion(1, 1));
        }
    });

ItemMigrationResult result = factory.migrate(stack,
    new ItemSchemaVersion(1, 1), chain);
```

- 任一 migration 拋例外 → `ItemMigrationResult.failure` + rollback，
  `stack` 不被部分修改。
- `ItemMigrationContext` 是 in-memory 工作複本視圖：所有 `writeXxx` 只作用於
  複本，直到 chain 全部成功才 commit 回 `ItemStack`。

## 5. 執行緒與平台

- `AceItemFactory` 本身無可變狀態：`namespace` 與 PDC key 集為 immutable，
  單一 factory 實例可跨執行緒共用。
- 但 `ItemStack` / `ItemMeta` / `PersistentDataContainer` 都是 **mutable
  Bukkit 物件**，其執行緒／上下文限制由伺服器實作定義：Paper 通常要求主執行緒；
  Folia 對 inventory／世界綁定物件要求所屬 region thread。factory 不做同步、
  不派送排程，**不承諾**跨執行緒操作這些物件安全。
- 呼叫端必須依執行環境（Paper／Folia）在伺服器允許的上下文內建立／存取物品；
  不要把 inventory／世界綁定的 `ItemStack` 帶到背景執行緒讀寫。

## 常見失敗與錯誤碼

見 `ItemErrorCode`（`ACELIB-ITEM-001` ~ `ACELIB-ITEM-005`）：

- `ACELIB-ITEM-001 INVALID_SPEC`：規格不合法（null／必填缺失）
- `ACELIB-ITEM-002 UNKNOWN_NAMESPACE`：namespace／key 空白或不合法
- `ACELIB-ITEM-003 UNSUPPORTED_DATA`：不支援的資料型別
- `ACELIB-ITEM-004 MIGRATION_FAILED`：migration chain 失敗（rollback）
- `ACELIB-ITEM-005 DESERIALIZE_FAILED`：序列化／反序列化失敗

所有例外透過 `ItemException`（unchecked）拋出並攜帶對應代碼。

## 下一步

- 需要保存玩家資料：查看 [資料儲存](data.md)。
- 需要處理玩家 GUI：查看 [GUI 服務](gui.md)。

## 查核來源

- 入口：`AceItemFactory`（Supported）
- SPI：`ItemMigration`、`ItemMigrationContext`
- 型別：`ItemIdentity`、`ItemSchemaVersion`、`ItemMigrationResult`、
  `ItemMigrationChain`、`ItemException`、`ItemErrorCode`
- 測試：`src/test/java/com/smile/acelib/item/AceItemFactoryTest.java`、
  `ItemIdentityTest.java`、`ItemMigrationChainTest.java`
