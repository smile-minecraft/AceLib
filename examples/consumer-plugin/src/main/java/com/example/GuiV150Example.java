package com.example;

import com.smile.acelib.gui.GuiMask;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiView;
import java.util.Objects;
import java.util.UUID;
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
}
