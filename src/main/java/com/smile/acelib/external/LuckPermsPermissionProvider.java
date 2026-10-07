package com.smile.acelib.external;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.luckperms.api.LuckPerms;

/**
 * LuckPerms 權限查詢的 typed 包裝（Internal）。
 *
 * <p>LuckPerms 型別只出現在本類別內；本類別只在 LuckPerms adapter 探測為
 * {@code AVAILABLE} 後才由 plugin 建構，因此 LuckPerms 缺席時本類別不會被載入。
 * {@code LuckPerms}／{@code User} 實例每次呼叫重新解析（經 supplier 與
 * {@code UserManager#getUser}），不跨呼叫快取（停用後下一次呼叫即回到不可用，
 * 無舊引用殘留）；離線未知玩家以 {@code getUser} 的 null 結果判定，不做阻塞載入。</p>
 *
 * <p>本類別為 Internal 實作細節，下游不得直接依賴；下游以
 * {@link PermissionProvider} 介面取得權限能力。</p>
 *
 * @since 1.4.0
 */
public final class LuckPermsPermissionProvider implements PermissionProvider {

    private final Supplier<LuckPerms> api;

    /**
     * 建構子。
     *
     * @param api 每次呼叫解析 {@code LuckPerms} 實例的 supplier（例如
     *     {@code LuckPermsProvider::get}；缺席時回 null）；不可為 null
     */
    public LuckPermsPermissionProvider(Supplier<LuckPerms> api) {
        this.api = Objects.requireNonNull(api, "api");
    }

    @Override
    public PermissionResult getPermissionGroups(UUID playerId) {
        if (playerId == null) {
            throw new IllegalArgumentException("playerId must not be null");
        }
        LuckPerms luckPerms;
        try {
            luckPerms = api.get();
        } catch (IllegalStateException e) {
            // 解析失敗（production 為 LuckPermsProvider.get 在未載入時拋的
            // NotLoadedException）：提供者尚未可用，視為缺席而非操作失敗。
            return unavailable("luckperms api is not loaded: " + concise(e));
        } catch (Exception e) {
            return failed("luckperms api resolution failed: " + e);
        }
        if (luckPerms == null) {
            return unavailable();
        }
        return lookupGroups(luckPerms, playerId);
    }

    private static PermissionResult unavailable() {
        return unavailable("luckperms permission service is not available");
    }

    private static PermissionResult unavailable(String detail) {
        return PermissionResult.failure(ExternalResultState.UNAVAILABLE,
            ExternalIntegrationErrorCodes.ACELIB_EXT_PERMISSION_UNAVAILABLE,
            detail);
    }

    private static PermissionResult failed(String detail) {
        return PermissionResult.failure(ExternalResultState.FAILED,
            ExternalIntegrationErrorCodes.ACELIB_EXT_PERMISSION_FAILED, detail);
    }

    // ----- typed 查詢 -----

    /**
     * 方法鏈（LuckPerms 5.5 官方 API）：{@code UserManager#getUser} 取快取使用者
     * （null 即查無，不做阻塞載入）→ {@code User#getPrimaryGroup} 取主要群組 →
     * {@code ContextManager#getQueryOptions} 取當前情境選項（使用者選項缺席時
     * 回退靜態選項）→ {@code resolveDistinctInheritedNodes} 過濾
     * {@code InheritanceNode#getGroupName} 取所屬群組 →
     * {@code QueryOptions#context→toMap} 取情境快照。
     */
    private static PermissionResult lookupGroups(LuckPerms luckPerms, UUID playerId) {
        try {
            net.luckperms.api.model.user.User user =
                luckPerms.getUserManager().getUser(playerId);
            if (user == null) {
                return failed("no luckperms user for " + playerId);
            }
            net.luckperms.api.context.ContextManager contextManager =
                luckPerms.getContextManager();
            net.luckperms.api.query.QueryOptions queryOptions = contextManager
                .getQueryOptions(user)
                .orElseGet(contextManager::getStaticQueryOptions);
            java.util.Set<String> groups = user
                .resolveDistinctInheritedNodes(queryOptions).stream()
                .filter(net.luckperms.api.node.types.InheritanceNode.class::isInstance)
                .map(node -> ((net.luckperms.api.node.types.InheritanceNode) node)
                    .getGroupName())
                .collect(java.util.stream.Collectors.toCollection(
                    java.util.TreeSet::new));
            java.util.Map<String, java.util.Set<String>> contexts =
                new java.util.LinkedHashMap<>(queryOptions.context().toMap());
            return PermissionResult.success(user.getPrimaryGroup(), groups, contexts,
                "luckperms lookup for " + playerId + " succeeded");
        } catch (Exception e) {
            return failed("luckperms lookup failed: " + concise(e));
        }
    }

    private static String concise(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName()
            + (message == null ? "" : ": " + message);
    }
}
