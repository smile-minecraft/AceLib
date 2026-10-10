package com.smile.acelib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * 分頁清單元件契約：遮罩＋頁面 model 組出上一頁／下一頁／頁碼
 * 與空／載入中／錯誤三種畫面；翻頁由呼叫端驅動，不做自動資料綁定。
 *
 * <p>過期按鈕與退服安全沿用既有 session／generation 機制：
 * 元件本身不建快取、每次重組全新視圖。</p>
 */
@DisplayName("GuiPager 分頁清單")
class GuiPagerTest {

    private ServerMock server;
    private JavaPlugin plugin;
    private GuiService service;
    private GuiScope scope;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("PagerPlugin");
        when(plugin.isEnabled()).thenReturn(true);
        service = new GuiServiceImpl();
        scope = GuiScopes.create(plugin, service);
    }

    @AfterEach
    void tearDown() {
        GuiScopes.close(plugin);
        MockBukkit.unmock();
    }

    private static GuiMask mask() {
        return GuiMask.of(
            "#########",
            "#IIIIIII#",
            "PPP###NNN");
    }

    private static List<String> range(int n) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add("item-" + i);
        }
        return list;
    }

    private static GuiPager<String> pager(List<String> clicked) {
        return GuiPager.of(mask(), 'I', 'P', 'N', "名單",
            (builder, slot, item) -> builder.button(slot, "item-" + slot,
                click -> clicked.add(item)));
    }

    private static GuiPager.Actions actions(AtomicBoolean nextFired,
            AtomicBoolean prevFired) {
        return new GuiPager.Actions(
            click -> nextFired.set(true),
            click -> prevFired.set(true));
    }

    private static boolean hasButton(GuiView view, String buttonId) {
        return view.buttons().values().stream()
            .anyMatch(button -> button.id().equals(buttonId));
    }

    private static int buttonSlot(GuiView view, String buttonId) {
        return view.buttons().entrySet().stream()
            .filter(entry -> entry.getValue().id().equals(buttonId))
            .mapToInt(java.util.Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new AssertionError("找不到按鈕: " + buttonId));
    }

    @Test
    void navigationIconsFollowPageAvailabilityAndFirstSymbolSlot() {
        ItemStack prev = new ItemStack(Material.ARROW);
        ItemStack next = new ItemStack(Material.SPECTRAL_ARROW);
        GuiPager<String> original = pager(new ArrayList<>());
        GuiPager<String> decorated = original.withPrevIcon(prev).withNextIcon(next);
        GuiPager.Actions actions = new GuiPager.Actions(click -> {}, click -> {});
        for (int index = 0; index < 3; index++) {
            GuiPage<String> page = GuiPage.page(range(20), 7, index);
            GuiView view = decorated.viewFor(page, actions);
            assertEquals(index > 0, view.buttonIcons().containsKey(18));
            assertEquals(index < 2, view.buttonIcons().containsKey(24));
            assertEquals((index > 0 ? 1 : 0) + (index < 2 ? 1 : 0),
                view.buttonIcons().size());
            if (index > 0) {
                assertSame(prev, view.buttonIcons().get(18));
                assertEquals(18, buttonSlot(view, GuiPager.PREV_BUTTON_ID));
            }
            if (index < 2) {
                assertSame(next, view.buttonIcons().get(24));
                assertEquals(24, buttonSlot(view, GuiPager.NEXT_BUTTON_ID));
            }
            assertTrue(original.viewFor(page, actions).buttonIcons().isEmpty());
        }
        assertTrue(decorated.viewFor(GuiPage.empty(), actions).buttonIcons().isEmpty());
        assertTrue(decorated.viewFor(GuiPage.loading(), actions).buttonIcons().isEmpty());
    }

    @Test
    void navigationIconDeclarationsKeepReferencesWithoutCachingViews() {
        ItemStack icon = new ItemStack(Material.ARROW);
        GuiPager<String> original = pager(new ArrayList<>());
        GuiPager<String> decorated = original.withNextIcon(icon);
        GuiPage<String> page = GuiPage.page(range(20), 7, 0);
        GuiPager.Actions actions = new GuiPager.Actions(click -> {}, click -> {});
        GuiView first = decorated.viewFor(page, actions);
        icon.setAmount(3);
        GuiView second = decorated.viewFor(page, actions);
        assertNotSame(first, second);
        assertSame(icon, first.buttonIcons().get(24));
        assertSame(icon, second.buttonIcons().get(24));
        assertEquals(3, second.buttonIcons().get(24).getAmount());
        assertTrue(original.viewFor(page, actions).buttonIcons().isEmpty());
        assertThrows(NullPointerException.class, () -> original.withPrevIcon(null));
        assertThrows(NullPointerException.class, () -> original.withNextIcon(null));
    }

    // -----------------------------------------------------------------
    // CONTENT：導覽按鈕存在條件、頁碼標題、項目數量
    // -----------------------------------------------------------------

    @Test
    @DisplayName("第一頁：有下一頁、無上一頁；標題帶頁碼；項目數＝min(項目, 欄位數)")
    void firstPage_hasOnlyNext() {
        GuiPager<String> pager = pager(new ArrayList<>());
        GuiPage<String> page = GuiPage.page(range(25), 7, 0);

        GuiView view = pager.viewFor(page,
            actions(new AtomicBoolean(), new AtomicBoolean()));

        assertTrue(hasButton(view, GuiPager.NEXT_BUTTON_ID), "第一頁必須有下一頁按鈕");
        assertFalse(hasButton(view, GuiPager.PREV_BUTTON_ID), "第一頁不得有上一頁按鈕");
        assertTrue(view.title().contains("頁 1/4"), "標題必須帶頁碼；實際: " + view.title());
        assertEquals(7, view.buttons().size() - 1, "項目按鈕數必須等於項目欄位數");
    }

    @Test
    @DisplayName("中間頁：上一頁與下一頁皆有；標題帶頁碼")
    void middlePage_hasBothNavButtons() {
        GuiPager<String> pager = pager(new ArrayList<>());
        GuiPage<String> page = GuiPage.page(range(25), 7, 1);

        GuiView view = pager.viewFor(page,
            actions(new AtomicBoolean(), new AtomicBoolean()));

        assertTrue(hasButton(view, GuiPager.PREV_BUTTON_ID), "中間頁必須有上一頁按鈕");
        assertTrue(hasButton(view, GuiPager.NEXT_BUTTON_ID), "中間頁必須有下一頁按鈕");
        assertTrue(view.title().contains("頁 2/4"), "標題必須帶頁碼；實際: " + view.title());
    }

    @Test
    @DisplayName("最後頁：只有上一頁、無下一頁；項目數跟隨尾頁餘量")
    void lastPage_hasOnlyPrev() {
        GuiPager<String> pager = pager(new ArrayList<>());
        GuiPage<String> page = GuiPage.page(range(25), 7, 3);

        GuiView view = pager.viewFor(page,
            actions(new AtomicBoolean(), new AtomicBoolean()));

        assertTrue(hasButton(view, GuiPager.PREV_BUTTON_ID), "最後頁必須有上一頁按鈕");
        assertFalse(hasButton(view, GuiPager.NEXT_BUTTON_ID),
            "最後頁不得有下一頁按鈕：翻到最後一頁再按沒有目標，呼叫端不得再取新頁");
        assertTrue(view.title().contains("頁 4/4"), "標題必須帶頁碼；實際: " + view.title());
        assertEquals(4, view.buttons().size() - 1, "尾頁項目按鈕數必須等於餘量 4");
    }

    @Test
    @DisplayName("項目數超過項目欄位數時被拒（fail-fast，不靜默截斷）")
    void overflowItems_areRejected() {
        GuiPager<String> pager = pager(new ArrayList<>());
        // 項目欄位只有 7 格，直接給 10 個項目（呼叫端 pageSize 與欄位數不一致時）
        GuiPage<String> page = GuiPage.content(0, 1, range(10));

        IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> pager.viewFor(page,
                actions(new AtomicBoolean(), new AtomicBoolean())));
        assertTrue(failure.getMessage().contains(GuiErrorCode.INVALID_INPUT),
            "拒絕訊息必須攜帶 INVALID_INPUT；實際: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("10"),
            "拒絕訊息必須包含實際項目數 10；實際: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("7"),
            "拒絕訊息必須包含實際欄位數 7；實際: " + failure.getMessage());
    }

    // -----------------------------------------------------------------
    // EMPTY／LOADING／ERROR：無導覽按鈕、標題標狀態
    // -----------------------------------------------------------------

    @Test
    @DisplayName("EMPTY：無導覽按鈕，標題標空資料")
    void emptyPage_hasNoNavButtons() {
        GuiPager<String> pager = pager(new ArrayList<>());

        GuiView view = pager.viewFor(GuiPage.empty(),
            actions(new AtomicBoolean(), new AtomicBoolean()));

        assertFalse(hasButton(view, GuiPager.PREV_BUTTON_ID), "空資料畫面不得有上一頁按鈕");
        assertFalse(hasButton(view, GuiPager.NEXT_BUTTON_ID), "空資料畫面不得有下一頁按鈕");
        assertTrue(view.title().contains("空資料"), "標題必須標空資料；實際: " + view.title());
    }

    @Test
    @DisplayName("LOADING：無導覽按鈕，標題標載入中")
    void loadingPage_hasNoNavButtons() {
        GuiPager<String> pager = pager(new ArrayList<>());

        GuiView view = pager.viewFor(GuiPage.loading(),
            actions(new AtomicBoolean(), new AtomicBoolean()));

        assertFalse(hasButton(view, GuiPager.PREV_BUTTON_ID), "載入中畫面不得有上一頁按鈕");
        assertFalse(hasButton(view, GuiPager.NEXT_BUTTON_ID), "載入中畫面不得有下一頁按鈕");
        assertTrue(view.title().contains("載入中"), "標題必須標載入中；實際: " + view.title());
    }

    @Test
    @DisplayName("ERROR：無導覽按鈕，標題帶錯誤碼")
    void errorPage_hasNoNavButtonsAndShowsCode() {
        GuiPager<String> pager = pager(new ArrayList<>());

        GuiView view = pager.viewFor(
            GuiPage.error(GuiErrorCode.OPERATION_FAILED, "載入失敗"),
            actions(new AtomicBoolean(), new AtomicBoolean()));

        assertFalse(hasButton(view, GuiPager.PREV_BUTTON_ID), "錯誤畫面不得有上一頁按鈕");
        assertFalse(hasButton(view, GuiPager.NEXT_BUTTON_ID), "錯誤畫面不得有下一頁按鈕");
        assertTrue(view.title().contains(GuiErrorCode.OPERATION_FAILED),
            "標題必須帶錯誤碼；實際: " + view.title());
    }

    // -----------------------------------------------------------------
    // 資料縮減：totalPages 變小後仍給合法視圖
    // -----------------------------------------------------------------

    @Test
    @DisplayName("資料縮減後以舊頁碼重取仍回到範圍內頁，視圖合法")
    void shrunkData_clampsToValidPage() {
        GuiPager<String> pager = pager(new ArrayList<>());
        // 原 25 筆、每頁 7 筆＝4 頁；呼叫端停在第 4 頁時資料縮成 8 筆（＝2 頁）
        List<String> shrunk = range(8);
        GuiPage<String> page = GuiPage.page(shrunk, 7, 3);

        GuiView view = pager.viewFor(page,
            actions(new AtomicBoolean(), new AtomicBoolean()));

        assertEquals(1, page.pageIndex(), "縮減後必須 clamp 到最後一頁");
        assertTrue(view.title().contains("頁 2/2"), "標題必須是合法頁碼；實際: " + view.title());
        assertTrue(hasButton(view, GuiPager.PREV_BUTTON_ID), "第 2 頁必須有上一頁按鈕");
        assertFalse(hasButton(view, GuiPager.NEXT_BUTTON_ID), "最後頁不得有下一頁按鈕");
        assertEquals(1, view.buttons().size() - 1, "尾頁只剩 1 個項目");
    }

    // -----------------------------------------------------------------
    // 過期按鈕：舊 generation 的翻頁點擊被拒且動作不執行
    // -----------------------------------------------------------------

    @Test
    @DisplayName("舊畫面的翻頁點擊被拒且 onNext 不執行")
    void stalePageClick_isRejectedWithoutAction() {
        List<String> clicked = new ArrayList<>();
        GuiPager<String> pager = pager(clicked);
        AtomicBoolean nextFired = new AtomicBoolean(false);
        AtomicBoolean prevFired = new AtomicBoolean(false);
        GuiPager.Actions pagerActions = actions(nextFired, prevFired);
        UUID player = server.addPlayer().getUniqueId();

        GuiView first = pager.viewFor(GuiPage.page(range(25), 7, 0), pagerActions);
        long staleGeneration = scope.openView(player, first).session().generation();
        int staleNextSlot = buttonSlot(first, GuiPager.NEXT_BUTTON_ID);

        // 呼叫端取新頁再開新畫面（新 generation）
        GuiView second = pager.viewFor(GuiPage.page(range(25), 7, 1), pagerActions);
        scope.pushView(player, second);

        GuiResult stale = scope.handleClick(player, staleGeneration, staleNextSlot);
        assertEquals(GuiState.REJECTED, stale.state(), "舊代點擊必須被拒");
        assertEquals(GuiErrorCode.GENERATION_MISMATCH, stale.errorCode());
        assertFalse(nextFired.get(), "過期按鈕不得觸發翻頁動作");
        assertTrue(clicked.isEmpty(), "過期按鈕不得觸發項目動作");
    }

    // -----------------------------------------------------------------
    // 退服安全：LOADING 畫面期間退服不殘留
    // -----------------------------------------------------------------

    @Test
    @DisplayName("LOADING 畫面期間退服：session 清理、無殘留")
    void quitDuringLoading_clearsSession() {
        GuiPager<String> pager = pager(new ArrayList<>());
        UUID player = server.addPlayer().getUniqueId();
        GuiView loading = pager.viewFor(GuiPage.loading(),
            actions(new AtomicBoolean(), new AtomicBoolean()));
        assertEquals(GuiState.SUCCESS, scope.openView(player, loading).state());

        scope.handlePlayerQuit(player);

        assertEquals(Optional.empty(), scope.viewOf(player), "退服後不得殘留視圖狀態");
        GuiResult query = service.getActiveSession(player);
        assertEquals(GuiState.REJECTED, query.state(), "退服後 session 必須清理");
        assertEquals(GuiErrorCode.SESSION_NOT_FOUND, query.errorCode());
    }

    // -----------------------------------------------------------------
    // 邊界：建構驗證與 null 拒絕
    // -----------------------------------------------------------------

    @Test
    @DisplayName("保留符號重複或項目區為空時建構被拒")
    void invalidMask_isRejected() {
        GuiPager.ItemRenderer<String> renderer = (builder, slot, item) -> {
        };
        assertThrows(IllegalArgumentException.class,
            () -> GuiPager.of(mask(), 'I', 'I', 'N', "名單", renderer),
            "項目與上一頁同符號必須被拒");
        assertThrows(IllegalArgumentException.class,
            () -> GuiPager.of(mask(), 'I', 'P', 'P', "名單", renderer),
            "上一頁與下一頁同符號必須被拒");
        assertThrows(IllegalArgumentException.class,
            () -> GuiPager.of(mask(), 'Z', 'P', 'N', "名單", renderer),
            "遮罩沒有的項目符號必須被拒");
    }

    @Test
    @DisplayName("null 輸入一律被拒")
    void nullInputs_throw() {
        GuiPager<String> pager = pager(new ArrayList<>());
        GuiPager.Actions pagerActions =
            actions(new AtomicBoolean(), new AtomicBoolean());
        assertThrows(NullPointerException.class,
            () -> GuiPager.of(null, 'I', 'P', 'N', "名單", (b, s, i) -> {
            }));
        assertThrows(NullPointerException.class,
            () -> GuiPager.of(mask(), 'I', 'P', 'N', null, (b, s, i) -> {
            }));
        assertThrows(NullPointerException.class,
            () -> GuiPager.of(mask(), 'I', 'P', 'N', "名單", null));
        assertThrows(NullPointerException.class,
            () -> pager.viewFor(null, pagerActions));
        assertThrows(NullPointerException.class,
            () -> pager.viewFor(GuiPage.loading(), null));
        assertThrows(NullPointerException.class,
            () -> new GuiPager.Actions(null, click -> {
            }));
        assertThrows(NullPointerException.class,
            () -> new GuiPager.Actions(click -> {
            }, null));
    }
}
