package com.example.acelibformprobe;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.form.FormSendResult;
import com.smile.acelib.form.FormSpec;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 表單相容性探針：部署到 Folia 測試服後，提供兩個指令把固定 FormSpec 案例
 * 經 AceLib {@code FormService} 發送給玩家，供真人用 Bedrock（經 Geyser）
 * 客戶端觀察轉換結果。
 *
 * <p>指令：</p>
 * <ul>
 *   <li>{@code /fprobe list}：列出所有案例 id 與說明（不發送）。</li>
 *   <li>{@code /fprobe send [player]}：把全部案例依序發送給執令者本人；若給定
 *       {@code player} 則改發送給該線上玩家（可用來對準基岩玩家觀察 Geyser 轉換）。</li>
 *   <li>{@code /fprobe send <player> <caseId>}：只發送指定識別碼的那一個案例；
 *       識別碼不分大小寫、前後空白忽略，未知時回報可用清單且不發送。
 *       表單為彈出式介面，逐案發送才利於截圖觀察。</li>
 * </ul>
 *
 * <p>可觀察性：</p>
 * <ul>
 *   <li>每個案例發送前，伺服器 log 會寫入
 *       {@code [fprobe-send] case=<id> target=<name> kind=<SIMPLE|MODAL|CUSTOM>
 *       buttons=<n> result=<SENT|REJECTED>}（custom 表單以
 *       {@code components=<n>} 代替 {@code buttons}，記錄元件總數），
 *       作為「已送出」的伺服器端證據（不等同客戶端渲染觀察）。</li>
 *   <li>玩家每次回應（點擊／送出／關閉），伺服器 log 會寫入
 *       {@code [fprobe-response] case=<id> player=<name> <response>}，
 *       其中 simple 表單的被點按鈕索引可直接對照案例的按鈕順序，
 *       modal 表單的被點按鈕索引為 0（第一顆）或 1（第二顆），
 *       custom 表單的各元件答案依產值元件順序排列（label 不產值、不佔位），
 *       供真人驗證「點擊索引與按鈕順序的對應」與「元件答案順序」。</li>
 *   <li>{@code SENT} 只代表 Floodgate 已接受遞送，不代表玩家已開啟或已回應；
 *       回應以 {@code [fprobe-response]} 為準，關閉／無效回應不解讀為玩家意圖。</li>
 * </ul>
 *
 * <p>安全約束：</p>
 * <ul>
 *   <li>所有案例皆為固定、無破壞性內容；點擊不觸發刪除、重建世界、付款或
 *       外部訊息等不可逆操作（consumer 只記錄 log）。</li>
 *   <li>本 plugin 只使用 AceLib 公開 Supported API，不碰 internal package。</li>
 *   <li>AceLib 未就緒（provider 缺席或 {@code isReady()} 為 false）時如實回報
 *       並拒絕發送，不靜默略過。</li>
 * </ul>
 */
public final class FormCompatibilityProbePlugin extends JavaPlugin implements CommandExecutor {

    private static final String USAGE = "/fprobe <list | send [player] [caseId]>";

    @Override
    public void onEnable() {
        var command = getCommand("fprobe");
        if (command == null) {
            getLogger().severe("plugin.yml 未定義 fprobe 指令；停用本 plugin。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        command.setExecutor(this);
        getLogger().info("AceLib 表單相容性探針已啟用；/fprobe 可用。");
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
            default -> report(sender, "未知子指令。" + USAGE);
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        List<FormProbeCase> catalog = FormProbeCases.buildCatalog();
        report(sender, "== /fprobe list（共 " + catalog.size() + " 個案例）==");
        for (FormProbeCase c : catalog) {
            report(sender, "[" + c.id() + "] " + c.description());
            report(sender, "    預期觀察：" + c.expectation());
        }
    }

    private AceLibApi requireApi(CommandSender sender) {
        var registration = getServer().getServicesManager()
            .getRegistration(AceLibApi.AceLibProvider.class);
        if (registration == null) {
            report(sender, "AceLib provider 尚未註冊；請確認 AceLib 已啟用後再發送。");
            return null;
        }
        AceLibApi api = registration.getProvider().api();
        if (!api.isReady()) {
            report(sender, "AceLib 尚未就緒（isReady 為 false）；拒絕發送。");
            return null;
        }
        return api;
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
            report(sender, "console 執行 send 必須指定線上玩家：/fprobe send <player>");
            return;
        }

        AceLibApi api = requireApi(sender);
        if (api == null) {
            return;
        }

        List<FormProbeCase> catalog = FormProbeCases.buildCatalog();
        // 先做案例選擇：識別碼未知時直接回報並返回，任何發送都發生在選擇成功之後。
        List<FormProbeCase> selected;
        try {
            selected = FormProbeSendSelection.selectCases(catalog, args.length >= 3 ? args[2] : null);
        } catch (IllegalArgumentException ex) {
            report(sender, ex.getMessage());
            return;
        }
        report(sender, "== /fprobe send → " + target.getName()
            + "（共 " + selected.size() + " 個案例）==");
        for (FormProbeCase c : selected) {
            FormSpec spec;
            try {
                spec = c.buildSpec();
            } catch (RuntimeException ex) {
                // 不吞錯：如實回報建構失敗（含例外訊息），由觀察者判斷。
                getLogger().severe("[fprobe-send] case=" + c.id()
                    + " 建構失敗: " + ex.getMessage());
                report(sender, "[fprobe-send] case=" + c.id() + " 建構失敗：" + ex.getMessage());
                continue;
            }
            int buttons = spec instanceof FormSpec.Simple simple ? simple.buttons().size() : 0;
            int components = spec instanceof FormSpec.Custom custom ? custom.components().size() : 0;
            if (spec instanceof FormSpec.Modal) {
                buttons = 2;
            }
            try {
                FormSendResult result = api.getBedrockService().forms().sendForm(
                    target.getUniqueId(), spec,
                    response -> getLogger().info("[fprobe-response] case=" + c.id()
                        + " player=" + target.getName() + " " + response));
                // 伺服器端證據：記錄 case id、目標、表單種類與發送結果，確認已送出。
                // simple／modal 記按鈕數，custom 記元件總數（label 含在內，順序觀察用）。
                String sizeEvidence = spec instanceof FormSpec.Custom
                    ? "components=" + components
                    : "buttons=" + buttons;
                getLogger().info("[fprobe-send] case=" + c.id()
                    + " target=" + target.getName() + " kind=" + spec.kind()
                    + " " + sizeEvidence
                    + " result=" + result);
            } catch (RuntimeException ex) {
                // 不吞錯：如實回報發送失敗（含例外訊息），由觀察者判斷。
                getLogger().severe("[fprobe-send] case=" + c.id()
                    + " 發送失敗: " + ex.getMessage());
                report(sender, "[fprobe-send] case=" + c.id() + " 發送失敗：" + ex.getMessage());
            }
        }
        report(sender, "已發送 " + selected.size() + " 個案例給 " + target.getName()
            + "；請以 Bedrock 客戶端觀察並回填矩陣報告（點擊索引見 [fprobe-response]）。");
    }

    /** 同步輸出到呼叫者與 server log，方便真人驗收時對照。 */
    private void report(CommandSender sender, String line) {
        sender.sendMessage(line);
        getLogger().info(line);
    }
}
