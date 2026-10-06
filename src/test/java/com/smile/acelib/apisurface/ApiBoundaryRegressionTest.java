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
    void canonicalTopLevelInventoryIs179() throws IOException {
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
        // 任務完成語意與作用域任務再 +4（→ 155），設定啟動／快照／型別綁定任務再 +5（→ 160），新增二十八個頂層型別：
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
        // 此為公開契約；收斂 Internal 為非 public 會靜默縮減 inventory，屬於未授權
        // breaking change。
        assertTrue(supported == 146,
            "Supported 數量偏離 canonical 146，實際=" + supported);
        assertTrue(spi == 12,
            "SPI 數量偏離 canonical 12，實際=" + spi);
        assertTrue(internal == 21,
            "Internal 數量偏離 canonical 21，實際=" + internal
                + "（Internal 收斂為非 public 前必須先經 review 並同步 canonical 契約）");
        assertTrue(types.size() == 179,
            "top-level inventory 偏離 canonical 179，實際=" + types.size());
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
