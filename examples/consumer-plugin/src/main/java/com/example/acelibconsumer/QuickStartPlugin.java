package com.example.acelibconsumer;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.command.CommandCatalog;
import com.smile.acelib.command.CommandDoc;
import com.smile.acelib.form.FormImage;
import com.smile.acelib.message.FormText;
import com.smile.acelib.message.FormTextOptions;
import com.smile.acelib.scheduler.AceLibScheduler;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.TaskScope;
import com.smile.acelib.scheduler.TaskTicket;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 下游 plugin 使用 AceLib 的 Quick Start 範例（正式 provider contract）。
 *
 * <p>重點：</p>
 * <ul>
 *   <li>不 import {@code com.smile.acelib.AceLibPlugin}、不做 unchecked cast；
 *       正式取得入口是 {@code AceLibApi.AceLibProvider}（Bukkit {@code ServicesManager} 註冊）。</li>
 *   <li>{@code depend: [AceLib]} 保證 AceLib 先於本 plugin 載入；但 runtime 仍須
 *       處理 provider missing / not-ready 兩種防禦（AceLib 可能尚未 enable 或已 disable）。</li>
 *   <li>{@code provider.api()} 永不回傳 null；disable 後 {@code isReady()} 為 false，
 *       呼叫端必須檢查後再使用。</li>
 * </ul>
 */
public class QuickStartPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // 1. 經 ServicesManager 取得正式 provider（enable 後註冊、disable 時解除）。
        RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
            getServer().getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);

        // 2. missing / not-ready：registration 為 null（AceLib 尚未 enable 或已 disable）。
        if (registration == null) {
            getLogger().warning("AceLib provider 未註冊（AceLib 尚未啟用？）；停用本 plugin。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. provider.api() 永不回傳 null；但 reload / disable 後可能不 ready。
        AceLibApi api = registration.getProvider().api();
        if (!api.isReady()) {
            getLogger().warning("AceLib 存在但尚未 ready；停用本 plugin 避免半初始化。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("AceLib " + api.getVersion()
            + " on " + api.getPlatform().getDisplayName());

        // 4. 依平台能力決定分支（Folia region 排程 / Paper 全域排程）。
        if (api.getPlatformCapability().regionScheduling()) {
            // Folia 環境：操作實體 / 方塊 / 玩家必須在 region thread 上執行，
            // 使用 AceLib 提供的安全排程 API（本範例僅示意，不實際排程）。
        } else if (api.getPlatformCapability().globalScheduler()) {
            // Paper 環境：可安全使用全域 BukkitScheduler。
        }

        // 5. 只依賴公開 API 使用本版三個新功能（編譯期即證明 Supported 面足夠）。
        demonstrateNewApis(api);

        // 6. 作用域任務群組（本版新 API）：讀取→背景計算→回玩家執行緒回覆。
        //    只用 Supported 型別；SafeSchedulerImpl 等 Internal 型別不可引用。
        SafeScheduler scheduler =
            AceLibScheduler.create(this, api.getPlatform(), api.getPlatformCapability());
        getServer().getOnlinePlayers().stream()
            .findFirst()
            .ifPresent(player -> demonstrateScopedTasks(scheduler, player));
    }

    /**
     * 作用域任務示範：讀取、背景計算、回玩家所在執行緒回覆串成單一流程。
     *
     * <p>玩家退服時流程自動取消（終態 {@code CANCELLED}），回覆不再執行；
     * 終態經由票據通知，呼叫端不阻塞 region 執行緒。</p>
     *
     * @param scheduler 本 plugin 擁有的排程器；不可為 null
     * @param player 作用域擁有者；不可為 null
     */
    private void demonstrateScopedTasks(SafeScheduler scheduler, Player player) {
        TaskScope scope = scheduler.scopeFor(player);
        TaskTicket<String> ticket = scope.pipeline(
            () -> "raw",
            String::toUpperCase,
            upper -> player.sendMessage(Component.text("computed: " + upper)));
        ticket.whenComplete(
            result -> getLogger().info("scoped pipeline settled: " + result.outcome()));
    }

    /**
     * 最小消費示範：外部插件只依賴 Supported API 就能使用
     * {@link FormImage}、{@link FormText}／{@link FormTextOptions}、
     * {@link CommandCatalog} 三者。
     *
     * @param api 已就緒的 AceLib facade；不可為 null
     */
    private void demonstrateNewApis(AceLibApi api) {
        // 表單按鈕圖示：資源包路徑圖示值型別（Cumulus 型別不外洩）。
        FormImage icon = FormImage.path("textures/items/example");

        // 表單安全字串：Adventure Component → 基岩表單可顯示字串。
        String label = FormText.render(
            Component.text("hello"), FormTextOptions.defaults());
        getLogger().info("form label preview: " + label + " (icon=" + icon + ")");

        // 指令目錄：讀快照並以前後 revision 判斷期間有無變動。
        CommandCatalog catalog = api.getCommandCatalog();
        long before = catalog.revision();
        List<CommandDoc> docs = catalog.snapshot();
        long after = catalog.revision();
        if (before != after) {
            getLogger().info("command catalog changed while reading ("
                + before + " -> " + after + "); re-read if a stable view is needed.");
        }
        getLogger().info("command catalog holds " + docs.size() + " entries.");
    }
}
