package com.smile.acelib.testing;

import com.smile.acelib.gui.FakeGuiService;
import com.smile.acelib.gui.GuiService;
import com.smile.acelib.testing.contracts.GuiServiceContract;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;

/**
 * GUI 契約假側：{@code FakeGuiService} 跑同一套契約。
 */
@DisplayName("GUI 契約 — 假實作")
class GuiServiceFakeContractTest extends GuiServiceContract {

    private final List<FakeGuiService> created = new ArrayList<>();

    @Override
    protected GuiService createService() {
        FakeGuiService service = new FakeGuiService();
        created.add(service);
        return service;
    }

    @Override
    protected UUID newPlayer() {
        return UUID.randomUUID();
    }

    @Override
    protected void disconnect(UUID playerUuid) {
        created.get(created.size() - 1).markOffline(playerUuid);
    }
}
