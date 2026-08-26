package com.smile.acelib.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.form.FormResponseDispatcher.DeferredFormResponseDispatcher;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 表單回應派送管線測試：thread-agnostic 重新派送、at-most-once、五種失效情境
 * （離線／shutdown／token 過期／reload／disable）零執行且不留 pending state，
 * 以及 callback 執行前五項重檢。
 *
 * <p>以 {@link CapturingFormSender} 模擬 external seam（捕獲 token + sink），
 * 以 {@link FormResponseDispatcher#deferred()} 模擬「已 enqueue、尚未在玩家
 * region 執行」的 production 視窗。</p>
 */
@DisplayName("表單回應派送管線")
class FormResponseDispatchTest {

    private static final UUID PLAYER_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000007");

    private static FormSpec.Simple sampleSpec() {
        return FormSpec.simple("派送").content("內容").button("按鈕").build();
    }

    /**
     * 捕獲型 fake sender：覆寫四參數 default seam（模擬 FloodgateFormSender 的
     * 覆寫），暴露 token 與接收端供測試從「Floodgate 回呼執行緒」觸發。
     */
    static final class CapturingFormSender implements FormService.FormSender {

        private final FormSendResult result;
        final AtomicReference<UUID> tokenRef = new AtomicReference<>();
        final AtomicReference<Consumer<FormResponse>> responseRef = new AtomicReference<>();

        CapturingFormSender(FormSendResult result) {
            this.result = result;
        }

        @Override
        public FormSendResult sendForm(UUID playerId, FormSpec form) {
            return result;
        }

        @Override
        public FormSendResult sendForm(UUID playerId, FormSpec form,
                UUID token, Consumer<FormResponse> onResponse) {
            tokenRef.set(token);
            responseRef.set(onResponse);
            return result;
        }

        /** 模擬 Floodgate 回呼（任意執行緒呼叫）。 */
        void fire(FormResponse response) {
            Consumer<FormResponse> onResponse = responseRef.get();
            assertNotNull(onResponse, "回應接收端必須已在 sendForm 時被捕獲");
            onResponse.accept(response);
        }

        /** 模擬 Floodgate 回呼（拆開欄位版）。 */
        void fire(FormResponseStatus status, Integer clickedButton, List<FormValue> values) {
            fire(new FormResponse(status, clickedButton, values));
        }
    }

    /**
     * 送出中途終結生命週期的 fake sender：四參數 seam 內同步呼叫
     * {@code service.shutdown()}（模擬實體送出進行中，另一執行緒完成 shutdown），
     * 並比照 {@link CapturingFormSender} 捕獲 token 與接收端，供測試於
     * shutdown 完成後觸發遲到回呼。
     */
    static final class ShutdownInsideSendFormSender implements FormService.FormSender {

        private final AtomicReference<FormService> serviceRef;
        final AtomicReference<UUID> tokenRef = new AtomicReference<>();
        final AtomicReference<Consumer<FormResponse>> responseRef = new AtomicReference<>();

        ShutdownInsideSendFormSender(AtomicReference<FormService> serviceRef) {
            this.serviceRef = serviceRef;
        }

        @Override
        public FormSendResult sendForm(UUID playerId, FormSpec form) {
            return FormSendResult.SENT;
        }

        @Override
        public FormSendResult sendForm(UUID playerId, FormSpec form,
                UUID token, Consumer<FormResponse> onResponse) {
            tokenRef.set(token);
            responseRef.set(onResponse);
            serviceRef.get().shutdown();
            return FormSendResult.SENT;
        }

        /** 模擬 Floodgate 於 shutdown 完成後才回呼（遲到回呼）。 */
        void fire(FormResponseStatus status, Integer clickedButton, List<FormValue> values) {
            Consumer<FormResponse> onResponse = responseRef.get();
            assertNotNull(onResponse, "回應接收端必須已在 sendForm 時被捕獲");
            onResponse.accept(new FormResponse(status, clickedButton, values));
        }
    }

    // -----------------------------------------------------------------
    // thread-agnostic：回呼不在原始執行緒執行，只在派送 runnable 內執行
    // -----------------------------------------------------------------

    @Test
    @DisplayName("thread-agnostic：另一執行緒觸發 sink，callback 不在該執行緒執行，只在派送 runnable 內執行一次")
    void callback_redispatchedOffCallbackThread() throws Exception {
        DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl service = new FormServiceImpl(sender, dispatcher);

        AtomicReference<FormResponse> received = new AtomicReference<>();
        AtomicReference<String> callbackThread = new AtomicReference<>();
        service.sendForm(PLAYER_ID, sampleSpec(), response -> {
            received.set(response);
            callbackThread.set(Thread.currentThread().getName());
        });

        String sinkThreadName = "simulated-floodgate-callback";
        CountDownLatch fired = new CountDownLatch(1);
        Thread callbackThreadHandle = new Thread(() -> {
            sender.fire(FormResponseStatus.VALID, 0, List.of());
            fired.countDown();
        }, sinkThreadName);
        callbackThreadHandle.start();
        assertTrue(fired.await(5, TimeUnit.SECONDS), "sink 觸發必須完成");

        // sink 已在另一執行緒觸發，但 callback 尚未執行（未派送前不得直接呼叫）
        assertNull(received.get(),
            "callback 不得在 sink 呼叫執行緒上直接執行（必須先重新派送）");
        assertEquals(1, dispatcher.pendingCount(), "回應必須被 enqueue 到玩家 region 派送");

        dispatcher.runPending();

        assertNotNull(received.get(), "派送 runnable 執行後 callback 必須被呼叫");
        assertNotEquals(sinkThreadName, callbackThread.get(),
            "callback 執行緒不得是 sink 觸發執行緒");
        assertEquals(Thread.currentThread().getName(), callbackThread.get(),
            "callback 必須在派送 runnable 所在（模擬玩家 region）執行緒內執行");
        assertEquals(FormResponseStatus.VALID, received.get().status());
        assertEquals(0, received.get().clickedButton().orElse(-1));
    }

    // -----------------------------------------------------------------
    // at-most-once：同一 token 二次 sink 呼叫，第二次丟棄
    // -----------------------------------------------------------------

    @Test
    @DisplayName("at-most-once：同一 token 二次回呼，第二次丟棄，callback 恰好一次")
    void duplicateCallback_deliveredAtMostOnce() {
        DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl service = new FormServiceImpl(sender, dispatcher);

        AtomicInteger deliveries = new AtomicInteger();
        service.sendForm(PLAYER_ID, sampleSpec(), response -> deliveries.incrementAndGet());

        sender.fire(FormResponseStatus.VALID, 0, List.of());
        sender.fire(FormResponseStatus.CLOSED, null, List.of());
        dispatcher.runPending();

        assertEquals(1, deliveries.get(), "有效結果最多執行一次");
        assertEquals(0, service.pendingCountForTesting(), "交付後 pending 必須清空");
    }

    // -----------------------------------------------------------------
    // 失效情境一：玩家離線（dispatch 拒絕）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("離線：dispatch 拒絕時 callback 零執行，pending 清空")
    void offlinePlayer_zeroExecutions_pendingCleared() {
        FormResponseDispatcher noopDispatcher = FormResponseDispatcher.noop();
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl service = new FormServiceImpl(sender, noopDispatcher);

        AtomicInteger deliveries = new AtomicInteger();
        service.sendForm(PLAYER_ID, sampleSpec(), response -> deliveries.incrementAndGet());

        sender.fire(FormResponseStatus.VALID, 0, List.of());

        assertEquals(0, deliveries.get(), "離線玩家不得收到 callback");
        assertEquals(0, service.pendingCountForTesting(), "拒絕派送後 pending 必須清空");
    }

    // -----------------------------------------------------------------
    // 失效情境二：服務 shutdown 後遲到回呼
    // -----------------------------------------------------------------

    @Test
    @DisplayName("shutdown：遲到回呼零執行，pending 清空")
    void shutdown_lateCallback_zeroExecutions() {
        DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl service = new FormServiceImpl(sender, dispatcher);

        AtomicInteger deliveries = new AtomicInteger();
        service.sendForm(PLAYER_ID, sampleSpec(), response -> deliveries.incrementAndGet());

        service.shutdown();
        sender.fire(FormResponseStatus.VALID, 0, List.of());
        dispatcher.runPending();

        assertEquals(0, deliveries.get(), "shutdown 後遲到回呼不得執行 callback");
        assertEquals(0, service.pendingCountForTesting(), "shutdown 後 pending 必須清空");
    }

    // -----------------------------------------------------------------
    // 失效情境三：token 過期（派送中 shutdown，runnable 內重檢擋下）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("token 過期：sink 已 CAS、派送已 enqueue 後 shutdown，runnable 內重檢零執行")
    void tokenExpired_inFlightDispatch_shutdownRecheckBlocks() {
        DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl service = new FormServiceImpl(sender, dispatcher);

        AtomicInteger deliveries = new AtomicInteger();
        service.sendForm(PLAYER_ID, sampleSpec(), response -> deliveries.incrementAndGet());

        // 回呼先到（CAS 完成、已 enqueue），但 runnable 尚未執行時服務 shutdown
        sender.fire(FormResponseStatus.VALID, 0, List.of());
        assertEquals(1, dispatcher.pendingCount(), "派送已 enqueue");
        service.shutdown();
        dispatcher.runPending();

        assertEquals(0, deliveries.get(), "token 已隨 shutdown 過期，runnable 內重檢必須擋下");
        assertEquals(0, service.pendingCountForTesting(), "不得殘留 pending state");
    }

    // -----------------------------------------------------------------
    // 失效情境四：reload（舊實例 shutdown 後回呼；新實例獨立運作）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("reload：舊實例 shutdown 後遲到回呼零執行；新實例 pending 天然隔離且可正常交付")
    void reload_oldInstanceLateCallbackDropped_newInstanceIndependent() {
        DeferredFormResponseDispatcher oldDispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender oldSender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl oldService = new FormServiceImpl(oldSender, oldDispatcher);

        AtomicInteger oldDeliveries = new AtomicInteger();
        oldService.sendForm(PLAYER_ID, sampleSpec(), response -> oldDeliveries.incrementAndGet());

        // reload：bindBedrockService 建全新實例；舊實例 shutdown
        DeferredFormResponseDispatcher newDispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender newSender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl newService = new FormServiceImpl(newSender, newDispatcher);
        oldService.shutdown();

        // 遲到回呼打到舊實例：零執行
        oldSender.fire(FormResponseStatus.VALID, 0, List.of());
        oldDispatcher.runPending();
        assertEquals(0, oldDeliveries.get(), "reload 後舊實例的遲到回呼不得執行");
        assertEquals(0, oldService.pendingCountForTesting(), "舊實例不得殘留 pending");

        // 新實例獨立註冊與交付
        AtomicInteger newDeliveries = new AtomicInteger();
        AtomicReference<FormResponse> received = new AtomicReference<>();
        newService.sendForm(PLAYER_ID, sampleSpec(), response -> {
            newDeliveries.incrementAndGet();
            received.set(response);
        });
        newSender.fire(FormResponseStatus.VALID, 1, List.of());
        newDispatcher.runPending();

        assertEquals(1, newDeliveries.get(), "reload 後新實例必須正常交付");
        assertEquals(1, received.get().clickedButton().orElse(-1));
    }

    // -----------------------------------------------------------------
    // 失效情境五：disable（unbind 路徑＝shutdown，冪等且無殘留）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("disable：unbind 即 shutdown，冪等重複呼叫後遲到回呼仍零執行")
    void disable_unbindShutdown_idempotentAndNoResidue() {
        DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl service = new FormServiceImpl(sender, dispatcher);

        AtomicInteger deliveries = new AtomicInteger();
        service.sendForm(PLAYER_ID, sampleSpec(), response -> deliveries.incrementAndGet());

        // unbindBedrockService → shutdown（冪等）
        service.shutdown();
        service.shutdown();
        sender.fire(FormResponseStatus.VALID, 0, List.of());
        dispatcher.runPending();

        assertEquals(0, deliveries.get(), "disable 後 callback 不得執行");
        assertEquals("FAILED", service.getModuleStatus());
        assertEquals(0, service.pendingCountForTesting(), "disable 後不得殘留 pending state");
    }

    // -----------------------------------------------------------------
    // 五項重檢：玩家 context 內離線重檢
    // -----------------------------------------------------------------

    @Test
    @DisplayName("五項重檢：派送後、runnable 執行前玩家離線，runnable 內重檢擋下並清 pending")
    void recheck_playerOfflineBetweenEnqueueAndRun_blocksDelivery() {
        DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl service = new FormServiceImpl(sender, dispatcher);

        AtomicInteger deliveries = new AtomicInteger();
        service.sendForm(PLAYER_ID, sampleSpec(), response -> deliveries.incrementAndGet());

        sender.fire(FormResponseStatus.VALID, 0, List.of());
        dispatcher.setOnline(false);
        dispatcher.runPending();

        assertEquals(0, deliveries.get(), "runnable 內玩家在線重檢失敗時不得執行 callback");
        assertEquals(0, service.pendingCountForTesting(), "重檢失敗後 pending 必須清空");
    }

    // -----------------------------------------------------------------
    // REJECTED 發送：pending 立即清理
    // -----------------------------------------------------------------

    @Test
    @DisplayName("REJECTED：發送被拒時 pending 立即清理，遲到 sink 亦無法交付")
    void rejectedSend_pendingCleanedImmediately() {
        DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.REJECTED);
        FormServiceImpl service = new FormServiceImpl(sender, dispatcher);

        AtomicInteger deliveries = new AtomicInteger();
        assertEquals(FormSendResult.REJECTED,
            service.sendForm(PLAYER_ID, sampleSpec(), response -> deliveries.incrementAndGet()));

        assertEquals(0, service.pendingCountForTesting(), "REJECTED 後不得殘留 pending");
        if (sender.responseRef.get() != null) {
            sender.fire(FormResponseStatus.VALID, 0, List.of());
            dispatcher.runPending();
        }
        assertEquals(0, deliveries.get(), "被拒發送的回呼不得交付");
    }

    // -----------------------------------------------------------------
    // CLOSED / INVALID 回應也會交付（語意由 consumer 判讀）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("CLOSED 回應：正常派送交付，status=CLOSED、無按鈕、空 values")
    void closedResponse_deliveredWithClosedSemantics() {
        DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl service = new FormServiceImpl(sender, dispatcher);

        AtomicReference<FormResponse> received = new AtomicReference<>();
        service.sendForm(PLAYER_ID, sampleSpec(), received::set);

        sender.fire(FormResponseStatus.CLOSED, null, List.of());
        dispatcher.runPending();

        assertNotNull(received.get(), "關閉表單也是有效回應事件，必須交付");
        assertEquals(FormResponseStatus.CLOSED, received.get().status());
        assertTrue(received.get().clickedButton().isEmpty());
        assertTrue(received.get().values().isEmpty());
    }

    // -----------------------------------------------------------------
    // shutdown 線性化：五項重檢＋callback 執行對 shutdown 必須是原子區段
    // -----------------------------------------------------------------

    @Test
    @DisplayName("線性化：callback 已開始執行時，shutdown 必須等它完成後才返回")
    void shutdown_waitsForInFlightCallbackToComplete() throws Exception {
        DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl service = new FormServiceImpl(sender, dispatcher);

        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicBoolean callbackFinished = new AtomicBoolean(false);
        AtomicInteger deliveries = new AtomicInteger();
        service.sendForm(PLAYER_ID, sampleSpec(), response -> {
            deliveries.incrementAndGet();
            callbackStarted.countDown();
            try {
                releaseCallback.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                callbackFinished.set(true);
            }
        });

        sender.fire(FormResponseStatus.VALID, 0, List.of());
        assertEquals(1, dispatcher.pendingCount(), "派送已 enqueue，runnable 尚未執行");

        // 模擬玩家 region 執行緒：runnable 通過五項重檢後進入 consumer，
        // 並決定性阻塞在 releaseCallback（鎖／臨界區仍被本次交付持有）
        Thread regionThread = new Thread(dispatcher::runPending, "simulated-player-region");
        regionThread.start();
        assertTrue(callbackStarted.await(5, TimeUnit.SECONDS),
            "callback 必須已開始執行（五項檢查已全過、consumer 已進入）");

        AtomicBoolean shutdownReturned = new AtomicBoolean(false);
        Thread shutdownThread = new Thread(() -> {
            service.shutdown();
            shutdownReturned.set(true);
        }, "concurrent-shutdown");
        shutdownThread.start();

        // 分支觀察窗：正確實作下 shutdown 被監視器決定性擋住（callback 阻塞中
        // 未釋放臨界區），join 必定逾時；缺陷實作下 shutdown 不等 callback、
        // 微秒內返回。逾時在此是分支訊號，不是時序賭注。
        shutdownThread.join(1000);
        assertFalse(shutdownReturned.get(),
            "callback 執行中時 shutdown() 不得先行返回（必須等交付完成）");

        releaseCallback.countDown();
        shutdownThread.join(10_000);
        regionThread.join(10_000);
        assertFalse(shutdownThread.isAlive(), "釋放 callback 後 shutdown 執行緒必須收尾");
        assertFalse(regionThread.isAlive(), "region 執行緒必須收尾");
        assertTrue(shutdownReturned.get(), "shutdown 最終必須返回");
        assertTrue(callbackFinished.get(), "已開始的交付必須完整執行完畢");
        assertEquals(1, deliveries.get(), "已開始的交付屬服務生命週期內，恰好一次");
        assertEquals("FAILED", service.getModuleStatus());
        assertEquals(0, service.pendingCountForTesting(), "交付與 shutdown 完成後不得殘留 pending");
    }

    @Test
    @DisplayName("線性化：消費者在 callback 內呼叫 shutdown 不死鎖，該次交付合法完成")
    void consumerCallingShutdownInsideCallback_doesNotDeadlock() {
        DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl service = new FormServiceImpl(sender, dispatcher);

        AtomicReference<String> statusSeenInsideCallback = new AtomicReference<>();
        AtomicInteger deliveries = new AtomicInteger();
        service.sendForm(PLAYER_ID, sampleSpec(), response -> {
            deliveries.incrementAndGet();
            // 重入：交付進行中同一執行緒再取得生命週期監視器，不得自死鎖；
            // 重入 shutdown 返回後，服務狀態必須已是 FAILED
            service.shutdown();
            statusSeenInsideCallback.set(service.getModuleStatus());
        });

        sender.fire(FormResponseStatus.VALID, 0, List.of());
        dispatcher.runPending();

        assertEquals(1, deliveries.get(), "shutdown 前已開始的交付必須完成");
        assertEquals("FAILED", statusSeenInsideCallback.get(),
            "callback 內重入 shutdown 後狀態即為 FAILED");
        assertEquals("FAILED", service.getModuleStatus());
        assertEquals(0, service.pendingCountForTesting(), "不得殘留 pending state");
    }

    @Test
    @DisplayName("並發煙霧（輔助、上限 100 輪）：shutdown 與派送競逐不死鎖、不雙執行、無殘留")
    void concurrentShutdownAndDelivery_boundedStressSmoke() throws Exception {
        int iterations = 100;
        for (int i = 0; i < iterations; i++) {
            DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
            CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
            FormServiceImpl service = new FormServiceImpl(sender, dispatcher);
            AtomicInteger deliveries = new AtomicInteger();
            service.sendForm(PLAYER_ID, sampleSpec(), response -> deliveries.incrementAndGet());
            sender.fire(FormResponseStatus.VALID, 0, List.of());

            CountDownLatch startGate = new CountDownLatch(1);
            Thread deliveryThread = new Thread(() -> {
                try {
                    startGate.await();
                    dispatcher.runPending();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "stress-delivery-" + i);
            Thread shutdownThread = new Thread(() -> {
                try {
                    startGate.await();
                    service.shutdown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "stress-shutdown-" + i);
            deliveryThread.start();
            shutdownThread.start();
            startGate.countDown();

            deliveryThread.join(10_000);
            shutdownThread.join(10_000);
            assertFalse(deliveryThread.isAlive(),
                "第 " + i + " 輪 delivery 執行緒必須收尾（不得死鎖）");
            assertFalse(shutdownThread.isAlive(),
                "第 " + i + " 輪 shutdown 執行緒必須收尾（不得死鎖）");

            assertTrue(deliveries.get() <= 1, "第 " + i + " 輪 at-most-once 被破壞");
            assertEquals(0, service.pendingCountForTesting(),
                "第 " + i + " 輪結束後不得殘留 pending");
            assertEquals("FAILED", service.getModuleStatus(), "第 " + i + " 輪結束後必須為 FAILED");
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> service.sendForm(PLAYER_ID, sampleSpec()),
                "競逐結束後新發送必須以 FORM-002 拒絕");
            assertTrue(rejected.getMessage().contains("ACELIB-FORM-002"));
        }
    }

    // -----------------------------------------------------------------
    // 送出端原子性：生命週期檢查＋pending 註冊對 shutdown 線性化
    // -----------------------------------------------------------------

    @Test
    @DisplayName("送出中途 shutdown：sendForm 返回後 pending 必空，遲到回呼零執行")
    void shutdownDuringPhysicalSend_pendingEmptyAfterReturn_lateCallbackZeroExecutions() {
        AtomicReference<FormService> serviceRef = new AtomicReference<>();
        ShutdownInsideSendFormSender sender = new ShutdownInsideSendFormSender(serviceRef);
        DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
        FormServiceImpl service = new FormServiceImpl(sender, dispatcher);
        serviceRef.set(service);

        AtomicInteger deliveries = new AtomicInteger();
        FormSendResult result = service.sendForm(PLAYER_ID, sampleSpec(),
            response -> deliveries.incrementAndGet());

        assertEquals(FormSendResult.SENT, result,
            "實體送出已完成時即使服務已停，仍如實回報 SENT（SENT-after-shutdown 語意）");
        assertEquals("FAILED", service.getModuleStatus(),
            "送出中途的 shutdown 必須已生效");
        assertEquals(0, service.pendingCountForTesting(),
            "shutdown 返回後 pending 必須為空（不得殘留永不交付的註冊）");

        // 模擬 Floodgate 於 shutdown 完成後才回呼：遲到回呼必須零執行
        sender.fire(FormResponseStatus.VALID, 0, List.of());
        dispatcher.runPending();

        assertEquals(0, deliveries.get(), "shutdown 後遲到回呼不得執行 callback");
        assertEquals(0, service.pendingCountForTesting(), "遲到回呼處理後同樣不得殘留");
    }

    @Test
    @DisplayName("並發壓力（輔助、上限 500 輪）：註冊與 shutdown 競逐後 pending 必空、新發送以 FORM-002 拒絕")
    void concurrentRegistrationVsShutdown_boundedStressNoResidue() throws Exception {
        int iterations = 500;
        for (int i = 0; i < iterations; i++) {
            CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
            FormServiceImpl service = new FormServiceImpl(sender,
                FormResponseDispatcher.noop());
            AtomicReference<String> sendRejection = new AtomicReference<>();
            CountDownLatch startGate = new CountDownLatch(1);
            Thread registerThread = new Thread(() -> {
                try {
                    startGate.await();
                    try {
                        service.sendForm(PLAYER_ID, sampleSpec(), response -> { });
                    } catch (IllegalStateException rejected) {
                        // shutdown 搶先取得鎖：FORM-002 拒絕屬合法競逐結果
                        sendRejection.set(rejected.getMessage());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "stress-register-" + i);
            Thread shutdownThread = new Thread(() -> {
                try {
                    startGate.await();
                    service.shutdown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "stress-shutdown-" + i);
            registerThread.start();
            shutdownThread.start();
            startGate.countDown();

            registerThread.join(10_000);
            shutdownThread.join(10_000);
            assertFalse(registerThread.isAlive(),
                "第 " + i + " 輪 sendForm 執行緒必須收尾（不得死鎖）");
            assertFalse(shutdownThread.isAlive(),
                "第 " + i + " 輪 shutdown 執行緒必須收尾（不得死鎖）");

            assertTrue(sendRejection.get() == null
                    || sendRejection.get().contains("ACELIB-FORM-002"),
                "第 " + i + " 輪競逐拒絕必須攜帶 FORM-002，實際：" + sendRejection.get());
            assertEquals(0, service.pendingCountForTesting(),
                "第 " + i + " 輪結束後 pending 必須為空（註冊對 shutdown 原子）");
            assertEquals("FAILED", service.getModuleStatus(),
                "第 " + i + " 輪結束後必須為 FAILED");
            IllegalStateException lateSend = assertThrows(IllegalStateException.class,
                () -> service.sendForm(PLAYER_ID, sampleSpec()),
                "第 " + i + " 輪結束後新發送必須以 FORM-002 拒絕");
            assertTrue(lateSend.getMessage().contains("ACELIB-FORM-002"));
        }
    }

    // -----------------------------------------------------------------
    // dispatcher.dispatch 拋例外：pending 清理＋例外原樣傳播
    // -----------------------------------------------------------------

    @Test
    @DisplayName("dispatch 拋例外：pending 必須清理且例外原樣傳播（不吞錯）")
    void dispatchThrowingDispatcher_pendingCleanedAndExceptionPropagates() {
        FormResponseDispatcher throwingDispatcher = (playerId, task) -> {
            throw new IllegalStateException("simulated dispatch failure");
        };
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl service = new FormServiceImpl(sender, throwingDispatcher);

        AtomicInteger deliveries = new AtomicInteger();
        service.sendForm(PLAYER_ID, sampleSpec(), response -> deliveries.incrementAndGet());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> sender.fire(FormResponseStatus.VALID, 0, List.of()),
            "dispatch 的例外必須原樣傳播給回呼來源，不得吞掉");
        assertEquals("simulated dispatch failure", thrown.getMessage());

        assertEquals(0, deliveries.get(), "派送失敗時 callback 不得執行");
        assertEquals(0, service.pendingCountForTesting(),
            "dispatch 拋例外時 pending 必須清理，不得殘留");
    }

    // -----------------------------------------------------------------
    // generation 獨立重檢：stopped=false 且 entry 存在但代數不符 → 零執行
    // -----------------------------------------------------------------

    @Test
    @DisplayName("generation 獨立重檢：stopped=false 且 entry 存在但代數不符 → 零執行並清 pending")
    void generationMismatch_stoppedFalse_entryPresent_blocksDelivery() {
        DeferredFormResponseDispatcher dispatcher = FormResponseDispatcher.deferred();
        CapturingFormSender sender = new CapturingFormSender(FormSendResult.SENT);
        FormServiceImpl service = new FormServiceImpl(sender, dispatcher);

        AtomicInteger deliveries = new AtomicInteger();
        service.sendForm(PLAYER_ID, sampleSpec(), response -> deliveries.incrementAndGet());

        sender.fire(FormResponseStatus.VALID, 0, List.of());
        assertEquals(1, dispatcher.pendingCount(), "派送已 enqueue");
        assertEquals(1, service.pendingCountForTesting(), "entry 仍在 pending");

        // 只遞增代數：stopped 維持 false、entry 仍在 pending，
        // 隔離「generation 重檢」這一項，不被 stopped／pending.clear 掩護
        service.bumpGenerationForTesting();
        assertEquals("READY", service.getModuleStatus(), "前置條件：服務仍未停用");

        dispatcher.runPending();

        assertEquals(0, deliveries.get(), "代數不符時 callback 必須零執行");
        assertEquals(0, service.pendingCountForTesting(), "代數不符後 pending 必須清空");
        assertEquals("READY", service.getModuleStatus(), "代數拒絕路徑不得改動 stopped 狀態");
    }
}
