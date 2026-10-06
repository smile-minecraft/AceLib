package com.smile.acelib.testing;

import com.smile.acelib.gui.FakeGuiService;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.testing.contracts.GuiServiceContract;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;

/**
 * GUI 契約純公開側：只實作公開 {@link GuiService} 的服務跑同一套契約。
 *
 * <p>下游 plugin 若只實作公開 {@code GuiService}（不碰 {@code internal}
 * 的 {@code GuiServiceControl}），跑契約不得炸 {@link ClassCastException}；
 * 「內部停用後拒開」案例僅對具備內部生命週期的實作執行，其餘全跑。</p>
 */
@DisplayName("GUI 契約 — 純公開實作")
class GuiServicePublicOnlyContractTest extends GuiServiceContract {

    private FakeGuiService current;

    @Override
    protected GuiService createService() {
        current = new FakeGuiService();
        FakeGuiService delegate = current;
        // 代理只掛公開 GuiService 介面：下游純公開實作的等價替身。
        // 拆開反射包裝再拋，替身行為與真實作一致（否則例外型別失真）。
        return (GuiService) Proxy.newProxyInstance(
            GuiService.class.getClassLoader(),
            new Class<?>[] {GuiService.class},
            (proxy, method, args) -> {
                try {
                    return method.invoke(delegate, args);
                } catch (java.lang.reflect.InvocationTargetException ex) {
                    throw ex.getCause();
                }
            });
    }

    @Override
    protected UUID newPlayer() {
        return UUID.randomUUID();
    }

    @Override
    protected void disconnect(UUID playerUuid) {
        current.markOffline(playerUuid);
    }
}
