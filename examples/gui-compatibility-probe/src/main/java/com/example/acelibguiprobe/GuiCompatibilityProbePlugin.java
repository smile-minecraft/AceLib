package com.example.acelibguiprobe;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.diagnostics.Clock;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.gui.GuiFlow;
import com.smile.acelib.gui.GuiFlowStep;
import com.smile.acelib.gui.GuiInputPrompt;
import com.smile.acelib.gui.GuiResult;
import com.smile.acelib.gui.GuiScope;
import com.smile.acelib.gui.GuiScopes;
import com.smile.acelib.gui.GuiView;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * GUI 相容性探針：部署到 Folia 測試服後，用 {@code /gprobe} 指令把固定探針案例
 * 經 AceLib {@code GuiScope}（插件隔離 handle）開啟給玩家，供真人驗證五項行為。
 *
 * <p>指令：</p>
 * <ul>
 *   <li>{@code /gprobe list}：列出所有案例 id 與說明（不執行）。</li>
 *   <li>{@code /gprobe send [player] [caseId]}：對目標玩家執行案例；
 *       未給識別碼時只執行導航案例（表單為彈出式、輸入會搶聊天，逐案執行才利於觀察）。</li>
 *   <li>{@code /gprobe confirm}：消耗自己待處理的一次性票券（重複執行用來觀察
 *       ACTION_ALREADY_RESOLVED）。</li>
 *   <li>{@code /gprobe back}／{@code /gprobe close}：對自己的 session 執行返回／關閉。</li>
 * </ul>
 *
 * <p>可觀察性：所有關鍵結果都以 {@code [gprobe-...]} 前綴寫入 server log：</p>
 * <ul>
 *   <li>{@code [gprobe-nav]}：每步導航的 result state。</li>
 *   <li>{@code [gprobe-ticket]}：票券簽發、callback 觸發、重複消耗的拒絕。</li>
 *   <li>{@code [gprobe-input]}：聊天輸入的接收結果。</li>
 *   <li>{@code [gprobe-cooldown]}：冷卻按鈕的回呼次數與拒絕。</li>
 *   <li>{@code [gprobe-form]}：基岩表單的發送結果。</li>
 * </ul>
 *
 * <p>安全約束：</p>
 * <ul>
 *   <li>所有案例皆為固定、無破壞性內容；按鈕回呼只記錄 log，不觸發刪除、
 *       付款或外部訊息等不可逆操作。</li>
 *   <li>本 plugin 只使用 AceLib 公開 Supported API，不碰 internal package；
 *       作用域在 {@code onEnable} 建立、{@code onDisable} 關閉。</li>
 *   <li>AceLib 未就緒（provider 缺席或 {@code isReady()} 為 false）時如實回報
 *       並拒絕執行，不靜默略過。</li>
 * </ul>
 */
public final class GuiCompatibilityProbePlugin extends JavaPlugin implements CommandExecutor {

    private static final String USAGE = "/gprobe <list | send [player] [caseId] | confirm | back | close>";

    /** 待處理的一次性票券（玩家 → 票券資訊），供 /gprobe confirm 消耗。 */
    private final Map<UUID, PendingTicket> pendingTickets = new ConcurrentHashMap<>();

    /** 冷卻按鈕的實際回呼次數（玩家 → 次數），用來斷言冷卻擋下重複點擊。 */
    private final Map<UUID, AtomicInteger> cooldownHits = new ConcurrentHashMap<>();

    private volatile GuiScope scope;

    @Override
    public void onEnable() {
        var command = getCommand("gprobe");
        if (command == null) {
            getLogger().severe("plugin.yml 未定義 gprobe 指令；停用本 plugin。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(this);
        getLogger().info("AceLib GUI 相容性探針已啟用；/gprobe 可用（作用域待首次執行時建立）。");
    }

    @Override
    public void onDisable() {
        pendingTickets.clear();
        GuiScopes.close(this);
        scope = null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            report(sender, USAGE);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> handleList(sender);
            case "send" -> handleSend(sender, args);
            case "confirm" -> handleConfirm(sender);
            case "back" -> handleBack(sender);
            case "close" -> handleClose(sender);
            default -> report(sender, "未知子指令。" + USAGE);
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        List<GuiProbeCase> catalog = GuiProbeCases.buildCatalog();
        report(sender, "== /gprobe list（共 " + catalog.size() + " 個案例）==");
        for (GuiProbeCase c : catalog) {
            report(sender, "[" + c.id() + "] " + c.description());
        }
    }

    /** 取得可用作用域；AceLib 未就緒時回報並回傳 null。 */
    private GuiScope requireScope(CommandSender sender) {
        GuiScope current = scope;
        if (current != null && !current.isClosed()) {
            return current;
        }
        var registration = getServer().getServicesManager()
            .getRegistration(AceLibApi.AceLibProvider.class);
        if (registration == null) {
            report(sender, "AceLib provider 尚未註冊；請確認 AceLib 已啟用後再執行。");
            return null;
        }
        AceLibApi api = registration.getProvider().api();
        if (!api.isReady()) {
            report(sender, "AceLib 尚未就緒（isReady 為 false）；拒絕執行。");
            return null;
        }
        // 五參數接線（啟用基岩呈現）：forms 與 bedrockProbe 同進退；
        // GuiService 以 supplier 每次重讀 provider（reload 後仍拿到新服務）。
        // 二參數多載是純 Java 呈現（forms／bedrockProbe 皆 null），基岩玩家會
        // 退回箱型 GUI——flow 案例要走原生表單，必須用此接線。
        BedrockService bedrock = api.getBedrockService();
        current = GuiScopes.create(this,
            () -> registration.getProvider().api().getGuiService(),
            Clock.system(), bedrock.forms(), bedrock::isBedrockPlayer);
        scope = current;
        return current;
    }

    private void handleSend(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = getServer().getPlayer(args[1]);
            if (target == null) {
                report(sender, "找不到線上玩家：" + args[1] + "（目標必須已登入）");
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            report(sender, "console 執行 send 必須指定線上玩家：/gprobe send <player> [caseId]");
            return;
        }
        GuiScope active = requireScope(sender);
        if (active == null) {
            return;
        }
        List<GuiProbeCase> catalog = GuiProbeCases.buildCatalog();
        List<GuiProbeCase> selected;
        try {
            selected = GuiProbeSendSelection.selectCases(catalog, args.length >= 3 ? args[2] : "nav");
        } catch (IllegalArgumentException ex) {
            report(sender, ex.getMessage());
            return;
        }
        for (GuiProbeCase c : selected) {
            runCase(active, sender, target, c.id());
        }
    }

    private void runCase(GuiScope active, CommandSender sender, Player target, String caseId) {
        switch (caseId) {
            case "nav" -> runNavCase(active, target);
            case "ticket" -> runTicketCase(active, target, sender);
            case "input" -> runInputCase(active, target);
            case "cooldown" -> runCooldownCase(active, target);
            case "form" -> runFormCase(sender, target);
            case "flow" -> runFlowCase(active, target);
            default -> report(sender, "未實作的案例：" + caseId);
        }
    }

    /** 導航案例：三層箱型視圖，按鈕分別走 push／replace／back／close。 */
    private void runNavCase(GuiScope active, Player target) {
        UUID uuid = target.getUniqueId();
        GuiView third = GuiView.chest("探針第三層（replace 而來）", 27)
            .button(11, "back-btn", click -> logNav(active.back(uuid)))
            .button(15, "close-btn", click -> logNav(active.close(uuid)))
            .build();
        GuiView second = GuiView.chest("探針第二層（push 而來）", 27)
            .button(11, "replace-btn", click -> logNav(active.replaceView(uuid, third)))
            .button(15, "back-btn", click -> logNav(active.back(uuid)))
            .build();
        GuiView root = GuiView.chest("探針主畫面（open 而來）", 27)
            .button(13, "next-btn", click -> logNav(active.pushView(uuid, second)))
            .build();
        GuiResult result = active.openView(uuid, root);
        getLogger().info("[gprobe-nav] open state=" + result.state()
            + " detail=" + result.detail());
        target.sendMessage("導航探針已開啟：點中央按鈕 push 第二層，再試 replace／back／close。");
    }

    private void logNav(GuiResult result) {
        getLogger().info("[gprobe-nav] step state=" + result.state()
            + " detail=" + result.detail());
    }

    /** 票券案例：簽發一次性確認票券；確認鈕或 /gprobe confirm 消耗。 */
    private void runTicketCase(GuiScope active, Player target, CommandSender sender) {
        UUID uuid = target.getUniqueId();
        GuiView view = GuiView.chest("探針確認畫面", 27)
            .button(13, "confirm-btn", click -> consumeTicket(active, uuid))
            .build();
        GuiResult opened = active.openView(uuid, view);
        if (!opened.isSuccess() && !opened.isAccepted()) {
            report(sender, "開啟確認畫面失敗：" + opened.state() + " " + opened.detail());
            return;
        }
        long generation = opened.session().generation();
        GuiResult issued = active.createConfirmation(uuid, generation, "gprobe-delete",
            () -> getLogger().info("[gprobe-ticket] callback=FIRED player=" + target.getName()));
        if (issued.confirmation() == null) {
            report(sender, "簽發票券失敗：" + issued.state() + " " + issued.detail());
            return;
        }
        String token = issued.confirmation().actionToken();
        pendingTickets.put(uuid, new PendingTicket(generation, token));
        getLogger().info("[gprobe-ticket] issued player=" + target.getName()
            + " action=gprobe-delete");
        target.sendMessage("票券已簽發：點中央按鈕或執行 /gprobe confirm 消耗；再執行一次觀察拒絕。");
    }

    private void consumeTicket(GuiScope active, UUID uuid) {
        PendingTicket pending = pendingTickets.get(uuid);
        if (pending == null) {
            getLogger().info("[gprobe-ticket] consume=NONE（無待處理票券）");
            return;
        }
        GuiResult result = active.confirm(uuid, pending.generation(), pending.token());
        getLogger().info("[gprobe-ticket] consume state=" + result.state()
            + " code=" + result.errorCode() + " detail=" + result.detail());
        if (result.isSuccess() || result.isAccepted()) {
            pendingTickets.remove(uuid);
        }
    }

    private void handleConfirm(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            report(sender, "console 沒有待處理票券；請以玩家身分執行 /gprobe confirm。");
            return;
        }
        GuiScope active = requireScope(sender);
        if (active == null) {
            return;
        }
        consumeTicket(active, player.getUniqueId());
        // 保留票券以便再次執行觀察一次性語意；成功消耗才移除（見 consumeTicket）。
        report(sender, "已嘗試消耗票券，結果見 server log [gprobe-ticket]。");
    }

    /** 輸入案例：聊天提示；輸入被消耗不外流，結果寫 log。 */
    private void runInputCase(GuiScope active, Player target) {
        UUID uuid = target.getUniqueId();
        GuiView view = GuiView.chest("探針輸入畫面", 27).build();
        GuiResult opened = active.openView(uuid, view);
        if (!opened.isSuccess() && !opened.isAccepted()) {
            target.sendMessage("開啟輸入畫面失敗：" + opened.state());
            return;
        }
        long generation = opened.session().generation();
        try {
            active.promptChat(uuid, generation,
                GuiInputPrompt.chat("請在聊天欄輸入任意文字（將被探針消耗，不會公開發送）", 32, 60_000L),
                result -> getLogger().info("[gprobe-input] received kind=" + result.kind()
                    + " player=" + target.getName()));
        } catch (RuntimeException ex) {
            // 不吞錯：如實回報（含例外訊息），由觀察者判斷。
            getLogger().severe("[gprobe-input] prompt 失敗: " + ex.getMessage());
            target.sendMessage("輸入提示建立失敗：" + ex.getMessage());
            return;
        }
        target.sendMessage("請在聊天欄輸入任意文字；正常聊天（無提示時）不受影響。");
    }

    /** 冷卻案例：5 秒冷卻按鈕；快速連點應只觸發一次回呼。 */
    private void runCooldownCase(GuiScope active, Player target) {
        UUID uuid = target.getUniqueId();
        cooldownHits.put(uuid, new AtomicInteger());
        GuiView view = GuiView.chest("探針冷卻畫面（連點測試）", 27)
            .button(13, "cooldown-btn", 5_000L, click -> {
                int hits = cooldownHits.get(uuid).incrementAndGet();
                getLogger().info("[gprobe-cooldown] callback hits=" + hits
                    + " player=" + target.getName());
            })
            .build();
        GuiResult opened = active.openView(uuid, view);
        getLogger().info("[gprobe-cooldown] open state=" + opened.state());
        target.sendMessage("請快速連點中央按鈕：回呼應只執行一次，多餘點擊回 COOLDOWN_ACTIVE（見 log）。");
    }

    /** 共用流程案例：同一份 GuiFlow 雙呈現；Java 按鈕 goTo、基岩表單 transitions 推進。 */
    private void runFlowCase(GuiScope active, Player target) {
        UUID uuid = target.getUniqueId();
        GuiView secondView = GuiView.chest("探針流程·第二步", 27)
            .button(13, "done-btn", click -> {
                GuiResult result = active.close(uuid);
                getLogger().info("[gprobe-flow] done state=" + result.state()
                    + " player=" + target.getName());
            })
            .build();
        GuiView firstView = GuiView.chest("探針流程·第一步", 27)
            .button(13, "next-btn", click -> {
                GuiResult result = active.goTo(uuid, "confirm-step");
                getLogger().info("[gprobe-flow] step=confirm-step state=" + result.state()
                    + " detail=" + result.detail());
            })
            .build();
        GuiFlow flow = GuiFlow.of(List.of(
                new GuiFlowStep("intro", firstView,
                    FormSpec.simple("探針流程第一步")
                        .content("同一份 GuiFlow 的基岩呈現；按鈕應推進到第二步。")
                        .button("下一步")
                        .build(),
                    Map.of(0, "confirm-step")),
                new GuiFlowStep("confirm-step", secondView,
                    FormSpec.simple("探針流程第二步")
                        .content("最後一步；按鈕應結束流程並觸發完成回呼。")
                        .button("完成")
                        .build())),
            "intro",
            done -> getLogger().info("[gprobe-flow] complete player=" + target.getName()));
        GuiResult opened = active.openFlow(uuid, flow);
        getLogger().info("[gprobe-flow] open state=" + opened.state()
            + " detail=" + opened.detail() + " player=" + target.getName());
        if (!opened.isSuccess() && !opened.isAccepted()) {
            target.sendMessage("流程開啟失敗：" + opened.state());
            return;
        }
        target.sendMessage("流程探針已開啟：Java 點中央按鈕推進；基岩看原生表單按鈕推進。");
    }

    /** 表單案例：經共用流程發送固定 simple 表單（Bedrock 觀察用）。 */
    private void runFormCase(CommandSender sender, Player target) {
        var registration = getServer().getServicesManager()
            .getRegistration(AceLibApi.AceLibProvider.class);
        if (registration == null || !registration.getProvider().api().isReady()) {
            report(sender, "AceLib 未就緒；拒絕發送表單。");
            return;
        }
        AceLibApi api = registration.getProvider().api();
        FormSpec spec = FormSpec.simple("探針：GUI 共用流程表單")
            .content("經 GuiScope 共用流程發送；Java 端應不受影響。")
            .button("第一顆")
            .button("第二顆")
            .build();
        try {
            FormSendResult result = api.getBedrockService().forms().sendForm(
                target.getUniqueId(), spec,
                response -> getLogger().info("[gprobe-form-response] player="
                    + target.getName() + " " + response));
            getLogger().info("[gprobe-form] target=" + target.getName()
                + " buttons=2 result=" + result);
        } catch (RuntimeException ex) {
            // 不吞錯：如實回報發送失敗（含例外訊息），由觀察者判斷。
            getLogger().severe("[gprobe-form] 發送失敗: " + ex.getMessage());
            report(sender, "表單發送失敗：" + ex.getMessage());
            return;
        }
        report(sender, "表單已發送給 " + target.getName()
            + "；Bedrock 客戶端觀察彈窗，Java 端應不受影響。");
    }

    private void handleBack(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            report(sender, "console 沒有 GUI session；請以玩家身分執行。");
            return;
        }
        GuiScope active = requireScope(sender);
        if (active == null) {
            return;
        }
        GuiResult result = active.back(player.getUniqueId());
        getLogger().info("[gprobe-nav] back state=" + result.state());
        report(sender, "back 結果：" + result.state() + "（詳見 log [gprobe-nav]）。");
    }

    private void handleClose(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            report(sender, "console 沒有 GUI session；請以玩家身分執行。");
            return;
        }
        GuiScope active = requireScope(sender);
        if (active == null) {
            return;
        }
        GuiResult result = active.close(player.getUniqueId());
        pendingTickets.remove(player.getUniqueId());
        getLogger().info("[gprobe-nav] close state=" + result.state());
        report(sender, "close 結果：" + result.state() + "（詳見 log [gprobe-nav]）。");
    }

    /** 同步輸出到呼叫者與 server log，方便真人驗收時對照。 */
    private void report(CommandSender sender, String line) {
        sender.sendMessage(line);
        getLogger().info(line);
    }

    /** 待處理的一次性票券資訊。 */
    private record PendingTicket(long generation, String token) {
    }
}
