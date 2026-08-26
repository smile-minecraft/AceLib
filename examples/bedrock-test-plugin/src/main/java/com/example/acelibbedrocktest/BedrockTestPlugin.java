package com.example.acelibbedrocktest;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.bedrock.BedrockPlayerInfo;
import com.smile.acelib.bedrock.BedrockService;
import com.smile.acelib.external.IntegrationProbeResult;
import com.smile.acelib.form.FormResponse;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormService;
import com.smile.acelib.form.FormSpec;
import com.smile.acelib.form.FormValue;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 基岩功能驗收工具：部署到 Folia 測試服，提供兩個診斷指令。
 *
 * <p>{@code /btest info} 印出 floodgate 整合探測結果（status + reason）、
 * bedrock 模組狀態，以及呼叫者本人的基岩玩家判定與資訊——reason 字串是
 * 排查「整合呈現失敗」的關鍵證據。{@code /btest form <simple|modal|custom>}
 * 以三參數 {@code sendForm(uuid, spec, consumer)} 送出表單，consumer 記錄
 * 回應抵達的執行緒名稱與內容，用來驗證回應派送是否落在玩家 region context。</p>
 *
 * <p>取得 {@link AceLibApi} 的方式與 consumer fixture 相同：
 * 經 Bukkit {@code ServicesManager} 取得正式 provider contract，
 * 不依賴 AceLib 內部型別。Folia 上玩家指令在玩家 region 執行緒執行，
 * 可直接呼叫 sendForm，不需額外排程。</p>
 */
public final class BedrockTestPlugin extends JavaPlugin implements CommandExecutor {

    private static final String USAGE = "/btest <info | form <simple|modal|custom>>";

    private AceLibApi api;

    @Override
    public void onEnable() {
        // depend: [AceLib] 保證載入順序，但 runtime 仍須防禦 provider missing / not-ready
        // （AceLib 可能尚未 enable 或已 disable）。
        RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
            getServer().getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);
        if (registration == null) {
            getLogger().warning("AceLib provider 未註冊（AceLib 尚未啟用？）；停用本 plugin。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        AceLibApi resolved = registration.getProvider().api();
        if (!resolved.isReady()) {
            getLogger().warning("AceLib 存在但尚未 ready；停用本 plugin 避免半初始化。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.api = resolved;
        getLogger().info("AceLib " + api.getVersion() + " on "
            + api.getPlatform().getDisplayName() + "；/btest 可用。");

        PluginCommand command = getCommand("btest");
        if (command == null) {
            // plugin.yml 缺少 btest 定義時才會發生；屬部署錯誤，必須可見。
            getLogger().severe("plugin.yml 未定義 btest 指令；停用本 plugin。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            report(sender, USAGE);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "info" -> handleInfo(sender);
            case "form" -> handleForm(sender, args);
            default -> report(sender, "未知子指令。" + USAGE);
        }
        return true;
    }

    /**
     * /btest info：印出整合探測、模組狀態與呼叫者基岩資訊。
     *
     * <p>console 也可執行：此時印出整合探測與模組狀態，略過呼叫者本人的
     * 基岩資訊查詢（該部分需要玩家 UUID）。</p>
     */
    private void handleInfo(CommandSender sender) {
        report(sender, "== /btest info ==");
        report(sender, "platform: " + api.getPlatform().getDisplayName()
            + ", aceLib ready: " + api.isReady());

        IntegrationProbeResult probe = api.getExternalIntegrationService().getStatus("floodgate");
        report(sender, "[floodgate] status=" + probe.status());
        report(sender, "[floodgate] reason=" + probe.reason());

        BedrockService bedrock = api.getBedrockService();
        if (bedrock == null) {
            report(sender, "[bedrock] service=null（此 AceLib 版本未接線 bedrock facade）");
            return;
        }
        report(sender, "[bedrock] moduleStatus=" + bedrock.getModuleStatus());

        if (!(sender instanceof Player player)) {
            report(sender, "[player] console 執行：略過呼叫者基岩資訊（此段需由玩家執行）");
            return;
        }

        UUID playerId = player.getUniqueId();
        boolean isBedrock = bedrock.isBedrockPlayer(playerId);
        report(player, "[player] uuid=" + playerId + ", isBedrockPlayer=" + isBedrock);

        Optional<BedrockPlayerInfo> info = bedrock.getPlayerInfo(playerId);
        if (info.isEmpty()) {
            report(player, "[player] getPlayerInfo=empty（非基岩玩家或上游查無資料）");
            return;
        }
        BedrockPlayerInfo bedrockInfo = info.get();
        report(player, "[player] username=" + bedrockInfo.username()
            + ", deviceOs=" + bedrockInfo.deviceOs()
            + ", inputMode=" + bedrockInfo.inputMode());
        report(player, "[player] languageCode=" + bedrockInfo.languageCode()
            + ", linkState=" + bedrockInfo.linkState()
            + ", linkedUsername=" + bedrockInfo.linkedUsername());
    }

    /**
     * /btest form &lt;simple|modal|custom&gt;：送出對應表單並註冊回應 consumer。
     */
    private void handleForm(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            report(sender, "此子指令需要玩家執行（表單只能送給線上玩家）。");
            return;
        }
        if (args.length < 2) {
            report(sender, "缺少表單種類。/btest form <simple|modal|custom>");
            return;
        }

        FormSpec spec;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "simple" -> spec = buildSimpleSpec();
            case "modal" -> spec = buildModalSpec();
            case "custom" -> spec = buildCustomSpec();
            default -> {
                report(sender, "未知表單種類：" + args[1] + "（可用：simple | modal | custom）");
                return;
            }
        }

        BedrockService bedrock = api.getBedrockService();
        if (bedrock == null) {
            report(player, "[form] bedrock service=null，無法送出表單。");
            return;
        }
        FormService forms = bedrock.forms();

        try {
            FormSendResult result = forms.sendForm(player.getUniqueId(), spec, response ->
                logFormResponse(player, spec.kind(), response));
            report(player, "[form] kind=" + spec.kind() + ", sendResult=" + result
                + (result.isSent() ? "（等待玩家回應）" : "（Floodgate 拒絕遞送，consumer 不會被呼叫）"));
        } catch (IllegalStateException e) {
            // ACELIB-FORM-001（傳輸層未啟用）/ ACELIB-FORM-002（已 shutdown）
            // 是 sendForm 的明確拒絕路徑：如實回報訊息（含錯誤代碼），不吞掉。
            getLogger().severe("[form] sendForm 被拒絕: " + e.getMessage());
            report(player, "[form] sendForm 被拒絕：" + e.getMessage());
        }
    }

    /**
     * 回應 consumer：記錄抵達執行緒、狀態與內容。
     *
     * <p>派送契約保證 consumer 在玩家所屬 region context 執行且最多一次；
     * 對同一玩家 sendMessage 屬 region 安全操作。</p>
     */
    private void logFormResponse(Player player, FormSpec.Kind kind, FormResponse response) {
        String threadName = Thread.currentThread().getName();
        String line = "[form-response] kind=" + kind
            + ", thread=" + threadName
            + ", status=" + response.status()
            + ", clickedButton=" + response.clickedButton()
            + ", values=" + describeValues(response.values());
        getLogger().info(line);
        player.sendMessage(line);
    }

    /** 窮舉 sealed {@link FormValue} 四種答案型別。 */
    private static String describeValues(List<FormValue> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(describeValue(values.get(i)));
        }
        return sb.append("]").toString();
    }

    private static String describeValue(FormValue value) {
        return switch (value) {
            case FormValue.Text t -> "Text(" + t.value() + ")";
            case FormValue.Option o -> "Option(" + o.index() + ")";
            case FormValue.Number n -> "Number(" + n.value() + ")";
            case FormValue.Switch s -> "Switch(" + s.on() + ")";
        };
    }

    private static FormSpec buildSimpleSpec() {
        return FormSpec.simple("AceLib 測試：Simple 表單")
            .content("點選任一按鈕，回應會記錄到 server log。")
            .button("按鈕 A")
            .button("按鈕 B")
            .button("關閉（觸發 CLOSED）")
            .build();
    }

    private static FormSpec buildModalSpec() {
        return FormSpec.modal("AceLib 測試：Modal 表單")
            .content("這是是／否確認對話框。")
            .button1("是")
            .button2("否")
            .build();
    }

    private static FormSpec buildCustomSpec() {
        return FormSpec.custom("AceLib 測試：Custom 表單")
            .label("涵蓋 input / dropdown / slider / toggle 四種元件。")
            .input("暱稱", "輸入文字…", "")
            .dropdown("偏好顏色", List.of("紅", "綠", "藍"), 0)
            .slider("音量", 0f, 100f, 5f, 50f)
            .toggle("啟用通知", false)
            .build();
    }

    /** 同步輸出到呼叫者 chat 與 server log，方便真人驗收時對照。 */
    private void report(CommandSender sender, String line) {
        sender.sendMessage(line);
        getLogger().info(line);
    }
}
