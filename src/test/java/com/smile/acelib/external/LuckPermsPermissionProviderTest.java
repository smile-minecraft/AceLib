package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ContextManager;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.query.QueryOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * LuckPerms 權限 typed 包裝測試。
 *
 * <p>以 Mockito mock 官方 API 方法鏈（{@code UserManager#getUser} →
 * {@code User#getPrimaryGroup}／{@code resolveDistinctInheritedNodes} 過濾
 * {@code InheritanceNode#getGroupName}、{@code ContextManager#getQueryOptions}
 * 回退靜態選項、{@code QueryOptions#context→toMap}）驗證映射；API 版本為
 * {@code net.luckperms:api:5.5}。</p>
 */
@DisplayName("LuckPerms 權限 typed 包裝")
class LuckPermsPermissionProviderTest {

    private LuckPerms api;
    private UserManager userManager;
    private User user;
    private ContextManager contextManager;
    private QueryOptions queryOptions;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        api = Mockito.mock(LuckPerms.class);
        userManager = Mockito.mock(UserManager.class);
        user = Mockito.mock(User.class);
        contextManager = Mockito.mock(ContextManager.class);
        queryOptions = Mockito.mock(QueryOptions.class);
        playerId = UUID.randomUUID();

        Mockito.when(api.getUserManager()).thenReturn(userManager);
        Mockito.when(api.getContextManager()).thenReturn(contextManager);
        Mockito.when(userManager.getUser(playerId)).thenReturn(user);
        Mockito.when(user.getPrimaryGroup()).thenReturn("admin");
        InheritanceNode vip = Mockito.mock(InheritanceNode.class);
        InheritanceNode member = Mockito.mock(InheritanceNode.class);
        Mockito.when(vip.getGroupName()).thenReturn("vip");
        Mockito.when(member.getGroupName()).thenReturn("member");
        java.util.SortedSet<net.luckperms.api.node.Node> nodes = new java.util.TreeSet<>(
            java.util.Comparator.comparing(Object::toString));
        nodes.add(vip);
        nodes.add(member);
        Mockito.when(user.resolveDistinctInheritedNodes(queryOptions)).thenReturn(nodes);
        Mockito.when(contextManager.getQueryOptions(user))
            .thenReturn(Optional.of(queryOptions));
        ImmutableContextSet contexts = Mockito.mock(ImmutableContextSet.class);
        Mockito.when(queryOptions.context()).thenReturn(contexts);
        Mockito.when(contexts.toMap())
            .thenReturn(Map.of("world", Set.of("world_nether")));
    }

    @Test
    @DisplayName("正常：回主要群組、所屬群組與情境")
    void groups_success() {
        LuckPermsPermissionProvider provider =
            new LuckPermsPermissionProvider(() -> api);

        PermissionResult result = provider.getPermissionGroups(playerId);
        assertTrue(result.isSuccess());
        assertEquals("admin", result.primaryGroup());
        assertEquals(Set.of("member", "vip"), result.groups());
        assertEquals(Map.of("world", Set.of("world_nether")), result.contexts());
        assertEquals(null, result.errorCode());
    }

    @Test
    @DisplayName("情境回退：使用者查詢選項缺席時用靜態選項")
    void contextFallback_usesStaticOptions() {
        QueryOptions staticOptions = Mockito.mock(QueryOptions.class);
        Mockito.when(contextManager.getQueryOptions(user)).thenReturn(Optional.empty());
        Mockito.when(contextManager.getStaticQueryOptions()).thenReturn(staticOptions);
        ImmutableContextSet staticContexts = Mockito.mock(ImmutableContextSet.class);
        Mockito.when(staticOptions.context()).thenReturn(staticContexts);
        Mockito.when(staticContexts.toMap()).thenReturn(Map.of());
        Mockito.when(user.resolveDistinctInheritedNodes(staticOptions)).thenReturn(
            new java.util.TreeSet<>(
                java.util.Comparator.comparing(Object::toString)));

        LuckPermsPermissionProvider provider =
            new LuckPermsPermissionProvider(() -> api);

        PermissionResult result = provider.getPermissionGroups(playerId);
        assertTrue(result.isSuccess());
        assertEquals("admin", result.primaryGroup());
        assertTrue(result.contexts().isEmpty());
    }

    @Test
    @DisplayName("邊界：API 缺席回 UNAVAILABLE")
    void absent_unavailable() {
        LuckPermsPermissionProvider provider =
            new LuckPermsPermissionProvider(() -> null);

        PermissionResult result = provider.getPermissionGroups(playerId);
        assertEquals(ExternalResultState.UNAVAILABLE, result.state());
        assertEquals(
            ExternalIntegrationErrorCodes.ACELIB_EXT_PERMISSION_UNAVAILABLE,
            result.errorCode());
    }

    @Test
    @DisplayName("邊界：解析器拋 IllegalStateException（未載入）回 UNAVAILABLE")
    void resolutionThrowsIllegalState_unavailable() {
        LuckPermsPermissionProvider provider =
            new LuckPermsPermissionProvider(() -> {
                throw new IllegalStateException("LuckPerms is not loaded");
            });

        PermissionResult result = provider.getPermissionGroups(playerId);
        assertEquals(ExternalResultState.UNAVAILABLE, result.state());
        assertEquals(
            ExternalIntegrationErrorCodes.ACELIB_EXT_PERMISSION_UNAVAILABLE,
            result.errorCode());
    }

    @Test
    @DisplayName("失敗：查無使用者回 FAILED（群組為空）")
    void unknownUser_failed() {
        Mockito.when(userManager.getUser(playerId)).thenReturn(null);
        LuckPermsPermissionProvider provider =
            new LuckPermsPermissionProvider(() -> api);

        PermissionResult result = provider.getPermissionGroups(playerId);
        assertEquals(ExternalResultState.FAILED, result.state());
        assertFalse(result.isSuccess());
        assertTrue(result.groups().isEmpty());
        assertEquals(
            ExternalIntegrationErrorCodes.ACELIB_EXT_PERMISSION_FAILED,
            result.errorCode());
    }

    @Test
    @DisplayName("失敗：底層拋例外轉為明確 FAILED（不逃逸）")
    void apiThrows_failed() {
        Mockito.when(userManager.getUser(playerId))
            .thenThrow(new IllegalStateException("boom"));
        LuckPermsPermissionProvider provider =
            new LuckPermsPermissionProvider(() -> api);

        PermissionResult result = provider.getPermissionGroups(playerId);
        assertEquals(ExternalResultState.FAILED, result.state());
    }

    @Test
    @DisplayName("無效：null 識別拋 IllegalArgumentException")
    void nullId_throws() {
        LuckPermsPermissionProvider provider =
            new LuckPermsPermissionProvider(() -> api);
        assertThrows(IllegalArgumentException.class,
            () -> provider.getPermissionGroups(null));
    }
}
