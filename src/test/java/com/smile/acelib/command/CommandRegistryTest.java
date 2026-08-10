package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smile.acelib.diagnostics.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link CommandRegistryImpl} 核心 dispatcher 測試。
 *
 * <p>對應 Plan §十一全部驗收標準：</p>
 * <ul>
 *   <li>主指令與子指令可宣告；權限 / 玩家限定 / console 可用可設定</li>
 *   <li>參數錯誤、未知子指令、無權限訊息清楚；help 與 tab 補全可運作</li>
 *   <li>tab 補全不暴露無權限指令</li>
 *   <li>非同步指令流程完成後回覆仍符合 Folia 安全上下文（透過 ReplySink 抽象）</li>
 *   <li>冷卻 / 防重複觸發在 reload 過程中也不會破壞狀態</li>
 *   <li>指令執行後立刻離線 / 中途查詢錯誤能被正確處理</li>
 * </ul>
 *
 * <h2>測試設計</h2>
 * <p>所有測試使用純 {@link Sender} mock + {@link RecordingReplySink}，
 * 不依賴 MockBukkit；Bukkit adapter 行為另在
 * {@code CommandRegistryBukkitTest} 測試。</p>
 */
@DisplayName("CommandRegistry 核心 dispatcher")
class CommandRegistryTest {

    private RecordingReplySink replySink;
    private TestClock clock;
    private CooldownTracker cooldowns;
    private CommandRegistryImpl registry;

    @BeforeEach
    void setUp() {
        replySink = new RecordingReplySink();
        clock = new TestClock(1000);
        cooldowns = new CooldownTracker(clock);
        registry = new CommandRegistryImpl(replySink, cooldowns);
    }

    // ---------------------------------------------------------------------
    // 註冊 / 查找
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("註冊與查詢")
    class Registration {

        @Test
        @DisplayName("register 後可透過主名與別名查詢")
        void register_andFindByNameAndAlias() {
            CommandSpec spec = CommandSpec.builder("acelib")
                .aliases("al", "lib")
                .description("AceLib")
                .build();
            registry.register(spec);
            assertSame(spec, registry.findCommand("acelib"));
            assertSame(spec, registry.findCommand("ACELIB"));
            assertSame(spec, registry.findCommand("AL"));
            assertSame(spec, registry.findCommand("lib"));
            assertSame(spec, registry.findCommand("LIB"));
            assertNull(registry.findCommand("nonexistent"));
            assertNull(registry.findCommand("acelibx"));
        }

        @Test
        @DisplayName("重複註冊同名拋 IllegalArgumentException")
        void duplicatePrimary_throws() {
            registry.register(CommandSpec.builder("acelib").build());
            assertThrows(IllegalArgumentException.class,
                () -> registry.register(CommandSpec.builder("acelib").build()));
        }

        @Test
        @DisplayName("別名與既有主名衝突拋 IllegalArgumentException")
        void aliasConflict_throws() {
            registry.register(CommandSpec.builder("acelib").build());
            assertThrows(IllegalArgumentException.class,
                () -> registry.register(CommandSpec.builder("lib").aliases("acelib").build()));
        }

        @Test
        @DisplayName("unregister 解除主指令後 findCommand 回 null；別名也連帶清除")
        void unregister_clearsPrimaryAndAliases() {
            CommandSpec spec = CommandSpec.builder("acelib")
                .aliases("al").build();
            registry.register(spec);
            registry.unregister("acelib");
            assertNull(registry.findCommand("acelib"));
            assertNull(registry.findCommand("al"));
            assertTrue(registry.getRegisteredCommands().isEmpty());
        }

        @Test
        @DisplayName("unregister 不存在的名稱為 no-op，不丟例外")
        void unregister_unknown_isNoop() {
            assertDoesNotThrow(() -> registry.unregister("nope"));
        }

        @Test
        @DisplayName("register 對 null spec 拋 NPE")
        void registerNull_throws() {
            assertThrows(NullPointerException.class, () -> registry.register(null));
        }
    }

    // ---------------------------------------------------------------------
    // 路由 / dispatch
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("dispatch 路由")
    class Dispatch {

        @Test
        @DisplayName("已知指令 + 子指令 → 對應 handler 被呼叫")
        void knownCommand_dispatchesToHandler() {
            java.util.concurrent.atomic.AtomicReference<CommandContext> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("status")
                    .description("show status")
                    .handler(ctx -> captured.set(ctx))
                    .build())
                .build());
            TestSender sender = new TestSender("alice", false);
            registry.dispatch(sender, "acelib", List.of("status"));
            assertNotNull(captured.get());
            assertSame(sender, captured.get().sender());
            assertEquals("status", captured.get().sub().name());
            assertTrue(replySink.sent.isEmpty(),
                "成功 dispatch 不應輸出錯誤訊息");
        }

        @Test
        @DisplayName("未知主指令 → ACELIB-CMD-002 unknown command")
        void unknownCommand_returnsCmd002() {
            TestSender sender = new TestSender("alice", false);
            registry.dispatch(sender, "nope", List.of());
            CommandException ex = replySink.lastError();
            assertNotNull(ex);
            assertEquals(CommandErrorKind.UNKNOWN_SUBCOMMAND, ex.getKind());
            assertEquals("ACELIB-CMD-002", ex.getCode());
        }

        @Test
        @DisplayName("未知子指令 → ACELIB-CMD-002 unknown subcommand")
        void unknownSubCommand_returnsCmd002() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("status").handler(SubCommand.NOOP).build())
                .build());
            TestSender sender = new TestSender("alice", false);
            registry.dispatch(sender, "acelib", List.of("nope"));
            CommandException ex = replySink.lastError();
            assertNotNull(ex);
            assertEquals(CommandErrorKind.UNKNOWN_SUBCOMMAND, ex.getKind());
            assertEquals("ACELIB-CMD-002", ex.getCode());
            assertEquals("nope", ex.getVars().get("sub"));
        }

        @Test
        @DisplayName("args 為空 → 顯示主指令 help（含權限過濾後的子指令）")
        void emptyArgs_showsHelp() {
            registry.register(CommandSpec.builder("acelib")
                .description("AceLib root")
                .subCommand(SubCommandSpec.builder("status")
                    .description("show status").handler(SubCommand.NOOP).build())
                .subCommand(SubCommandSpec.builder("reload")
                    .permission("acelib.reload")
                    .description("reload config").handler(SubCommand.NOOP).build())
                .build());
            TestSender sender = new TestSender("alice", false);
            registry.dispatch(sender, "acelib", List.of());
            assertEquals(1, replySink.sent.size());
            String help = replySink.sent.get(0);
            assertTrue(help.contains("acelib"));
            assertTrue(help.contains("status"));
            assertTrue(help.contains("show status"));
        }
    }

    // ---------------------------------------------------------------------
    // 權限檢查
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("權限檢查")
    class Permission {

        @Test
        @DisplayName("子指令需要權限；無權限 sender → ACELIB-CMD-003")
        void subcommandNoPermission_returnsCmd003() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("reload")
                    .permission("acelib.reload")
                    .handler(SubCommand.NOOP).build())
                .build());
            TestSender noPerm = new TestSender("alice", false);
            registry.dispatch(noPerm, "acelib", List.of("reload"));
            CommandException ex = replySink.lastError();
            assertNotNull(ex);
            assertEquals(CommandErrorKind.NO_PERMISSION, ex.getKind());
            assertEquals("ACELIB-CMD-003", ex.getCode());
            assertEquals("acelib.reload", ex.getVars().get("permission"));
        }

        @Test
        @DisplayName("有權限 sender → handler 被執行")
        void subcommandWithPermission_runsHandler() {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("reload")
                    .permission("acelib.reload")
                    .handler(ctx -> ran.set(true)).build())
                .build());
            TestSender withPerm = new TestSender("alice", false);
            withPerm.grant("acelib.reload");
            registry.dispatch(withPerm, "acelib", List.of("reload"));
            assertTrue(ran.get());
        }

        @Test
        @DisplayName("主指令權限也需檢查")
        void mainCommandNoPermission_returnsCmd003() {
            registry.register(CommandSpec.builder("acelib")
                .permission("acelib.root")
                .subCommand(SubCommandSpec.builder("status").handler(SubCommand.NOOP).build())
                .build());
            TestSender sender = new TestSender("alice", false);
            registry.dispatch(sender, "acelib", List.of("status"));
            CommandException ex = replySink.lastError();
            assertNotNull(ex);
            assertEquals(CommandErrorKind.NO_PERMISSION, ex.getKind());
        }

        @Test
        @DisplayName("權限為 null → 無權限需求")
        void nullPermission_alwaysAllowed() {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("status")
                    .handler(ctx -> ran.set(true)).build())
                .build());
            registry.dispatch(new TestSender("alice", false), "acelib", List.of("status"));
            assertTrue(ran.get());
        }
    }

    // ---------------------------------------------------------------------
    // 玩家 / console 限定
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("玩家 / console 限定")
    class SenderRestriction {

        @Test
        @DisplayName("playerOnly 子指令被 console 觸發 → ACELIB-CMD-004")
        void playerOnly_fromConsole_returnsCmd004() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("fly")
                    .playerOnly()
                    .handler(SubCommand.NOOP).build())
                .build());
            TestSender console = new TestSender("console", false);
            registry.dispatch(console, "acelib", List.of("fly"));
            assertEquals(CommandErrorKind.CONSOLE_NOT_ALLOWED, replySink.lastError().getKind());
            assertEquals("ACELIB-CMD-004", replySink.lastError().getCode());
        }

        @Test
        @DisplayName("playerOnly 子指令被玩家觸發 → handler 被執行")
        void playerOnly_fromPlayer_runsHandler() {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("fly")
                    .playerOnly()
                    .handler(ctx -> ran.set(true)).build())
                .build());
            registry.dispatch(new TestSender("alice", true),
                "acelib", List.of("fly"));
            assertTrue(ran.get());
        }

        @Test
        @DisplayName("consoleOnly 子指令被玩家觸發 → ACELIB-CMD-005")
        void consoleOnly_fromPlayer_returnsCmd005() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("reloadAll")
                    .consoleOnly()
                    .handler(SubCommand.NOOP).build())
                .build());
            TestSender player = new TestSender("alice", true);
            registry.dispatch(player, "acelib", List.of("reloadAll"));
            assertEquals(CommandErrorKind.PLAYER_NOT_ALLOWED, replySink.lastError().getKind());
            assertEquals("ACELIB-CMD-005", replySink.lastError().getCode());
        }

        @Test
        @DisplayName("consoleOnly 子指令被 console 觸發 → handler 被執行")
        void consoleOnly_fromConsole_runsHandler() {
            java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("reloadAll")
                    .consoleOnly()
                    .handler(ctx -> ran.set(true)).build())
                .build());
            registry.dispatch(new TestSender("console", false),
                "acelib", List.of("reloadAll"));
            assertTrue(ran.get());
        }
    }

    // ---------------------------------------------------------------------
    // 參數數量檢查
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("參數數量檢查")
    class ArgsValidation {

        @Test
        @DisplayName("minArgs 未滿足 → ACELIB-CMD-001")
        void missingArgs_returnsCmd001() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("set")
                    .usage("<key> <value>")
                    .minArgs(2)
                    .handler(SubCommand.NOOP).build())
                .build());
            registry.dispatch(new TestSender("alice", true),
                "acelib", List.of("set", "key"));
            CommandException ex = replySink.lastError();
            assertEquals(CommandErrorKind.MISSING_ARGUMENTS, ex.getKind());
            assertEquals("ACELIB-CMD-001", ex.getCode());
            assertEquals("<key> <value>", ex.getVars().get("usage"));
        }

        @Test
        @DisplayName("超過 maxArgs → ACELIB-CMD-001")
        void tooManyArgs_returnsCmd001() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("set")
                    .maxArgs(1)
                    .handler(SubCommand.NOOP).build())
                .build());
            registry.dispatch(new TestSender("alice", true),
                "acelib", List.of("set", "a", "b"));
            CommandException ex = replySink.lastError();
            assertEquals(CommandErrorKind.MISSING_ARGUMENTS, ex.getKind());
            assertEquals("ACELIB-CMD-001", ex.getCode());
        }

        @Test
        @DisplayName("參數數量在範圍內 → handler 被執行")
        void validArgs_runsHandler() {
            java.util.concurrent.atomic.AtomicReference<List<String>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("set")
                    .minArgs(1)
                    .maxArgs(2)
                    .handler(ctx -> captured.set(ctx.commandArgs())).build())
                .build());
            registry.dispatch(new TestSender("alice", true),
                "acelib", List.of("set", "key", "value"));
            assertEquals(List.of("key", "value"), captured.get());
        }
    }

    // ---------------------------------------------------------------------
    // 冷卻檢查
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("冷卻檢查")
    class Cooldown {

        @Test
        @DisplayName("同玩家短時間重複觸發 → 第二次 ACELIB-CMD-006")
        void rapidRetrigger_returnsCmd006() {
            java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("ping")
                    .cooldownMillis(5000)
                    .handler(ctx -> counter.incrementAndGet()).build())
                .build());
            TestSender sender = new TestSender("alice", true);
            UUID playerId = sender.asPlayer().getUniqueId();
            registry.dispatch(sender, "acelib", List.of("ping"));
            assertEquals(1, counter.get());
            // 第二次（在冷卻期內）
            registry.dispatch(sender, "acelib", List.of("ping"));
            assertEquals(1, counter.get(), "第二次應被冷卻擋下");
            CommandException ex = replySink.lastError();
            assertEquals(CommandErrorKind.COOLDOWN_ACTIVE, ex.getKind());
            assertEquals("ACELIB-CMD-006", ex.getCode());
        }

        @Test
        @DisplayName("冷卻時間經過後可再次觸發")
        void cooldownExpires_allowsRetrigger() {
            java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("ping")
                    .cooldownMillis(1000)
                    .handler(ctx -> counter.incrementAndGet()).build())
                .build());
            TestSender sender = new TestSender("alice", true);
            registry.dispatch(sender, "acelib", List.of("ping"));
            clock.advance(1500);
            registry.dispatch(sender, "acelib", List.of("ping"));
            assertEquals(2, counter.get());
        }

        @Test
        @DisplayName("冷卻不影響 console sender")
        void cooldownDoesNotApplyToConsole() {
            java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("ping")
                    .cooldownMillis(5000)
                    .handler(ctx -> counter.incrementAndGet()).build())
                .build());
            TestSender console = new TestSender("console", false);
            registry.dispatch(console, "acelib", List.of("ping"));
            registry.dispatch(console, "acelib", List.of("ping"));
            assertEquals(2, counter.get(),
                "console sender 不受冷卻限制（Plan §十一：玩家導向的防重複）");
        }

        @Test
        @DisplayName("reload 流程中（disable → register 重新）冷卻狀態保留")
        void reload_preservesCooldowns() {
            java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger();
            CommandSpec spec = CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("ping")
                    .cooldownMillis(5000)
                    .handler(ctx -> counter.incrementAndGet()).build())
                .build();
            registry.register(spec);
            TestSender sender = new TestSender("alice", true);
            registry.dispatch(sender, "acelib", List.of("ping"));
            assertEquals(1, counter.get());

            // 模擬 reload：disable + unregister + 重新 register 同一個 spec
            registry.onPluginDisable();
            registry.unregister("acelib");
            // 重新持有同一個 CooldownTracker
            CommandRegistryImpl newRegistry = new CommandRegistryImpl(replySink, cooldowns);
            newRegistry.register(spec);
            newRegistry.dispatch(sender, "acelib", List.of("ping"));
            // cooldown 仍在
            assertEquals(1, counter.get(),
                "reload 後冷卻狀態應保留（Plan §十一驗收標準）");
        }
    }

    // ---------------------------------------------------------------------
    // Handler 例外處理
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("handler 例外處理")
    class HandlerException {

        @Test
        @DisplayName("handler 拋 CommandException → 自動呼叫 sendError")
        void handlerThrowsCommandException_callSendError() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("fail")
                    .handler(ctx -> {
                        throw CommandException.custom("ACELIB-CMD-099", "my custom fail");
                    })
                    .build())
                .build());
            registry.dispatch(new TestSender("alice", false),
                "acelib", List.of("fail"));
            CommandException ex = replySink.lastError();
            assertNotNull(ex);
            assertEquals("ACELIB-CMD-099", ex.getCode());
            assertEquals("my custom fail", ex.getMessage());
        }

        @Test
        @DisplayName("handler 拋 RuntimeException（非 CommandException）→ 包裝為 ACELIB-CMD-008")
        void handlerThrowsRuntimeException_wrapsAsCmd008() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("fail")
                    .handler(ctx -> {
                        throw new IllegalStateException("db down");
                    })
                    .build())
                .build());
            registry.dispatch(new TestSender("alice", false),
                "acelib", List.of("fail"));
            CommandException ex = replySink.lastError();
            assertNotNull(ex);
            assertEquals(CommandErrorKind.ASYNC_EXECUTION_FAILED, ex.getKind());
            assertEquals("ACELIB-CMD-008", ex.getCode());
            assertTrue(ex.getMessage().contains("db down"));
        }
    }

    // ---------------------------------------------------------------------
    // 玩家離線 / 失效處理
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("玩家離線 / 失效處理")
    class PlayerOffline {

        @Test
        @DisplayName("requireOnlinePlayer 對離線玩家拋 ACELIB-CMD-007")
        void requireOnlinePlayer_offline_throwsCmd007() {
            java.util.concurrent.atomic.AtomicBoolean reached = new java.util.concurrent.atomic.AtomicBoolean();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("check")
                    .handler(ctx -> {
                        try {
                            ctx.requireOnlinePlayer();
                            reached.set(true);
                        } catch (CommandException ignore) {
                            // handler 拋出 → dispatcher 透過 sendError 接收
                            throw ignore;
                        }
                    })
                    .build())
                .build());
            TestSender sender = new TestSender("alice", true);
            sender.markOffline();
            registry.dispatch(sender, "acelib", List.of("check"));
            assertFalse(reached.get(), "requireOnlinePlayer 應在 mutate 之前拋例外");
            CommandException ex = replySink.lastError();
            assertEquals(CommandErrorKind.PLAYER_OFFLINE, ex.getKind());
            assertEquals("ACELIB-CMD-007", ex.getCode());
        }

        @Test
        @DisplayName("在線玩家 requireOnlinePlayer 不拋例外")
        void requireOnlinePlayer_online_ok() {
            java.util.concurrent.atomic.AtomicReference<PlayerHandle> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("check")
                    .handler(ctx -> captured.set(ctx.requireOnlinePlayer()))
                    .build())
                .build());
            TestSender sender = new TestSender("alice", true);
            registry.dispatch(sender, "acelib", List.of("check"));
            assertNotNull(captured.get());
            assertTrue(replySink.sent.isEmpty());
            assertNull(replySink.lastError());
        }

        @Test
        @DisplayName("handler 中途查詢錯誤（拋 RuntimeException）→ 包裝為 CMD-008")
        void midExecutionError_wrapsAsCmd008() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("query")
                    .handler(ctx -> {
                        // 模擬「中途資料查詢錯誤」（Plan §十一 邊界條件）
                        try {
                            throw new RuntimeException("data not loaded yet");
                        } catch (RuntimeException ex) {
                            // 重新包裝為 CommandException
                            throw new CommandException(
                                CommandErrorKind.ASYNC_EXECUTION_FAILED,
                                "data not loaded",
                                Map.of("sub", "query", "cause", ex.getMessage()));
                        }
                    })
                    .build())
                .build());
            registry.dispatch(new TestSender("alice", false),
                "acelib", List.of("query"));
            CommandException ex = replySink.lastError();
            assertEquals("ACELIB-CMD-008", ex.getCode());
            assertTrue(ex.getMessage().contains("data not loaded"));
        }
    }

    // ---------------------------------------------------------------------
    // Tab Complete
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Tab Complete")
    class TabComplete {

        @Test
        @DisplayName("args 為空 → 列出主指令名 + 可見子指令")
        void emptyArgs_listsMainAndVisibleSubs() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("status").handler(SubCommand.NOOP).build())
                .subCommand(SubCommandSpec.builder("reload")
                    .permission("acelib.reload").handler(SubCommand.NOOP).build())
                .build());
            TestSender noPerm = new TestSender("alice", false);
            List<String> result = registry.tabComplete(noPerm, "acelib", List.of());
            assertTrue(result.contains("acelib"));
            assertTrue(result.contains("status"));
            assertFalse(result.contains("reload"),
                "無權限子指令不應出現在 tab 補全");
        }

        @Test
        @DisplayName("args[0] 已知子指令 → 委派給 completer")
        void knownSubcommand_delegatesToCompleter() {
            java.util.concurrent.atomic.AtomicReference<CommandContext> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("set")
                    .handler(SubCommand.NOOP)
                    .completer((ctx, args) -> {
                        captured.set(ctx);
                        return List.of("alpha", "beta", "gamma");
                    })
                    .build())
                .build());
            TestSender sender = new TestSender("alice", false);
            List<String> result = registry.tabComplete(sender, "acelib", List.of("set"));
            assertEquals(List.of("alpha", "beta", "gamma"), result);
            assertNotNull(captured.get());
            assertEquals("set", captured.get().sub().name());
        }

        @Test
        @DisplayName("tab 補全不暴露無權限指令")
        void tabComplete_doesNotLeakNoPermission() {
            java.util.concurrent.atomic.AtomicReference<List<String>> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("reload")
                    .permission("acelib.reload")
                    .handler(SubCommand.NOOP)
                    .completer((ctx, args) -> {
                        captured.set(List.of("secret-arg-1", "secret-arg-2"));
                        return List.of("secret-arg-1", "secret-arg-2");
                    })
                    .build())
                .build());
            TestSender noPerm = new TestSender("alice", false);
            List<String> result = registry.tabComplete(noPerm, "acelib", List.of("reload"));
            assertTrue(result.isEmpty(),
                "無權限子指令的 completer 不應被呼叫");
            assertNull(captured.get());
        }

        @Test
        @DisplayName("未知主指令 → 回傳空 list")
        void unknownCommand_returnsEmpty() {
            assertTrue(registry.tabComplete(
                new TestSender("alice", false), "nope", List.of()).isEmpty());
        }

        @Test
        @DisplayName("args[0] 部分字串前綴 → 列出以該字串開頭的可見子指令")
        void partialPrefix_listsMatchingSubs() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("reload").handler(SubCommand.NOOP).build())
                .subCommand(SubCommandSpec.builder("restart").handler(SubCommand.NOOP).build())
                .subCommand(SubCommandSpec.builder("status").handler(SubCommand.NOOP).build())
                .build());
            TestSender sender = new TestSender("alice", false);
            List<String> result = registry.tabComplete(sender, "acelib", List.of("re"));
            assertTrue(result.contains("reload"));
            assertTrue(result.contains("restart"));
            assertFalse(result.contains("status"));
        }

        @Test
        @DisplayName("completer 拋 RuntimeException → 回傳空 list 不中斷")
        void completerThrows_returnsEmpty() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("set")
                    .handler(SubCommand.NOOP)
                    .completer((ctx, args) -> {
                        throw new IllegalStateException("nope");
                    })
                    .build())
                .build());
            List<String> result = registry.tabComplete(
                new TestSender("alice", false), "acelib", List.of("set"));
            assertTrue(result.isEmpty());
        }
    }

    // ---------------------------------------------------------------------
    // Help 格式化
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Help 格式化")
    class Help {

        @Test
        @DisplayName("formatHelp 包含主指令名與所有可見子指令")
        void formatHelp_includesMainAndVisibleSubs() {
            registry.register(CommandSpec.builder("acelib")
                .description("AceLib root command")
                .subCommand(SubCommandSpec.builder("status")
                    .description("show status").handler(SubCommand.NOOP).build())
                .subCommand(SubCommandSpec.builder("reload")
                    .permission("acelib.reload")
                    .description("reload config").handler(SubCommand.NOOP).build())
                .build());
            TestSender noPerm = new TestSender("alice", false);
            String help = registry.formatHelp("acelib", noPerm);
            assertTrue(help.contains("acelib"));
            assertTrue(help.contains("show status"));
            assertFalse(help.contains("reload config"),
                "無權限子指令不應出現在 help");
        }

        @Test
        @DisplayName("未知主指令 → formatHelp 回空字串")
        void unknownCommand_emptyHelp() {
            assertEquals("", registry.formatHelp("nope",
                new TestSender("alice", false)));
        }

        @Test
        @DisplayName("有權限 sender 看到所有子指令")
        void withPermission_seesAllSubs() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("status")
                    .description("show status").handler(SubCommand.NOOP).build())
                .subCommand(SubCommandSpec.builder("reload")
                    .permission("acelib.reload")
                    .description("reload config").handler(SubCommand.NOOP).build())
                .build());
            TestSender admin = new TestSender("alice", false);
            admin.grant("acelib.reload");
            String help = registry.formatHelp("acelib", admin);
            assertTrue(help.contains("show status"));
            assertTrue(help.contains("reload config"));
        }
    }

    // ---------------------------------------------------------------------
    // Disable 行為
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Disable / Reload 行為")
    class DisableBehavior {

        @Test
        @DisplayName("onPluginDisable 後 dispatch → ACELIB-CMD-009")
        void disabledDispatch_returnsCmd009() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("status").handler(SubCommand.NOOP).build())
                .build());
            registry.onPluginDisable();
            registry.dispatch(new TestSender("alice", false),
                "acelib", List.of("status"));
            CommandException ex = replySink.lastError();
            assertNotNull(ex);
            assertEquals(CommandErrorKind.REGISTRY_DISABLED, ex.getKind());
            assertEquals("ACELIB-CMD-009", ex.getCode());
        }

        @Test
        @DisplayName("disabled 後 register 拋 ACELIB-CMD-009")
        void disabledRegister_throws() {
            registry.onPluginDisable();
            assertThrows(CommandException.class,
                () -> registry.register(CommandSpec.builder("newone").build()));
        }

        @Test
        @DisplayName("重複 onPluginDisable 不丟例外（idempotent）")
        void doubleDisable_isNoop() {
            registry.onPluginDisable();
            assertDoesNotThrow(() -> registry.onPluginDisable());
        }

        @Test
        @DisplayName("disabled 後 tabComplete 回空 list")
        void disabledTabComplete_returnsEmpty() {
            registry.register(CommandSpec.builder("acelib")
                .subCommand(SubCommandSpec.builder("status").handler(SubCommand.NOOP).build())
                .build());
            registry.onPluginDisable();
            assertTrue(registry.tabComplete(
                new TestSender("alice", false), "acelib", List.of()).isEmpty());
        }
    }

    // ---------------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------------

    private static void assertDoesNotThrow(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            throw new AssertionError("expected no throw, got " + t);
        }
    }

    // -----------------------------------------------------------------
    // Sender mock
    // -----------------------------------------------------------------

    static final class TestSender implements Sender {
        private final String name;
        private final boolean player;
        private final UUID uuid;
        private final Set<String> perms = new HashSet<>();
        private final TestPlayerHandle playerHandle;

        TestSender(String name, boolean player) {
            this.name = name;
            this.player = player;
            this.uuid = UUID.nameUUIDFromBytes(name.getBytes());
            this.playerHandle = player ? new TestPlayerHandle(this.uuid, name) : null;
        }

        void grant(String permission) {
            perms.add(permission);
        }

        void markOffline() {
            if (playerHandle != null) {
                playerHandle.online = false;
            }
        }

        @Override
        public String getName() { return name; }

        @Override
        public boolean isPlayer() { return player; }

        @Override
        public PlayerHandle asPlayer() { return playerHandle; }

        @Override
        public boolean hasPermission(String permission) {
            if (permission == null || permission.isEmpty()) return true;
            return perms.contains(permission);
        }

        @Override
        public Locale getLocale() { return Locale.US; }
    }

    static final class TestPlayerHandle implements PlayerHandle {
        private final UUID uuid;
        private final String name;
        volatile boolean online = true;

        TestPlayerHandle(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }

        @Override public UUID getUniqueId() { return uuid; }
        @Override public boolean isOnline() { return online; }
        @Override public String getName() { return name; }
        @Override public Locale getLocale() { return Locale.US; }
    }

    // -----------------------------------------------------------------
    // ReplySink recording
    // -----------------------------------------------------------------

    static final class RecordingReplySink implements ReplySink {
        final List<String> sent = new CopyOnWriteArrayList<>();
        final List<Throwable> errors = new CopyOnWriteArrayList<>();
        final List<AsyncDispatch> asyncDispatches = new CopyOnWriteArrayList<>();

        String lastSent() {
            return sent.isEmpty() ? null : sent.get(sent.size() - 1);
        }

        CommandException lastError() {
            for (int i = errors.size() - 1; i >= 0; i--) {
                Throwable t = errors.get(i);
                if (t instanceof CommandException) {
                    return (CommandException) t;
                }
            }
            return null;
        }

        @Override
        public void send(Sender sender, String message) {
            sent.add(message);
        }

        @Override
        public void sendError(Sender sender, Throwable error) {
            errors.add(error);
        }

        @Override
        public void sendPlayerAsync(PlayerHandle player, String message) {
            asyncDispatches.add(new AsyncDispatch(player, message));
        }
    }

    record AsyncDispatch(PlayerHandle player, String message) { }

    // -----------------------------------------------------------------
    // 注入式時鐘
    // -----------------------------------------------------------------

    static final class TestClock implements Clock {
        private final AtomicLong now;

        TestClock(long initial) {
            this.now = new AtomicLong(initial);
        }

        void advance(long delta) {
            now.addAndGet(delta);
        }

        @Override
        public long currentTimeMillis() {
            return now.get();
        }
    }
}