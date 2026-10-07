package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.external.BuildCheckResult;
import com.smile.acelib.external.EconomyResult;
import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.ExternalOperationResult;
import com.smile.acelib.external.ExternalResultState;
import com.smile.acelib.external.PermissionResult;
import com.smile.acelib.platform.PlatformDetector;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockito.Mockito;

/**
 * AceLibPlugin 外部業務提供者接線測試。
 *
 * <p>外部插件全數缺席的 MockBukkit 環境：內建提供者皆為 null，四類業務回明確
 * 不可用；下游可註冊自有建造查詢提供者並生效；reload 後舊服務已停用、新服務
 * 無舊引用殘留。</p>
 */
@DisplayName("AceLibPlugin 外部業務提供者接線")
class AceLibPluginExternalProvidersTest {

    private static ServerMock server;
    private AceLibPlugin plugin;

    @BeforeAll
    static void setUpClass() {
        server = MockBukkit.mock();
    }

    @BeforeEach
    void loadFresh() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        plugin = (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
        plugin.onEnable(server, new PlatformDetector(getClass().getClassLoader()));
    }

    @AfterEach
    void unloadPlugin() {
        if (plugin != null && plugin.isReady()) {
            plugin.onDisable();
        }
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("外部插件缺席時四類業務皆回不可用（建造預設拒絕）")
    void absentProviders_allUnavailable() {
        ExternalIntegrationService ext = plugin.getExternalIntegrationService();
        assertNotNull(ext);
        UUID id = UUID.randomUUID();
        OfflinePlayer player = Mockito.mock(OfflinePlayer.class);

        EconomyResult balance = ext.getBalance(player);
        assertEquals(ExternalResultState.UNAVAILABLE, balance.state());
        assertFalse(balance.isSuccess());

        PermissionResult groups = ext.getPermissionGroups(id);
        assertEquals(ExternalResultState.UNAVAILABLE, groups.state());

        ExternalOperationResult registered =
            ext.registerPlaceholder("probe", (p, params) -> "v");
        assertEquals(ExternalResultState.UNAVAILABLE, registered.state());

        BuildCheckResult build = ext.canBuild(id, "world", 10, 64, -5);
        assertEquals(ExternalResultState.UNAVAILABLE, build.state());
        assertFalse(build.allowed());
    }

    @Test
    @DisplayName("下游可註冊自有建造查詢提供者並生效")
    void downstreamBuildCheckProvider_effective() {
        ExternalIntegrationService ext = plugin.getExternalIntegrationService();
        UUID id = UUID.randomUUID();

        ext.setBuildCheckProvider(
            (player, world, x, y, z) -> BuildCheckResult.success(true, "downstream-ok"));

        BuildCheckResult result = ext.canBuild(id, "world", 10, 64, -5);
        assertTrue(result.isSuccess());
        assertTrue(result.allowed());
    }

    @Test
    @DisplayName("reload 後舊服務已停用且無舊引用：舊下游覆寫不影響新服務")
    void reload_dropsOldOverrides() {
        ExternalIntegrationService before = plugin.getExternalIntegrationService();
        before.setBuildCheckProvider(
            (player, world, x, y, z) -> BuildCheckResult.success(true, "old"));
        UUID id = UUID.randomUUID();
        assertTrue(before.canBuild(id, "world", 0, 64, 0).allowed());

        assertTrue(plugin.reload());

        assertEquals("SHUTDOWN", before.getModuleStatus());
        BuildCheckResult stale = before.canBuild(id, "world", 0, 64, 0);
        assertEquals(ExternalResultState.UNAVAILABLE, stale.state());

        ExternalIntegrationService after = plugin.getExternalIntegrationService();
        assertNotSame(before, after);
        BuildCheckResult fresh = after.canBuild(id, "world", 0, 64, 0);
        assertEquals(ExternalResultState.UNAVAILABLE, fresh.state(),
            "新服務不得殘留舊服務的下游覆寫");
        assertFalse(fresh.allowed());
    }
}
