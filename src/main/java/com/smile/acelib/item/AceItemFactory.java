package com.smile.acelib.item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * 自訂物品工廠（公開 API facade）。
 *
 * <p>對應 Plan Phase 12 自訂物品核心：
 * 以 Paper API 的 {@link ItemStack} + {@link org.bukkit.persistence.PersistentDataContainer}
 * 建立可被辨識、可序列化、可升級的「自訂物品」。
 * 所有 {@link ItemStack} 上的「辨識資料」「display-only tag」「gameplay tag」
 * 統一透過此 factory 與其內部 PDC keying 處理，外部呼叫端無需感知 low-level 細節。</p>
 *
 * <h2>辨識與隔離</h2>
 * <ul>
 *   <li>每個 factory 綁定一個 {@code namespace}，做為 PDC key 的上一層前綴</li>
 *   <li>辨識以 {@link ItemIdentity} 三欄位（namespace / key / formatVersion）為依據；
 *       <strong>不依賴 display name / lore</strong></li>
 *   <li>不同 factory 寫入的 tag 即使 key 字串重疊，也不會互相誤判</li>
 * </ul>
 *
 * <h2>序列化</h2>
 * <p>呼叫 {@link #serialize(ItemStack)} 回傳可寫入任意 byte store 的位元組陣列；
 * 反向透過 {@link #deserialize(byte[])} 還原為新的 {@link ItemStack}。
 * 序列化內容包含：物種 / amount / meta / 全部自訂 PDC / 顯示層 lore 等。</p>
 *
 * <h2>升級</h2>
 * <p>見 {@link #migrate}：在 in-memory write 視圖上跑 migration chain；
 * 任一失敗觸發 rollback，<strong>輸入 ItemStack 不被破壞</strong>，並回報
 * {@link ItemErrorCode#MIGRATION_FAILED}。</p>
 *
 * <h2>執行緒與平台</h2>
 * <ul>
 *   <li>本類別不假設主執行緒；所有 ItemStack / meta / PDC 操作皆為同步、執行緒安全</li>
 *   <li>Paper 與 Folia 行為一致（Bukkit PDC API 跨平台定義）</li>
 * </ul>
 */
public final class AceItemFactory {

    private final String namespace;
    private final NamespacedKeys keys;

    private AceItemFactory(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new ItemException(ItemErrorCode.UNKNOWN_NAMESPACE,
                "AceItemFactory namespace 不可為 null 或空字串");
        }
        this.namespace = namespace;
        this.keys = new NamespacedKeys(namespace);
    }

    /**
     * 建立以指定 {@code namespace} 為前綴的 factory。
     *
     * @param namespace 綁定 plugin / 模組命名空間
     * @return 新的 {@link AceItemFactory}
     */
    public static AceItemFactory create(String namespace) {
        return new AceItemFactory(namespace);
    }

    /**
     * 取得此 factory 的 namespace。
     *
     * @return 永遠不為 null
     */
    public String namespace() {
        return namespace;
    }

    /**
     * 依 spec 建立自訂物品 ItemStack。
     *
     * @param spec 不可為 null
     * @return 帶 PDC 識別資料的 {@link ItemStack}
     * @throws ItemException 當 {@code spec} 或其 {@code material} / {@code identity} 為 null 時拋出（{@code ACELIB-ITEM-001}）
     */
    public ItemStack create(ItemSpec spec) {
        if (spec == null) {
            throw new ItemException(ItemErrorCode.INVALID_SPEC, "ItemSpec 不可為 null");
        }
        if (spec.material() == null) {
            throw new ItemException(ItemErrorCode.INVALID_SPEC, "ItemSpec.material 不可為 null");
        }
        if (spec.identity() == null) {
            throw new ItemException(ItemErrorCode.INVALID_SPEC, "ItemSpec.identity 不可為 null");
        }

        ItemStack stack = new ItemStack(spec.material(), spec.amount());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            throw new ItemException(ItemErrorCode.INVALID_SPEC,
                "Material " + spec.material() + " 不支援 ItemMeta (無法建立自訂物品)");
        }

        if (spec.displayName() != null) {
            meta.customName(spec.displayName());
        }
        if (spec.lore() != null && !spec.lore().isEmpty()) {
            meta.lore(spec.lore());
        }

        // Identity 寫入 PDC（使用 factory 自身的 prefix，確保隔離）
        writeIdentity(meta, spec.identity());
        writeSchemaVersion(meta, ItemSchemaVersion.V1_0);

        stack.setItemMeta(meta);

        // Gameplay tags & display tags
        if (spec.gameplayTags() != null) {
            for (Map.Entry<String, GameplayTag> e : spec.gameplayTags().entrySet()) {
                applyGameplayTag(stack, e.getKey(), e.getValue());
            }
        }
        if (spec.displayTags() != null) {
            for (Map.Entry<String, Object> e : spec.displayTags().entrySet()) {
                applyDisplayTag(stack, e.getKey(), e.getValue());
            }
        }

        return stack;
    }

    /**
     * 為向後相容的 legacy 建立方式 — 直接以 {@link ItemIdentity} 與材質建立最小 ItemStack。
     *
     * <p>此方法不做 lore / display 等附加欄位，僅寫入 identity PDC；
     * 主要供「舊版測試資料」或「最簡建立」場景使用。{@link #create(ItemSpec)} 是主要入口。</p>
     *
     * @param identity 不可為 null
     * @param material 不可為 null
     * @param amount   數量（&gt;= 1）
     * @return 已寫入 identity PDC 的 {@link ItemStack}
     */
    public ItemStack createLegacy(ItemIdentity identity, Material material, int amount) {
        return create(ItemSpec.builder()
            .identity(identity)
            .material(material)
            .amount(amount)
            .build());
    }

    /**
     * 判斷 {@code stack} 是否帶有「自家」factory 寫入的<strong>完整可解析</strong> identity PDC。
     *
     * <p>本方法委派給 {@link #readIdentity(ItemStack)}：四個欄位
     * {@code namespace} / {@code key} / {@code major} / {@code minor} 全部齊全且
     * namespace 等於 factory namespace 才視為 {@code true}。
     * <strong>不</strong>只看 {@code key} 是否存在，避免「部分／偽造 PDC」被誤判。</p>
     *
     * @param stack 不可為 null
     * @return 若 {@code stack} 帶有完整可解析的自家 identity，則為 {@code true}
     */
    public boolean identify(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        return readIdentity(stack).isPresent();
    }

    /**
     * 從 {@code stack} 讀回 identity。
     *
     * @param stack 不可為 null
     * @return 若存在則回傳 {@link ItemIdentity}，否則回傳 {@link java.util.Optional#empty()}
     */
    public java.util.Optional<ItemIdentity> readIdentity(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return java.util.Optional.empty();
        }
        String ns = meta.getPersistentDataContainer().get(keys.identityNamespaceKey,
            PersistentDataType.STRING);
        String k = meta.getPersistentDataContainer().get(keys.identityKey,
            PersistentDataType.STRING);
        Integer major = meta.getPersistentDataContainer().get(keys.identityMajorKey,
            PersistentDataType.INTEGER);
        Integer minor = meta.getPersistentDataContainer().get(keys.identityMinorKey,
            PersistentDataType.INTEGER);
        if (ns == null || k == null || major == null || minor == null) {
            return java.util.Optional.empty();
        }
        // Identity 的 namespace 必須 == factory namespace，
        // 否則視為不同 item 的同名 key 碰撞
        if (!ns.equals(namespace)) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(new ItemIdentity(ns, k, major, minor));
        } catch (ItemException ex) {
            return java.util.Optional.empty();
        }
    }

    /**
     * 讀取 schema version（從 PDC）。
     *
     * @param stack     不可為 null
     * @param namespace 不可為 null 或空
     * @return 若有則回傳該版本
     */
    public java.util.Optional<ItemSchemaVersion> readSchemaVersion(ItemStack stack, String namespace) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(namespace, "namespace");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return java.util.Optional.empty();
        }
        // 用 caller 傳入的 namespace 為 key 讀取（測試 helper）
        org.bukkit.NamespacedKey versionKey = new org.bukkit.NamespacedKey(namespace, "_version");
        String s = meta.getPersistentDataContainer().get(versionKey, PersistentDataType.STRING);
        if (s == null) {
            return java.util.Optional.empty();
        }
        int dot = s.indexOf('.');
        if (dot < 0) {
            return java.util.Optional.empty();
        }
        try {
            int ma = Integer.parseInt(s.substring(0, dot));
            int mi = Integer.parseInt(s.substring(dot + 1));
            return java.util.Optional.of(new ItemSchemaVersion(ma, mi));
        } catch (NumberFormatException ex) {
            return java.util.Optional.empty();
        }
    }

    /** 寫入 schema version 到 PDC（以 factory.namespace 為 key 前綴）。 */
    private void writeSchemaVersion(ItemMeta meta, ItemSchemaVersion version) {
        org.bukkit.NamespacedKey versionKey = new org.bukkit.NamespacedKey(namespace, "_version");
        meta.getPersistentDataContainer().set(versionKey, PersistentDataType.STRING,
            version.major() + "." + version.minor());
    }

    /** 寫入 identity 三欄位到 PDC（以 factory.namespace 為 key 前綴）。 */
    private void writeIdentity(ItemMeta meta, ItemIdentity identity) {
        var pdc = meta.getPersistentDataContainer();
        pdc.set(keys.identityNamespaceKey, PersistentDataType.STRING, identity.namespace());
        pdc.set(keys.identityKey, PersistentDataType.STRING, identity.key());
        pdc.set(keys.identityMajorKey, PersistentDataType.INTEGER, identity.major());
        pdc.set(keys.identityMinorKey, PersistentDataType.INTEGER, identity.minor());
    }

    // ===== gameplay / display tag 工具 =====

    /**
     * 設定 gameplay tag — int 型別；用於實際遊戲邏輯（數值／權重）等。
     *
     * @param stack 不可為 null
     * @param key   對自家 factory 內唯一；不可為 null 或空
     * @param value 任意整數
     */
    public void setGameplayInt(ItemStack stack, String key, int value) {
        applyGameplayTag(stack, key, GameplayTag.intTag(value));
    }

    /**
     * 設定 gameplay tag — String 型別。
     *
     * @param stack 不可為 null
     * @param key   不可為 null 或空
     * @param value 不可為 null
     */
    public void setGameplayString(ItemStack stack, String key, String value) {
        applyGameplayTag(stack, key, GameplayTag.stringTag(value));
    }

    /**
     * 設定 display-only tag — String 型別；用於 GUI 顯示字串，不參與實際遊戲邏輯。
     *
     * @param stack 不可為 null
     * @param key   不可為 null 或空
     * @param value 不可為 null
     */
    public void setDisplayString(ItemStack stack, String key, String value) {
        applyDisplayTag(stack, key, value);
    }

    /**
     * 讀取 gameplay int。
     *
     * @param stack 不可為 null
     * @param key   不可為 null 或空
     * @return 若有則回傳，否則 {@link java.util.Optional#empty()}
     */
    public java.util.Optional<Integer> readGameplayInt(ItemStack stack, String key) {
        Objects.requireNonNull(key, "key");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return java.util.Optional.empty();
        }
        var pdc = meta.getPersistentDataContainer();
        org.bukkit.NamespacedKey namespaced = keys.gameplayKey(key);
        if (!pdc.has(namespaced, PersistentDataType.INTEGER)) {
            return java.util.Optional.empty();
        }
        Integer v = pdc.get(namespaced, PersistentDataType.INTEGER);
        return v == null ? java.util.Optional.empty() : java.util.Optional.of(v);
    }

    /**
     * 讀取 gameplay string。
     */
    public java.util.Optional<String> readGameplayString(ItemStack stack, String key) {
        Objects.requireNonNull(key, "key");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return java.util.Optional.empty();
        }
        var pdc = meta.getPersistentDataContainer();
        org.bukkit.NamespacedKey namespaced = keys.gameplayKey(key);
        if (!pdc.has(namespaced, PersistentDataType.STRING)) {
            return java.util.Optional.empty();
        }
        String v = pdc.get(namespaced, PersistentDataType.STRING);
        return v == null ? java.util.Optional.empty() : java.util.Optional.of(v);
    }

    /**
     * 讀取 display-only int（預期不存在；不該把 gameplay int 寫到 display）。
     */
    public java.util.Optional<Integer> readDisplayInt(ItemStack stack, String key) {
        Objects.requireNonNull(key, "key");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return java.util.Optional.empty();
        }
        var pdc = meta.getPersistentDataContainer();
        org.bukkit.NamespacedKey namespaced = keys.displayKey(key);
        if (!pdc.has(namespaced, PersistentDataType.INTEGER)) {
            return java.util.Optional.empty();
        }
        Integer v = pdc.get(namespaced, PersistentDataType.INTEGER);
        return v == null ? java.util.Optional.empty() : java.util.Optional.of(v);
    }

    /**
     * 讀取 display-only string。
     */
    public java.util.Optional<String> readDisplayString(ItemStack stack, String key) {
        Objects.requireNonNull(key, "key");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return java.util.Optional.empty();
        }
        var pdc = meta.getPersistentDataContainer();
        org.bukkit.NamespacedKey namespaced = keys.displayKey(key);
        if (!pdc.has(namespaced, PersistentDataType.STRING)) {
            return java.util.Optional.empty();
        }
        String v = pdc.get(namespaced, PersistentDataType.STRING);
        return v == null ? java.util.Optional.empty() : java.util.Optional.of(v);
    }

    private void applyGameplayTag(ItemStack stack, String key, GameplayTag tag) {
        validateKey(key);
        Objects.requireNonNull(tag, "tag");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            throw new ItemException(ItemErrorCode.INVALID_SPEC,
                "ItemStack 不支援 ItemMeta，無法寫入 gameplay tag");
        }
        var pdc = meta.getPersistentDataContainer();
        org.bukkit.NamespacedKey namespaced = keys.gameplayKey(key);
        if (tag.isInt()) {
            pdc.set(namespaced, PersistentDataType.INTEGER, tag.intValue());
        } else if (tag.isString()) {
            pdc.set(namespaced, PersistentDataType.STRING, tag.stringValue());
        }
        stack.setItemMeta(meta);
    }

    private void applyDisplayTag(ItemStack stack, String key, Object value) {
        validateKey(key);
        Objects.requireNonNull(value, "value");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            throw new ItemException(ItemErrorCode.INVALID_SPEC,
                "ItemStack 不支援 ItemMeta，無法寫入 display tag");
        }
        var pdc = meta.getPersistentDataContainer();
        org.bukkit.NamespacedKey namespaced = keys.displayKey(key);
        if (value instanceof Integer i) {
            pdc.set(namespaced, PersistentDataType.INTEGER, i);
        } else if (value instanceof String s) {
            pdc.set(namespaced, PersistentDataType.STRING, s);
        } else {
            throw new ItemException(ItemErrorCode.UNSUPPORTED_DATA,
                "display-only tag 僅支援 Integer / String，目前: "
                    + value.getClass().getName());
        }
        stack.setItemMeta(meta);
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ItemException(ItemErrorCode.UNKNOWN_NAMESPACE,
                "tag key 不可為 null 或空字串");
        }
    }

    // ===== 序列化 =====

    /**
     * 序列化 ItemStack 為可儲存位元組；包含物種 / amount / meta / 全部自訂 PDC / 顯示層 lore。
     *
     * @param stack 不可為 null
     * @return 位元組陣列
     * @throws ItemException 當序列化失敗時拋出（{@code ACELIB-ITEM-005}）
     */
    public byte[] serialize(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        try {
            // 利用 Paper 提供的 byte[] PDC 序列化能力（meta 自帶 PDC）
            ItemMeta meta = stack.getItemMeta();
            if (meta == null) {
                // 無 meta 表示 vanilla 物品，使用 Bukkit 序列化
                return stack.serializeAsBytes();
            }
            // 為避免使用 NMS / craftbukkit，這裡採用 ItemStack#serializeAsBytes，
            // 該方法在 Paper 中走「不含 NBT 但含 PersistentDataContainer」的版本。
            return stack.serializeAsBytes();
        } catch (RuntimeException ex) {
            throw new ItemException(ItemErrorCode.DESERIALIZE_FAILED,
                "serialize 失敗: " + ex.getMessage(), ex);
        }
    }

    /**
     * 反序列化為 ItemStack。
     *
     * @param bytes 不可為 null
     * @return 還原的 {@link ItemStack}
     * @throws ItemException 當反序列化失敗時拋出（{@code ACELIB-ITEM-005}）
     */
    public java.util.Optional<ItemStack> deserialize(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            ItemStack stack = ItemStack.deserializeBytes(bytes);
            return stack == null ? java.util.Optional.empty() : java.util.Optional.of(stack);
        } catch (RuntimeException ex) {
            throw new ItemException(ItemErrorCode.DESERIALIZE_FAILED,
                "deserialize 失敗: " + ex.getMessage(), ex);
        }
    }

    // ===== migration =====

    /**
     * 對 {@code stack} 執行 migration — 在 in-memory 工作複本上跑 chain；
     * chain 全部成功才把工作複本（連同所有 metadata 變更）寫回 {@code stack}；任一失敗 rollback。
     *
     * <p>注意：本方法對 {@code stack} 的修改是<strong>原子性</strong>的 —
     * 先 clone 一份 {@code ItemMeta} 作為工作複本；所有 migration 都透過
     * {@link ItemMigrationContext} 對工作複本進行讀寫；commit hook 才把工作複本
     * {@code setItemMeta} 回 {@code stack}。任一失敗則 {@code stack} 完全不被改動
     * （連 serialize bytes 都保持原樣）。失敗時拋 {@link ItemException}
     * （{@link ItemErrorCode#MIGRATION_FAILED}）。</p>
     *
     * <h2>atomicity 來源</h2>
     * <ul>
     *   <li>輸入 {@code stack} 的 {@code ItemMeta} 在 migrate 開始時被 {@code clone()} 為
     *       {@code backupMeta}（rollback 用）與 {@code workingMeta}（工作複本）</li>
     *   <li>所有 migration 的 {@code writeXxx} 都作用於 {@code workingMeta}，
     *       <em>不會</em>觸碰 {@code stack}</li>
     *   <li>commit hook 透過 {@code setItemMeta(workingMeta)} 一次性把工作複本寫回</li>
     *   <li>失敗時 {@code setItemMeta(backupMeta)} 把備份寫回，
     *       <strong>輸入 ItemStack 的 serialize bytes 維持原樣</strong></li>
     * </ul>
     *
     * @param stack         不可為 null；會被原地修改（若 chain 成功）
     * @param targetVersion 不可為 null
     * @param chain         不可為 null
     * @return {@link ItemMigrationResult}
     * @throws ItemException 當 chain 失敗或輸入為 null 時拋出
     */
    public ItemMigrationResult migrate(ItemStack stack, ItemSchemaVersion targetVersion,
                                       ItemMigrationChain chain) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(targetVersion, "targetVersion");
        Objects.requireNonNull(chain, "chain");

        ItemMeta originalMeta = stack.getItemMeta();
        if (originalMeta == null) {
            throw new ItemException(ItemErrorCode.INVALID_SPEC,
                "ItemStack 不支援 ItemMeta，無法執行 migration");
        }

        java.util.Optional<ItemSchemaVersion> currentOpt =
            readSchemaVersion(stack, namespace);
        ItemSchemaVersion currentVersion = currentOpt.orElseGet(() -> {
            // 沒寫過版本：預設 V1_0
            writeSchemaVersion(stack.getItemMeta(), ItemSchemaVersion.V1_0);
            return ItemSchemaVersion.V1_0;
        });

        if (currentVersion.compareTo(targetVersion) >= 0) {
            return ItemMigrationResult.success(null, List.of());
        }

        // 建立工作複本與備份；commit hook 才把工作複本寫回 stack
        ItemMeta workingMeta = originalMeta.clone();
        ItemMeta backupMeta = originalMeta.clone();
        ItemMigrationContext context = ItemMigrationContext.workingCopy(
            namespace, keys, workingMeta, currentVersion);

        ItemMigrationResult result = chain.migrateTracked(context, targetVersion,
            finalVersion -> {
                // commit hook — 把工作複本一次性寫回 stack
                ItemMeta m = stack.getItemMeta();
                if (m == null) {
                    throw new ItemException(ItemErrorCode.INVALID_SPEC,
                        "migrate commit hook：ItemStack 不支援 ItemMeta");
                }
                // 確保 workingMeta 已是最新（context.writeVersion 已經更新 workingMeta PDC）
                stack.setItemMeta(workingMeta);
            });

        if (!result.success()) {
            // rollback：把備份的 meta set 回 stack
            stack.setItemMeta(backupMeta);
            throw new ItemException(ItemErrorCode.MIGRATION_FAILED,
                "Item migration failed: " + result.errorMessage(),
                result.cause());
        }
        return result;
    }

    /**
     * 加入附魔 enchantment（helper，方便測試建立 enchanted item）。
     */
    public ItemStack withEnchantment(ItemStack stack, Enchantment ench, int level) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(ench, "ench");
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            throw new ItemException(ItemErrorCode.INVALID_SPEC, "ItemMeta 不可為 null");
        }
        meta.addEnchant(ench, level, true);
        stack.setItemMeta(meta);
        return stack;
    }

    // ===== 內部：NamespacedKey 統一管理 =====

    /**
     * 對 {@link ItemMigrationContext} 提供 PDC key 集（package-private 內部介面）。
     *
     * <p>供 {@link ItemMigrationContext.WorkingCopy} 在 commit 時把工作複本的
     * PDC 寫入與讀取路徑統一收斂，避免再散落各處。</p>
     *
     * @return 不可變的 {@link NamespacedKeys} 視圖
     */
    NamespacedKeys namespaceKeys() {
        return keys;
    }

    /**
     * PDC key 集 — 全部以 factory.namespace 為前綴。
     *
     * <p>package-private：供 {@link ItemMigrationContext.WorkingCopy} 直接存取；
     * 外部 caller 仍須透過 {@link AceItemFactory} 的公開 API 進行讀寫。</p>
     */
    static final class NamespacedKeys {
        static final String GAMEPLAY_PREFIX = "_gameplay_";
        static final String DISPLAY_PREFIX = "_display_";
        static final String IDENTITY_NAMESPACE_SUFFIX = "_id_namespace";
        static final String IDENTITY_SUFFIX = "_id";
        static final String IDENTITY_MAJOR_SUFFIX = "_id_major";
        static final String IDENTITY_MINOR_SUFFIX = "_id_minor";

        private final String namespace;
        final org.bukkit.NamespacedKey identityNamespaceKey;
        final org.bukkit.NamespacedKey identityKey;
        final org.bukkit.NamespacedKey identityMajorKey;
        final org.bukkit.NamespacedKey identityMinorKey;

        NamespacedKeys(String namespace) {
            this.namespace = namespace;
            this.identityNamespaceKey = new org.bukkit.NamespacedKey(namespace,
                IDENTITY_NAMESPACE_SUFFIX);
            this.identityKey = new org.bukkit.NamespacedKey(namespace, IDENTITY_SUFFIX);
            this.identityMajorKey = new org.bukkit.NamespacedKey(namespace,
                IDENTITY_MAJOR_SUFFIX);
            this.identityMinorKey = new org.bukkit.NamespacedKey(namespace,
                IDENTITY_MINOR_SUFFIX);
        }

        org.bukkit.NamespacedKey gameplayKey(String key) {
            return new org.bukkit.NamespacedKey(namespace, GAMEPLAY_PREFIX + key);
        }

        org.bukkit.NamespacedKey displayKey(String key) {
            return new org.bukkit.NamespacedKey(namespace, DISPLAY_PREFIX + key);
        }
    }

    /**
     * 物品規格建構器（不可變 record）。
     *
     * <p>透過 {@link #builder()} 取得 builder，使用流式 API 設定欄位後呼叫
     * {@link ItemSpecBuilder#build()} 取得不可變的 {@link ItemSpec}。</p>
     */
    public static final class ItemSpec {
        private final Material material;
        private final int amount;
        private final ItemIdentity identity;
        private final Component displayName;
        private final List<Component> lore;
        private final Map<String, GameplayTag> gameplayTags;
        private final Map<String, Object> displayTags;

        ItemSpec(Material material, int amount, ItemIdentity identity,
                 Component displayName, List<Component> lore,
                 Map<String, GameplayTag> gameplayTags,
                 Map<String, Object> displayTags) {
            this.material = material;
            this.amount = amount;
            this.identity = identity;
            this.displayName = displayName;
            this.lore = lore == null ? null : List.copyOf(lore);
            this.gameplayTags = gameplayTags == null ? null : Map.copyOf(gameplayTags);
            this.displayTags = displayTags == null ? null : Map.copyOf(displayTags);
        }

        public Material material() { return material; }
        public int amount() { return amount; }
        public ItemIdentity identity() { return identity; }
        public Component displayName() { return displayName; }
        public List<Component> lore() { return lore; }
        public Map<String, GameplayTag> gameplayTags() { return gameplayTags; }
        public Map<String, Object> displayTags() { return displayTags; }

        /**
         * 取得建構器。
         *
         * @return 新的 {@link ItemSpecBuilder}
         */
        public static ItemSpecBuilder builder() {
            return new ItemSpecBuilder();
        }
    }

    /**
     * {@link ItemSpec} 的流式 builder。
     *
     * <p>所有欄位都是 optional；只有 {@link #material(Material)} /
     * {@link #identity(ItemIdentity)} 為必填，{@link #build()} 會做檢查。</p>
     */
    public static final class ItemSpecBuilder {
        private Material material;
        private int amount = 1;
        private ItemIdentity identity;
        private Component displayName;
        private List<Component> lore;
        private final Map<String, GameplayTag> gameplayTags = new LinkedHashMap<>();
        private final Map<String, Object> displayTags = new LinkedHashMap<>();

        public ItemSpecBuilder material(Material material) {
            this.material = material;
            return this;
        }

        public ItemSpecBuilder amount(int amount) {
            this.amount = amount;
            return this;
        }

        public ItemSpecBuilder identity(ItemIdentity identity) {
            this.identity = identity;
            return this;
        }

        public ItemSpecBuilder displayName(Component name) {
            this.displayName = name;
            return this;
        }

        public ItemSpecBuilder displayName(String name) {
            this.displayName = name == null ? null : Component.text(name);
            return this;
        }

        public ItemSpecBuilder lore(List<Component> lines) {
            this.lore = lines;
            return this;
        }

        public ItemSpecBuilder lore(String... lines) {
            if (lines == null) {
                this.lore = null;
                return this;
            }
            this.lore = new java.util.ArrayList<>();
            for (String l : lines) {
                this.lore.add(Component.text(l));
            }
            return this;
        }

        /**
         * 新增 gameplay int tag。
         */
        public ItemSpecBuilder gameplayTag(String key, int value) {
            gameplayTags.put(key, GameplayTag.intTag(value));
            return this;
        }

        /**
         * 新增 gameplay string tag。
         */
        public ItemSpecBuilder gameplayTag(String key, String value) {
            gameplayTags.put(key, GameplayTag.stringTag(value));
            return this;
        }

        /**
         * 新增 display-only tag（只接受 String / int）。
         */
        public ItemSpecBuilder displayTag(String key, String value) {
            displayTags.put(key, value);
            return this;
        }

        /**
         * 新增 display-only int tag。
         */
        public ItemSpecBuilder displayTag(String key, int value) {
            displayTags.put(key, value);
            return this;
        }

        /**
         * 將 builder 設定組合成不可變的 {@link ItemSpec}。
         *
         * @return 不可變的 {@link ItemSpec}
         */
        public ItemSpec build() {
            return new ItemSpec(material, amount, identity, displayName, lore,
                gameplayTags, displayTags);
        }
    }

    /**
     * 用於 builder 內部表示的 gameplay tag 值（int / String）。
     */
    private static final class GameplayTag {
        private final int intValue;
        private final String stringValue;

        private GameplayTag(int intValue, String stringValue) {
            this.intValue = intValue;
            this.stringValue = stringValue;
        }

        static GameplayTag intTag(int v) {
            return new GameplayTag(v, null);
        }

        static GameplayTag stringTag(String s) {
            Objects.requireNonNull(s, "string");
            return new GameplayTag(0, s);
        }

        boolean isInt() {
            return stringValue == null;
        }

        boolean isString() {
            return stringValue != null;
        }

        public int intValue() { return intValue; }
        public String stringValue() { return stringValue; }
    }
}
