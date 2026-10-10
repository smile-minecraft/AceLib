package com.example;

import com.smile.acelib.gui.GuiFlowStep;
import com.smile.acelib.gui.GuiMask;
import com.smile.acelib.gui.GuiPage;
import com.smile.acelib.gui.GuiPager;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiView;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * AceLib 1.5.0 GUI 版面的外部 consumer 範例。
 *
 * <p>本類別位於 AceLib 外部套件（{@code com.example}），只用公開 API：
 * 以 {@link GuiMask} 字元遮罩描述箱子版面，遮罩符號換算的欄位同時用於
 * 放行欄位與按鈕宣告；購買按鈕一併給物品，開啟時 AceLib 於玩家所在執行緒
 * 把物品放進與按鈕相同的欄位。</p>
 *
 * <p>作用域（{@link GuiScope}）的建立與關閉由擁有插件負責
 *（見 {@code QuickStartPlugin}）；本範例只示範「組視圖、開視圖」。</p>
 */
public final class GuiV150Example {

    private GuiV150Example() {
    }

    /**
     * 商店版面的字元遮罩：邊框放行、中央三格為購買按鈕群組。
     *
     * @return 商店遮罩；永不為 null
     */
    public static GuiMask shopMask() {
        return GuiMask.of(
            "#########",
            "#..BBB..#",
            "#########");
    }

    /**
     * 以遮罩組出商店視圖。
     *
     * <p>宣告時不複製 {@code icon}：開啟前修改它會反映到放置結果；
     * 開啟時於玩家所在執行緒即時複製，多位玩家各自獨立。</p>
     *
     * @param icon 購買按鈕的物品；不可為 null（只存參照，不複製）
     * @param onBuy 購買按鈕的點擊行為；不可為 null
     * @return 商店視圖；永不為 null
     */
    public static GuiView shopView(ItemStack icon, Runnable onBuy) {
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(onBuy, "onBuy");
        GuiMask mask = shopMask();
        GuiView.Builder builder = GuiView.chest("商店", mask)
            .allow(mask.slots('.'));
        for (int slot : mask.slots('B')) {
            builder.button(slot, "buy-" + slot, icon,
                click -> onBuy.run());
        }
        return builder.build();
    }

    /**
     * 為玩家開啟商店（預設鑽石劍圖示）。
     *
     * @param gui 擁有插件的作用域；不可為 null
     * @param playerUuid 目標玩家；不可為 null
     * @param onBuy 購買按鈕的點擊行為；不可為 null
     * @return 開啟結果；永不為 null
     */
    public static GuiResult openShop(GuiScope gui, UUID playerUuid, Runnable onBuy) {
        Objects.requireNonNull(gui, "gui");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(onBuy, "onBuy");
        return gui.openView(playerUuid,
            shopView(new ItemStack(Material.DIAMOND_SWORD), onBuy));
    }

    /**
     * 名單翻頁的字符遮罩：中央七格為項目區，底列左右分別為上一頁／下一頁。
     *
     * @return 名單遮罩；永不為 null
     */
    public static GuiMask rosterMask() {
        return GuiMask.of(
            "#########",
            "#IIIIIII#",
            "PPP###NNN");
    }

    /**
     * 以遮罩與標題前綴組出名單分頁元件。
     *
     * <p>項目按鈕一併給物品與基岩標籤；翻頁動作由呼叫端在回呼內取新頁再開
     *（見 {@link #openRoster}），元件本身不記頁碼、不做資料綁定。</p>
     *
     * @param onPick 點到項目時的行為（收到項目名稱）；不可為 null
     * @return 名單分頁元件；永不為 null
     */
    public static GuiPager<String> rosterPager(Consumer<String> onPick) {
        Objects.requireNonNull(onPick, "onPick");
        return GuiPager.of(rosterMask(), 'I', 'P', 'N', "名單",
            (builder, slot, name) -> builder.button(slot, "roster-" + slot,
                new ItemStack(Material.PLAYER_HEAD), name,
                click -> onPick.accept(name)));
    }

    /**
     * 為玩家開啟名單（指定頁），翻頁時以取代方式換頁，不堆返回歷史。
     *
     * @param gui 擁有插件的作用域；不可為 null
     * @param playerUuid 目標玩家；不可為 null
     * @param names 全部名單；不可為 null
     * @param pageIndex 要求的頁碼（0 起算；越界會被夾到範圍內）
     * @param onPick 點到項目時的行為；不可為 null
     * @return 開啟結果；永不為 null
     */
    public static GuiResult openRoster(GuiScope gui, UUID playerUuid,
            List<String> names, int pageIndex, Consumer<String> onPick) {
        Objects.requireNonNull(gui, "gui");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(names, "names");
        Objects.requireNonNull(onPick, "onPick");
        GuiPager<String> pager = rosterPager(onPick);
        GuiPage<String> page = GuiPage.page(names, pager.itemCapacity(), pageIndex);
        int current = page.pageIndex();
        GuiPager.Actions actions = new GuiPager.Actions(
            click -> showRosterPage(gui, playerUuid, names, current + 1, onPick),
            click -> showRosterPage(gui, playerUuid, names, current - 1, onPick));
        return gui.openView(playerUuid, pager.viewFor(page, actions));
    }

    private static void showRosterPage(GuiScope gui, UUID playerUuid,
            List<String> names, int pageIndex, Consumer<String> onPick) {
        GuiPager<String> pager = rosterPager(onPick);
        GuiPage<String> page = GuiPage.page(names, pager.itemCapacity(), pageIndex);
        int current = page.pageIndex();
        GuiPager.Actions actions = new GuiPager.Actions(
            click -> showRosterPage(gui, playerUuid, names, current + 1, onPick),
            click -> showRosterPage(gui, playerUuid, names, current - 1, onPick));
        gui.replaceView(playerUuid, pager.viewFor(page, actions));
    }

    /**
     * 同一份按鈕宣告兼做基岩流程表單的選單步驟。
     *
     * <p>有標籤的按鈕依欄位升序排成表單按鈕，轉移表索引對應該順序；
     * 純裝飾按鈕不給標籤，就不會進表單。</p>
     *
     * @param onShop 商店按鈕的點擊行為；不可為 null
     * @param onSettings 設定按鈕的點擊行為；不可為 null
     * @return 選單步驟；永不為 null
     */
    public static GuiFlowStep menuStep(Runnable onShop, Runnable onSettings) {
        Objects.requireNonNull(onShop, "onShop");
        Objects.requireNonNull(onSettings, "onSettings");
        GuiView view = GuiView.chest("選單", 9)
            .button(2, "shop", "商店", click -> onShop.run())
            .button(6, "settings", "設定", click -> onSettings.run())
            .button(8, "deco", click -> {
            })
            .build();
        return GuiFlowStep.labeled("menu", view, Map.of(0, "shop", 1, "settings"));
    }
}
