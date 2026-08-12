package com.smile.acelib.item;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.item.AceItemFactory.ItemSpec;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@link AceItemFactory} 端對端行為測試。
 *
 * <p>對應 Plan Phase 12 自訂物品核心：</p>
 * <ul>
 *   <li>建立帶名稱、lore、namespace/key/version 自訂識別資料的 ItemStack</li>
 *   <li>改名後仍可依 PDC 識別</li>
 *   <li>不同 namespace/key 不互相誤判</li>
 *   <li>serialize/deserialize round-trip 保留必要資料</li>
 *   <li>display-only 與 gameplay tag 分離</li>
 *   <li>舊版資料可升級；升級失敗不破壞原物品並回報 {@code ACELIB-ITEM-*}</li>
 *   <li>multi-step migration 第一步 metadata 修改在第二步失敗時必須 rollback</li>
 *   <li>partial identity（缺欄位）不可被 {@link AceItemFactory#identify} 視為「自家」物品</li>
 *   <li>null / blank / 不支援資料：拋 ItemException</li>
 * </ul>
 *
 * <p>測試環境為 MockBukkit，PDC 走 {@link ServerMock} 的 fake 實作；
 * 部分測試若 MockBukkit 對特定 meta 行為有限制，將以人工檢查步驟與替代證據紀錄。</p>
 */
@DisplayName("AceItemFactory")
class AceItemFactoryTest {

    private static ServerMock server;
    private static AceItemFactory factory;

    /** 測試用 helper：直接寫入 schema version PDC，模擬「舊版資料」。 */
    private static void writeSchemaVersion(ItemStack stack, ItemSchemaVersion version) {
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(
            new NamespacedKey(factory.namespace(), "_version"),
            PersistentDataType.STRING,
            version.major() + "." + version.minor());
        stack.setItemMeta(meta);
    }

    @BeforeAll
    static void setUp() {
        server = MockBukkit.mock();
        factory = AceItemFactory.create("acelib");
    }

    @AfterAll
    static void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("create：建立帶 display name / lore / 自訂 identity 的 ItemStack")
    void create_setsDisplayNameAndIdentity() {
        ItemStack stack = factory.create(ItemSpec.builder()
            .material(Material.DIAMOND_SWORD)
            .displayName("Heritage Blade")
            .lore("Line 1", "Line 2")
            .identity(new ItemIdentity("acelib", "blade", 1, 0))
            .gameplayTag("skill_damage", 12)
            .build());
        assertNotNull(stack);
        assertEquals(Material.DIAMOND_SWORD, stack.getType());
        ItemMeta meta = stack.getItemMeta();
        assertTrue(meta.hasCustomName(), "應設定 custom name");
        assertEquals("Heritage Blade", serializeComponent(meta.customName()));
        assertTrue(meta.hasLore());
        assertEquals(List.of("Line 1", "Line 2"), meta.lore().stream()
            .map(AceItemFactoryTest::serializeComponent).toList());
        // Identity 走 PDC 寫入
        ItemIdentity written = factory.readIdentity(stack).orElseThrow();
        assertEquals(new ItemIdentity("acelib", "blade", 1, 0), written);
    }

    @Test
    @DisplayName("rename 後仍可依 PDC identity 辨識")
    void rename_preservesIdentity() {
        ItemStack stack = factory.create(ItemSpec.builder()
            .material(Material.DIAMOND_SWORD)
            .displayName("Original")
            .identity(new ItemIdentity("acelib", "blade", 1, 0))
            .build());
        // 模擬重新命名
        ItemMeta meta = stack.getItemMeta();
        meta.customName(Component.text("Renamed"));
        stack.setItemMeta(meta);
        assertTrue(factory.identify(stack));
        assertEquals(new ItemIdentity("acelib", "blade", 1, 0),
            factory.readIdentity(stack).orElseThrow());
    }

    @Test
    @DisplayName("不同 namespace 不互相誤判")
    void differentNamespace_isolation() {
        AceItemFactory other = AceItemFactory.create("otherplug");
        ItemStack mine = factory.create(ItemSpec.builder()
            .material(Material.DIAMOND_SWORD)
            .identity(new ItemIdentity("acelib", "blade", 1, 0)).build());
        ItemStack theirs = other.create(ItemSpec.builder()
            .material(Material.DIAMOND_SWORD)
            .identity(new ItemIdentity("otherplug", "blade", 1, 0)).build());
        // 兩者 key 相同但 namespace 不同；identify 必須分開看
        assertTrue(factory.identify(mine));
        assertFalse(factory.identify(theirs), "acelib factory 不應識別 otherplug namespace");
        assertTrue(other.identify(theirs));
    }

    @Test
    @DisplayName("無 identity 的 ItemStack：identify 回傳 false")
    void vanillaItem_isNotIdentified() {
        ItemStack stack = new ItemStack(Material.STONE);
        assertFalse(factory.identify(stack));
        assertFalse(factory.readIdentity(stack).isPresent());
    }

    @Test
    @DisplayName("identify：部分 identity（只寫 key、缺 namespace/major/minor）回傳 false")
    void identify_partialIdentity_returnsFalse() {
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        // 故意只寫 _id，缺 namespace / major / minor
        meta.getPersistentDataContainer().set(
            new NamespacedKey(factory.namespace(), "_id"),
            PersistentDataType.STRING, "blade");
        stack.setItemMeta(meta);
        assertFalse(factory.identify(stack),
            "部分 identity 不可被視為「自家」物品（避免偽造 PDC 通過 identify）");
        assertFalse(factory.readIdentity(stack).isPresent());
    }

    @Test
    @DisplayName("identify：key 一致但 namespace 不一致回傳 false")
    void identify_crossNamespaceKeyOnly_returnsFalse() {
        // 用 acelib factory 的 _id key 但寫入「otherplug」namespace 字串，
        // 模擬 namespace 欄位存在但與 factory.namespace 不符
        ItemStack stack = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = stack.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        pdc.set(new NamespacedKey(factory.namespace(), "_id_namespace"),
            PersistentDataType.STRING, "otherplug");
        pdc.set(new NamespacedKey(factory.namespace(), "_id"),
            PersistentDataType.STRING, "blade");
        pdc.set(new NamespacedKey(factory.namespace(), "_id_major"),
            PersistentDataType.INTEGER, 1);
        pdc.set(new NamespacedKey(factory.namespace(), "_id_minor"),
            PersistentDataType.INTEGER, 0);
        stack.setItemMeta(meta);
        assertFalse(factory.identify(stack),
            "namespace 不符的完整 PDC 不可被 acelib factory 視為自家物品");
    }

    @Test
    @DisplayName("serialize / deserialize round-trip：保留 identity、display、gameplay tag")
    void serializeRoundTripPreservesPayload() throws Exception {
        ItemStack original = factory.create(ItemSpec.builder()
            .material(Material.DIAMOND_SWORD)
            .displayName("Heritage Blade")
            .lore("Line A")
            .identity(new ItemIdentity("acelib", "blade", 1, 0))
            .gameplayTag("damage", 42)
            .displayTag("rarity", "rare")
            .build());
        byte[] serialized = factory.serialize(original);
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);

        ItemStack restored = factory.deserialize(serialized).orElseThrow();
        assertEquals(original.getType(), restored.getType());
        // display name
        ItemMeta meta = restored.getItemMeta();
        assertTrue(meta.hasCustomName());
        assertEquals("Heritage Blade", serializeComponent(meta.customName()));
        // identity
        ItemIdentity id = factory.readIdentity(restored).orElseThrow();
        assertEquals(new ItemIdentity("acelib", "blade", 1, 0), id);
        // gameplay tag
        assertEquals(42, factory.readGameplayInt(restored, "damage").orElseThrow());
        // display tag
        assertEquals("rare", factory.readDisplayString(restored, "rarity").orElseThrow());
    }

    @Test
    @DisplayName("display-only tag 與 gameplay tag 分屬不同 namespace key prefix")
    void displayVsGameplayTags_areSeparated() {
        ItemStack stack = factory.create(ItemSpec.builder()
            .material(Material.DIAMOND_SWORD)
            .identity(new ItemIdentity("acelib", "blade", 1, 0))
            .gameplayTag("damage", 10)
            .displayTag("theme", "fire")
            .build());
        // gameplay 與 display 應分別可被讀取，且互相獨立
        assertEquals(10, factory.readGameplayInt(stack, "damage").orElseThrow());
        assertEquals("fire", factory.readDisplayString(stack, "theme").orElseThrow());
        // 在 display 層讀 gameplay key 應取不到
        assertFalse(factory.readDisplayInt(stack, "damage").isPresent());
        assertFalse(factory.readGameplayString(stack, "theme").isPresent());
    }

    @Test
    @DisplayName("create：null spec 拋 ItemException")
    void createNullSpec_throws() {
        assertThrows(ItemException.class, () -> factory.create((ItemSpec) null));
    }

    @Test
    @DisplayName("create：null material � ItemException")
    void createNullMaterial_throws() {
        ItemSpec spec = ItemSpec.builder()
            .identity(new ItemIdentity("acelib", "blade", 1, 0))
            .build();
        assertThrows(ItemException.class, () -> factory.create(spec));
    }

    @Test
    @DisplayName("create：null identity 拋 ItemException (本 factory 需要 explicit identity)")
    void createNullIdentity_throws() {
        ItemSpec spec = ItemSpec.builder().material(Material.DIAMOND_SWORD).build();
        assertThrows(ItemException.class, () -> factory.create(spec));
    }

    @Test
    @DisplayName("migrate：舊版 0.9 ItemStack 升級後 formatVersion 與內容保留")
    void migrate_oldVersionUpgrades() {
        ItemStack oldStack = factory.createLegacy(new ItemIdentity("acelib", "blade", 1, 0),
            Material.DIAMOND_SWORD, 1);
        // 模擬舊版資料降版
        writeSchemaVersion(oldStack, new ItemSchemaVersion(0, 9));

        ItemMigrationChain chain = new ItemMigrationChain()
            .add(new ItemMigration() {
                @Override public ItemSchemaVersion fromVersion() { return new ItemSchemaVersion(0, 9); }
                @Override public ItemSchemaVersion toVersion() { return new ItemSchemaVersion(1, 0); }
                @Override public void migrate(ItemMigrationContext ctx) {
                    ctx.writeVersion(new ItemSchemaVersion(1, 0));
                }
            });
        ItemMigrationResult r = factory.migrate(oldStack, new ItemSchemaVersion(1, 0), chain);
        assertTrue(r.success(), "升級結果: " + r.errorMessage());
        assertEquals(new ItemSchemaVersion(1, 0),
            factory.readSchemaVersion(oldStack, factory.namespace()).orElseThrow());
        // display/lore 等保留
        assertEquals(Material.DIAMOND_SWORD, oldStack.getType());
    }

    @Test
    @DisplayName("migrate 成功：commit 後 identity / display / gameplay metadata 變更都生效")
    void migrate_success_commitsMetadataChanges() {
        ItemStack before = factory.create(ItemSpec.builder()
            .material(Material.DIAMOND_SWORD)
            .displayName("Before")
            .identity(new ItemIdentity("acelib", "blade", 1, 0))
            .gameplayTag("damage", 5)
            .build());
        // 注入 0.9 舊版資料
        writeSchemaVersion(before, new ItemSchemaVersion(0, 9));

        ItemMigrationChain chain = new ItemMigrationChain()
            .add(new ItemMigration() {
                @Override public ItemSchemaVersion fromVersion() { return new ItemSchemaVersion(0, 9); }
                @Override public ItemSchemaVersion toVersion() { return new ItemSchemaVersion(1, 0); }
                @Override public void migrate(ItemMigrationContext ctx) {
                    ctx.writeGameplayInt("damage", 99);
                    ctx.writeDisplayName(Component.text("Upgraded"));
                    ctx.writeDisplayString("theme", "shadow");
                    ctx.writeVersion(new ItemSchemaVersion(1, 0));
                }
            });

        ItemMigrationResult r = factory.migrate(before, new ItemSchemaVersion(1, 0), chain);
        assertTrue(r.success(), "migrate 結果: " + r.errorMessage());

        // commit 後 metadata 必須實際生效於 stack
        assertEquals(99, factory.readGameplayInt(before, "damage").orElseThrow(),
            "migration 寫入的 gameplay int 必須 commit 到 stack");
        assertEquals("shadow", factory.readDisplayString(before, "theme").orElseThrow(),
            "migration 寫入的 display string 必須 commit 到 stack");
        ItemMeta metaAfter = before.getItemMeta();
        assertEquals("Upgraded", serializeComponent(metaAfter.customName()),
            "migration 寫入的 display name 必須 commit 到 stack");
        assertEquals(new ItemSchemaVersion(1, 0),
            factory.readSchemaVersion(before, factory.namespace()).orElseThrow(),
            "schema version 必須推進");
    }

    @Test
    @DisplayName("migrate 失敗：輸入 ItemStack 完全不被修改，拋 ItemException (ACELIB-ITEM-004)")
    void migrate_failure_leavesInputUnchanged() {
        ItemStack before = factory.create(ItemSpec.builder()
            .material(Material.DIAMOND_SWORD)
            .displayName("Before")
            .identity(new ItemIdentity("acelib", "blade", 1, 0))
            .gameplayTag("damage", 5)
            .build());
        // 注入 0.9 舊版資料：讓 migrate() 真的會跑 chain（current != target）
        writeSchemaVersion(before, new ItemSchemaVersion(0, 9));
        byte[] snapshot = factory.serialize(before);

        ItemMigrationChain chain = new ItemMigrationChain()
            .add(new ItemMigration() {
                @Override public ItemSchemaVersion fromVersion() { return new ItemSchemaVersion(0, 9); }
                @Override public ItemSchemaVersion toVersion() { return new ItemSchemaVersion(1, 0); }
                @Override public void migrate(ItemMigrationContext ctx) {
                    throw new IllegalStateException("nope");
                }
            });
        ItemException ex = assertThrows(ItemException.class,
            () -> factory.migrate(before, new ItemSchemaVersion(1, 0), chain));
        assertEquals(ItemErrorCode.MIGRATION_FAILED, ex.getCode());
        // 還原：serialize 必須與執行 migrate 之前一致
        byte[] after = factory.serialize(before);
        assertArrayEquals(snapshot, after,
            "migration 失敗時輸入 ItemStack 必須保持原樣（serialize bytes 不變）");
    }

    @Test
    @DisplayName("migrate 多步：第一步修改 metadata、第二步失敗；第一步變更必須 rollback")
    void migrate_multiStep_rollbackLeavesNoMetadataSideEffects() {
        ItemStack before = factory.create(ItemSpec.builder()
            .material(Material.DIAMOND_SWORD)
            .displayName("Before")
            .identity(new ItemIdentity("acelib", "blade", 1, 0))
            .gameplayTag("damage", 5)
            .build());
        writeSchemaVersion(before, new ItemSchemaVersion(0, 9));
        byte[] snapshot = factory.serialize(before);

        ItemMigrationChain chain = new ItemMigrationChain()
            .add(new ItemMigration() {
                @Override public ItemSchemaVersion fromVersion() { return new ItemSchemaVersion(0, 9); }
                @Override public ItemSchemaVersion toVersion() { return new ItemSchemaVersion(1, 0); }
                @Override public void migrate(ItemMigrationContext ctx) {
                    // 第一步：寫入若干 metadata 變更
                    ctx.writeGameplayInt("damage", 99);
                    ctx.writeDisplayName(Component.text("Mid"));
                    ctx.writeDisplayString("theme", "shadow");
                    ctx.writeVersion(new ItemSchemaVersion(1, 0));
                }
            })
            .add(new ItemMigration() {
                @Override public ItemSchemaVersion fromVersion() { return new ItemSchemaVersion(1, 0); }
                @Override public ItemSchemaVersion toVersion() { return new ItemSchemaVersion(1, 1); }
                @Override public void migrate(ItemMigrationContext ctx) {
                    throw new IllegalStateException("simulated mid-chain failure");
                }
            });

        ItemException ex = assertThrows(ItemException.class,
            () -> factory.migrate(before, new ItemSchemaVersion(1, 1), chain));
        assertEquals(ItemErrorCode.MIGRATION_FAILED, ex.getCode());

        // serialize bytes 必須與執行 migrate 前一致（整批 rollback）
        byte[] after = factory.serialize(before);
        assertArrayEquals(snapshot, after,
            "多步 migration 失敗：第一步的 metadata 變更必須全部 rollback（serialize bytes 不變）");
        // 第一步寫入的數值必須不存在
        assertEquals(5, factory.readGameplayInt(before, "damage").orElse(-1),
            "damage 必須仍是初始值 5，第一步寫入的 99 必須被 rollback");
        assertFalse(factory.readDisplayString(before, "theme").isPresent(),
            "display theme 不可殘留，第一步寫入必須被 rollback");
        ItemMeta metaAfter = before.getItemMeta();
        assertEquals("Before", serializeComponent(metaAfter.customName()),
            "display name 必須仍是初始值，第一步寫入的 Mid 必須被 rollback");
    }

    @Test
    @DisplayName("migrate：到目前版本時 no-op，不修改輸入")
    void migrate_alreadyCurrent_isNoop() {
        ItemStack before = factory.create(ItemSpec.builder()
            .material(Material.DIAMOND_SWORD)
            .identity(new ItemIdentity("acelib", "blade", 1, 0))
            .build());
        byte[] snapshot = factory.serialize(before);
        ItemMigrationResult r = factory.migrate(before, new ItemSchemaVersion(1, 0),
            new ItemMigrationChain());
        assertTrue(r.success());
        assertArrayEquals(snapshot, factory.serialize(before));
    }

    @Test
    @DisplayName("key collision：不同 factory 寫入相同 namespace.key 應被不同 factory 視為不同資料")
    void keyCollision_isIsolatedByFactoryNamespace() {
        AceItemFactory a = AceItemFactory.create("alpha");
        AceItemFactory b = AceItemFactory.create("beta");
        ItemStack sa = a.create(ItemSpec.builder()
            .material(Material.STONE)
            .identity(new ItemIdentity("acelib", "rock", 1, 0))
            .gameplayTag("damage", 5)
            .build());
        // alpha factory 寫的 key 不應被 beta factory 讀到「自家的 gameplay」
        // —— 因為 namespace prefix 是以 factory.namespace() 區隔的
        assertEquals(5, a.readGameplayInt(sa, "damage").orElseThrow());
        // beta factory 對同一個 stack 不應該讀到 alpha 的 gameplay damage key
        assertFalse(b.readGameplayInt(sa, "damage").isPresent());
    }

    @Test
    @DisplayName("createLegacy：對外 API 走 ItemStack 序列化（不依賴 PDC Bukkit.copy）— 供向後相容測試")
    void createLegacy_returnsValidItemStack() {
        ItemStack legacy = factory.createLegacy(new ItemIdentity("acelib", "rock", 1, 0),
            Material.STONE, 1);
        assertNotNull(legacy);
        assertEquals(Material.STONE, legacy.getType());
    }

    /**
     * Adventure {@link Component} 序列化成純字串。
     */
    private static String serializeComponent(Component component) {
        AtomicReference<String> ref = new AtomicReference<>();
        try {
            // Paper / Adventure 提供 plain text serializer
            ref.set(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(component));
        } catch (Throwable ignored) {
            ref.set(component.toString());
        }
        return ref.get();
    }
}
