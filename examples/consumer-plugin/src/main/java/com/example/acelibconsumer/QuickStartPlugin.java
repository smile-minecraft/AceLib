package com.example.acelibconsumer;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.command.Arguments;
import com.smile.acelib.command.BrigadierRegistrar;
import com.smile.acelib.command.CommandArgument;
import com.smile.acelib.command.CommandCatalog;
import com.smile.acelib.command.CommandDoc;
import com.smile.acelib.command.TypedCommand;
import com.smile.acelib.command.TypedSubCommand;
import com.smile.acelib.diagnostics.Clock;
import com.smile.acelib.form.FormImage;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.gui.GuiFlow;
import com.smile.acelib.gui.GuiFlowStep;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiScopes;
import com.smile.acelib.gui.GuiView;
import com.smile.acelib.message.FormText;
import com.smile.acelib.message.FormTextOptions;
import com.smile.acelib.message.MessageLabel;
import com.smile.acelib.message.MessageScope;
import com.smile.acelib.message.MessageScopes;
import com.smile.acelib.message.RenderedMessage;
import com.smile.acelib.scheduler.AceLibScheduler;
import com.smile.acelib.scheduler.SafeScheduler;
import com.smile.acelib.scheduler.TaskScope;
import com.smile.acelib.scheduler.TaskTicket;
import com.smile.acelib.world.LocationSnapshot;
import com.smile.acelib.world.WorldService;
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

    private MessageScope messageScope;
    private GuiScope guiScope;
    private BrigadierRegistrar commandRegistrar;

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

        // 5a. 型別化指令框架：Brigadier 註冊取代 plugin.yml 的 commands 宣告。
        //     註冊必須在 onEnable 期間完成；reload 不重建、不重複註冊。
        registerTypedCommands();

        // 5b. 插件作用域訊息服務：建 scope、升級補 key、共用渲染與顯示標籤。
        //     管理員覆寫檔缺的 key 退回本 JAR 內建 lang/en_US.yml。
        messageScope = MessageScopes.create(this, java.util.Locale.US);
        messageScope.syncBuiltinDefaults();
        demonstrateMessageScope(messageScope);

        // 5c. 插件隔離 GUI 作用域：服務以 supplier 包裝（reload 後自動讀到新實例），
        //     結束只關自己的作用域。公開介面沒有全服務 shutdown。
        AceLibApi guiApi = registration.getProvider().api();
        guiScope = GuiScopes.create(this,
            () -> registration.getProvider().api().getGuiService(),
            Clock.system(),
            guiApi.getBedrockService().forms(),
            guiApi.getBedrockService()::isBedrockPlayer);
        guiScope.onReplaced((uuid, oldSession, newSession) ->
            getLogger().info("gui replaced for " + uuid));
        getServer().getOnlinePlayers().stream()
            .findFirst()
            .ifPresent(player -> demonstrateGuiScope(guiScope, player));

        // 6. 作用域任務群組（本版新 API）：讀取→背景計算→回玩家執行緒回覆。
        //    只用 Supported 型別；SafeSchedulerImpl 等 Internal 型別不可引用。
        SafeScheduler scheduler =
            AceLibScheduler.create(this, api.getPlatform(), api.getPlatformCapability());
        WorldService world = api.getWorldService();
        getServer().getOnlinePlayers().stream()
            .findFirst()
            .ifPresent(player -> {
                demonstrateScopedTasks(scheduler, player);
                demonstrateDeferredOperations(scheduler, world, player);
            });
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
     * 事件處理後的延後操作示範（本版新 API）。
     *
     * <p>取消移動事件後不要在事件處理內直接傳送（位置可能被還原）；
     * 延後傳送排到之後的 tick 並確認到達，未到達時回報失敗而非謊報成功。
     * 為避免範例在啟用時搬動玩家，此處以玩家目前位置為目標
     * （原地傳送仍完整走過延後派送與到達確認）。</p>
     *
     * @param scheduler 本 plugin 擁有的排程器；不可為 null
     * @param world 世界操作服務；不可為 null
     * @param player 作用域擁有者；不可為 null
     */
    private void demonstrateDeferredOperations(SafeScheduler scheduler,
                                               WorldService world,
                                               Player player) {
        LocationSnapshot stay = LocationSnapshot.of(
            player.getWorld().getUID(),
            player.getLocation().getBlockX(),
            player.getLocation().getBlockY(),
            player.getLocation().getBlockZ());
        world.teleportPlayerDeferred(player.getUniqueId(), stay, false, scheduler)
            .thenAccept(result -> {
                if (result.isSuccess()) {
                    getLogger().info("deferred teleport arrived");
                } else {
                    getLogger().warning("deferred teleport did not arrive: "
                        + result.detail());
                }
            });
        TaskTicket<String> ticket = world.deferForPlayer(
            player.getUniqueId(), () -> "done", scheduler);
        ticket.whenComplete(done ->
            getLogger().info("deferred action settled: " + done.outcome()));
    }

    /**
     * 插件作用域訊息示範：一次渲染同時餵聊天、GUI 與表單；按鈕顯示文字與
     * 程式識別字分離（分支永遠比對 {@code id}，不比對顯示文字）。
     *
     * @param scope 本 plugin 的訊息作用域；不可為 null
     */
    private void demonstrateMessageScope(MessageScope scope) {
        RenderedMessage rendered = scope.render("greeting", java.util.Map.of("player", "world"));
        getLogger().info("chat preview: " + rendered.text());
        getLogger().info("form preview: " + rendered.formText());

        MessageLabel confirm = scope.label("confirm", "button.confirm", java.util.Map.of());
        getLogger().info("button [" + confirm.id() + "] displays: " + confirm.text());
    }

    /**
     * 型別化指令註冊示範：不需要 {@code plugin.yml} 的 {@code commands} 宣告。
     *
     * <p>固定選項（{@code mode}）在 Brigadier 樹中編譯為 literal 分支，是基岩版
     * 唯一看得見補全的結構；開放式引數則送 vanilla 型別給客戶端先行驗證。
     * handler 以引數實例取值，不碰原始字串。</p>
     *
     * <p>註冊只在 {@code onEnable} 呼叫一次：平台在 plugin disable 時移除節點，
     * reload 不重建（handler 讀的狀態應自行以 supplier 取得最新）。</p>
     */
    private void registerTypedCommands() {
        BrigadierRegistrar registrar = new BrigadierRegistrar(this);

        CommandArgument<org.bukkit.Material> itemArg = Arguments.material("item");
        CommandArgument<Integer> amountArg = Arguments.intArg("amount", 1, 64);
        CommandArgument<String> modeArg = Arguments.fixed("mode", "buy", "sell");

        TypedCommand shop = TypedCommand.builder("shop")
            .description("商店指令")
            .usage("/shop trade <item> <amount:1-64> <mode:buy|sell>")
            .permission("shop.use")
            .aliases("s")
            .subcommand(TypedSubCommand.builder("trade")
                .description("買賣物品")
                .cooldownMillis(1_000L)
                .argument(itemArg)
                .argument(amountArg)
                .argument(modeArg)
                .executes(ctx -> getLogger().info("trade "
                    + ctx.get(modeArg) + " " + ctx.get(amountArg) + "x "
                    + ctx.get(itemArg)))
                .build())
            .build();

        registrar.register(shop);
        this.commandRegistrar = registrar;
        getLogger().info("registered typed commands: "
            + registrar.getRegisteredCommands().size());
    }

    @Override
    public void onDisable() {
        // 停用：內部 registry 標記 disabled 並清空；平台側節點由平台移除。
        if (commandRegistrar != null) {
            commandRegistrar.shutdown();
            commandRegistrar = null;
        }
        if (messageScope != null) {
            messageScope.close();
            messageScope = null;
        }
        GuiScopes.close(this);
        guiScope = null;
    }

    /**
     * 插件隔離 GUI 示範：預設全擋的視圖、按鈕回呼與冷卻、跨呈現共用流程。
     *
     * <p>只用 Supported 型別；`GuiServiceControl` 等 Internal 型別不可引用。
     * 結束 GUI 請關自己的作用域（見 {@code onDisable}），不要找全服務 shutdown。</p>
     *
     * @param gui 本 plugin 的 GUI 作用域；不可為 null
     * @param player 作用域擁有者；不可為 null
     */
    private void demonstrateGuiScope(GuiScope gui, Player player) {
        GuiView shop = GuiView.chest("商店", 27)
            .allow(10, 11, 12)
            .button(13, "buy", 5_000L,
                click -> getLogger().info("buy pressed by " + click.playerUuid()))
            .build();
        gui.openView(player.getUniqueId(), shop);

        GuiFlow flow = GuiFlow.of(java.util.List.of(
            new GuiFlowStep("menu", shop,
                FormSpec.simple("選單").content("請選擇").button("商店").build(),
                java.util.Map.of(0, "shop")),
            new GuiFlowStep("shop", shop,
                FormSpec.simple("商店").content("買賣").button("買").button("賣").build())),
            "menu",
            uuid -> getLogger().info("flow finished for " + uuid));
        gui.openFlow(player.getUniqueId(), flow);
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
