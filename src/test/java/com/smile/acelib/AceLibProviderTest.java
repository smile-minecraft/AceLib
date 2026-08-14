package com.smile.acelib;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.platform.PlatformDetector;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * {@code AceLibProvider} 正式取得入口的 lifecycle 測試。
 *
 * <p>驗證契約：</p>
 * <ul>
 *   <li>plugin enable 前，ServicesManager 沒有 provider registration</li>
 *   <li>plugin enable 後，可透過 {@code getRegistration} 取得 provider，
 *       且 {@code provider.api()} 與 {@code plugin.getApi()} 一致</li>
 *   <li>reload 後，同一個 provider 回傳最新 facade（不殘留 stale facade）</li>
 *   <li>disable 後，registration 被解除；已持有 provider 的呼叫端取得
 *       shutdown facade（{@code isReady() = false}）</li>
 *   <li>重複 lifecycle（重複 onEnable）不產生重複 registration</li>
 * </ul>
 *
 * <p>本測試沿用 {@link AceLibPluginTest} 的環境隔離模式：
 * {@code MockBukkit.mock()} + {@code loadPlugin}（不 enable）+ 手動
 * {@code onEnable(server, detector)}，避免 plugin classloader NPE。</p>
 */
@DisplayName("AceLibProvider")
class AceLibProviderTest {

    private static ServerMock server;
    private AceLibPlugin plugin;

    @AfterAll
    static void tearDownBukkit() {
        MockBukkit.unmock();
    }

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
        MockBukkit.unmock();
    }

    private AceLibPlugin freshNotEnabled() {
        MockBukkit.unmock();
        server = MockBukkit.mock();
        return (AceLibPlugin) server.getPluginManager().loadPlugin(AceLibPlugin.class);
    }

    private RegisteredServiceProvider<AceLibApi.AceLibProvider> registration() {
        return server.getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);
    }

    // ---------------------------------------------------------------------
    // 正常 / 缺失（not-ready）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("plugin enable 後，ServicesManager 可取得 AceLibProvider registration")
    void enabled_providerRegistered() {
        RegisteredServiceProvider<AceLibApi.AceLibProvider> reg = registration();
        assertNotNull(reg, "enable 後必須有 provider registration");
        assertNotNull(reg.getProvider(), "registration 必須攜帶非 null provider");
        assertTrue(server.getServicesManager().isProvidedFor(AceLibApi.AceLibProvider.class),
            "enable 後 isProvidedFor 必須為 true");
    }

    @Test
    @DisplayName("enable 後 provider.api() 與 plugin.getApi() 為同一目前 facade")
    void enabled_providerReturnsCurrentFacade() {
        AceLibApi.AceLibProvider provider = registration().getProvider();
        assertNotNull(provider);
        assertSame(plugin.getApi(), provider.api(),
            "provider 必須回傳與 plugin.getApi() 相同的 facade");
        assertTrue(provider.api().isReady(), "enable 後 provider.api().isReady() 必須為 true");
    }

    @Test
    @DisplayName("enable 後 load(AceLibProvider) 與 getRegistration 回傳相同 provider")
    void enabled_loadReturnsSameProvider() {
        AceLibApi.AceLibProvider fromLoad =
            server.getServicesManager().load(AceLibApi.AceLibProvider.class);
        assertSame(registration().getProvider(), fromLoad,
            "load 與 getRegistration 必須回傳同一 provider");
    }

    @Test
    @DisplayName("plugin enable 前，ServicesManager 沒有 provider（缺失/not-ready）")
    void notEnabled_providerNotRegistered() {
        AceLibPlugin fresh = freshNotEnabled();
        assertFalse(fresh.isReady(), "fresh plugin 不應 ready");
        assertNull(server.getServicesManager().getRegistration(AceLibApi.AceLibProvider.class),
            "enable 前不得有 provider registration");
        assertFalse(server.getServicesManager().isProvidedFor(AceLibApi.AceLibProvider.class),
            "enable 前 isProvidedFor 必須為 false");
        assertTrue(
            server.getServicesManager().getRegistrations(AceLibApi.AceLibProvider.class).isEmpty(),
            "enable 前不得有任何 provider registrations");
    }

    // ---------------------------------------------------------------------
    // reload
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("reload 後同一個 provider 回傳新的目前 facade（不殘留 stale）")
    void reload_providerReturnsNewFacade() {
        AceLibApi.AceLibProvider provider = registration().getProvider();
        AceLibApi before = plugin.getApi();
        assertSame(before, provider.api(), "reload 前 provider 應回傳目前 facade");

        assertTrue(plugin.reload(), "已啟用時 reload 應成功");

        AceLibApi after = plugin.getApi();
        assertNotSame(before, after, "reload 必須替換 facade instance");
        assertSame(after, provider.api(), "reload 後 provider 必須回傳新 facade");
        assertTrue(provider.api().isReady(), "reload 後 provider.api().isReady() 必須為 true");

        RegisteredServiceProvider<AceLibApi.AceLibProvider> reg = registration();
        assertNotNull(reg, "reload 不得解除 registration");
        assertSame(provider, reg.getProvider(), "reload 後 registration 應仍是同一個 provider");
    }

    @Test
    @DisplayName("reload 在尚未 enable 時回傳 false，且不產生 registration")
    void reloadBeforeEnable_noRegistration() {
        AceLibPlugin fresh = freshNotEnabled();
        assertFalse(fresh.reload(), "尚未 enable 時 reload 應回傳 false");
        assertNull(server.getServicesManager().getRegistration(AceLibApi.AceLibProvider.class),
            "尚未 enable 時 reload 不得產生 registration");
    }

    // ---------------------------------------------------------------------
    // disable
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("disable 後 provider registration 已解除（不可取得）")
    void disable_providerUnregistered() {
        assertNotNull(registration(), "disable 前必須有 registration");
        plugin.onDisable();
        assertNull(server.getServicesManager().getRegistration(AceLibApi.AceLibProvider.class),
            "disable 後不得再取得 provider registration");
        assertFalse(server.getServicesManager().isProvidedFor(AceLibApi.AceLibProvider.class),
            "disable 後 isProvidedFor 必須為 false");
    }

    @Test
    @DisplayName("disable 後已持有 provider 的呼叫端取得 shutdown facade（isReady=false）")
    void disable_cachedProviderReturnsShutdownFacade() {
        AceLibApi.AceLibProvider provider = registration().getProvider();
        plugin.onDisable();
        assertSame(plugin.getApi(), provider.api(),
            "disable 後 cached provider 應與 plugin.getApi() 一致（shutdown facade）");
        assertFalse(provider.api().isReady(), "disable 後 cached provider.api().isReady() 必須為 false");
    }

    @Test
    @DisplayName("disable 在尚未 enable 時安全 no-op，不丟例外且無 registration")
    void disableBeforeEnable_safe() {
        AceLibPlugin fresh = freshNotEnabled();
        assertDoesNotThrow(fresh::onDisable, "尚未 enable 時 onDisable 不可丟例外");
        assertNull(server.getServicesManager().getRegistration(AceLibApi.AceLibProvider.class));
    }

    // ---------------------------------------------------------------------
    // 重複 lifecycle（edge cases）
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("重複 onEnable 為冪等：只保留單一 registration")
    void onEnableIdempotent_singleRegistration() {
        AceLibApi.AceLibProvider before = registration().getProvider();
        ServerMock s = server;
        assertDoesNotThrow(() -> plugin.onEnable(s,
            new PlatformDetector(getClass().getClassLoader())),
            "重複 onEnable 不可丟例外");

        assertEquals(1,
            server.getServicesManager().getRegistrations(AceLibApi.AceLibProvider.class).size(),
            "重複 onEnable 不得造成重複 registration");
        assertSame(before, registration().getProvider(),
            "冪等 onEnable 不得替換 provider 實例");
    }
}
