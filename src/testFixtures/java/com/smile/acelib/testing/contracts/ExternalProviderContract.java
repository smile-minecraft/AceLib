package com.smile.acelib.testing.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.external.EconomyProvider;
import com.smile.acelib.external.EconomyResult;
import com.smile.acelib.external.ExternalIntegrationService;
import com.smile.acelib.external.ExternalResultState;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 外部整合業務提供者契約（真實作與假實作共用）。
 *
 * <p>鎖定提供者替換語意：缺席時不可用（不得視為成功）、注入後生效、
 * 替換後舊提供者不再被引用、移除後回到不可用、提供者拋例外轉為明確失敗、
 * 停用後 set 被拒絕。子類提供 harness（生產側用
 * {@code ExternalIntegrationServiceImpl}＋可數假提供者，假側用
 * {@code FakeExternalIntegrationService}）。</p>
 *
 * @since 1.4.0
 */
@DisplayName("外部整合業務提供者契約（真／假共用）")
public abstract class ExternalProviderContract {

    /** 可操控經濟提供者的測試 harness（每測試全新）。 */
    protected interface Harness {
        ExternalIntegrationService service();

        /** 測試用玩家替身（由兩側以各自的 mock 工具提供）。 */
        OfflinePlayer player();

        /** 注入假經濟提供者。 */
        void useEconomy(EconomyProvider provider);

        /** 移除經濟提供者（回到缺席）。 */
        void dropEconomy();
    }

    /** 每個測試全新 harness。 */
    protected abstract Harness newHarness();

    /** 固定成功的假提供者（附呼叫計數，驗證無舊引用殘留）。 */
    protected static final class CountingEconomy implements EconomyProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public EconomyResult getBalance(OfflinePlayer player) {
            calls.incrementAndGet();
            return EconomyResult.success(100.0, "ok");
        }

        @Override
        public EconomyResult withdraw(OfflinePlayer player, double amount) {
            calls.incrementAndGet();
            return EconomyResult.success(100.0 - amount, "ok");
        }

        @Override
        public EconomyResult deposit(OfflinePlayer player, double amount) {
            calls.incrementAndGet();
            return EconomyResult.success(100.0 + amount, "ok");
        }
    }

    @Test
    @DisplayName("缺席時餘額查詢回 UNAVAILABLE 且不成功")
    void absentEconomy_isUnavailableNotSuccess() {
        Harness harness = newHarness();

        EconomyResult result = harness.service().getBalance(harness.player());
        assertEquals(ExternalResultState.UNAVAILABLE, result.state());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("注入提供者後餘額查詢成功")
    void injectedEconomy_isUsed() {
        Harness harness = newHarness();
        CountingEconomy economy = new CountingEconomy();
        harness.useEconomy(economy);

        EconomyResult result = harness.service().getBalance(harness.player());
        assertTrue(result.isSuccess());
        assertEquals(100.0, result.balance());
        assertEquals(1, economy.calls.get());
    }

    @Test
    @DisplayName("替換後舊提供者不再被引用")
    void replacedEconomy_oldNotReferenced() {
        Harness harness = newHarness();
        CountingEconomy old = new CountingEconomy();
        CountingEconomy current = new CountingEconomy();
        harness.useEconomy(old);
        harness.useEconomy(current);

        EconomyResult result = harness.service().getBalance(harness.player());
        assertTrue(result.isSuccess());
        assertEquals(0, old.calls.get(), "替換後舊提供者不得再被呼叫");
        assertEquals(1, current.calls.get());
    }

    @Test
    @DisplayName("移除後回到 UNAVAILABLE")
    void removedEconomy_isUnavailableAgain() {
        Harness harness = newHarness();
        harness.useEconomy(new CountingEconomy());
        harness.dropEconomy();

        EconomyResult result = harness.service().getBalance(harness.player());
        assertEquals(ExternalResultState.UNAVAILABLE, result.state());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("提供者拋例外轉為明確 FAILED（不逃逸）")
    void throwingEconomy_mapsToFailed() {
        Harness harness = newHarness();
        harness.useEconomy(new EconomyProvider() {
            @Override
            public EconomyResult getBalance(OfflinePlayer player) {
                throw new IllegalStateException("boom");
            }

            @Override
            public EconomyResult withdraw(OfflinePlayer player, double amount) {
                throw new IllegalStateException("boom");
            }

            @Override
            public EconomyResult deposit(OfflinePlayer player, double amount) {
                throw new IllegalStateException("boom");
            }
        });

        EconomyResult result = harness.service().getBalance(harness.player());
        assertEquals(ExternalResultState.FAILED, result.state());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("停用後注入被拒絕且查詢不可用")
    void shutdown_rejectsInjectionAndIsUnavailable() {
        Harness harness = newHarness();
        harness.useEconomy(new CountingEconomy());
        harness.service().shutdown();

        assertThrows(IllegalStateException.class,
            () -> harness.service().setEconomyProvider(new CountingEconomy()));
        EconomyResult result = harness.service().getBalance(harness.player());
        assertEquals(ExternalResultState.UNAVAILABLE, result.state());
    }
}
