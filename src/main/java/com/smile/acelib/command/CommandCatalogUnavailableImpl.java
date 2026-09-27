package com.smile.acelib.command;

import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.plugin.Plugin;

/**
 * {@link CommandCatalog} 的未啟用／已停用退回實作。
 *
 * <p>任何狀態下呼叫本類別的 {@code publish} 都回 {@link CatalogResult#REJECTED}
 * 並記錄 {@code ACELIB-CMD-014}，{@code snapshot()} 回空清單，
 * {@code unpublishAll} 與 {@code shutdown()} 為無害的 no-op —
 * <strong>永不為 null，絕不丟例外（除了 null inputs 的契約例外）</strong>。
 * 後續插件於 AceLib 未啟用或已停用後呼叫
 * {@code AceLibApi.getCommandCatalog()} 即取得此 instance。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴；透過
 * {@link CommandCatalog#forUnavailable()} 或
 * {@link com.smile.acelib.AceLibApi#getCommandCatalog()} 取得
 * {@link CommandCatalog} 介面。比照
 * {@code GuiServiceUnavailableImpl}／{@code BedrockServiceUnavailableImpl}
 * 的隱藏實作慣例。</p>
 */
final class CommandCatalogUnavailableImpl implements CommandCatalog {

    private static final Logger LOGGER = Logger.getLogger("AceLib");

    @Override
    public CatalogResult publish(Plugin owner, CommandSpec spec, CatalogMeta meta) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(meta, "meta");
        LOGGER.log(Level.WARNING,
            CommandCatalogErrorCodes.NOT_READY
                + ": command catalog not ready or disabled; publish of '"
                + spec.name() + "' rejected");
        return CatalogResult.REJECTED;
    }

    @Override
    public CatalogResult publish(Plugin owner, CommandDoc doc) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(doc, "doc");
        LOGGER.log(Level.WARNING,
            CommandCatalogErrorCodes.NOT_READY
                + ": command catalog not ready or disabled; publish of '"
                + doc.name() + "' rejected");
        return CatalogResult.REJECTED;
    }

    @Override
    public void unpublishAll(Plugin owner) {
        Objects.requireNonNull(owner, "owner");
        // 不可用時為無害的 no-op
    }

    @Override
    public List<CommandDoc> snapshot() {
        return List.of();
    }

    @Override
    public long revision() {
        return 0;
    }

    @Override
    public void shutdown() {
        // 已是不可用狀態，無事可做
    }
}
