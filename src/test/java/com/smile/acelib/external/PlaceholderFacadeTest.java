package com.smile.acelib.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 佔位符門面測試：註冊／取消註冊的正常、無效輸入、邊界與清理。
 */
@DisplayName("佔位符門面")
class PlaceholderFacadeTest {

    private ExternalIntegrationServiceImpl service;

    /** 不碰 PAPI 執行期的假 expansion。 */
    private static final class FakeExpansion
            implements PlaceholderApiPlaceholderProvider.ManagedExpansion {
        final boolean registerResult;
        boolean unregistered;
        final PlaceholderHandler handler;

        FakeExpansion(boolean registerResult, PlaceholderHandler handler) {
            this.registerResult = registerResult;
            this.handler = handler;
        }

        @Override
        public boolean register() {
            return registerResult;
        }

        @Override
        public void unregister() {
            unregistered = true;
        }
    }

    private final java.util.Map<String, FakeExpansion> created = new java.util.LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        service = new ExternalIntegrationServiceImpl(new IntegrationRegistry());
        created.clear();
        service.setPlaceholderProvider(new PlaceholderApiPlaceholderProvider(
            (identifier, handler) -> {
                FakeExpansion expansion = new FakeExpansion(true, handler);
                created.put(identifier, expansion);
                return expansion;
            }));
    }

    @Test
    @DisplayName("正常：註冊成功")
    void register_success() {
        ExternalOperationResult result =
            service.registerPlaceholder("acelib_test", (player, params) -> "v:" + params);
        assertTrue(result.isSuccess());
        assertTrue(created.containsKey("acelib_test"));
    }

    @Test
    @DisplayName("處理器可被呼叫：expansion 轉交玩家識別與參數")
    void handler_delegates() {
        service.registerPlaceholder("acelib_test",
            (player, params) -> (player == null ? "none" : player.toString()) + ":" + params);
        FakeExpansion expansion = created.get("acelib_test");

        UUID id = UUID.randomUUID();
        assertEquals(id + ":hp", expansion.handler.onRequest(id, "hp"));
        assertEquals("none:", expansion.handler.onRequest(null, ""));
    }

    @Test
    @DisplayName("正常：取消註冊成功且清理 expansion")
    void unregister_successAndCleans() {
        service.registerPlaceholder("acelib_test", (player, params) -> "x");

        ExternalOperationResult result = service.unregisterPlaceholder("acelib_test");
        assertTrue(result.isSuccess());
        assertTrue(created.get("acelib_test").unregistered);
    }

    @Test
    @DisplayName("邊界：重複識別回 FAILED")
    void duplicate_failed() {
        service.registerPlaceholder("acelib_test", (player, params) -> "x");

        ExternalOperationResult result =
            service.registerPlaceholder("acelib_test", (player, params) -> "y");
        assertEquals(ExternalResultState.FAILED, result.state());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("邊界：取消未註冊識別回 FAILED（不拋例外）")
    void unregisterUnknown_failed() {
        ExternalOperationResult result = service.unregisterPlaceholder("ghost");
        assertEquals(ExternalResultState.FAILED, result.state());
    }

    @Test
    @DisplayName("邊界：底層拒絕註冊回 FAILED")
    void rejected_failed() {
        service.setPlaceholderProvider(new PlaceholderApiPlaceholderProvider(
            (identifier, handler) -> new FakeExpansion(false, handler)));

        ExternalOperationResult result =
            service.registerPlaceholder("rejected", (player, params) -> "x");
        assertEquals(ExternalResultState.FAILED, result.state());
    }

    @Test
    @DisplayName("無效：空白識別／null 處理器拋 IllegalArgumentException")
    void invalid_throws() {
        assertThrows(IllegalArgumentException.class,
            () -> service.registerPlaceholder("  ", (player, params) -> "x"));
        assertThrows(IllegalArgumentException.class,
            () -> service.registerPlaceholder(null, (player, params) -> "x"));
        assertThrows(IllegalArgumentException.class,
            () -> service.registerPlaceholder("ok", null));
        assertThrows(IllegalArgumentException.class,
            () -> service.unregisterPlaceholder(null));
    }

    @Test
    @DisplayName("邊界：無提供者時註冊回 UNAVAILABLE")
    void absentProvider_unavailable() {
        service.clearPlaceholderProvider();

        ExternalOperationResult result =
            service.registerPlaceholder("acelib_test", (player, params) -> "x");
        assertEquals(ExternalResultState.UNAVAILABLE, result.state());
        assertEquals(
            ExternalIntegrationErrorCodes.ACELIB_EXT_PLACEHOLDER_UNAVAILABLE,
            result.errorCode());
    }

    @Test
    @DisplayName("生命週期：服務停用時清理全部註冊、不殘留")
    void shutdown_cleansAll() {
        PlaceholderApiPlaceholderProvider provider = new PlaceholderApiPlaceholderProvider(
            (identifier, handler) -> {
                FakeExpansion expansion = new FakeExpansion(true, handler);
                created.put(identifier, expansion);
                return expansion;
            });
        ExternalIntegrationServiceImpl owned =
            new ExternalIntegrationServiceImpl(new IntegrationRegistry(),
                null, null, provider, null);
        owned.registerPlaceholder("one", (player, params) -> "1");
        owned.registerPlaceholder("two", (player, params) -> "2");

        owned.shutdown();

        assertTrue(created.get("one").unregistered);
        assertTrue(created.get("two").unregistered);
        assertTrue(provider.registeredIdentifiers().isEmpty());
    }

    @Test
    @DisplayName("生命週期：reload（重建服務）後舊註冊不殘留")
    void rebind_doesNotRetain() {
        service.registerPlaceholder("acelib_test", (player, params) -> "x");
        FakeExpansion old = created.get("acelib_test");
        service.shutdown();

        PlaceholderApiPlaceholderProvider fresh = new PlaceholderApiPlaceholderProvider(
            (identifier, handler) -> new FakeExpansion(true, handler));
        ExternalIntegrationServiceImpl rebound =
            new ExternalIntegrationServiceImpl(new IntegrationRegistry(),
                null, null, fresh, null);

        assertTrue(old.unregistered, "舊服務停用時必須清理舊註冊");
        assertTrue(fresh.registeredIdentifiers().isEmpty());
        ExternalOperationResult unknown = rebound.unregisterPlaceholder("acelib_test");
        assertEquals(ExternalResultState.FAILED, unknown.state());
    }
}
