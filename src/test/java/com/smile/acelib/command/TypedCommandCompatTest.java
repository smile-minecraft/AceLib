package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 型別化子指令／根指令與既有 {@link SubCommandSpec} 相容層測試（Slice 2）。
 *
 * <p>驗證：builder 組裝 → {@code toSubCommandSpec} 轉出的 {@link SubCommandSpec}
 * 可直接註冊進 {@link CommandRegistryImpl}，沿用既有的權限／玩家限定／
 * 參數數量／冷卻／help 流程；型別化 handler 透過 {@link TypedContext} 取得
 * 已解析值；解析失敗路由為 {@code ACELIB-CMD-015}。</p>
 */
@DisplayName("型別化指令與 SubCommandSpec 相容層")
class TypedCommandCompatTest {

    private CommandRegistryTest.RecordingReplySink sink;
    private CommandRegistryImpl registry;
    private CommandRegistryTest.TestClock clock;

    private CommandArgument<String> targetArg;
    private CommandArgument<Long> lengthArg;

    @BeforeEach
    void setUp() {
        sink = new CommandRegistryTest.RecordingReplySink();
        clock = new CommandRegistryTest.TestClock(1_000L);
        registry = new CommandRegistryImpl(sink, new CooldownTracker(clock));
        targetArg = new TestArgs.NameArg("target");
        lengthArg = new TestArgs.LongArg("length");
    }

    private CommandRegistryTest.TestSender console() {
        return new CommandRegistryTest.TestSender("Console", false);
    }

    private TypedSubCommand banSub(AtomicReference<String> seenTarget,
                             AtomicReference<Long> seenLength) {
        return TypedSubCommand.builder("ban")
            .description("停權玩家")
            .argument(targetArg)
            .argument(lengthArg)
            .executes(ctx -> {
                seenTarget.set(ctx.get(targetArg));
                seenLength.set(ctx.get(lengthArg));
            })
            .build();
    }

    private void registerBan(AtomicReference<String> seenTarget,
                             AtomicReference<Long> seenLength) {
        TypedCommand cmd = TypedCommand.builder("punish")
            .description("處分指令")
            .subcommand(banSub(seenTarget, seenLength))
            .build();
        registry.register(cmd.toCommandSpec());
    }

    @Nested
    @DisplayName("相容層 dispatch")
    class CompatDispatch {

        @Test
        @DisplayName("合法輸入 → 型別化 handler 收到已解析值")
        void validInput_typedHandlerReceivesValues() {
            AtomicReference<String> seenTarget = new AtomicReference<>();
            AtomicReference<Long> seenLength = new AtomicReference<>();
            registerBan(seenTarget, seenLength);
            registry.dispatch(console(), "punish", List.of("ban", "Steve", "100"));
            assertEquals("Steve", seenTarget.get());
            assertEquals(100L, seenLength.get());
            assertTrue(sink.errors.isEmpty(), "不應有錯誤；實際: " + sink.errors);
        }

        @Test
        @DisplayName("型別解析失敗 → ACELIB-CMD-015 且 handler 不執行")
        void parseFailure_invalidArgument_handlerNotRun() {
            AtomicReference<String> seenTarget = new AtomicReference<>();
            registerBan(seenTarget, new AtomicReference<>());
            registry.dispatch(console(), "punish", List.of("ban", "Steve", "not-a-number"));
            assertEquals(null, seenTarget.get());
            CommandException err = sink.lastError();
            assertTrue(err != null, "應有錯誤輸出");
            assertEquals(CommandErrorKind.INVALID_ARGUMENT, err.getKind());
            assertEquals("ACELIB-CMD-015", err.getCode());
        }

        @Test
        @DisplayName("參數數量不符 → 沿用 MISSING_ARGUMENTS（minArgs=maxArgs=引數數）")
        void argCountMismatch_missingArguments() {
            registerBan(new AtomicReference<>(), new AtomicReference<>());
            registry.dispatch(console(), "punish", List.of("ban", "Steve"));
            CommandException err = sink.lastError();
            assertTrue(err != null, "應有錯誤輸出");
            assertEquals(CommandErrorKind.MISSING_ARGUMENTS, err.getKind());
            registry.dispatch(console(), "punish", List.of("ban", "Steve", "100", "extra"));
            assertEquals(CommandErrorKind.MISSING_ARGUMENTS, sink.lastError().getKind());
        }

        @Test
        @DisplayName("usage 自動帶出引數 token")
        void usage_containsArgTokens() {
            AtomicReference<String> t = new AtomicReference<>();
            AtomicReference<Long> l = new AtomicReference<>();
            TypedSubCommand sub = banSub(t, l);
            String usage = sub.toSubCommandSpec().usage();
            assertTrue(usage.contains("target"), "usage 應含 target；實際: " + usage);
            assertTrue(usage.contains("length"), "usage 應含 length；實際: " + usage);
        }
    }

    @Nested
    @DisplayName("沿用既有能力")
    class ReusedCapabilities {

        @Test
        @DisplayName("權限不足 → NO_PERMISSION（registry 既有流程）")
        void noPermission_rejected() {
            AtomicReference<String> t = new AtomicReference<>();
            AtomicReference<Long> l = new AtomicReference<>();
            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(TypedSubCommand.builder("ban")
                    .permission("punish.ban")
                    .argument(targetArg)
                    .argument(lengthArg)
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());
            registry.dispatch(console(), "punish", List.of("ban", "Steve", "100"));
            assertEquals(CommandErrorKind.NO_PERMISSION, sink.lastError().getKind());
        }

        @Test
        @DisplayName("冷卻沿用 CooldownTracker（第二擊 COOLDOWN_ACTIVE）")
        void cooldown_reused() {
            AtomicReference<String> t = new AtomicReference<>();
            AtomicReference<Long> l = new AtomicReference<>();
            CommandRegistryTest.TestSender player =
                new CommandRegistryTest.TestSender("Steve", true);
            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(TypedSubCommand.builder("ban")
                    .cooldownMillis(60_000L)
                    .argument(targetArg)
                    .argument(lengthArg)
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());
            registry.dispatch(player, "punish", List.of("ban", "Steve", "100"));
            assertTrue(sink.errors.isEmpty(), "首擊應放行；實際: " + sink.errors);
            registry.dispatch(player, "punish", List.of("ban", "Steve", "100"));
            assertEquals(CommandErrorKind.COOLDOWN_ACTIVE, sink.lastError().getKind());
        }

        @Test
        @DisplayName("playerOnly 沿用；help 依權限過濾")
        void playerOnlyAndHelp_reused() {
            TypedCommand cmd = TypedCommand.builder("punish")
                .subcommand(TypedSubCommand.builder("ban")
                    .playerOnly()
                    .permission("punish.ban")
                    .argument(targetArg)
                    .argument(lengthArg)
                    .executes(ctx -> { })
                    .build())
                .build();
            registry.register(cmd.toCommandSpec());
            // dispatch 先查權限再查玩家限定：有權限的 console 才會走到
            // CONSOLE_NOT_ALLOWED（既有 registry 檢查順序）。
            CommandRegistryTest.TestSender privilegedConsole = console();
            privilegedConsole.grant("punish.ban");
            registry.dispatch(privilegedConsole, "punish", List.of("ban", "Steve", "100"));
            assertEquals(CommandErrorKind.CONSOLE_NOT_ALLOWED, sink.lastError().getKind());
            String help = registry.formatHelp("punish", console());
            assertTrue(!help.contains("ban"),
                "無權限者 help 不應看到 ban；實際: " + help);
        }

        @Test
        @DisplayName("補全委派給對應位置的引數 suggest")
        void completer_delegatesToArgSuggest() {
            registerBan(new AtomicReference<>(), new AtomicReference<>());
            List<String> result = registry.tabComplete(
                console(), "punish", List.of("ban", "St"));
            assertTrue(result.contains("Steve"),
                "應委派 target 引數補全；實際: " + result);
        }
    }

    @Nested
    @DisplayName("builder 契約")
    class BuilderContract {

        @Test
        @DisplayName("缺 executes 建構即失敗；零引數子指令允許；重複引數名拒絕")
        void invalidBuilder_rejected() {
            assertThrows(NullPointerException.class,
                () -> TypedSubCommand.builder("ban")
                    .argument(targetArg)
                    .argument(lengthArg)
                    .build());
            // 零引數子指令合法（handler 直接執行，minArgs=maxArgs=0）
            TypedSubCommand noArgs = TypedSubCommand.builder("info")
                .description("查詢")
                .executes(ctx -> { })
                .build();
            assertEquals(0, noArgs.toSubCommandSpec().minArgs());
            assertThrows(NullPointerException.class,
                () -> TypedSubCommand.builder("ban")
                    .argument(targetArg)
                    .argument(lengthArg)
                    .executes(null));
            assertThrows(IllegalArgumentException.class,
                () -> TypedSubCommand.builder("ban")
                    .argument(targetArg)
                    .argument(new TestArgs.NameArg("target"))
                    .executes(ctx -> { })
                    .build());
        }

        @Test
        @DisplayName("根指令無子指令建構即失敗；別名保留")
        void rootBuilder_contract() {
            assertThrows(IllegalArgumentException.class,
                () -> TypedCommand.builder("punish").build());
            TypedCommand cmd = TypedCommand.builder("punish")
                .aliases("p")
                .subcommand(banSub(new AtomicReference<>(), new AtomicReference<>()))
                .build();
            CommandSpec spec = cmd.toCommandSpec();
            assertEquals(List.of("p"), spec.aliases());
            assertTrue(spec.subCommands().containsKey("ban"));
        }
    }

    // -----------------------------------------------------------------
    // 與 TypedContext 契約（跨 slice 共用見 TestArgs）
    // -----------------------------------------------------------------

    @Test
    @DisplayName("TypedContext 未解析的引數 get 拋 IllegalArgumentException")
    void typedContext_unknownArg_throws() {
        TypedSubCommand sub = banSub(
            new AtomicReference<>(), new AtomicReference<>());
        SubCommandSpec spec = sub.toSubCommandSpec();
        CommandRegistryTest.TestSender sender = console();
        CommandContext ctx = new CommandContext(sender, "punish",
            List.of("ban", "Steve", "100"),
            CommandSpec.builder("punish").subCommand(spec).build(), spec, sink);
        TypedContext typed = new TypedContext(ctx, Map.of(targetArg, (Object) "Steve"));
        assertThrows(IllegalArgumentException.class, () -> typed.get(lengthArg));
    }
}
