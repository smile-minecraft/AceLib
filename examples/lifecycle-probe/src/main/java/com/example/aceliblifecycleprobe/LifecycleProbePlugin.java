package com.example.aceliblifecycleprobe;

import com.smile.acelib.AceLibApi;
import com.smile.acelib.lifecycle.LifecycleHost;
import com.smile.acelib.lifecycle.LifecycleModule;
import com.smile.acelib.lifecycle.LifecycleResult;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

/** 透過公開 API 記錄核心 reload 與下游 lifecycle 模組的實機探針。 */
public final class LifecycleProbePlugin extends JavaPlugin implements CommandExecutor {

    private static final String MODULE_ID = "lifecycle-probe:probe";

    private final AtomicLong reloadSequence = new AtomicLong();

    private AceLibApi.AceLibProvider apiProvider;
    private LifecycleHost lifecycleHost;
    private boolean moduleRegistered;

    @Override
    public void onEnable() {
        RegisteredServiceProvider<AceLibApi.AceLibProvider> registration = getServer()
            .getServicesManager().getRegistration(AceLibApi.AceLibProvider.class);
        if (registration == null) {
            disableWithError("AceLib provider 未註冊；請確認 AceLib 已先啟用。");
            return;
        }

        apiProvider = registration.getProvider();
        AceLibApi api = apiProvider.api();
        if (!api.isReady()) {
            disableWithError("AceLib API 尚未就緒；探針不會註冊 lifecycle 模組。");
            return;
        }

        lifecycleHost = api.getLifecycleHost();
        LifecycleResult registrationResult = lifecycleHost.register(this, List.of(
            new LifecycleModule(MODULE_ID, Set.of(), context -> {
                if (context.owner() != this) {
                    throw new IllegalStateException("lifecycle owner does not match probe plugin");
                }
                getLogger().info(LifecycleProbeOutput.MODULE_ENABLED);
                return () -> getLogger().info(LifecycleProbeOutput.MODULE_CLOSED);
            })));
        getLogger().info(LifecycleProbeOutput.operation("register", registrationResult));
        if (!registrationResult.isSuccess()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        moduleRegistered = true;

        var command = getCommand("lprobe");
        if (command == null) {
            disableWithError("plugin.yml 未定義 lprobe 指令。");
            return;
        }
        command.setExecutor(this);
        getLogger().info("[lprobe] ready; use /lprobe reload or /lprobe status");
    }

    @Override
    public void onDisable() {
        if (moduleRegistered && lifecycleHost != null) {
            LifecycleResult result = lifecycleHost.unregister(this);
            getLogger().info(LifecycleProbeOutput.operation("unregister", result));
            moduleRegistered = false;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var parsed = LifecycleProbeCommand.parse(args);
        if (parsed.isEmpty()) {
            sender.sendMessage("用法：/lprobe reload 或 /lprobe status（詳細結果見伺服器記錄）");
            return true;
        }
        if (!sender.hasPermission("aceliblifecycleprobe.use")) {
            sender.sendMessage("沒有使用 lifecycle 探針的權限。");
            return true;
        }

        LifecycleProbeCommand request = parsed.orElseThrow();
        getServer().getGlobalRegionScheduler().run(this, ignored -> execute(request));
        sender.sendMessage("lprobe 已排入全域執行區；詳細結果見伺服器記錄。");
        return true;
    }

    private void execute(LifecycleProbeCommand request) {
        if (apiProvider == null) {
            getLogger().warning("[lprobe] provider is unavailable; no operation was run");
            return;
        }

        AceLibApi api = apiProvider.api();
        if (request.action() == LifecycleProbeCommand.Action.STATUS) {
            logStatus(api);
            return;
        }

        long sequence = reloadSequence.incrementAndGet();
        if (!api.isReady()) {
            getLogger().warning("[lprobe] reload #" + sequence
                + " skipped: apiReady=false; no reload was invoked");
            logStatus(api);
            return;
        }

        api.reload();
        AceLibApi currentApi = apiProvider.api();
        LifecycleHost currentHost = currentApi.getLifecycleHost();
        getLogger().info(LifecycleProbeOutput.reload(sequence, currentApi.isReady(),
            currentHost.status(), currentHost.lastResult()));
    }

    private void logStatus(AceLibApi api) {
        LifecycleHost currentHost = api.getLifecycleHost();
        getLogger().info(LifecycleProbeOutput.status(api.isReady(), currentHost.status(),
            currentHost.lastResult()));
    }

    private void disableWithError(String message) {
        getLogger().severe(message + " 請檢查依賴順序與伺服器啟動記錄。");
        getServer().getPluginManager().disablePlugin(this);
    }
}
