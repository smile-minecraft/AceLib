package com.smile.acelib.external;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * PlaceholderAPI 佔位符註冊的 typed 包裝（Internal）。
 *
 * <p>PlaceholderAPI 型別只出現在本類別與 {@link AceLibPlaceholderExpansion} 內；
 * 本類別只在 PlaceholderAPI adapter 探測為 {@code AVAILABLE} 後才由 plugin 建構，
 * 因此 PlaceholderAPI 缺席時本類別不會被載入。每個 identifier 對應一個 expansion
 * 子類別實例；{@link #close()}（服務停用／reload／提供者被替換時由門面呼叫）
 * 取消全部註冊，不殘留。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴；下游以
 * {@link PlaceholderProvider} 介面註冊自有佔位符。</p>
 *
 * @since 1.4.0
 */
public final class PlaceholderApiPlaceholderProvider implements PlaceholderProvider {

    /** 佔位符識別規則（沿用 PlaceholderAPI 社群慣例的保守子集）。 */
    static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,32}");

    /** 清理路徑日誌（沿用 {@code Logger.getLogger("AceLib")} 慣例）。 */
    private static final Logger LOGGER = Logger.getLogger("AceLib");

    /** Expansion 建構函式（production 建 {@link AceLibPlaceholderExpansion}）。 */
    @FunctionalInterface
    interface ExpansionFactory {
        /**
         * 建立尚未註冊的 expansion。
         *
         * @param identifier 佔位符識別
         * @param handler 解析處理器
         * @return 新 expansion
         */
        ManagedExpansion create(String identifier, PlaceholderHandler handler);
    }

    /** 可註冊／取消註冊的 expansion 抽象（production 由子類別實作承接 PAPI 型別）。 */
    interface ManagedExpansion {
        /**
         * 向 PlaceholderAPI 註冊。
         *
         * @return 是否註冊成功
         */
        boolean register();

        /** 向 PlaceholderAPI 取消註冊（盡力而為）。 */
        void unregister();
    }

    private final ExpansionFactory factory;
    private final Map<String, ManagedExpansion> expansions = new ConcurrentHashMap<>();

    /**
     * 建構子（production 用：以 {@link AceLibPlaceholderExpansion} 建 expansion）。
     */
    public PlaceholderApiPlaceholderProvider() {
        this(PapiExpansion::new);
    }

    /**
     * 完整建構子（package-private，供測試注入 factory 以隔離 PAPI 執行期）。
     *
     * @param factory expansion 建構函式；不可為 null
     */
    PlaceholderApiPlaceholderProvider(ExpansionFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public ExternalOperationResult registerPlaceholder(String identifier,
            PlaceholderHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        String normalized = normalize(identifier);
        if (expansions.containsKey(normalized)) {
            return ExternalOperationResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_FAILED,
                "placeholder '" + normalized + "' is already registered");
        }
        ManagedExpansion expansion;
        try {
            expansion = factory.create(normalized, handler);
        } catch (Exception | LinkageError e) {
            return ExternalOperationResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_FAILED,
                "placeholder '" + normalized + "' expansion creation failed: " + e);
        }
        boolean registered;
        try {
            registered = expansion.register();
        } catch (Exception | LinkageError e) {
            return ExternalOperationResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_FAILED,
                "placeholder '" + normalized + "' registration failed: " + e);
        }
        if (!registered) {
            return ExternalOperationResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_FAILED,
                "placeholder '" + normalized + "' was rejected by PlaceholderAPI");
        }
        expansions.put(normalized, expansion);
        return ExternalOperationResult.success(
            "placeholder '" + normalized + "' registered");
    }

    @Override
    public ExternalOperationResult unregisterPlaceholder(String identifier) {
        String normalized = normalize(identifier);
        ManagedExpansion expansion = expansions.remove(normalized);
        if (expansion == null) {
            return ExternalOperationResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_FAILED,
                "placeholder '" + normalized + "' is not registered");
        }
        try {
            expansion.unregister();
        } catch (Exception | LinkageError e) {
            // 追蹤已先移除（不殘留）；外部失敗留一行日誌並回明確失敗。
            LOGGER.warning("["
                + ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_FAILED
                + "] placeholder '" + normalized + "' unregistration failed: " + e);
            return ExternalOperationResult.failure(ExternalResultState.FAILED,
                ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_FAILED,
                "placeholder '" + normalized + "' unregistration failed: " + e);
        }
        return ExternalOperationResult.success(
            "placeholder '" + normalized + "' unregistered");
    }

    @Override
    public void close() {
        for (Map.Entry<String, ManagedExpansion> entry : expansions.entrySet()) {
            try {
                entry.getValue().unregister();
            } catch (Exception | LinkageError e) {
                // 盡力清理：個別失敗記一行日誌但不中斷其他。
                LOGGER.warning("["
                    + ExternalIntegrationErrorCodes.ACELIB_EXT_CLEANUP_FAILED
                    + "] placeholder '" + entry.getKey()
                    + "' cleanup failed: " + e);
            }
        }
        expansions.clear();
    }

    /**
     * 目前已註冊的 identifier 快照（測試用）。
     *
     * @return 不可變快照
     */
    Set<String> registeredIdentifiers() {
        return Set.copyOf(expansions.keySet());
    }

    private static String normalize(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("identifier must not be null or blank");
        }
        String normalized = identifier.strip().toLowerCase(Locale.ROOT);
        if (!IDENTIFIER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                "identifier must match [A-Za-z0-9_]{1,32}, got: " + identifier);
        }
        return normalized;
    }

    /**
     * Production expansion 承接器：把 PAPI 型別收斂在單一子類別內。
     */
    private static final class PapiExpansion implements ManagedExpansion {
        private final AceLibPlaceholderExpansion delegate;

        PapiExpansion(String identifier, PlaceholderHandler handler) {
            this.delegate = new AceLibPlaceholderExpansion(identifier, handler);
        }

        @Override
        public boolean register() {
            return delegate.register();
        }

        @Override
        public void unregister() {
            delegate.unregister();
        }
    }
}
