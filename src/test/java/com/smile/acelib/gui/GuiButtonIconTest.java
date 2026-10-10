package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * 按鈕物品契約：宣告存參照、放置前於玩家 region 內即時複製、
 * 逐欄失敗有碼可查、純回呼按鈕相容。
 *
 * <p>複製語意：宣告時<strong>不</strong>複製 — 開啟前修改該物品會反映到
 * 放置結果；保護點在放置時（region runnable 內、{@code setItem} 之前即時
 * {@code clone}），同一宣告給多位玩家開啟時各自獨立。</p>
 *
 * <p>執行緒說明（MockBukkit 限制如實記錄）：MockBukkit 為 Paper-like，
 * {@link PlayerContextExecutor#direct()} 同步執行即 region context 安全；
 * {@link PlayerContextExecutor#deferred()} 模擬 Folia entity scheduler 的
 * enqueue 語意。真 Folia runtime 的 region 執行語意不在此驗證。</p>
 */
@DisplayName("GuiView 按鈕物品")
class GuiButtonIconTest {

    private ServerMock server;
    private PlayerMock player;
    private UUID uuid;
    private JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        uuid = player.getUniqueId();
        plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("IconTestPlugin");
        when(plugin.isEnabled()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        GuiScopes.close(plugin);
        MockBukkit.unmock();
    }

    private static GuiScope scopeWith(PlayerContextExecutor executor,
            JavaPlugin plugin) {
        GuiServiceImpl service = new GuiServiceImpl(executor);
        return GuiScopes.create(plugin, service);
    }

    private static Inventory topOf(PlayerMock player) {
        assertNotNull(player.getOpenInventory(), "玩家必須有 active inventory view");
        Inventory top = player.getOpenInventory().getTopInventory();
        assertNotNull(top, "top inventory 不可為 null");
        return top;
    }

    /**
     * 記錄 {@code clone} 執行緒的物品：證明複製發生在哪個執行緒。
     */
    private static ItemStack recordingIcon(Material material,
            List<String> cloneThreads) {
        return new ItemStack(material) {
            @Override
            public ItemStack clone() {
                cloneThreads.add(Thread.currentThread().getName());
                return super.clone();
            }
        };
    }

    /**
     * 複製即拋錯的物品：失敗注入用。
     */
    private static ItemStack failingIcon() {
        return new ItemStack(Material.DIAMOND) {
            @Override
            public ItemStack clone() {
                throw new RuntimeException("boom-clone");
            }
        };
    }

    /**
     * 跳室執行緒的 executor：runnable 在名為 {@code region-sim} 的執行緒執行，
     * 呼叫端等待完成。宣告執行緒（測試執行緒）與 region 執行緒必然不同，
     * {@code clone} 落在哪一邊可直接分辨。
     *
     * <p>注意：MockBukkit 的開箱呼叫不得離開測試執行緒，
     * 本 executor 僅保留作文件用途，實際執行緒區分改用「宣告跳室」模式
     * （見 {@link #cloneRunsAtOpen_notAtDeclaration}）。</p>
     */
    @SuppressWarnings("unused")
    private static PlayerContextExecutor hopExecutor(List<String> runThreads) {
        return (target, runnable) -> {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread region = new Thread(() -> {
                runThreads.add(Thread.currentThread().getName());
                try {
                    runnable.run();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "region-sim");
            region.start();
            try {
                region.join(10_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (failure.get() != null) {
                throw new AssertionError("region runnable 拋錯", failure.get());
            }
            return true;
        };
    }

    @Test
    @DisplayName("宣告後、開啟前修改物品：開啟時放置的是修改後的內容（存參照語意）")
    void mutationBeforeOpen_reflectsInPlacedIcon() {
        GuiScope scope = scopeWith(PlayerContextExecutor.direct(), plugin);

        ItemStack icon = new ItemStack(Material.DIAMOND_SWORD);
        icon.setAmount(1);
        GuiView view = GuiView.chest("商店", 27)
            .button(13, "buy", icon, click -> {
            })
            .build();
        // 宣告後、開啟前修改：存參照語意下必須反映到放置結果。
        icon.setAmount(64);
        icon.setType(Material.STONE);

        GuiResult opened = scope.openView(uuid, view);
        assertEquals(GuiState.SUCCESS, opened.state(), "開啟必須成功");

        ItemStack placed = topOf(player).getItem(13);
        assertNotNull(placed, "按鈕欄位必須放進物品");
        assertEquals(Material.STONE, placed.getType(),
            "開啟前修改必須反映到放置的物品");
        assertEquals(64, placed.getAmount());
    }

    @Test
    @DisplayName("同一宣告給兩位玩家開啟：放置的複本各自獨立、互不影響")
    void sharedDeclaration_placesIndependentCopies() {
        GuiScope scope = scopeWith(PlayerContextExecutor.direct(), plugin);
        PlayerMock other = server.addPlayer();

        ItemStack icon = new ItemStack(Material.GOLD_INGOT);
        icon.setAmount(5);
        GuiView view = GuiView.chest("商店", 27)
            .button(13, "buy", icon, click -> {
            })
            .build();

        assertEquals(GuiState.SUCCESS, scope.openView(uuid, view).state());
        assertEquals(GuiState.SUCCESS,
            scope.openView(other.getUniqueId(), view).state());

        ItemStack first = topOf(player).getItem(13);
        ItemStack second = topOf(other).getItem(13);
        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(icon, first, "放置的必須是複本，不是宣告物品本身");
        assertNotSame(icon, second);
        assertNotSame(first, second, "兩位玩家的複本必須各自獨立");

        // 修改一位玩家的箱內物品：另一位與宣告物品都不受影響。
        first.setAmount(64);
        first.setType(Material.STONE);
        assertEquals(Material.GOLD_INGOT, second.getType(),
            "修改一位玩家的物品不得影響另一位");
        assertEquals(5, second.getAmount());
        assertEquals(Material.GOLD_INGOT, icon.getType(),
            "修改箱內物品不得回寫宣告物品");
    }

    @Test
    @DisplayName("複製發生在開啟（region）時，不在宣告執行緒，也不在取值時")
    void cloneRunsAtOpen_notAtDeclaration() throws Exception {
        List<String> cloneThreads = new CopyOnWriteArrayList<>();
        GuiScope scope = scopeWith(PlayerContextExecutor.direct(), plugin);
        String openThread = Thread.currentThread().getName();

        // 宣告跳室：在另一執行緒建物品與視圖（GuiView 組裝為純 Java，不碰開箱）。
        AtomicReference<GuiView> viewRef = new AtomicReference<>();
        AtomicReference<Throwable> declarationFailure = new AtomicReference<>();
        Thread declaration = new Thread(() -> {
            try {
                viewRef.set(GuiView.chest("商店", 27)
                    .button(13, "buy",
                        recordingIcon(Material.DIAMOND_SWORD, cloneThreads),
                        click -> {
                        })
                    .build());
            } catch (Throwable t) {
                declarationFailure.set(t);
            }
        }, "declaration-thread");
        declaration.start();
        declaration.join(10_000);
        if (declarationFailure.get() != null) {
            throw new AssertionError("宣告執行緒拋錯", declarationFailure.get());
        }
        GuiView view = viewRef.get();
        assertNotNull(view, "宣告執行緒必須建出視圖");

        // 宣告完成、取值完成後：沒有任何複製發生。
        assertTrue(cloneThreads.isEmpty(),
            "宣告與組裝不得觸發 clone，實際: " + cloneThreads);
        view.buttonIcons();
        assertTrue(cloneThreads.isEmpty(),
            "buttonIcons 取值不得觸發 clone，實際: " + cloneThreads);

        // 開啟（direct executor 同步執行即 region context）：複製恰好一次，
        // 且發生在開啟執行緒，不在宣告執行緒。
        GuiResult opened = scope.openView(uuid, view);
        assertEquals(GuiState.SUCCESS, opened.state(), "開啟必須成功");
        assertEquals(List.of(openThread), cloneThreads,
            "clone 必須恰好一次，且發生在開啟（region）執行緒");
        assertTrue(!cloneThreads.contains("declaration-thread"),
            "clone 不得發生在宣告執行緒");

        ItemStack placed = topOf(player).getItem(13);
        assertNotNull(placed, "按鈕欄位必須放進物品");
        assertEquals(Material.DIAMOND_SWORD, placed.getType());
    }

    @Test
    @DisplayName("Folia-like deferred：派送時無複製（可觀察），region 執行後才複製放置")
    void deferredExecutor_clonesOnlyWhenRegionRuns() {
        PlayerContextExecutor.DeferredPlayerContextExecutor deferred =
            PlayerContextExecutor.deferred();
        GuiScope scope = scopeWith(deferred, plugin);

        List<String> cloneThreads = new CopyOnWriteArrayList<>();
        AtomicBoolean clicked = new AtomicBoolean(false);
        GuiView view = GuiView.chest("商店", 27)
            .button(13, "buy",
                recordingIcon(Material.IRON_INGOT, cloneThreads),
                click -> clicked.set(true))
            .build();

        GuiResult opened = scope.openView(uuid, view);
        assertEquals(GuiState.SUCCESS, opened.state(),
            "deferred 派送接受即回 SUCCESS（Folia enqueue 語意）");
        // 可觀察斷言：派送接受、region 尚未執行時，沒有任何複製發生
        // （複製即放置的前一步；無複製＝未提前放置）。
        assertTrue(cloneThreads.isEmpty(),
            "region runnable 執行前不得有任何 clone（不得提前放置），實際: "
                + cloneThreads);

        // MockBukkit 的開箱呼叫必須留在測試執行緒，故 runPending 同步執行：
        // 斷言重點是「派送前零次、執行後恰好一次」——複製發生在 region runnable 內。
        deferred.runPending();

        assertEquals(1, cloneThreads.size(),
            "region runnable 執行後必須恰好複製一次，實際: " + cloneThreads);
        assertEquals(Thread.currentThread().getName(), cloneThreads.get(0),
            "複製必須發生在執行 region runnable 的執行緒");
        ItemStack placed = topOf(player).getItem(13);
        assertNotNull(placed, "region runnable 執行後按鈕欄位必須有物品");
        assertEquals(Material.IRON_INGOT, placed.getType());

        GuiResult click = scope.handleClick(uuid, opened.session().generation(), 13);
        assertEquals(GuiState.SUCCESS, click.state());
        assertTrue(clicked.get(), "點擊同一欄位仍觸發同一 handler");
    }

    @Test
    @DisplayName("複製失敗：WARNING 帶碼與例外、該欄跳過、開啟與點擊不受影響")
    void placementFailure_loggedWithCodeAndSkipped() {
        GuiScope scope = scopeWith(PlayerContextExecutor.direct(), plugin);
        Logger acel = Logger.getLogger("AceLib");
        List<LogRecord> records = new CopyOnWriteArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        acel.addHandler(capture);
        try {
            AtomicBoolean clicked = new AtomicBoolean(false);
            GuiView view = GuiView.chest("商店", 27)
                .button(10, "good", new ItemStack(Material.GOLD_INGOT),
                    click -> {
                    })
                .button(13, "broken", failingIcon(),
                    click -> clicked.set(true))
                .build();

            GuiResult opened = scope.openView(uuid, view);
            assertEquals(GuiState.SUCCESS, opened.state(),
                "單欄放置失敗不得影響開啟結果");
            long generation = opened.session().generation();

            assertNull(topOf(player).getItem(13),
                "複製失敗的欄位必須跳過（保持為空）");
            ItemStack good = topOf(player).getItem(10);
            assertNotNull(good, "其他欄位的物品仍須放置");
            assertEquals(Material.GOLD_INGOT, good.getType());

            GuiResult click = scope.handleClick(uuid, generation, 13);
            assertEquals(GuiState.SUCCESS, click.state(),
                "放置失敗不得影響點擊語意");
            assertTrue(clicked.get(), "同一欄位的 handler 仍須被觸發");
        } finally {
            acel.removeHandler(capture);
        }

        LogRecord warning = null;
        for (LogRecord record : records) {
            if (record.getMessage() != null
                    && record.getMessage().contains(GuiErrorCode.OPERATION_FAILED)
                    && record.getMessage().contains("13")) {
                warning = record;
                break;
            }
        }
        assertNotNull(warning,
            "必須有 OPERATION_FAILED 的 WARNING 日誌，實際日誌: " + records.size()
                + " 筆");
        assertEquals(java.util.logging.Level.WARNING, warning.getLevel());
        assertNotNull(warning.getThrown(),
            "WARNING 必須攜帶完整例外（getThrown 不可為 null）");
        assertTrue(warning.getThrown().getMessage().contains("boom-clone"),
            "例外必須是複製失敗的原始例外，實際: "
                + warning.getThrown().getMessage());
    }

    @Test
    @DisplayName("放置與點擊欄位一致；純回呼按鈕共存且行為不變")
    void placementSlot_matchesClickSlot_pureButtonCompatible() {
        GuiScope scope = scopeWith(PlayerContextExecutor.direct(), plugin);

        AtomicBoolean iconClicked = new AtomicBoolean(false);
        AtomicBoolean pureClicked = new AtomicBoolean(false);
        ItemStack icon = new ItemStack(Material.GOLD_INGOT);
        GuiView view = GuiView.chest("商店", 27)
            .button(10, "icon-buy", icon, click -> iconClicked.set(true))
            .button(12, "pure-info", click -> pureClicked.set(true))
            .build();

        GuiResult opened = scope.openView(uuid, view);
        assertEquals(GuiState.SUCCESS, opened.state());
        long generation = opened.session().generation();

        ItemStack placed = topOf(player).getItem(10);
        assertNotNull(placed, "物品按鈕欄位必須有物品");
        assertEquals(Material.GOLD_INGOT, placed.getType());

        GuiResult iconClick = scope.handleClick(uuid, generation, 10);
        assertEquals(GuiState.SUCCESS, iconClick.state(), "物品按鈕點擊必須走回呼");
        assertTrue(iconClicked.get(), "同一欄位的 handler 必須被觸發");

        GuiResult pureClick = scope.handleClick(uuid, generation, 12);
        assertEquals(GuiState.SUCCESS, pureClick.state(), "純回呼按鈕點擊必須成功");
        assertTrue(pureClicked.get(), "純回呼按鈕 handler 必須被觸發");
    }

    @Test
    @DisplayName("越界欄位的物品要求在服務層被跳過：開啟仍成功，不影響其他欄位")
    void outOfRangeIcon_isSkipped_openStillSucceeds() {
        GuiServiceImpl service = new GuiServiceImpl(PlayerContextExecutor.direct());
        ItemStack icon = new ItemStack(Material.DIAMOND);

        OwnedOpenOutcome outcome = service.openOwned("IconTestPlugin", uuid, "t",
            GuiView.Kind.CHEST, 9, java.util.Set.of(), true,
            Map.of(99, icon, 4, icon));

        assertEquals(GuiState.SUCCESS, outcome.result().state(),
            "越界物品放置失敗不得影響開啟結果");
        ItemStack placed = topOf(player).getItem(4);
        assertNotNull(placed, "合法欄位的物品仍須放置");
        assertEquals(Material.DIAMOND, placed.getType());
        service.shutdownService();
    }

    @Test
    @DisplayName("buttonIcons 回傳 live 參照：與宣告物品同一實例")
    void buttonIcons_returnsLiveReferences() {
        ItemStack icon = new ItemStack(Material.DIAMOND);
        GuiView view = GuiView.chest("商店", 9)
            .button(0, "buy", icon, click -> {
            })
            .build();
        Map<Integer, ItemStack> first = view.buttonIcons();
        assertEquals(1, first.size());
        assertSame(icon, first.get(0),
            "buttonIcons 回傳的是宣告物品本身（存參照語意）");
        assertSame(first.get(0), view.buttonIcons().get(0),
            "多次取回必須是同一實例");
    }

    @Test
    @DisplayName("純回呼按鈕視圖的 buttonIcons 為空（相容）")
    void pureCallbackView_hasNoIcons() {
        GuiView view = GuiView.chest("商店", 9)
            .button(0, "info", click -> {
            })
            .button(1, "buy", 5_000L, click -> {
            })
            .build();
        assertTrue(view.buttonIcons().isEmpty(),
            "純回呼按鈕視圖不得攜帶物品");
    }
}
