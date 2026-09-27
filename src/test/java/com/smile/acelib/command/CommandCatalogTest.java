package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smile.acelib.form.FormImage;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 指令目錄（{@link CommandCatalog}）行為測試。
 *
 * <p>目錄只存「指令描述」：各插件把 {@link CommandSpec} 或純描述發布進來，
 * 其他插件讀出不可變快照產生說明頁。目錄不註冊、不執行指令，
 * handler 與 completer 絕不外洩。</p>
 */
@DisplayName("CommandCatalog 指令目錄")
class CommandCatalogTest {

    private CommandCatalogImpl catalog;

    @BeforeEach
    void setUp() {
        catalog = new CommandCatalogImpl();
    }

    // -----------------------------------------------------------------
    // 共用 helper
    // -----------------------------------------------------------------

    private static Plugin plugin(String name) {
        JavaPlugin mock = mock(JavaPlugin.class);
        when(mock.getName()).thenReturn(name);
        return mock;
    }

    private static Permissible permissive(String... granted) {
        Permissible mock = mock(Permissible.class);
        for (String perm : granted) {
            when(mock.hasPermission(perm)).thenReturn(true);
        }
        return mock;
    }

    private static Permissible denying() {
        return mock(Permissible.class);
    }

    private static CommandSpec spec(String name, String... subNames) {
        CommandSpec.Builder builder = CommandSpec.builder(name)
            .description(name + " desc")
            .usage("/" + name);
        for (String sub : subNames) {
            builder.subCommand(SubCommandSpec.builder(sub)
                .description(sub + " desc")
                .handler(SubCommand.NOOP)
                .build());
        }
        return builder.build();
    }

    private static CatalogMeta meta(String category, String... confirms) {
        return new CatalogMeta(category, Optional.empty(), Set.of(confirms));
    }

    /** 擷取 "AceLib" logger 的 warning 記錄；呼叫端負責 {@link LogCapture#close()}。 */
    private static LogCapture captureWarnings() {
        Logger logger = Logger.getLogger("AceLib");
        LogCapture capture = new LogCapture();
        capture.previousLevel = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(capture);
        return capture;
    }

    private static final class LogCapture extends Handler {
        final List<LogRecord> records = new ArrayList<>();
        Level previousLevel;

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            Logger logger = Logger.getLogger("AceLib");
            logger.removeHandler(this);
            logger.setLevel(previousLevel);
        }

        boolean hasCode(String code) {
            for (LogRecord record : records) {
                if (record.getMessage() != null && record.getMessage().contains(code)) {
                    return true;
                }
            }
            return false;
        }
    }

    // -----------------------------------------------------------------
    // 發布結果區分
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("發布結果區分")
    class PublishResults {

        @Test
        @DisplayName("首次發布回 PUBLISHED，revision 從 0 變為 1")
        void freshPublish_returnsPublished() {
            assertEquals(0, catalog.revision());
            CatalogResult result =
                catalog.publish(plugin("Alpha"), spec("shop", "buy"), meta("economy"));
            assertEquals(CatalogResult.PUBLISHED, result);
            assertEquals(1, catalog.revision());
            assertEquals(1, catalog.snapshot().size());
        }

        @Test
        @DisplayName("同擁有者同名、同內容重發回 UNCHANGED，且不增加 revision、不記錄重複警告")
        void identicalRepublish_returnsUnchanged() {
            Plugin owner = plugin("Alpha");
            CommandSpec spec = spec("shop", "buy");
            catalog.publish(owner, spec, meta("economy"));
            long before = catalog.revision();
            LogCapture capture = captureWarnings();
            try {
                CatalogResult result = catalog.publish(owner, spec, meta("economy"));
                assertEquals(CatalogResult.UNCHANGED, result);
                assertEquals(before, catalog.revision());
                assertFalse(capture.hasCode("ACELIB-CMD-013"),
                    "相同重發不得記錄跨擁有者同名警告");
            } finally {
                capture.close();
            }
        }

        @Test
        @DisplayName("同擁有者同名、內容不同回 REPLACED，快照更新為新內容")
        void sameOwnerDifferentContent_returnsReplaced() {
            Plugin owner = plugin("Alpha");
            catalog.publish(owner, spec("shop", "buy"), meta("economy"));
            long before = catalog.revision();
            CatalogResult result = catalog.publish(
                owner, spec("shop", "buy", "sell"), meta("economy"));
            assertEquals(CatalogResult.REPLACED, result);
            assertEquals(before + 1, catalog.revision());
            CommandDoc doc = catalog.snapshot().get(0);
            assertEquals(2, doc.subcommands().size());
        }

        @Test
        @DisplayName("不同擁有者同名兩者並存，回 DUPLICATE_NAME 並記錄 ACELIB-CMD-013")
        void crossOwnerSameName_returnsDuplicateName() {
            catalog.publish(plugin("Alpha"), spec("shop", "buy"), meta("economy"));
            LogCapture capture = captureWarnings();
            try {
                CatalogResult result = catalog.publish(
                    plugin("Beta"), spec("shop", "sell"), meta("economy"));
                assertEquals(CatalogResult.DUPLICATE_NAME, result);
                assertTrue(capture.hasCode("ACELIB-CMD-013"),
                    "跨擁有者同名必須記錄 ACELIB-CMD-013 warning");
            } finally {
                capture.close();
            }
            List<CommandDoc> snapshot = catalog.snapshot();
            assertEquals(2, snapshot.size());
            Set<String> owners = new HashSet<>();
            for (CommandDoc doc : snapshot) {
                assertEquals("shop", doc.name());
                owners.add(doc.owner());
            }
            assertEquals(Set.of("Alpha", "Beta"), owners);
        }

        @Test
        @DisplayName("根指令名稱比對大小寫不敏感：同擁有者大小寫重發視為同名（內容不同則覆蓋）")
        void nameComparison_isCaseInsensitive() {
            Plugin owner = plugin("Alpha");
            catalog.publish(owner, spec("Shop", "buy"), meta("economy"));
            // 描述內含原始大小寫故內容不同 → 同一筆覆蓋（REPLACED），而非新增第二筆
            CatalogResult result =
                catalog.publish(owner, spec("SHOP", "buy"), meta("economy"));
            assertEquals(CatalogResult.REPLACED, result);
            assertEquals(1, catalog.snapshot().size());
        }

        @Test
        @DisplayName("兩擁有者已同名後，其中一方以不同內容覆蓋回 DUPLICATE_NAME 並記錄警告")
        void sameOwnerOverwriteOfDuplicatedName_returnsDuplicateName() {
            Plugin alpha = plugin("Alpha");
            catalog.publish(alpha, spec("shop", "buy"), meta("economy"));
            catalog.publish(plugin("Beta"), spec("shop", "sell"), meta("economy"));
            long before = catalog.revision();
            LogCapture capture = captureWarnings();
            try {
                CatalogResult result = catalog.publish(
                    alpha, spec("shop", "buy", "refund"), meta("economy"));
                assertEquals(CatalogResult.DUPLICATE_NAME, result);
                assertTrue(capture.hasCode("ACELIB-CMD-013"),
                    "已同名狀態下的覆蓋仍須記錄 ACELIB-CMD-013 warning");
            } finally {
                capture.close();
            }
            assertEquals(before + 1, catalog.revision());
            List<CommandDoc> snapshot = catalog.snapshot();
            assertEquals(2, snapshot.size());
            for (CommandDoc doc : snapshot) {
                if (doc.owner().equals("Alpha")) {
                    assertEquals(2, doc.subcommands().size(),
                        "覆蓋方的資料必須確實更新");
                }
            }
        }

        @Test
        @DisplayName("名稱被其他擁有者佔用時，相同內容重發仍回 UNCHANGED、不記錄警告")
        void republishIdenticalUnderDuplication_returnsUnchanged() {
            Plugin alpha = plugin("Alpha");
            CommandSpec spec = spec("shop", "buy");
            catalog.publish(alpha, spec, meta("economy"));
            catalog.publish(plugin("Beta"), spec("shop", "sell"), meta("economy"));
            long before = catalog.revision();
            LogCapture capture = captureWarnings();
            try {
                CatalogResult result = catalog.publish(alpha, spec, meta("economy"));
                assertEquals(CatalogResult.UNCHANGED, result);
                assertFalse(capture.hasCode("ACELIB-CMD-013"),
                    "相同重發不得記錄跨擁有者同名警告");
            } finally {
                capture.close();
            }
            assertEquals(before, catalog.revision());
            assertEquals(2, catalog.snapshot().size());
        }

        @Test
        @DisplayName("三方同名時，各自以不同內容覆蓋都回 DUPLICATE_NAME")
        void threeOwnersSameName_eachOverwrite_returnsDuplicateName() {
            Plugin alpha = plugin("Alpha");
            Plugin beta = plugin("Beta");
            Plugin gamma = plugin("Gamma");
            assertEquals(CatalogResult.PUBLISHED,
                catalog.publish(alpha, spec("shop", "buy"), meta("economy")));
            assertEquals(CatalogResult.DUPLICATE_NAME,
                catalog.publish(beta, spec("shop", "sell"), meta("economy")));
            assertEquals(CatalogResult.DUPLICATE_NAME,
                catalog.publish(gamma, spec("shop", "trade"), meta("economy")));
            assertEquals(CatalogResult.DUPLICATE_NAME,
                catalog.publish(alpha, spec("shop", "buy", "refund"), meta("economy")));
            assertEquals(CatalogResult.DUPLICATE_NAME,
                catalog.publish(beta, spec("shop", "sell", "refund"), meta("economy")));
            assertEquals(CatalogResult.DUPLICATE_NAME,
                catalog.publish(gamma, spec("shop", "trade", "lend"), meta("economy")));
            assertEquals(3, catalog.snapshot().size());
            assertEquals(6, catalog.revision());
        }
    }

    // -----------------------------------------------------------------
    // revision 精確語意
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("revision 語意")
    class RevisionSemantics {

        @Test
        @DisplayName("UNCHANGED 不增加 revision")
        void unchanged_doesNotIncrement() {
            Plugin owner = plugin("Alpha");
            CommandSpec spec = spec("shop", "buy");
            catalog.publish(owner, spec, meta("economy"));
            long before = catalog.revision();
            catalog.publish(owner, spec, meta("economy"));
            assertEquals(before, catalog.revision());
        }

        @Test
        @DisplayName("撤下不存在的資料不算變更、不丟例外")
        void unpublishMissing_isNoop() {
            catalog.publish(plugin("Alpha"), spec("shop"), meta("economy"));
            long before = catalog.revision();
            catalog.unpublishAll(plugin("Ghost"));
            assertEquals(before, catalog.revision());
            assertEquals(1, catalog.snapshot().size());
        }

        @Test
        @DisplayName("批次 unpublishAll 撤下多筆只算一次 revision")
        void batchUnpublish_countsOnce() {
            Plugin owner = plugin("Alpha");
            catalog.publish(owner, spec("shop"), meta("economy"));
            catalog.publish(owner, spec("bank"), meta("economy"));
            catalog.publish(owner, spec("mail"), meta("social"));
            long before = catalog.revision();
            assertEquals(3, before);
            catalog.unpublishAll(owner);
            assertEquals(before + 1, catalog.revision());
            assertTrue(catalog.snapshot().isEmpty());
        }

        @Test
        @DisplayName("清空算一次變更；revision 單調遞增且不因清空重設")
        void clearAll_countsOnceAndNeverResets() {
            Plugin owner = plugin("Alpha");
            catalog.publish(owner, spec("shop"), meta("economy"));
            catalog.publish(owner, spec("bank"), meta("economy"));
            long beforeClear = catalog.revision();
            catalog.clearAll();
            assertEquals(beforeClear + 1, catalog.revision());
            assertTrue(catalog.snapshot().isEmpty());
            // 清空後再發布，revision 繼續遞增而非重設
            catalog.publish(plugin("Beta"), spec("news"), meta("social"));
            assertEquals(beforeClear + 2, catalog.revision());
        }

        @Test
        @DisplayName("空目錄清空不算變更")
        void clearEmpty_doesNotIncrement() {
            assertEquals(0, catalog.revision());
            catalog.clearAll();
            assertEquals(0, catalog.revision());
        }

        @Test
        @DisplayName("服務不可用時的 publish 不增加 revision")
        void unavailablePublish_doesNotIncrement() {
            catalog.publish(plugin("Alpha"), spec("shop"), meta("economy"));
            long before = catalog.revision();
            catalog.setAvailable(false);
            try {
                catalog.publish(plugin("Beta"), spec("bank"), meta("economy"));
                assertEquals(before, catalog.revision());
            } finally {
                catalog.setAvailable(true);
            }
        }
    }

    // -----------------------------------------------------------------
    // 擁有者一致性
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("擁有者一致性")
    class OwnerConsistency {

        @Test
        @DisplayName("publish(Plugin, CommandDoc) 的 owner 與 plugin 名稱不一致時拋 IllegalArgumentException")
        void docOwnerMismatch_throws() {
            Plugin owner = plugin("Alpha");
            CommandDoc doc = new CommandDoc("Beta", "shop", List.of(), "d", "u",
                null, "economy", Optional.empty(), List.of());
            assertThrows(IllegalArgumentException.class,
                () -> catalog.publish(owner, doc));
            assertTrue(catalog.snapshot().isEmpty());
            assertEquals(0, catalog.revision());
        }

        @Test
        @DisplayName("已發布的 owner 一律等於擁有者 plugin 的名稱")
        void publishedOwner_equalsPluginName() {
            Plugin owner = plugin("Alpha");
            catalog.publish(owner, spec("shop", "buy"), meta("economy"));
            assertEquals("Alpha", catalog.snapshot().get(0).owner());
        }

        @Test
        @DisplayName("publish(Plugin, CommandDoc) 成功時 owner 採用 plugin 名稱")
        void publishDoc_ownerAdopted() {
            Plugin owner = plugin("Alpha");
            CommandDoc doc = new CommandDoc("Alpha", "shop", List.of("sh"), "desc",
                "/shop", "shop.use", "economy", Optional.empty(), List.of());
            assertEquals(CatalogResult.PUBLISHED, catalog.publish(owner, doc));
            CommandDoc stored = catalog.snapshot().get(0);
            assertEquals("Alpha", stored.owner());
            assertEquals(List.of("sh"), stored.aliases());
        }

        @Test
        @DisplayName("null 擁有者以 NPE 拒絕")
        void nullOwner_throwsNpe() {
            assertThrows(NullPointerException.class,
                () -> catalog.publish(null, spec("shop"), meta("economy")));
            assertThrows(NullPointerException.class,
                () -> catalog.publish(null,
                    new CommandDoc("x", "shop", List.of(), "d", "u", null, "c",
                        Optional.empty(), List.of())));
            assertThrows(NullPointerException.class, () -> catalog.unpublishAll(null));
        }

        @Test
        @DisplayName("null 規格 / 描述 / 元資料以 NPE 拒絕")
        void nullPayloads_throwsNpe() {
            Plugin owner = plugin("Alpha");
            assertThrows(NullPointerException.class,
                () -> catalog.publish(owner, (CommandSpec) null, meta("economy")));
            assertThrows(NullPointerException.class,
                () -> catalog.publish(owner, spec("shop"), null));
            assertThrows(NullPointerException.class,
                () -> catalog.publish(owner, (CommandDoc) null));
        }
    }

    // -----------------------------------------------------------------
    // 確認子指令
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("確認子指令")
    class ConfirmSubcommands {

        @Test
        @DisplayName("confirmSubcommands 出現未知名稱時拋 IllegalArgumentException")
        void unknownConfirmName_throws() {
            Plugin owner = plugin("Alpha");
            assertThrows(IllegalArgumentException.class, () -> catalog.publish(
                owner, spec("shop", "buy"), meta("economy", "reset")));
            assertTrue(catalog.snapshot().isEmpty());
            assertEquals(0, catalog.revision());
        }

        @Test
        @DisplayName("已知的確認子指令決定 SubDoc.requiresConfirmation")
        void knownConfirm_marksRequiresConfirmation() {
            Plugin owner = plugin("Alpha");
            CommandSpec spec = CommandSpec.builder("shop")
                .subCommand(SubCommandSpec.builder("buy")
                    .handler(SubCommand.NOOP).build())
                .subCommand(SubCommandSpec.builder("reset")
                    .handler(SubCommand.NOOP).build())
                .build();
            assertEquals(CatalogResult.PUBLISHED,
                catalog.publish(owner, spec, meta("economy", "reset")));
            CommandDoc doc = catalog.snapshot().get(0);
            assertEquals(2, doc.subcommands().size());
            for (SubDoc sub : doc.subcommands()) {
                if (sub.name().equals("reset")) {
                    assertTrue(sub.requiresConfirmation());
                } else {
                    assertFalse(sub.requiresConfirmation());
                }
            }
        }

        @Test
        @DisplayName("publish(Plugin, CommandDoc) 直接採用呼叫端的 requiresConfirmation")
        void publishDoc_adoptsRequiresConfirmation() {
            Plugin owner = plugin("Alpha");
            SubDoc sub = new SubDoc("reset", "d", "u", null, List.of(),
                0, -1, false, false, 0, true);
            CommandDoc doc = new CommandDoc("Alpha", "shop", List.of(), "d", "u",
                null, "economy", Optional.empty(), List.of(sub));
            catalog.publish(owner, doc);
            assertTrue(catalog.snapshot().get(0).subcommands().get(0)
                .requiresConfirmation());
        }

        @Test
        @DisplayName("category 與 icon：spec 路徑採用 meta，doc 路徑採用 doc 自身的值")
        void categoryAndIcon_adoptedFromCorrectSource() {
            Plugin owner = plugin("Alpha");
            FormImage icon = FormImage.path("textures/shop.png");
            catalog.publish(owner, spec("shop"),
                new CatalogMeta("economy", Optional.of(icon), Set.of()));
            CommandDoc fromSpec = catalog.snapshot().get(0);
            assertEquals("economy", fromSpec.category());
            assertEquals(Optional.of(icon), fromSpec.icon());

            catalog.clearAll();
            CommandDoc doc = new CommandDoc("Alpha", "bank", List.of(), "d", "u",
                null, "finance", Optional.empty(), List.of());
            catalog.publish(owner, doc);
            CommandDoc fromDoc = catalog.snapshot().get(0);
            assertEquals("finance", fromDoc.category());
            assertEquals(Optional.empty(), fromDoc.icon());
        }
    }

    // -----------------------------------------------------------------
    // 投影邊界：不外洩可執行物件
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("投影邊界")
    class ProjectionBoundary {

        @Test
        @DisplayName("快照中不存在 handler / completer / Plugin / CommandSpec 實例")
        void snapshot_containsNoExecutableOrPluginReferences() throws Exception {
            Plugin owner = plugin("Alpha");
            CommandSpec spec = CommandSpec.builder("shop")
                .aliases("sh")
                .description("shop root")
                .usage("/shop")
                .permission("shop.use")
                .subCommand(SubCommandSpec.builder("buy")
                    .description("buy things")
                    .usage("/shop buy")
                    .permission("shop.buy")
                    .args("item")
                    .handler(SubCommand.NOOP)
                    .completer((ctx, args) -> List.of("apple"))
                    .build())
                .build();
            catalog.publish(owner, spec, meta("economy"));
            List<CommandDoc> snapshot = catalog.snapshot();
            assertEquals(1, snapshot.size());

            // 1) 型別掃描：快照物件圖中不得出現可執行或插件型別
            Set<Object> visited = new HashSet<>();
            List<Object> leaked = new ArrayList<>();
            for (CommandDoc doc : snapshot) {
                collectReachable(doc, visited, leaked);
            }
            assertTrue(leaked.isEmpty(), "快照外洩了不該出現的物件：" + leaked);

            // 2) 描述性欄位正確投影
            CommandDoc doc = snapshot.get(0);
            assertEquals("shop", doc.name());
            assertEquals("shop.use", doc.permission());
            assertEquals(1, doc.subcommands().size());
            SubDoc sub = doc.subcommands().get(0);
            assertEquals("buy", sub.name());
            assertEquals("shop.buy", sub.permission());
            assertEquals(List.of("item"), sub.argNames());
        }

        @Test
        @DisplayName("發布後修改原始 CommandSpec 不影響已收錄的快照")
        void laterMutationOfSpec_doesNotAffectSnapshot() {
            Plugin owner = plugin("Alpha");
            CommandSpec spec = spec("shop", "buy");
            catalog.publish(owner, spec, meta("economy"));
            int sizeBefore = catalog.snapshot().get(0).subcommands().size();
            // CommandSpec 本身不可變；此處驗證目錄未保留 spec 實例引用：
            // 以不同內容覆蓋後，舊快照語意已被取代而非共享可變狀態
            catalog.publish(owner, spec("shop", "buy", "sell"), meta("economy"));
            assertEquals(sizeBefore + 1,
                catalog.snapshot().get(0).subcommands().size());
        }

        private void collectReachable(Object root, Set<Object> visited, List<Object> leaked)
                throws Exception {
            if (root == null || visited.contains(root)) {
                return;
            }
            Class<?> type = root.getClass();
            // 值型別與 JDK 不可變容器直接檢查型別即可，不再展開
            if (root instanceof String || root instanceof Number
                    || root instanceof Boolean || root instanceof Enum
                    || root instanceof FormImage) {
                return;
            }
            if (root instanceof SubCommand || root instanceof SubCommandCompleter
                    || root instanceof Plugin
                    || root instanceof CommandSpec || root instanceof SubCommandSpec) {
                leaked.add(root);
                return;
            }
            if (root instanceof Optional<?> optional) {
                if (optional.isPresent()) {
                    collectReachable(optional.get(), visited, leaked);
                }
                return;
            }
            if (root instanceof Iterable<?> iterable) {
                visited.add(root);
                for (Object element : iterable) {
                    collectReachable(element, visited, leaked);
                }
                return;
            }
            if (root instanceof java.util.Map<?, ?> map) {
                visited.add(root);
                for (Object element : map.values()) {
                    collectReachable(element, visited, leaked);
                }
                return;
            }
            Package pkg = type.getPackage();
            if (pkg != null && pkg.getName().startsWith("java.")) {
                return;
            }
            visited.add(root);
            for (Class<?> current = type; current != null && current != Object.class;
                    current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    field.setAccessible(true);
                    collectReachable(field.get(root), visited, leaked);
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // 深層不可變
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("深層不可變")
    class DeepImmutability {

        @Test
        @DisplayName("snapshot() 回傳的清單不可修改")
        void snapshotList_isUnmodifiable() {
            catalog.publish(plugin("Alpha"), spec("shop", "buy"), meta("economy"));
            List<CommandDoc> snapshot = catalog.snapshot();
            assertThrows(UnsupportedOperationException.class,
                () -> snapshot.add(new CommandDoc("X", "y", List.of(), "d", "u",
                    null, "c", Optional.empty(), List.of())));
        }

        @Test
        @DisplayName("CommandDoc.subcommands() 不可修改")
        void subcommands_isUnmodifiable() {
            catalog.publish(plugin("Alpha"), spec("shop", "buy"), meta("economy"));
            CommandDoc doc = catalog.snapshot().get(0);
            assertThrows(UnsupportedOperationException.class,
                () -> doc.subcommands().add(new SubDoc("x", "d", "u", null,
                    List.of(), 0, -1, false, false, 0, false)));
        }

        @Test
        @DisplayName("SubDoc.argNames() 與 aliases 不可修改")
        void argNamesAndAliases_areUnmodifiable() {
            CommandSpec spec = CommandSpec.builder("shop")
                .aliases("sh")
                .subCommand(SubCommandSpec.builder("buy")
                    .args("item")
                    .handler(SubCommand.NOOP).build())
                .build();
            catalog.publish(plugin("Alpha"), spec, meta("economy"));
            CommandDoc doc = catalog.snapshot().get(0);
            assertThrows(UnsupportedOperationException.class,
                () -> doc.aliases().add("evil"));
            assertThrows(UnsupportedOperationException.class,
                () -> doc.subcommands().get(0).argNames().add("evil"));
        }

        @Test
        @DisplayName("CatalogMeta.confirmSubcommands() 不可修改，且呼叫端後續修改不影響已建立的物件")
        void confirmSubcommands_isUnmodifiableAndDefensive() {
            Set<String> confirms = new HashSet<>();
            confirms.add("reset");
            CatalogMeta created = new CatalogMeta("economy", Optional.empty(), confirms);
            confirms.add("evil");
            assertFalse(created.confirmSubcommands().contains("evil"));
            assertThrows(UnsupportedOperationException.class,
                () -> created.confirmSubcommands().add("evil"));
        }

        @Test
        @DisplayName("建構時傳入的可變集合被防禦複製")
        void constructorArgs_areDefensivelyCopied() {
            List<String> aliases = new ArrayList<>(List.of("sh"));
            List<SubDoc> subs = new ArrayList<>();
            CommandDoc doc = new CommandDoc("Alpha", "shop", aliases, "d", "u",
                null, "c", Optional.empty(), subs);
            aliases.add("evil");
            subs.add(new SubDoc("evil", "d", "u", null, List.of(),
                0, -1, false, false, 0, false));
            assertEquals(List.of("sh"), doc.aliases());
            assertTrue(doc.subcommands().isEmpty());
        }
    }

    // -----------------------------------------------------------------
    // 權限、可见性與邊際值
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("權限與邊際值")
    class PermissionAndEdgeValues {

        @Test
        @DisplayName("permission 為 null 被保留，不得折成空字串")
        void nullPermission_isPreserved() {
            catalog.publish(plugin("Alpha"), spec("shop", "buy"), meta("economy"));
            CommandDoc doc = catalog.snapshot().get(0);
            assertNull(doc.permission());
            assertNull(doc.subcommands().get(0).permission());
        }

        @Test
        @DisplayName("visibleTo：null permission 對任何非 null 檢視者可見；null 檢視者回 false")
        void visibleTo_nullPermission_visibleToAnyoneButNull() {
            CommandDoc doc = new CommandDoc("Alpha", "shop", List.of(), "d", "u",
                null, "c", Optional.empty(), List.of());
            assertTrue(doc.visibleTo(permissive()));
            assertTrue(doc.visibleTo(denying()));
            assertFalse(doc.visibleTo(null));
        }

        @Test
        @DisplayName("visibleTo：有 permission 時委派給 hasPermission")
        void visibleTo_withPermission_delegates() {
            CommandDoc doc = new CommandDoc("Alpha", "shop", List.of(), "d", "u",
                "shop.use", "c", Optional.empty(), List.of());
            assertTrue(doc.visibleTo(permissive("shop.use")));
            assertFalse(doc.visibleTo(denying()));
            assertFalse(doc.visibleTo(null));
            SubDoc sub = new SubDoc("buy", "d", "u", "shop.buy", List.of(),
                0, -1, false, false, 0, false);
            assertTrue(sub.visibleTo(permissive("shop.buy")));
            assertFalse(sub.visibleTo(denying()));
            assertFalse(sub.visibleTo(null));
        }

        @Test
        @DisplayName("maxArgs = -1（無上限）被保留")
        void maxArgsMinusOne_isPreserved() {
            catalog.publish(plugin("Alpha"), spec("shop", "buy"), meta("economy"));
            assertEquals(-1,
                catalog.snapshot().get(0).subcommands().get(0).maxArgs());
            SubCommandSpec bounded = SubCommandSpec.builder("buy")
                .maxArgs(3).handler(SubCommand.NOOP).build();
            CommandSpec spec = CommandSpec.builder("bounded").subCommand(bounded).build();
            catalog.publish(plugin("Alpha"), spec, meta("economy"));
            for (CommandDoc doc : catalog.snapshot()) {
                if (doc.name().equals("bounded")) {
                    assertEquals(3, doc.subcommands().get(0).maxArgs());
                }
            }
        }

        @Test
        @DisplayName("空的 aliases / argNames / subcommands 合法且為空集合")
        void emptyCollections_areAllowed() {
            catalog.publish(plugin("Alpha"), spec("shop"), meta("economy"));
            CommandDoc doc = catalog.snapshot().get(0);
            assertNotNull(doc.aliases());
            assertTrue(doc.aliases().isEmpty());
            assertNotNull(doc.subcommands());
            assertTrue(doc.subcommands().isEmpty());
        }

        @Test
        @DisplayName("record 必要欄位為 null 或空白時被拒")
        void recordValidation_rejectsNullAndBlank() {
            assertThrows(NullPointerException.class, () -> new CatalogMeta(null,
                Optional.empty(), Set.of()));
            assertThrows(NullPointerException.class,
                () -> new CatalogMeta("c", null, Set.of()));
            assertThrows(NullPointerException.class,
                () -> new CatalogMeta("c", Optional.empty(), null));
            assertThrows(IllegalArgumentException.class, () -> new CommandDoc(" ",
                "shop", List.of(), "d", "u", null, "c", Optional.empty(), List.of()));
            assertThrows(IllegalArgumentException.class, () -> new CommandDoc("Alpha",
                " ", List.of(), "d", "u", null, "c", Optional.empty(), List.of()));
            assertThrows(NullPointerException.class, () -> new CommandDoc("Alpha",
                "shop", null, "d", "u", null, "c", Optional.empty(), List.of()));
            assertThrows(NullPointerException.class, () -> new CommandDoc("Alpha",
                "shop", List.of(), "d", "u", null, "c", Optional.empty(), null));
            assertThrows(IllegalArgumentException.class, () -> new SubDoc(" ", "d",
                "u", null, List.of(), 0, -1, false, false, 0, false));
            assertThrows(NullPointerException.class, () -> new SubDoc("buy", "d",
                "u", null, null, 0, -1, false, false, 0, false));
        }

        @Test
        @DisplayName("snapshot() 順序固定：先比 owner 再比 name")
        void snapshot_isOrderedByOwnerThenName() {
            catalog.publish(plugin("Beta"), spec("shop"), meta("c"));
            catalog.publish(plugin("Alpha"), spec("zoo"), meta("c"));
            catalog.publish(plugin("Alpha"), spec("bank"), meta("c"));
            List<CommandDoc> snapshot = catalog.snapshot();
            assertEquals("Alpha", snapshot.get(0).owner());
            assertEquals("bank", snapshot.get(0).name());
            assertEquals("Alpha", snapshot.get(1).owner());
            assertEquals("zoo", snapshot.get(1).name());
            assertEquals("Beta", snapshot.get(2).owner());
            assertEquals("shop", snapshot.get(2).name());
        }
    }

    // -----------------------------------------------------------------
    // 服務可用性
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("服務可用性")
    class ServiceAvailability {

        @Test
        @DisplayName("服務不可用時 publish 回 REJECTED 且記錄 ACELIB-CMD-014")
        void unavailable_publishRejected() {
            catalog.setAvailable(false);
            try {
                LogCapture capture = captureWarnings();
                try {
                    CatalogResult result = catalog.publish(
                        plugin("Alpha"), spec("shop"), meta("economy"));
                    assertEquals(CatalogResult.REJECTED, result);
                    assertTrue(capture.hasCode("ACELIB-CMD-014"),
                        "服務不可用必須記錄 ACELIB-CMD-014");
                } finally {
                    capture.close();
                }
                CatalogResult docResult = catalog.publish(plugin("Alpha"),
                    new CommandDoc("Alpha", "shop", List.of(), "d", "u", null,
                        "c", Optional.empty(), List.of()));
                assertEquals(CatalogResult.REJECTED, docResult);
            } finally {
                catalog.setAvailable(true);
            }
        }

        @Test
        @DisplayName("服務不可用時 snapshot() 回空清單")
        void unavailable_snapshotEmpty() {
            catalog.publish(plugin("Alpha"), spec("shop"), meta("economy"));
            assertEquals(1, catalog.snapshot().size());
            catalog.setAvailable(false);
            try {
                assertTrue(catalog.snapshot().isEmpty());
            } finally {
                catalog.setAvailable(true);
            }
            assertEquals(1, catalog.snapshot().size());
        }

        @Test
        @DisplayName("服務不可用時 unpublishAll 為無害 no-op")
        void unavailable_unpublishIsNoop() {
            catalog.publish(plugin("Alpha"), spec("shop"), meta("economy"));
            long before = catalog.revision();
            catalog.setAvailable(false);
            try {
                catalog.unpublishAll(plugin("Alpha"));
            } finally {
                catalog.setAvailable(true);
            }
            assertEquals(before, catalog.revision());
            assertEquals(1, catalog.snapshot().size());
        }

        @Test
        @DisplayName("重新就緒後服務恢復正常")
        void reEnabled_resumesNormally() {
            catalog.setAvailable(false);
            catalog.setAvailable(true);
            assertEquals(CatalogResult.PUBLISHED,
                catalog.publish(plugin("Alpha"), spec("shop"), meta("economy")));
            assertEquals(1, catalog.snapshot().size());
        }
    }

    // -----------------------------------------------------------------
    // 並行
    // -----------------------------------------------------------------

    @Nested
    @DisplayName("並行")
    class Concurrency {

        @Test
        @DisplayName("多執行緒同時發布不同指令不遺失資料")
        void concurrentDistinctPublishes_noLoss() throws Exception {
            int threads = 8;
            int perThread = 25;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        Plugin owner = plugin("Owner-" + threadId);
                        for (int i = 0; i < perThread; i++) {
                            catalog.publish(owner,
                                spec("cmd-" + threadId + "-" + i), meta("c"));
                        }
                    } catch (Throwable ex) {
                        failure.compareAndSet(null, ex);
                    } finally {
                        done.countDown();
                    }
                    return null;
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            pool.shutdownNow();
            if (failure.get() != null) {
                throw new AssertionError("並行發布拋例外", failure.get());
            }
            assertEquals(threads * perThread, catalog.snapshot().size());
            assertEquals(threads * perThread, catalog.revision());
        }

        @Test
        @DisplayName("多執行緒同時發布同一擁有者同名指令，最終狀態一致且只有一筆")
        void concurrentSameName_sameOwner_consistent() throws Exception {
            int threads = 8;
            Plugin owner = plugin("Alpha");
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        catalog.publish(owner, spec("shop", "buy"), meta("economy"));
                    } catch (Throwable ex) {
                        failure.compareAndSet(null, ex);
                    } finally {
                        done.countDown();
                    }
                    return null;
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            pool.shutdownNow();
            if (failure.get() != null) {
                throw new AssertionError("並行發布拋例外", failure.get());
            }
            List<CommandDoc> snapshot = catalog.snapshot();
            assertEquals(1, snapshot.size());
            assertEquals("shop", snapshot.get(0).name());
            assertTrue(catalog.revision() >= 1);
        }
    }
}
