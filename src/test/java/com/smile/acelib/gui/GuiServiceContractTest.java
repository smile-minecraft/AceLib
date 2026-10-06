package com.smile.acelib.gui;

import com.smile.acelib.testing.contracts.GuiServiceContract;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * GUI 契約真實側：MockBukkit 路徑的 {@code GuiServiceImpl} 跑同一套契約。
 */
@DisplayName("GUI 契約 — 真實作")
class GuiServiceContractTest extends GuiServiceContract {

    private ServerMock server;
    private GuiServiceImpl current;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (current != null) {
            current.shutdown();
        }
        MockBukkit.unmock();
    }

    @Override
    protected GuiService createService() {
        current = new GuiServiceImpl();
        return current;
    }

    @Override
    protected UUID newPlayer() {
        return server.addPlayer().getUniqueId();
    }

    @Override
    protected void disconnect(UUID playerUuid) {
        PlayerMock mock = (PlayerMock) server.getPlayer(playerUuid);
        mock.disconnect();
    }
}
