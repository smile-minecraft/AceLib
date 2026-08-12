package com.smile.acelib.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Item migration 上下文 — 提供「讀取舊版資料」與「寫入新版資料」的 in-memory 視圖。
 *
 * <p>對應 Plan Phase 12「舊版資料升級」需求。
 * 該介面刻意保持 <strong>不可變讀寫視圖</strong>：
 * 任何透過 {@link #writeVersion(ItemSchemaVersion)}、{@link #writeIdentity(ItemIdentity)}
 * 、{@link #writeDisplayName(Component)}、{@link #writeLore(List)}、
 * {@link #writeGameplayInt(String, int)}、{@link #writeGameplayString(String, String)}、
 * {@link #writeDisplayInt(String, int)}、{@link #writeDisplayString(String, String)}
 * 的修改只會出現在當前 chain 持有的 <em>工作複本（working copy）</em> 上；
 * 直到整個 chain 結束且 commit 才會由 {@link ItemMigrationChain} 寫回實際 {@code ItemStack}。
 * 任一 migration 拋例外，{@link ItemMigrationChain} 會視為失敗並丟棄所有 in-memory 變更，
 * <strong>輸入 ItemStack 不被破壞</strong>。</p>
 *
 * <h2>讀寫語意</h2>
 * <ul>
 *   <li>所有 {@code readXxx} 回傳 {@link Optional} 或不可變清單；不存在或非預期型別回傳空值</li>
 *   <li>所有 {@code writeXxx} 寫入「工作複本」，不會動到外部 {@code ItemStack}</li>
 *   <li>{@link #currentVersion()} 回傳「截至目前為止」已套用的版本；
 *       {@link #writeVersion(ItemSchemaVersion)} 會更新此值並同步寫入工作複本的 PDC</li>
 *   <li>{@link #commitMeta()} 由 {@link ItemMigrationChain} 於成功時呼叫，
 *       把工作複本「套用」回 {@code ItemStack}；不應由 {@link ItemMigration} 直接呼叫</li>
 * </ul>
 */
public interface ItemMigrationContext {

    /**
     * 取得「截至目前為止」已套用的 schema 版本。
     *
     * @return 不可為 null
     */
    ItemSchemaVersion currentVersion();

    /**
     * 寫入目標 schema 版本；同步推進 {@link #currentVersion()}。
     *
     * @param version 不可為 null
     * @throws ItemException 當 {@code version} 為 null 時拋出（{@code ACELIB-ITEM-001}）
     */
    void writeVersion(ItemSchemaVersion version);

    /**
     * 讀取 identity（namespace / key / formatVersion）。
     *
     * @return 若四個欄位齊全且 namespace 等於 factory namespace 則回傳 {@link ItemIdentity}，否則空
     */
    Optional<ItemIdentity> readIdentity();

    /**
     * 寫入 identity；必須與 factory namespace 一致，否則拋 {@link ItemException}。
     *
     * @param identity 不可為 null
     * @throws ItemException 當 {@code identity.namespace()} 與 factory namespace 不一致時
     *                       拋出（{@code ACELIB-ITEM-002}）
     */
    void writeIdentity(ItemIdentity identity);

    /**
     * 讀取 display name。
     *
     * @return 若有自訂名稱則回傳，否則空
     */
    Optional<Component> readDisplayName();

    /**
     * 寫入 display name（會覆蓋既有 custom name）。
     *
     * @param name 不可為 null
     */
    void writeDisplayName(Component name);

    /**
     * 讀取 lore（不可變清單）。
     *
     * @return 不可變的 {@link List}；無 lore 時回傳空清單（不會回傳 null）
     */
    List<Component> readLore();

    /**
     * 寫入 lore（會覆蓋既有 lore）。
     *
     * @param lore 不可為 null
     */
    void writeLore(List<Component> lore);

    /**
     * 讀取 gameplay int tag。
     *
     * @param key 不可為 null 或空
     * @return 若有則回傳值，否則空
     */
    Optional<Integer> readGameplayInt(String key);

    /**
     * 寫入 gameplay int tag。
     *
     * @param key   不可為 null 或空
     * @param value 任意整數
     */
    void writeGameplayInt(String key, int value);

    /**
     * 讀取 gameplay string tag。
     *
     * @param key 不可為 null 或空
     * @return 若有則回傳值，否則空
     */
    Optional<String> readGameplayString(String key);

    /**
     * 寫入 gameplay string tag。
     *
     * @param key   不可為 null 或空
     * @param value 不可為 null
     */
    void writeGameplayString(String key, String value);

    /**
     * 讀取 display-only int tag。
     *
     * @param key 不可為 null 或空
     * @return 若有則回傳值，否則空
     */
    Optional<Integer> readDisplayInt(String key);

    /**
     * 寫入 display-only int tag。
     *
     * @param key   不可為 null 或空
     * @param value 任意整數
     */
    void writeDisplayInt(String key, int value);

    /**
     * 讀取 display-only string tag。
     *
     * @param key 不可為 null 或空
     * @return 若有則回傳值，否則空
     */
    Optional<String> readDisplayString(String key);

    /**
     * 寫入 display-only string tag。
     *
     * @param key   不可為 null 或空
     * @param value 不可為 null
     */
    void writeDisplayString(String key, String value);

    /**
     * 由 {@link ItemMigrationChain} 於 chain 全部成功後呼叫，把工作複本「套用」回 {@code ItemStack}。
     * <strong>不應由 {@link ItemMigration} 直接呼叫</strong>。
     */
    void commitMeta();

    /**
     * Factory helper：建立包裝工作複本的「真實」context，供
     * {@link ItemMigrationChain}（新簽章）使用。
     *
     * @param factoryNamespace factory 綁定的 namespace；不可為 null
     * @param keys             factory 內部 PDC key 集；不可為 null
     * @param workingMeta      工作複本；不可為 null
     * @param initialVersion   起始 schema 版本；不可為 null
     * @return 新的 {@link ItemMigrationContext}
     */
    static ItemMigrationContext workingCopy(String factoryNamespace,
                                             AceItemFactory.NamespacedKeys keys,
                                             ItemMeta workingMeta,
                                             ItemSchemaVersion initialVersion) {
        Objects.requireNonNull(factoryNamespace, "factoryNamespace");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(workingMeta, "workingMeta");
        Objects.requireNonNull(initialVersion, "initialVersion");
        return new WorkingCopy(factoryNamespace, keys, workingMeta, initialVersion);
    }

    /**
     * Factory helper：建立「stub」context — 所有 metadata 讀寫為 no-op，僅支援版本推進。
     *
     * <p>此實作僅用於 {@link ItemMigrationChain#migrateTracked(ItemSchemaVersion,
     * ItemSchemaVersion, java.util.function.Consumer)}（舊簽章）；
     * 該簽章不允許 migration 修改 metadata。</p>
     *
     * @param initialVersion 起始版本；不可為 null
     * @return 新的 stub {@link ItemMigrationContext}
     */
    static ItemMigrationContext stub(ItemSchemaVersion initialVersion) {
        Objects.requireNonNull(initialVersion, "initialVersion");
        return new Stub(initialVersion);
    }

    /**
     * 真實工作複本實作：包裝 {@link ItemMeta}，所有讀寫直接作用於複本。
     *
     * <p>由 {@link ItemMigrationChain} 透過 {@link #workingCopy(String,
     * AceItemFactory.NamespacedKeys, ItemMeta, ItemSchemaVersion)} 建立；
     * 套件外部無法直接 new。</p>
     */
    final class WorkingCopy implements ItemMigrationContext {
        private final String factoryNamespace;
        private final AceItemFactory.NamespacedKeys keys;
        private final ItemMeta workingMeta;
        private ItemSchemaVersion currentVersion;

        WorkingCopy(String factoryNamespace,
                    AceItemFactory.NamespacedKeys keys,
                    ItemMeta workingMeta,
                    ItemSchemaVersion initialVersion) {
            this.factoryNamespace = factoryNamespace;
            this.keys = keys;
            this.workingMeta = workingMeta;
            this.currentVersion = initialVersion;
        }

        @Override
        public ItemSchemaVersion currentVersion() {
            return currentVersion;
        }

        @Override
        public void writeVersion(ItemSchemaVersion version) {
            Objects.requireNonNull(version, "version");
            NamespacedKey versionKey = new NamespacedKey(factoryNamespace, "_version");
            workingMeta.getPersistentDataContainer().set(versionKey, PersistentDataType.STRING,
                version.major() + "." + version.minor());
            currentVersion = version;
        }

        @Override
        public Optional<ItemIdentity> readIdentity() {
            var pdc = workingMeta.getPersistentDataContainer();
            String ns = pdc.get(keys.identityNamespaceKey, PersistentDataType.STRING);
            String k = pdc.get(keys.identityKey, PersistentDataType.STRING);
            Integer major = pdc.get(keys.identityMajorKey, PersistentDataType.INTEGER);
            Integer minor = pdc.get(keys.identityMinorKey, PersistentDataType.INTEGER);
            if (ns == null || k == null || major == null || minor == null) {
                return Optional.empty();
            }
            if (!ns.equals(factoryNamespace)) {
                return Optional.empty();
            }
            try {
                return Optional.of(new ItemIdentity(ns, k, major, minor));
            } catch (ItemException ex) {
                return Optional.empty();
            }
        }

        @Override
        public void writeIdentity(ItemIdentity identity) {
            Objects.requireNonNull(identity, "identity");
            if (!identity.namespace().equals(factoryNamespace)) {
                throw new ItemException(ItemErrorCode.UNKNOWN_NAMESPACE,
                    "寫入 identity 的 namespace " + identity.namespace()
                        + " 與 factory namespace " + factoryNamespace + " 不一致");
            }
            var pdc = workingMeta.getPersistentDataContainer();
            pdc.set(keys.identityNamespaceKey, PersistentDataType.STRING, identity.namespace());
            pdc.set(keys.identityKey, PersistentDataType.STRING, identity.key());
            pdc.set(keys.identityMajorKey, PersistentDataType.INTEGER, identity.major());
            pdc.set(keys.identityMinorKey, PersistentDataType.INTEGER, identity.minor());
        }

        @Override
        public Optional<Component> readDisplayName() {
            if (!workingMeta.hasCustomName()) {
                return Optional.empty();
            }
            return Optional.ofNullable(workingMeta.customName());
        }

        @Override
        public void writeDisplayName(Component name) {
            Objects.requireNonNull(name, "name");
            workingMeta.customName(name);
        }

        @Override
        public List<Component> readLore() {
            List<Component> lore = workingMeta.lore();
            if (lore == null) {
                return List.of();
            }
            return List.copyOf(lore);
        }

        @Override
        public void writeLore(List<Component> lore) {
            Objects.requireNonNull(lore, "lore");
            workingMeta.lore(new ArrayList<>(lore));
        }

        @Override
        public Optional<Integer> readGameplayInt(String key) {
            validateTagKey(key);
            var pdc = workingMeta.getPersistentDataContainer();
            NamespacedKey namespaced = keys.gameplayKey(key);
            if (!pdc.has(namespaced, PersistentDataType.INTEGER)) {
                return Optional.empty();
            }
            Integer v = pdc.get(namespaced, PersistentDataType.INTEGER);
            return v == null ? Optional.empty() : Optional.of(v);
        }

        @Override
        public void writeGameplayInt(String key, int value) {
            validateTagKey(key);
            workingMeta.getPersistentDataContainer().set(
                keys.gameplayKey(key), PersistentDataType.INTEGER, value);
        }

        @Override
        public Optional<String> readGameplayString(String key) {
            validateTagKey(key);
            var pdc = workingMeta.getPersistentDataContainer();
            NamespacedKey namespaced = keys.gameplayKey(key);
            if (!pdc.has(namespaced, PersistentDataType.STRING)) {
                return Optional.empty();
            }
            String v = pdc.get(namespaced, PersistentDataType.STRING);
            return v == null ? Optional.empty() : Optional.of(v);
        }

        @Override
        public void writeGameplayString(String key, String value) {
            validateTagKey(key);
            Objects.requireNonNull(value, "value");
            workingMeta.getPersistentDataContainer().set(
                keys.gameplayKey(key), PersistentDataType.STRING, value);
        }

        @Override
        public Optional<Integer> readDisplayInt(String key) {
            validateTagKey(key);
            var pdc = workingMeta.getPersistentDataContainer();
            NamespacedKey namespaced = keys.displayKey(key);
            if (!pdc.has(namespaced, PersistentDataType.INTEGER)) {
                return Optional.empty();
            }
            Integer v = pdc.get(namespaced, PersistentDataType.INTEGER);
            return v == null ? Optional.empty() : Optional.of(v);
        }

        @Override
        public void writeDisplayInt(String key, int value) {
            validateTagKey(key);
            workingMeta.getPersistentDataContainer().set(
                keys.displayKey(key), PersistentDataType.INTEGER, value);
        }

        @Override
        public Optional<String> readDisplayString(String key) {
            validateTagKey(key);
            var pdc = workingMeta.getPersistentDataContainer();
            NamespacedKey namespaced = keys.displayKey(key);
            if (!pdc.has(namespaced, PersistentDataType.STRING)) {
                return Optional.empty();
            }
            String v = pdc.get(namespaced, PersistentDataType.STRING);
            return v == null ? Optional.empty() : Optional.of(v);
        }

        @Override
        public void writeDisplayString(String key, String value) {
            validateTagKey(key);
            Objects.requireNonNull(value, "value");
            workingMeta.getPersistentDataContainer().set(
                keys.displayKey(key), PersistentDataType.STRING, value);
        }

        @Override
        public void commitMeta() {
            // 由 ItemMigrationChain 在 commit hook 內把 workingMeta set 回 stack；
            // 此處留空以維持介面合約。
        }

        /**
         * 暴露 workingMeta 給 chain 用於 commit；套件外不可見。
         */
        ItemMeta workingMeta() {
            return workingMeta;
        }

        private static void validateTagKey(String key) {
            if (key == null || key.isBlank()) {
                throw new ItemException(ItemErrorCode.UNKNOWN_NAMESPACE,
                    "tag key 不可為 null 或空字串");
            }
        }
    }

    /**
     * Stub 實作：所有 metadata 讀寫為 no-op；只支援 {@link #writeVersion} 推進版本。
     */
    final class Stub implements ItemMigrationContext {
        private ItemSchemaVersion currentVersion;

        Stub(ItemSchemaVersion initialVersion) {
            this.currentVersion = initialVersion;
        }

        @Override public ItemSchemaVersion currentVersion() { return currentVersion; }

        @Override public void writeVersion(ItemSchemaVersion version) {
            Objects.requireNonNull(version, "version");
            this.currentVersion = version;
        }

        @Override public Optional<ItemIdentity> readIdentity() { return Optional.empty(); }
        @Override public void writeIdentity(ItemIdentity identity) {
            throw new ItemException(ItemErrorCode.UNSUPPORTED_DATA,
                "Stub context 不支援 metadata 寫入；請改用 ItemMigrationChain#migrateTracked(context,...)");
        }
        @Override public Optional<Component> readDisplayName() { return Optional.empty(); }
        @Override public void writeDisplayName(Component name) {
            throw new ItemException(ItemErrorCode.UNSUPPORTED_DATA,
                "Stub context 不支援 metadata 寫入");
        }
        @Override public List<Component> readLore() { return List.of(); }
        @Override public void writeLore(List<Component> lore) {
            throw new ItemException(ItemErrorCode.UNSUPPORTED_DATA,
                "Stub context 不支援 metadata 寫入");
        }
        @Override public Optional<Integer> readGameplayInt(String key) { return Optional.empty(); }
        @Override public void writeGameplayInt(String key, int value) {
            throw new ItemException(ItemErrorCode.UNSUPPORTED_DATA,
                "Stub context 不支援 metadata 寫入");
        }
        @Override public Optional<String> readGameplayString(String key) { return Optional.empty(); }
        @Override public void writeGameplayString(String key, String value) {
            throw new ItemException(ItemErrorCode.UNSUPPORTED_DATA,
                "Stub context 不支援 metadata 寫入");
        }
        @Override public Optional<Integer> readDisplayInt(String key) { return Optional.empty(); }
        @Override public void writeDisplayInt(String key, int value) {
            throw new ItemException(ItemErrorCode.UNSUPPORTED_DATA,
                "Stub context 不支援 metadata 寫入");
        }
        @Override public Optional<String> readDisplayString(String key) { return Optional.empty(); }
        @Override public void writeDisplayString(String key, String value) {
            throw new ItemException(ItemErrorCode.UNSUPPORTED_DATA,
                "Stub context 不支援 metadata 寫入");
        }
        @Override public void commitMeta() {
            // no-op for stub
        }
    }
}
