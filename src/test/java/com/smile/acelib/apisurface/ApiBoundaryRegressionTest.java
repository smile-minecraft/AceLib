package com.smile.acelib.apisurface;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * v1 API boundary regression：Internal 分類型別必須「已收斂為非 public」或
 * 「在 allowlist 留下具體 retention 理由」；Supported/SPI 型別不得標記 retention。
 *
 * <p>本測試與 {@link ApiSurfaceContractTest} 互補：後者驗證 source↔JSON↔MD 的
 * 型別集合一致性；本測試驗證 Internal 的可見性契約——不允許「默默保持 public
 * 卻沒有持久理由」的內部實作型別漂移進 v1 對外 surface。</p>
 *
 * <p>不依賴 Bukkit / MockBukkit 環境，純靜態掃描與 JSON 解析。</p>
 */
class ApiBoundaryRegressionTest {

    private static final Pattern TYPE_DECL = Pattern.compile(
        "\\b(class|interface|enum|record)\\s+(\\w+)\\b");

    private Path projectRoot() {
        Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (int i = 0; i < 12; i++) {
            if (Files.exists(dir.resolve("docs/reference/api-surface.json"))
                    || Files.exists(dir.resolve("build.gradle.kts"))) {
                return dir;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        return Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
    }

    @Test
     void canonicalTopLevelInventoryIs227() throws IOException {
        Path root = projectRoot();
        List<Map<String, String>> types = ApiSurfaceContractTestHelpers.parseTypes(
            Files.readString(root.resolve("docs/reference/api-surface.json")));
        int supported = 0;
        int spi = 0;
        int internal = 0;
        for (Map<String, String> t : types) {
            switch (t.get("classification")) {
                case "Supported" -> supported++;
                case "SPI" -> spi++;
                case "Internal" -> internal++;
                default -> throw new IllegalStateException("非法分類：" + t.get("fqcn"));
            }
        }
        // v1 canonical inventory：119 Supported + 12 SPI + 20 Internal = 151。
        // 基岩相容任務刻意擴充（132 → 137 → 141 → 143），表單圖示任務再 +1（→ 144），
        // 表單文字任務再 +2（→ 146），指令目錄任務再 +5（→ 151），
        // 任務完成語意與作用域任務再 +4（→ 155），設定啟動／快照／型別綁定任務再 +5（→ 160），
        // 插件隔離的介面流程與元件任務再 +13（→ 179），型別化指令框架任務再 +12（→ 191），
        // 前述各段合計新增四十個頂層型別：
        //   Supported +13：
        //     - com.smile.acelib.bedrock.BedrockService（interface）— 基岩玩家查詢 facade，
        //       缺席環境以 absent lookup 零影響
        //     - com.smile.acelib.bedrock.BedrockPlayerInfo（record）— 裝置/輸入/語言/連結
        //       值型別；列舉以 nested 型別承載以控制頂層數量
        //     - com.smile.acelib.bedrock.BedrockErrorCodes（class）— ACELIB-BED-* 常數表，
        //       比照 ExternalIntegrationErrorCodes 模式
        //     - com.smile.acelib.form.FormService（interface）— 表單服務 facade，供
        //       BedrockService.forms() 使用；發送 seam 以 nested FormSender 承載
        //     - com.smile.acelib.form.FormSpec（sealed class）— 基岩原生表單規格 DSL，
        //       消費者描述表單的唯一入口；Cumulus 外部型別不外洩
        //     - com.smile.acelib.form.FormSendResult（enum）— 發送結果具名狀態
        //       （SENT/REJECTED），取代原始 boolean 外洩
        //     - com.smile.acelib.form.FormResponseStatus（enum）— 回應狀態語意
        //       （VALID/CLOSED/INVALID），供回應派送層引用
        //     - com.smile.acelib.form.FormResponse（class）— 表單回應值型別（immutable），
        //       經 sendForm 三參數 overload 的 consumer 於玩家 region context 交付
        //     - com.smile.acelib.form.FormValue（sealed interface）— custom 元件答案
        //       （Text/Option/Number/Switch 以 nested records 承載，label 不產值）
        //     - com.smile.acelib.form.FormErrorCodes（class）— ACELIB-FORM-* 常數表，
        //       比照 BedrockErrorCodes 模式
        //     - com.smile.acelib.form.FormImage（record）— Simple 按鈕圖示值型別
        //       （PATH／URL 以 nested Type 列舉承載；Cumulus 外部型別不外洩）
        //     - com.smile.acelib.message.FormText（class）— Adventure Component →
        //       基岩表單安全字串的靜態渲染入口（click/hover 移除、hex 降 16 色、
        //       translatable 解析、換行 §r、長度截斷）；MessageService 語系化提示
        //     - com.smile.acelib.message.FormTextOptions（record）— 渲染選項值型別
        //       （clickHints／maxLength／locale；defaults 為 false／0／null）
        //   Supported +5：
        //     - com.smile.acelib.command.CommandCatalog（interface）— 指令目錄服務，
        //       只存指令描述、不註冊不執行；快照深層不可變、revision 快取失效
        //     - com.smile.acelib.command.CatalogResult（enum）— 發布結果具名狀態
        //       （PUBLISHED／REPLACED／UNCHANGED／DUPLICATE_NAME／REJECTED）
        //     - com.smile.acelib.command.CatalogMeta（record）— 發布元資料值型別
        //       （分類／圖示／需確認子指令；FormImage 跨模組重用不外洩 handler）
        //     - com.smile.acelib.command.CommandDoc（record）— 指令純描述投影值型別，
        //       欄位形狀本身排除 handler／completer／插件實例
        //     - com.smile.acelib.command.SubDoc（record）— 子指令純描述投影值型別
        //       （maxArgs -1 無上限語意保留；requiresConfirmation 需確認標記）
        //   Internal +1：
        //     - com.smile.acelib.external.FloodgateIntegrationAdapter — plugin 接線需跨
        //       package 建構並讀取 typed lookup，比照既有三個內建 adapter 保留 public
        //   Supported +4：
        //     - com.smile.acelib.scheduler.TaskOutcome（enum）— 終態分類
        //       （COMPLETED／FAILED／CANCELLED／REJECTED；v1 凍結常數順序）
        //     - com.smile.acelib.scheduler.TaskResult（record）— 終態值型別
        //       （攜值／攜因／攜紀錄；欄位組合由建構子強制）
        //     - com.smile.acelib.scheduler.TaskScope（interface）— 玩家／實體
        //       作用域任務群組（退服／退休／停用自動取消；讀取→計算→回覆流程）
        //     - com.smile.acelib.scheduler.TaskTicket（interface）— 可觀察終態的
        //       任務票據（沿用 ScheduledTask 句柄語意＋等待／串接）
        //   Supported +5（設定啟動／快照／型別綁定）：
        //     - com.smile.acelib.config.StartupResult（record）— 啟動四分類
        //       （首次安裝／有效／損壞／使用後缺檔；狀態以 nested Status 列舉承載）
        //     - com.smile.acelib.config.ConfigSnapshot（class）— 不可變快照
        //       （深層凍結、同輪一致）
        //     - com.smile.acelib.config.ConfigBinder（class）— record／一般類別
        //       綁定（型別／範圍／列舉驗證；路徑與範圍註解以 nested 承載）
        //     - com.smile.acelib.config.ConfigBindingException（class）—
        //       綁定例外（ACELIB-CFG-007，帶完整欄位路徑）
        //     - com.smile.acelib.config.ConfigChangeListener（interface）—
        //       監看回呼（自動重載成功／無效診斷）
        //   Supported +5（插件作用域訊息與在地化）：
        //     - com.smile.acelib.message.MessageLabel（record）— 顯示標籤值型別
        //       （穩定程式識別字 id＋渲染顯示文字 text；缺 key 時文字退回 id）
        //     - com.smile.acelib.message.MessageScope（class）— 單一 plugin
        //       訊息作用域 handle（專屬 LangManager＋MessageService；解析器語系
        //       發送／共用渲染／顯示標籤；close 具冪等性）
        //     - com.smile.acelib.message.MessageScopes（class）— 統一工廠
        //       （per-plugin 隔離建立與清理；重複建立以 ACELIB-MSG-006 拒絕）
        //     - com.smile.acelib.message.PlayerLocaleResolver（interface）—
        //       玩家語系解析器（可替換；預設跟隨 Player.locale；不強制偏好儲存）
        //     - com.smile.acelib.message.RenderedMessage（record）— 單次渲染結果
        //       （component／text／formText 三視圖＋缺 key／渲染失敗診斷）
        //   Supported +13（插件隔離的介面流程與元件）：
        //     - com.smile.acelib.gui.GuiButton（record）— 按鈕描述
        //       （識別字＋點擊冷卻；點擊走專屬回呼，不以 SLOT_PROTECTED 冒充）
        //     - com.smile.acelib.gui.GuiButtonClick（record）— 按鈕點擊事件快照
        //       （玩家／世代／欄位／按鈕識別字／鐵砧文字）
        //     - com.smile.acelib.gui.GuiFlow（class）— Java GUI／基岩表單共用流程
        //       （有序步驟＋起始＋結束回呼）
        //     - com.smile.acelib.gui.GuiFlowStep（record）— 流程步驟
        //       （Java 視圖＋可選基岩表單＋按鈕轉移表）
        //     - com.smile.acelib.gui.GuiInputKind（enum）— 輸入種類（CHAT／ANVIL）
        //     - com.smile.acelib.gui.GuiInputPrompt（record）— 輸入提示描述
        //       （種類／標題／提示／長度上限／逾時）
        //     - com.smile.acelib.gui.GuiInputResult（record）— 玩家輸入結果
        //       （region 內恰好一次交付）
        //     - com.smile.acelib.gui.GuiInputTicket（record）— 不透明一次性票券
        //     - com.smile.acelib.gui.GuiReplacementListener（interface）—
        //       GUI 被取代通知回呼
        //     - com.smile.acelib.gui.GuiRevalidation（interface）— 送出前重新驗證
        //     - com.smile.acelib.gui.GuiScope（class）— 單一 plugin 的 GUI 作用域
        //       handle（導航／按鈕／票券／輸入；close 具冪等性）
        //     - com.smile.acelib.gui.GuiScopes（class）— 統一工廠
        //       （重複建立以 ACELIB-GUI-020 拒絕）
        //     - com.smile.acelib.gui.GuiView（class）— 視圖描述
        //       （預設全擋、只開放指定欄位）
        //   Internal +1（介面流程）：
        //     - com.smile.acelib.gui.GuiServiceControl — 內部生命週期入口
        //       （AceLibPlugin 跨 package 停用；公開 shutdown 已於 1.4.0 移除）
        //   Supported +3、SPI +3（逐玩家儲存與玩家資料模型）：
        //     - com.smile.acelib.data.PlayerDataStore（interface, SPI）— 逐玩家儲存
        //       SPI；不保執行緒安全，實作者自行序列化操作
        //     - com.smile.acelib.data.PlayerDataStores（class）— 建立工廠
        //       （sqlite 預設／jdbc 換 MySQL／fromDataStore 相容既有 store）
        //     - com.smile.acelib.data.PlayerDataConverter（class）— 舊 JSON／JDBC
        //       玩家資料轉換入口（只補缺漏、來源零刪除、備份＋三層校驗報告）
        //     - com.smile.acelib.data.PlayerDataCodec（interface, SPI）— record
        //       資料模型與頂層欄位的編解碼 extension point
        //     - com.smile.acelib.data.RecordPlayerDataCodec（class）— 以 component
        //       名對映欄位的預設 codec 實作
        //     - com.smile.acelib.player.PlayerDataReadyListener（interface, SPI）—
        //       資料就緒回呼；I/O executor 執行緒，故非 Bukkit Event
        //   Supported +8、SPI +4（型別化指令框架）：
        //     - com.smile.acelib.command.Arguments（class）— 八種型別化引數工廠
        //       （玩家／離線玩家／整數／小數／時間長度／世界／列舉／材質）；
        //       解析、驗證與補全的單一組裝入口
        //     - com.smile.acelib.command.TypedCommand（class）— 型別化根指令 builder
        //       （別名／權限／子指令；同時產出 CommandSpec 與 Brigadier 根節點）
        //     - com.smile.acelib.command.TypedSubCommand（class）— 型別化子指令
        //       builder；toSubCommandSpec 保留 SubCommandSpec 相容層
        //     - com.smile.acelib.command.TypedContext（class）— 已解析引數值的
        //       執行 context（以引數實例為 key，非字串名）
        //     - com.smile.acelib.command.BrigadierRegistrar（class）— 註冊器；
        //       內部 registry ＋ Brigadier 節點雙寫入，取代 plugin.yml 宣告需求
        //     - com.smile.acelib.command.CommandMessages（interface, SPI）— 錯誤
        //       在地化契約；下游可自備語系實作
        //     - com.smile.acelib.command.DefaultCommandMessages（class）— 內建英文
        //       預設訊息表（既有 dispatcher 文案的單一來源）
        //     - com.smile.acelib.command.MessageServiceCommandMessages（class）— 經
        //       message 模組查 key 的轉接（下游自備語言檔）
        //     - com.smile.acelib.command.LocalizingReplySink（class）— presentation
        //       層裝飾器；CommandException 轉在地化字串，缺 key 退回原文
        //     - com.smile.acelib.command.CommandArgument（interface, SPI）— 型別化
        //       引數契約（解析／補全／vanilla 型別／固定選項 literal）
        //     - com.smile.acelib.command.TypedHandler（interface, SPI）— 收到已解析
        //       TypedContext 的子指令處理器
        //     - com.smile.acelib.command.BrigadierDispatch（interface, SPI）— 樹的
        //       executes 委派回呼，維持 dispatch 單一真相來源
        //   Supported +5、SPI +5、Internal +4（外部整合門面）：
        //     - com.smile.acelib.external.BuildCheckResult（class）— 建造查詢
        //       結果值型別（ALLOW／DENY／UNAVAILABLE；成功不帶 errorCode）
        //     - com.smile.acelib.external.EconomyResult（class）— 經濟操作
        //       結果值型別（非成功時餘額為 NaN，不可以 0 解讀為沒錢）
        //     - com.smile.acelib.external.ExternalOperationResult（class）—
        //       通用外部操作結果值型別（佔位符註冊／清理）
        //     - com.smile.acelib.external.ExternalResultState（enum）— 結果
        //       狀態（SUCCESS／FAILED／UNAVAILABLE；v1 凍結常數順序）
        //     - com.smile.acelib.external.PermissionResult（class）— 權限查詢
        //       結果值型別（不可用時不默認允許）
        //     - com.smile.acelib.external.BuildCheckProvider（interface, SPI）—
        //       建造查詢提供者契約（只做查詢，不觸碰世界狀態）
        //     - com.smile.acelib.external.EconomyProvider（interface, SPI）—
        //       經濟提供者契約（外部包裝，不自製帳本或去重）
        //     - com.smile.acelib.external.PermissionProvider（interface, SPI）—
        //       權限提供者契約（外部包裝，不代做領域授權）
        //     - com.smile.acelib.external.PlaceholderHandler（interface, SPI）—
        //       自有佔位符處理器（下游註冊鍵對應的解析回呼）
        //     - com.smile.acelib.external.PlaceholderProvider（interface, SPI）—
        //       佔位符提供者契約（註冊／清理無殘留）
        //   Internal +4（外部整合門面內建實作）：
        //     - com.smile.acelib.external.AceLibPlaceholderExpansion —
        //       PlaceholderAPI 子類別 expansion（自有佔位符橋接）
        //     - com.smile.acelib.external.LuckPermsPermissionProvider —
        //       LuckPerms typed 持有者（只在 AVAILABLE 後載入）
        //     - com.smile.acelib.external.PlaceholderApiPlaceholderProvider —
        //       PlaceholderAPI typed 持有者（只在 AVAILABLE 後載入）
        //     - com.smile.acelib.external.VaultEconomyProvider — Vault legacy
        //       純反射包裝（零外部 import，每次呼叫重新解析）
        // 顯示模組再增加 Supported +5、Internal +1（共 +6）：
        //   - DisplayService／DisplayResult／DisplayState／DisplayErrorCode／Hologram
        //     為下游顯示 facade、結果、狀態、錯誤碼與不暴露 Bukkit 實體的快照
        //   - DisplayServiceControl 為 AceLibPlugin 跨 package 的內部停用入口
        // 生命週期宿主再增加 Supported +3：
        //   - LifecycleHost（interface）— 下游模組生命週期管理入口
        //   - LifecycleModule（record）— 下游模組與相依關係宣告
        //   - LifecycleResult（record）— 操作狀態與結構化問題
        // 自訂引數型別任務再 +1 SPI（→ 221）：
        //   - com.smile.acelib.command.ArgumentTypeFactory（interface, SPI）—
        //     自訂引數選用 Brigadier 型別的公開 SPI（自 1.5.0 起公開，既有方法簽章與語意不變）
        // 訊息第四階段任務再 +4 Supported（→ 225）：
        //   - com.smile.acelib.message.BedrockFallbackStyle（enum, Supported）—
        //     基岩降級風格（HINTS 預設／PLAIN_TEXT 攤平）
        //   - com.smile.acelib.message.DetailedRender（record, Supported）—
        //     帶狀態的單次渲染結果
        //   - com.smile.acelib.message.RenderStatus（enum, Supported）—
        //     單次渲染狀態分類
        //   - com.smile.acelib.message.SendResult（record, Supported）—
        //     單次發送結果（是否送達／是否套用基岩降級）
        // 設定第四階段集合／跨欄位任務再 +1 Supported（→ 226）：
        //   - com.smile.acelib.config.ConfigCrossFieldValidator（interface, Supported）—
        //     消費者實作的跨欄位驗證規則（函式介面）；整份設定通過後才發布新快照，
        //     失敗時保留舊快照（ConfigManager.registerCrossFieldValidator 登記）
        // 設定第四階段缺檔攔截任務再 +1 Supported（→ 227）：
        //   - com.smile.acelib.config.ConfigMissingFileHandler（interface, Supported）—
        //     消費者實作的缺檔攔截規則（函式介面）；只在使用後缺檔、還原最後成功副本之前執行，
        //     拋 ConfigException 即拒絕還原（ConfigManager.registerMissingFileHandler 登記）
        // 此為公開契約；收斂 Internal 為非 public 會靜默縮減 inventory，屬於未授權
        // breaking change。
        assertTrue(supported == 176,
            "Supported 數量偏離 canonical 176，實際=" + supported);
        assertTrue(spi == 25,
            "SPI 數量偏離 canonical 25，實際=" + spi);
        assertTrue(internal == 26,
            "Internal 數量偏離 canonical 26，實際=" + internal
                + "（Internal 收斂為非 public 前必須先經 review 並同步 canonical 契約）");
        assertTrue(types.size() == 227,
            "top-level inventory 偏離 canonical 227，實際=" + types.size());
    }

    @Test
    void internalTypesAreConvergedOrCarryRetentionRationale() throws IOException {
        Path root = projectRoot();
        List<Map<String, String>> types = ApiSurfaceContractTestHelpers.parseTypes(
            Files.readString(root.resolve("docs/reference/api-surface.json")));
        Map<String, Boolean> sourcePublic = scanPublicTopLevelTypes(root.resolve("src/main/java"));

        List<String> missingRetention = new ArrayList<>();
        List<String> staleEntries = new ArrayList<>();
        for (Map<String, String> t : types) {
            if (!"Internal".equals(t.get("classification"))) {
                continue;
            }
            String fqcn = t.get("fqcn");
            String retention = t.get("retention");
            boolean isPublic = sourcePublic.containsKey(fqcn);
            if (isPublic && (retention == null || retention.isBlank())) {
                missingRetention.add(fqcn + "（source 仍 public，但 allowlist 無 retention 理由）");
            }
            if (!isPublic && retention != null && !retention.isBlank()) {
                staleEntries.add(fqcn + "（allowlist 標記 retention 但 source 已收斂為非 public）");
            }
        }
        assertTrue(missingRetention.isEmpty(),
            "Internal 型別仍為 public 卻無 retention 理由（需收斂或補理由）：" + missingRetention);
        assertTrue(staleEntries.isEmpty(),
            "allowlist 標記 retention 的型別已收斂但仍留在清單：" + staleEntries);
    }

    @Test
    void supportedAndSpiTypesNeverCarryRetentionField() throws IOException {
        Path root = projectRoot();
        List<Map<String, String>> types = ApiSurfaceContractTestHelpers.parseTypes(
            Files.readString(root.resolve("docs/reference/api-surface.json")));
        List<String> violations = new ArrayList<>();
        for (Map<String, String> t : types) {
            String cls = t.get("classification");
            if (("Supported".equals(cls) || "SPI".equals(cls))
                    && t.get("retention") != null && !t.get("retention").isBlank()) {
                violations.add(t.get("fqcn"));
            }
        }
        assertTrue(violations.isEmpty(),
            "Supported/SPI 型別不應帶 retention 欄位（該欄位僅供 Internal）：" + violations);
    }

    @Test
    void convergedTypesMustNotBePublicInSource() throws IOException {
        Path root = projectRoot();
        String json = Files.readString(root.resolve("docs/reference/api-surface.json"));
        List<Map<String, String>> converged = ApiSurfaceContractTestHelpers.parseConvergedTypes(json);
        Map<String, Boolean> sourcePublic = scanPublicTopLevelTypes(root.resolve("src/main/java"));
        List<String> reExposed = new ArrayList<>();
        for (Map<String, String> c : converged) {
            String fqcn = c.get("fqcn");
            String reason = c.get("reason");
            if (reason == null || reason.isBlank()) {
                throw new IllegalStateException("convergedTypes 缺少 reason：" + fqcn);
            }
            if (sourcePublic.containsKey(fqcn)) {
                reExposed.add(fqcn);
            }
        }
        assertTrue(reExposed.isEmpty(),
            "convergedTypes 中的型別不得重新變成 public top-level：" + reExposed);
    }

    @Test
    void everySourcePublicTypeIsListed() throws IOException {
        Path root = projectRoot();
        List<Map<String, String>> types = ApiSurfaceContractTestHelpers.parseTypes(
            Files.readString(root.resolve("docs/reference/api-surface.json")));
        Set<String> declared = new HashSet<>();
        for (Map<String, String> t : types) {
            declared.add(t.get("fqcn"));
        }
        Map<String, Boolean> sourcePublic = scanPublicTopLevelTypes(root.resolve("src/main/java"));
        Set<String> unlisted = new HashSet<>(sourcePublic.keySet());
        unlisted.removeAll(declared);
        assertTrue(unlisted.isEmpty(),
            "source 存在 public 頂層型別但 allowlist 未列出：" + unlisted);
    }

    private Map<String, Boolean> scanPublicTopLevelTypes(Path srcRoot) throws IOException {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (!Files.exists(srcRoot)) {
            return result;
        }
        try (Stream<Path> stream = Files.walk(srcRoot)) {
            List<Path> files = stream.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path file : files) {
                String base = file.getFileName().toString().replace(".java", "");
                Path rel = srcRoot.relativize(file).getParent();
                String pkg = rel == null ? "" : rel.toString().replace('/', '.');
                String text = Files.readString(file);
                if (isPublicTopLevel(text, base)) {
                    result.put(pkg + "." + base, true);
                }
            }
        }
        return result;
    }

    private boolean isPublicTopLevel(String text, String base) {
        Matcher m = TYPE_DECL.matcher(text);
        while (m.find()) {
            if (!m.group(2).equals(base)) {
                continue;
            }
            if (braceDepth(text, m.start()) != 0) {
                continue;
            }
            String before = text.substring(declarationStart(text, m.start()), m.start());
            String modifiers = before;
            int lastBlockClose = modifiers.lastIndexOf("*/");
            if (lastBlockClose >= 0) {
                modifiers = modifiers.substring(lastBlockClose + 2);
            }
            modifiers = modifiers.trim();
            if (modifiers.contains("public")
                    && !modifiers.contains("private")
                    && !modifiers.contains("protected")) {
                return true;
            }
        }
        return false;
    }

    private int declarationStart(String text, int keywordPos) {
        boolean lineComment = false;
        boolean blockComment = false;
        boolean inString = false;
        boolean inChar = false;
        for (int i = keywordPos - 1; i >= 0; i--) {
            char c = text.charAt(i);
            char prev = (i - 1 >= 0) ? text.charAt(i - 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && prev == '/') {
                    blockComment = false;
                    i--;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i--;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i--;
                    continue;
                }
                if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && prev == '*') {
                blockComment = true;
                i--;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }
            if (c == '{' || c == ';') {
                return i + 1;
            }
        }
        return 0;
    }

    private int braceDepth(String text, int pos) {
        int depth = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean inString = false;
        boolean inChar = false;
        for (int i = 0; i < pos; i++) {
            char c = text.charAt(i);
            char next = (i + 1 < text.length()) ? text.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i++;
                    continue;
                }
                if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '\'') {
                inChar = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return depth;
    }

    /**
     * 供同 package 測試共用的 JSON 解析 helper（避免每個測試複製一份 parser）。
     */
    static final class ApiSurfaceContractTestHelpers {
        private ApiSurfaceContractTestHelpers() {
        }

        static List<Map<String, String>> parseTypes(String json) {
            return parseSection(json, "types");
        }

        static List<Map<String, String>> parseConvergedTypes(String json) {
            return parseSection(json, "convergedTypes");
        }

        private static List<Map<String, String>> parseSection(String json, String sectionName) {
            List<Map<String, String>> out = new ArrayList<>();
            int idx = json.indexOf("\"" + sectionName + "\"");
            if (idx < 0) {
                return out;
            }
            int arr = json.indexOf('[', idx);
            int end = json.indexOf(']', arr);
            if (arr < 0 || end < 0) {
                return out;
            }
            String body = json.substring(arr + 1, end);
            int i = 0;
            while (true) {
                int objStart = body.indexOf('{', i);
                if (objStart < 0) {
                    break;
                }
                int objEnd = matchingBrace(body, objStart);
                String obj = body.substring(objStart, objEnd + 1);
                Map<String, String> map = new HashMap<>();
                for (String key : new String[] {"fqcn", "package", "simpleName", "kind",
                    "classification", "reason", "mainCallers", "retention", "convergedTo"}) {
                    map.put(key, stringField(obj, key));
                }
                out.add(map);
                i = objEnd + 1;
            }
            return out;
        }

        private static int matchingBrace(String s, int open) {
            int depth = 0;
            for (int i = open; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return s.length() - 1;
        }

        private static String stringField(String obj, String key) {
            Pattern p = Pattern.compile(
                "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher m = p.matcher(obj);
            return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\\\", "/")
                .replace("\\n", " ").replace("\\t", " ") : null;
        }
    }
}
