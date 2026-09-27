package com.smile.acelib.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/**
 * {@link CommandCatalog} 的預設實作。
 *
 * <p>內部以 copy-on-write 的不可變狀態（同時持有 entries 與 revision）
 * 做原子替換：讀寫各自執行緒安全，多執行緒同時發布不遺失資料。
 * 擁有者以 {@link Plugin} 實例識別（identity 比對，不是名稱字串），
 * 避免同名插件互相撤下對方的資料。</p>
 */
final class CommandCatalogImpl implements CommandCatalog {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    private final AtomicBoolean available = new AtomicBoolean(true);
    private final AtomicReference<State> state =
        new AtomicReference<>(new State(Map.of(), 0));

    /** 不可變狀態：entries 與 revision 同時替換。 */
    private record State(Map<CatalogKey, CommandDoc> entries, long revision) {
    }

    /**
     * 目錄鍵：擁有者實例（identity）+ 小寫根指令名。
     *
     * <p>同實例同名視為同一筆（覆蓋）；不同實例同名視為不同筆（並存）。</p>
     */
    private static final class CatalogKey {
        private final Plugin owner;
        private final String lowerName;
        private final int hash;

        CatalogKey(Plugin owner, String lowerName) {
            this.owner = owner;
            this.lowerName = lowerName;
            this.hash = System.identityHashCode(owner) * 31 + lowerName.hashCode();
        }

        boolean isOwnedBy(Plugin other) {
            return owner == other;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CatalogKey key)) {
                return false;
            }
            return owner == key.owner && lowerName.equals(key.lowerName);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    @Override
    public CatalogResult publish(Plugin owner, CommandSpec spec, CatalogMeta meta) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(meta, "meta");
        if (!available.get()) {
            LOGGER.log(Level.WARNING,
                CommandCatalogErrorCodes.NOT_READY
                    + ": command catalog not ready or disabled; publish of '"
                    + spec.name() + "' rejected");
            return CatalogResult.REJECTED;
        }
        CommandDoc doc = project(owner, spec, meta);
        return store(owner, doc);
    }

    @Override
    public CatalogResult publish(Plugin owner, CommandDoc doc) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(doc, "doc");
        if (!available.get()) {
            LOGGER.log(Level.WARNING,
                CommandCatalogErrorCodes.NOT_READY
                    + ": command catalog not ready or disabled; publish of '"
                    + doc.name() + "' rejected");
            return CatalogResult.REJECTED;
        }
        if (!doc.owner().equals(owner.getName())) {
            throw new IllegalArgumentException(
                "command doc owner '" + doc.owner()
                    + "' does not match publishing plugin '" + owner.getName() + "'");
        }
        return store(owner, doc);
    }

    @Override
    public void unpublishAll(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        if (!available.get()) {
            return;
        }
        while (true) {
            State current = state.get();
            Map<CatalogKey, CommandDoc> next = new LinkedHashMap<>();
            for (Map.Entry<CatalogKey, CommandDoc> entry : current.entries().entrySet()) {
                if (!entry.getKey().isOwnedBy(owner)) {
                    next.put(entry.getKey(), entry.getValue());
                }
            }
            if (next.size() == current.entries().size()) {
                return;
            }
            State updated = new State(
                Collections.unmodifiableMap(next), current.revision() + 1);
            if (state.compareAndSet(current, updated)) {
                return;
            }
        }
    }

    @Override
    public List<CommandDoc> snapshot() {
        if (!available.get()) {
            return List.of();
        }
        List<CommandDoc> docs = new ArrayList<>(state.get().entries().values());
        docs.sort(Comparator.comparing(CommandDoc::owner).thenComparing(CommandDoc::name));
        return List.copyOf(docs);
    }

    @Override
    public long revision() {
        return state.get().revision();
    }

    /**
     * 停用服務（冪等）：清空全部資料並標記不可用。
     *
     * <p>清空非空目錄算一次變更（revision +1）；空目錄清空不算變更。
     * 呼叫後 {@code publish} 一律 {@link CatalogResult#REJECTED}，
     * {@code snapshot()} 回空清單，{@code unpublishAll} 為無害的 no-op；
     * 透過舊參考仍不可再寫入。由擁有者插件在停用時呼叫。</p>
     */
    @Override
    public void shutdown() {
        clearAll();
        setAvailable(false);
    }

    /**
     * 設定服務可用與否，供後續生命週期接線使用。
     *
     * <p>設為不可用後：{@code publish} 一律 {@link CatalogResult#REJECTED}，
     * {@code snapshot()} 回空清單，{@code unpublishAll} 為無害的 no-op；
     * revision 保持不變。重新設為可用後服務恢復正常，既有資料保留。</p>
     *
     * @param ready true 表示就緒，false 表示未就緒或已停用
     */
    void setAvailable(boolean ready) {
        available.set(ready);
    }

    /**
     * 清空全部資料，供後續生命週期接線使用。
     *
     * <p>清空非空目錄算一次變更（revision +1）；空目錄清空不算變更。
     * revision 單調遞增，不因清空而重設。</p>
     */
    void clearAll() {
        while (true) {
            State current = state.get();
            if (current.entries().isEmpty()) {
                return;
            }
            State updated = new State(Map.of(), current.revision() + 1);
            if (state.compareAndSet(current, updated)) {
                return;
            }
        }
    }

    /**
     * 從 {@link CommandSpec} 投影純描述：只取描述性欄位，
     * handler、completer、插件實例與 spec 實例本身一律不保留。
     */
    private static CommandDoc project(Plugin owner, CommandSpec spec, CatalogMeta meta) {
        Map<String, SubCommandSpec> known = spec.subCommands();
        List<SubDoc> subs = new ArrayList<>(known.size());
        for (SubCommandSpec sub : known.values()) {
            String confirmKey = sub.name().toLowerCase(Locale.ROOT);
            boolean requiresConfirmation = false;
            for (String confirm : meta.confirmSubcommands()) {
                if (confirm != null && confirm.toLowerCase(Locale.ROOT).equals(confirmKey)) {
                    requiresConfirmation = true;
                    break;
                }
            }
            subs.add(new SubDoc(sub.name(), sub.description(), sub.usage(),
                sub.permission(), sub.argNames(), sub.minArgs(), sub.maxArgs(),
                sub.playerOnly(), sub.consoleOnly(), sub.cooldownMillis(),
                requiresConfirmation));
        }
        // 未知確認名稱拒絕：避免錯字讓危險操作漏掉確認
        for (String confirm : meta.confirmSubcommands()) {
            if (confirm == null || confirm.isBlank()
                    || !known.containsKey(confirm.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                    "unknown confirm subcommand '" + confirm
                        + "' for command '" + spec.name() + "'");
            }
        }
        return new CommandDoc(owner.getName(), spec.name(),
            List.copyOf(spec.aliases()), spec.description(), spec.usage(),
            spec.permission(), meta.category(), meta.icon(), subs);
    }

    private CatalogResult store(Plugin owner, CommandDoc doc) {
        String lowerName = doc.name().toLowerCase(Locale.ROOT);
        CatalogKey key = new CatalogKey(owner, lowerName);
        while (true) {
            State current = state.get();
            CommandDoc existing = current.entries().get(key);
            if (doc.equals(existing)) {
                return CatalogResult.UNCHANGED;
            }
            boolean crossOwnerDuplicate = false;
            for (Map.Entry<CatalogKey, CommandDoc> entry : current.entries().entrySet()) {
                if (!entry.getKey().isOwnedBy(owner)
                        && entry.getKey().lowerName.equals(lowerName)) {
                    crossOwnerDuplicate = true;
                    break;
                }
            }
            Map<CatalogKey, CommandDoc> next = new LinkedHashMap<>(current.entries());
            next.put(key, doc);
            CatalogResult result;
            if (crossOwnerDuplicate) {
                result = CatalogResult.DUPLICATE_NAME;
            } else if (existing != null) {
                result = CatalogResult.REPLACED;
            } else {
                result = CatalogResult.PUBLISHED;
            }
            State updated = new State(
                Collections.unmodifiableMap(next), current.revision() + 1);
            if (state.compareAndSet(current, updated)) {
                if (result == CatalogResult.DUPLICATE_NAME) {
                    LOGGER.log(Level.WARNING,
                        CommandCatalogErrorCodes.DUPLICATE_NAME
                            + ": duplicate command name '" + doc.name()
                            + "' published by '" + doc.owner()
                            + "'; both entries retained");
                }
                return result;
            }
        }
    }
}
