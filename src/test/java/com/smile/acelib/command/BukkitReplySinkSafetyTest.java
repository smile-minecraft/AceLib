package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@link BukkitReplySink} 玩家回覆安全契約測試（Plan §十一 + Momus P1 阻擋）。
 *
 * <p>對應的契約：</p>
 * <ul>
 *   <li>所有玩家導向回覆（不論 {@code ctx.reply()} 同步或
 *       {@code ctx.replyPlayerAsync()} 跨執行緒）必須經由
 *       {@link com.smile.acelib.context.SafeExecutor#executeOnRegion} 派送，
 *       由 {@link BukkitReplySink.SafeExecutorBackend} 抽象負責；禁止
 *       {@link BukkitReplySink} 直接呼叫 {@link Player#sendMessage}。</li>
 *   <li>當 {@code BukkitReplySink} 的 owner 不是 {@code AceLibPlugin}（亦即
 *       沒有 canonical platform / capability 快取）時，backend 必須明確
 *       拒絕 inline 派送 — 拋出 {@link IllegalStateException} 帶
 *       {@code ACELIB-CMD-011} 錯誤代碼；不得 inline {@code runnable.run()}
 *       規避 region 安全檢查。</li>
 *   <li>Console sender 仍走 plugin logger；不得誤派到 backend。</li>
 *   <li>玩家離線時不送訊息也不拋例外。</li>
 * </ul>
 *
 * <p>既有 {@code BukkitReplySink.send()}:65-68 直接呼叫 {@code safeSendMessage}
 * 內部 {@code player.sendMessage} 已被改為走 backend。既有
 * {@code SafeExecutorBackend.detect()}:163-172 的 inline fallback 已被改為
 * 拋例外。</p>
 */
@DisplayName("BukkitReplySink safe player reply")
class BukkitReplySinkSafetyTest {

    private Logger pluginLogger;
    private JavaPlugin fakeOwner;
    private CapturingHandler logHandler;

    @BeforeEach
    void setUp() {
        fakeOwner = Mockito.mock(JavaPlugin.class);
        pluginLogger = Logger.getLogger("BukkitReplySinkSafetyTest.Owner");
        pluginLogger.setUseParentHandlers(false);
        pluginLogger.setLevel(Level.ALL);
        when(fakeOwner.getLogger()).thenReturn(pluginLogger);
        logHandler = new CapturingHandler();
        pluginLogger.addHandler(logHandler);
    }

    @AfterEach
    void tearDown() {
        if (logHandler != null) {
            pluginLogger.removeHandler(logHandler);
            logHandler.close();
        }
    }

    // ---------------------------------------------------------------------
    // 玩家 reply 走 backend
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("玩家 sender reply 走 backend")
    class PlayerRoutesThroughBackend {

        @Test
        @DisplayName("send() 對玩家 sender 必須透過 backend.runOnPlayerRegion 派送，不直接 player.sendMessage")
        void send_routesThroughBackend_doesNotDirectSendMessage() {
            BukkitReplySink.SafeExecutorBackend backend =
                mock(BukkitReplySink.SafeExecutorBackend.class);
            Player mockedPlayer = Mockito.mock(Player.class);
            when(mockedPlayer.isOnline()).thenReturn(true);
            when(mockedPlayer.getName()).thenReturn("BackendRouteMock");

            BukkitReplySink sink = new BukkitReplySink(fakeOwner, backend);
            BukkitSender bs = new BukkitSender(mockedPlayer);

            sink.send(bs, "hello from test");

            // 必須透過 backend 派送
            verify(backend, times(1)).runOnPlayerRegion(
                eq(fakeOwner), eq(mockedPlayer), any(Runnable.class));
            // 禁止直接 player.sendMessage（必須由 backend 內部 lambda 執行）
            verify(mockedPlayer, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("backend.runOnPlayerRegion 內的 runnable 必須實際呼叫 player.sendMessage")
        void send_backendRunnableInvokesSendMessage() {
            BukkitReplySink.SafeExecutorBackend backend =
                mock(BukkitReplySink.SafeExecutorBackend.class);
            // 讓 backend 內部實際執行 runnable
            Mockito.doAnswer(inv -> {
                Runnable r = inv.getArgument(2);
                r.run();
                return null;
            }).when(backend).runOnPlayerRegion(any(JavaPlugin.class), any(Player.class),
                any(Runnable.class));

            Player mockedPlayer = Mockito.mock(Player.class);
            when(mockedPlayer.isOnline()).thenReturn(true);
            when(mockedPlayer.getName()).thenReturn("BackendRunnableMock");

            BukkitReplySink sink = new BukkitReplySink(fakeOwner, backend);
            BukkitSender bs = new BukkitSender(mockedPlayer);

            sink.send(bs, "via backend runnable");

            verify(mockedPlayer, times(1)).sendMessage("via backend runnable");
        }

        @Test
        @DisplayName("sendPlayerAsync 對玩家 sender 仍走 backend（既有契約不變）")
        void sendPlayerAsync_routesThroughBackend() {
            BukkitReplySink.SafeExecutorBackend backend =
                mock(BukkitReplySink.SafeExecutorBackend.class);
            Player mockedPlayer = Mockito.mock(Player.class);
            when(mockedPlayer.isOnline()).thenReturn(true);
            when(mockedPlayer.getName()).thenReturn("AsyncRouteMock");

            BukkitReplySink sink = new BukkitReplySink(fakeOwner, backend);
            BukkitSender bs = new BukkitSender(mockedPlayer);
            BukkitSender.BukkitPlayerHandle handle =
                (BukkitSender.BukkitPlayerHandle) bs.asPlayer();
            assertNotNull(handle);

            sink.sendPlayerAsync(handle, "async hello");

            verify(backend, times(1)).runOnPlayerRegion(
                eq(fakeOwner), eq(mockedPlayer), any(Runnable.class));
            verify(mockedPlayer, never()).sendMessage(anyString());
        }
    }

    // ---------------------------------------------------------------------
    // 非 AceLib owner 必須拒絕 inline
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("非 AceLib owner 不 inline 派送")
    class NonAceLibOwnerRefusesInline {

        @Test
        @DisplayName("SafeExecutorBackend.detect(非 AceLib owner) 必須拋 IllegalStateException 帶 ACELIB-CMD-011")
        void detect_nonAceLibOwner_backendRefusesInline() {
            // 觸發 detect fallback 路徑：fakeOwner 不是 AceLibPlugin
            BukkitReplySink.SafeExecutorBackend backend =
                BukkitReplySink.SafeExecutorBackend.detect(fakeOwner);
            Player mockedPlayer = Mockito.mock(Player.class);
            when(mockedPlayer.isOnline()).thenReturn(true);

            // 應拋 IllegalStateException 帶 ACELIB-CMD-011 代碼
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> backend.runOnPlayerRegion(fakeOwner, mockedPlayer, () -> {
                    throw new AssertionError(
                        "non-AceLib owner must NOT inline runnable");
                }));
            assertTrue(ex.getMessage() != null && ex.getMessage().contains("ACELIB-CMD-011"),
                "exception message 應包含 ACELIB-CMD-011 code，實際: " + ex.getMessage());
        }

        @Test
        @DisplayName("非 AceLib owner 的 sink.send 必須 swallow error，不直接 player.sendMessage")
        void nonAceLibOwner_sinkSwallowsErrorAndDoesNotDirectSend() {
            // 用真實 detect 結果（fakeOwner → 拋例外的 backend）
            BukkitReplySink sink = new BukkitReplySink(fakeOwner);
            Player mockedPlayer = Mockito.mock(Player.class);
            when(mockedPlayer.isOnline()).thenReturn(true);
            when(mockedPlayer.getName()).thenReturn("NonAceLibMock");
            BukkitSender bs = new BukkitSender(mockedPlayer);

            // 不應拋例外
            sink.send(bs, "should be swallowed");

            // 不得直接 sendMessage
            verify(mockedPlayer, never()).sendMessage(anyString());

            // 必須輸出 warning 攜帶 ACELIB-CMD-011
            String captured = logHandler.captured();
            assertTrue(captured.contains("ACELIB-CMD-011"),
                "logger 應輸出 ACELIB-CMD-011 code，實際: " + captured);
        }

        @Test
        @DisplayName("非 AceLib owner 的 sink.sendPlayerAsync 必須 swallow error，不再 fall back")
        void nonAceLibOwner_sendPlayerAsync_swallowsError() {
            BukkitReplySink sink = new BukkitReplySink(fakeOwner);
            Player mockedPlayer = Mockito.mock(Player.class);
            when(mockedPlayer.isOnline()).thenReturn(true);
            when(mockedPlayer.getName()).thenReturn("NonAceLibAsyncMock");
            BukkitSender bs = new BukkitSender(mockedPlayer);
            BukkitSender.BukkitPlayerHandle handle =
                (BukkitSender.BukkitPlayerHandle) bs.asPlayer();
            assertNotNull(handle);

            // 不應拋例外
            sink.sendPlayerAsync(handle, "should be swallowed");

            // 不得直接 sendMessage
            verify(mockedPlayer, never()).sendMessage(anyString());

            // 必須輸出 warning 攜帶 ACELIB-CMD-011
            String captured = logHandler.captured();
            assertTrue(captured.contains("ACELIB-CMD-011"),
                "logger 應輸出 ACELIB-CMD-011 code，實際: " + captured);
        }
    }

    // ---------------------------------------------------------------------
    // Console 路徑不回歸
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Console sender 走 plugin logger（不進 backend）")
    class ConsolePathUnchanged {

        @Test
        @DisplayName("console sender 仍走 plugin logger，不路由到 backend")
        void consoleSender_doesNotRouteToBackend() {
            BukkitReplySink.SafeExecutorBackend backend =
                mock(BukkitReplySink.SafeExecutorBackend.class);
            BukkitReplySink sink = new BukkitReplySink(fakeOwner, backend);

            // mock 一個 non-player Sender（console 等價）
            Sender console = mock(Sender.class);
            when(console.isPlayer()).thenReturn(false);
            when(console.getName()).thenReturn("TestConsole");

            sink.send(console, "console message");

            // backend 不應被呼叫
            verify(backend, never()).runOnPlayerRegion(
                any(JavaPlugin.class), any(Player.class), any(Runnable.class));
            // plugin logger 必須被使用
            verify(fakeOwner, times(1)).getLogger();
            // logger 必須收到包含 "console message" 與 "TestConsole" 的紀錄
            String captured = logHandler.captured();
            assertTrue(captured.contains("console message"),
                "logger 應輸出 console message，實際: " + captured);
            assertTrue(captured.contains("TestConsole"),
                "logger 應輸出 sender name，實際: " + captured);
        }

        @Test
        @DisplayName("console sender 的 sendError 仍走 plugin logger")
        void consoleSender_sendError_doesNotRouteToBackend() {
            BukkitReplySink.SafeExecutorBackend backend =
                mock(BukkitReplySink.SafeExecutorBackend.class);
            BukkitReplySink sink = new BukkitReplySink(fakeOwner, backend);

            Sender console = mock(Sender.class);
            when(console.isPlayer()).thenReturn(false);
            when(console.getName()).thenReturn("TestConsole");

            sink.sendError(console,
                CommandException.custom("ACELIB-TEST-001", "custom error"));

            verify(backend, never()).runOnPlayerRegion(
                any(JavaPlugin.class), any(Player.class), any(Runnable.class));
            String captured = logHandler.captured();
            assertTrue(captured.contains("ACELIB-TEST-001"),
                "logger 應輸出 ACELIB-TEST-001 code，實際: " + captured);
            assertTrue(captured.contains("custom error"),
                "logger 應輸出 error message，實際: " + captured);
        }
    }

    // ---------------------------------------------------------------------
    // 玩家離線 / 訊息發送失敗
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("邊界條件")
    class Boundary {

        @Test
        @DisplayName("sendPlayerAsync 對離線玩家為 no-op")
        void sendPlayerAsync_offlinePlayer_isNoop() {
            BukkitReplySink.SafeExecutorBackend backend =
                mock(BukkitReplySink.SafeExecutorBackend.class);
            BukkitReplySink sink = new BukkitReplySink(fakeOwner, backend);
            Player mockedPlayer = Mockito.mock(Player.class);
            when(mockedPlayer.isOnline()).thenReturn(false);
            BukkitSender bs = new BukkitSender(mockedPlayer);
            BukkitSender.BukkitPlayerHandle handle =
                (BukkitSender.BukkitPlayerHandle) bs.asPlayer();

            sink.sendPlayerAsync(handle, "should not send");

            verify(backend, never()).runOnPlayerRegion(
                any(JavaPlugin.class), any(Player.class), any(Runnable.class));
            verify(mockedPlayer, never()).sendMessage(anyString());
        }

        @Test
        @DisplayName("send() 對非 BukkitSender 介面（純 Sender mock）走 console logger 路徑，不崩潰")
        void send_nonBukkitSenderInterface_goesToConsolePath() {
            BukkitReplySink sink = new BukkitReplySink(fakeOwner);
            // 純 Sender interface mock（不是 BukkitSender）— `instanceof BukkitSender`
            // 為 false，sink 走 console logger 路徑（即便 sender.isPlayer()=true，
            // 非 BukkitSender 仍視為不可信的 console-style sender）。
            Sender weirdSender = mock(Sender.class);
            when(weirdSender.isPlayer()).thenReturn(true);
            when(weirdSender.getName()).thenReturn("NonBukkitSender");

            // 不應拋例外
            sink.send(weirdSender, "weird case");

            // 應走 plugin logger (console 路徑)
            String captured = logHandler.captured();
            assertTrue(captured.contains("weird case"),
                "logger 應輸出 message，實際: " + captured);
            assertTrue(captured.contains("NonBukkitSender"),
                "logger 應輸出 sender name，實際: " + captured);
            // backend 不應被呼叫（純 Sender interface 不被辨識為 Bukkit 玩家）
            // （此處 fakeOwner 未配置 backend，無法直接 verify；但未呼叫 =
            // 沒有例外、沒有 player.sendMessage 呼叫，因為根本沒進入 backend 分支）
        }
    }

    // ---------------------------------------------------------------------
    // 錯誤代碼常數契約
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("錯誤代碼契約")
    class ErrorCodeContract {

        @Test
        @DisplayName("ACELIB-CMD-011 對應 REPLY_BACKEND_UNAVAILABLE")
        void replyBackendUnavailableCode() {
            assertEquals("ACELIB-CMD-011",
                CommandErrorKind.REPLY_BACKEND_UNAVAILABLE.defaultCode());
        }

        @Test
        @DisplayName("所有現有 ACELIB-CMD-NNN 仍然可用，無重複代碼")
        void allKindCodes_areDistinct() {
            java.util.Set<String> codes = new java.util.HashSet<>();
            for (CommandErrorKind kind : CommandErrorKind.values()) {
                assertTrue(codes.add(kind.defaultCode()),
                    "duplicate code: " + kind.defaultCode() + " for " + kind);
            }
        }
    }

    // ---------------------------------------------------------------------
    // 測試輔助
    // ---------------------------------------------------------------------

    /**
     * 簡單日誌 capture handler（與既有測試同樣手法）。
     */
    static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override public void flush() { }

        @Override public void close() {
            // owner 負責從 logger 移除
        }

        String captured() {
            StringBuilder sb = new StringBuilder();
            for (LogRecord record : records) {
                String msg = record.getMessage();
                Object[] params = record.getParameters();
                if (params != null && params.length > 0) {
                    try {
                        msg = java.text.MessageFormat.format(msg, params);
                    } catch (Throwable ignored) {
                        // keep pattern as-is
                    }
                }
                if (sb.length() > 0) sb.append('\n');
                sb.append(msg);
            }
            return sb.toString();
        }
    }
}
