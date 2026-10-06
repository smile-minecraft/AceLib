package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.testing.FakeClock;
import com.smile.acelib.testing.FakeSafeScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 共用流程（Red）：同一份流程對應 Java GUI 與基岩表單兩種呈現；
 * 過時／重複回應拒絕；載入失敗以錯誤畫面呈現並可返回。
 */
@DisplayName("GUI／表單共用流程")
class GuiFlowTest {

    private ServerMock server;
    private JavaPlugin plugin;
    private GuiService guiService;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("FlowPlugin");
        when(plugin.isEnabled()).thenReturn(true);
        // direct executor：非同步 renderer 同步執行（Paper-like 語意）
        guiService = new GuiServiceImpl(PlayerContextExecutor.direct());
    }

    @AfterEach
    void tearDown() {
        GuiScopes.close(plugin);
        MockBukkit.unmock();
    }

    private static GuiView view(String title) {
        return GuiView.chest(title, 27).allow(10).build();
    }

    private static FormSpec menuForm(String title, String... buttons) {
        FormSpec.Simple.Builder builder = FormSpec.simple(title).content("請選擇");
        for (String button : buttons) {
            builder.button(button);
        }
        return builder.build();
    }

    private static GuiFlow twoStepFlow() {
        return GuiFlow.of(List.of(
            new GuiFlowStep("menu", view("選單"),
                menuForm("選單", "商店", "設定"), java.util.Map.of(0, "shop", 1, "settings")),
            new GuiFlowStep("shop", view("商店"),
                menuForm("商店", "買", "賣")),
            new GuiFlowStep("settings", view("設定"),
                menuForm("設定", "開", "關"))), "menu");
    }

    @Test
    @DisplayName("Java 呈現跟隨流程步驟；back 回到上一步")
    void javaFlow_followsSteps() {
        GuiScope scope = GuiScopes.create(plugin, guiService);
        UUID player = server.addPlayer().getUniqueId();

        assertEquals(GuiState.SUCCESS, scope.openFlow(player, twoStepFlow()).state());
        assertEquals("選單", scope.viewOf(player).orElseThrow().title());

        assertEquals(GuiState.SUCCESS, scope.goTo(player, "shop").state());
        assertEquals("商店", scope.viewOf(player).orElseThrow().title());

        assertEquals(GuiState.SUCCESS, scope.back(player).state());
        assertEquals("選單", scope.viewOf(player).orElseThrow().title());
    }

    @Test
    @DisplayName("未知步驟被拒（程式設計錯誤）")
    void unknownStep_throws() {
        GuiScope scope = GuiScopes.create(plugin, guiService);
        UUID player = server.addPlayer().getUniqueId();
        assertEquals(GuiState.SUCCESS, scope.openFlow(player, twoStepFlow()).state());

        assertThrows(IllegalArgumentException.class,
            () -> scope.goTo(player, "no-such-step"));
    }

    @Test
    @DisplayName("基岩玩家走表單呈現；VALID 回應推進到下一步")
    void bedrockFlow_sendsForms() {
        RecordingFormSender sender = new RecordingFormSender();
        FormService forms = bedrockForms(sender);
        GuiScope scope = GuiScopes.create(plugin, () -> guiService,
            com.smile.acelib.diagnostics.Clock.system(), forms, uuid -> true);
        UUID player = server.addPlayer().getUniqueId();

        assertEquals(GuiState.SUCCESS, scope.openFlow(player, twoStepFlow()).state());
        assertEquals(1, sender.sent.size());
        assertEquals("選單", sender.sent.get(0).title());

        // 玩家按下第一顆按鈕（VALID）→ 轉移到 shop 並發出第二張表單
        sender.respond(0, com.smile.acelib.form.FormResponseStatus.VALID, 0);
        assertEquals(2, sender.sent.size());
        assertEquals("商店", sender.sent.get(1).title());
    }

    @Test
    @DisplayName("第二顆按鈕依轉移表走到 settings；最後一步 VALID 結束流程")
    void bedrockFlow_branchAndFinish() {
        RecordingFormSender sender = new RecordingFormSender();
        FormService forms = bedrockForms(sender);
        java.util.concurrent.atomic.AtomicBoolean finished =
            new java.util.concurrent.atomic.AtomicBoolean();
        GuiFlow flow = GuiFlow.of(List.of(
            new GuiFlowStep("menu", view("選單"),
                menuForm("選單", "商店", "設定"), java.util.Map.of(0, "shop", 1, "settings")),
            new GuiFlowStep("shop", view("商店"), menuForm("商店", "買", "賣")),
            new GuiFlowStep("settings", view("設定"), menuForm("設定", "開", "關"))),
            "menu", uuid -> finished.set(true));
        GuiScope scope = GuiScopes.create(plugin, () -> guiService,
            com.smile.acelib.diagnostics.Clock.system(), forms, uuid -> true);
        UUID player = server.addPlayer().getUniqueId();

        assertEquals(GuiState.SUCCESS, scope.openFlow(player, flow).state());
        sender.respond(0, com.smile.acelib.form.FormResponseStatus.VALID, 1);
        assertEquals("設定", sender.sent.get(1).title());

        // 最後一步（settings 是第 3 個？不 — 線性 finish 只在「無下一步」時；
        // settings 之後無步驟？步驟順序 menu, shop, settings：settings 為末端）
        // settings 是線性末端：VALID 結束流程並觸發 onComplete
        sender.respond(1, com.smile.acelib.form.FormResponseStatus.VALID, 0);
        assertTrue(finished.get());
        assertTrue(scope.viewOf(player).isEmpty());
    }

    @Test
    @DisplayName("表單關閉（CLOSED）結束流程，不推進也不殘留")
    void formClosed_endsFlow() {
        RecordingFormSender sender = new RecordingFormSender();
        FormService forms = bedrockForms(sender);
        GuiScope scope = GuiScopes.create(plugin, () -> guiService,
            com.smile.acelib.diagnostics.Clock.system(), forms, uuid -> true);
        UUID player = server.addPlayer().getUniqueId();

        assertEquals(GuiState.SUCCESS, scope.openFlow(player, twoStepFlow()).state());
        sender.respond(0, com.smile.acelib.form.FormResponseStatus.CLOSED, null);

        assertEquals(1, sender.sent.size());
        assertTrue(scope.viewOf(player).isEmpty());
    }

    @Test
    @DisplayName("過時表單回應被忽略（推進後才回來的舊回應不重送）")
    void staleFormResponse_isIgnored() {
        RecordingFormSender sender = new RecordingFormSender();
        FormService forms = bedrockForms(sender);
        GuiScope scope = GuiScopes.create(plugin, () -> guiService,
            com.smile.acelib.diagnostics.Clock.system(), forms, uuid -> true);
        UUID player = server.addPlayer().getUniqueId();

        assertEquals(GuiState.SUCCESS, scope.openFlow(player, twoStepFlow()).state());
        sender.respond(0, com.smile.acelib.form.FormResponseStatus.VALID, 0);
        assertEquals(2, sender.sent.size());

        // 舊表單（第一張）的重複回呼 — FormService 已 at-most-once；
        // 即使送達，流程世代已推進，不得再觸發發送
        sender.respond(0, com.smile.acelib.form.FormResponseStatus.VALID, 0);
        assertEquals(2, sender.sent.size());
    }

    @Test
    @DisplayName("非同步載入失敗以錯誤頁呈現，可返回上一頁")
    void asyncFailure_showsErrorPageAndBack() {
        GuiScope scope = GuiScopes.create(plugin, guiService);
        UUID player = server.addPlayer().getUniqueId();

        assertEquals(GuiState.SUCCESS, scope.openFlow(player, twoStepFlow()).state());
        assertEquals(GuiState.SUCCESS, scope.goTo(player, "shop").state());
        long generation = scope.viewOf(player).isPresent()
            ? guiService.getActiveSession(player).session().generation() : -1L;

        // 非同步載入：先開 LOADING，再以 ERROR 頁完成
        GuiResult begun = scope.beginAsyncUpdate(player, generation, 0);
        assertEquals(GuiState.SUCCESS, begun.state());
        boolean[] rendered = {false};
        GuiResult applied = scope.applyAsyncUpdate(begun.asyncRequest(),
            GuiPage.error(GuiErrorCode.OPERATION_FAILED, "載入失敗"), () -> rendered[0] = true);
        assertTrue(applied.isSuccess() || applied.isAccepted());
        assertTrue(rendered[0]);

        // 錯誤後仍可 back 回選單
        assertEquals(GuiState.SUCCESS, scope.back(player).state());
        assertEquals("選單", scope.viewOf(player).orElseThrow().title());
    }

    /**
     * 基岩測試用表單服務：回應經假排程器同步派送到玩家 region（Paper-like 語意）。
     * 單參數 forProduction 是 fire-and-forget（consumer 永不執行），
     * 此處必須用雙參數接線回應派送，否則 VALID／CLOSED 永遠送不到流程。
     */
    private FormService bedrockForms(RecordingFormSender sender) {
        return FormService.forProduction(sender,
            () -> new FakeSafeScheduler(plugin, new FakeClock()));
    }

    /**
     * 測試用發送 seam：記錄發送並保留回應接收端，測試顯式觸發回應。
     */
    static final class RecordingFormSender implements FormService.FormSender {
        final List<FormSpec> sent = new ArrayList<>();
        final List<Consumer<com.smile.acelib.form.FormResponse>> receivers =
            new ArrayList<>();

        @Override
        public com.smile.acelib.form.FormSendResult sendForm(UUID playerId, FormSpec form) {
            sent.add(form);
            return com.smile.acelib.form.FormSendResult.SENT;
        }

        @Override
        public com.smile.acelib.form.FormSendResult sendForm(UUID playerId, FormSpec form,
                UUID token,
                Consumer<com.smile.acelib.form.FormResponse> onResponse) {
            sent.add(form);
            receivers.add(onResponse);
            return com.smile.acelib.form.FormSendResult.SENT;
        }

        void respond(int index, com.smile.acelib.form.FormResponseStatus status,
                Integer button) {
            receivers.get(index).accept(new com.smile.acelib.form.FormResponse(
                status, button, List.of()));
        }
    }
}
