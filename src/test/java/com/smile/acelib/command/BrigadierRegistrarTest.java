package com.smile.acelib.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BrigadierRegistrar} 註冊生命週期測試（Slice 4）。
 *
 * <p>以錄製型 {@link NodeRegistrar} 替身驗證：註冊→平台節點＋內部
 * {@link CommandRegistry} 雙寫入、重複註冊原子拒絕（平台側不殘留）、
 * reload 不重複註冊、shutdown 清空無殘留。</p>
 */
@DisplayName("BrigadierRegistrar 註冊生命週期")
class BrigadierRegistrarTest {

    /** 錄製型平台註冊器替身（取代 Paper LifecycleEvents.COMMANDS）。 */
    static final class RecordingNodes implements NodeRegistrar {
        record Entry(String name, String description, List<String> aliases,
                     LiteralCommandNode<CommandSourceStack> node) {
        }

        final List<Entry> registered = new ArrayList<>();

        @Override
        public void register(String name, String description, Collection<String> aliases,
                             java.util.function.Supplier<LiteralCommandNode<CommandSourceStack>> nodeSupplier) {
            // 替身在註冊時即求值（生產環境延後到事件觸發），以便斷言樹結構。
            registered.add(new Entry(name, description, List.copyOf(aliases),
                nodeSupplier.get()));
        }

        @Override
        public void unregister(String name) {
            registered.removeIf(entry -> entry.name().equalsIgnoreCase(name));
        }
    }

    private CommandRegistryTest.RecordingReplySink sink;
    private CommandRegistryImpl registry;
    private RecordingNodes nodes;
    private BrigadierRegistrar registrar;

    @BeforeEach
    void setUp() {
        sink = new CommandRegistryTest.RecordingReplySink();
        registry = new CommandRegistryImpl(sink,
            new CooldownTracker(new CommandRegistryTest.TestClock(1_000L)));
        nodes = new RecordingNodes();
        registrar = new BrigadierRegistrar(registry, nodes, new TestArgs.TestTypes());
    }

    private TypedCommand banCommand() {
        return TypedCommand.builder("punish")
            .description("處分指令")
            .aliases("p")
            .subcommand(TypedSubCommand.builder("ban")
                .argument(new TestArgs.NameArg("target"))
                .executes(ctx -> { })
                .build())
            .build();
    }

    @Test
    @DisplayName("註冊 → 內部 registry 與平台節點雙寫入（含別名與描述）")
    void register_writesRegistryAndPlatformNode() {
        registrar.register(banCommand());
        assertTrue(registry.findCommand("punish") != null, "內部 registry 應有 punish");
        assertTrue(registry.findCommand("p") != null, "別名 p 應可查");
        assertEquals(1, nodes.registered.size());
        RecordingNodes.Entry entry = nodes.registered.get(0);
        assertEquals("punish", entry.name());
        assertEquals("處分指令", entry.description());
        assertEquals(List.of("p"), entry.aliases());
        assertEquals("punish", entry.node().getLiteral());
    }

    @Test
    @DisplayName("重複註冊同名 → IllegalArgumentException，且平台側無殘留節點")
    void duplicateRegister_rejected_noPlatformResidue() {
        registrar.register(banCommand());
        assertThrows(IllegalArgumentException.class, () -> registrar.register(banCommand()));
        assertEquals(1, nodes.registered.size(),
            "重複註冊不可在平台側留下第二個節點");
    }

    @Test
    @DisplayName("unregister → 雙側移除；未知名稱為 no-op")
    void unregister_removesBothSides() {
        registrar.register(banCommand());
        registrar.unregister("punish");
        assertTrue(registry.findCommand("punish") == null, "內部 registry 應移除");
        assertTrue(nodes.registered.isEmpty(), "平台錄製應清空（替身語意：移除對應節點）");
        registrar.unregister("nonexistent");
    }

    @Test
    @DisplayName("reload 語意：重複 register 同一規格不產生第二份平台節點（冪等拒絕）")
    void reloadDoesNotDuplicate_platformHasSingleNode() {
        TypedCommand cmd = banCommand();
        registrar.register(cmd);
        // reload 流程重建前先 unregister 再 register（先解除再註冊，不跨越註冊時機）
        registrar.unregister("punish");
        registrar.register(cmd);
        assertEquals(1, nodes.registered.size(),
            "先解除再註冊後平台側應恰一個節點");
        assertTrue(registry.findCommand("punish") != null);
    }

    @Test
    @DisplayName("shutdown → registry disabled＋清空，平台節點清空；重複呼叫冪等")
    void shutdown_clearsAll_idempotent() {
        registrar.register(banCommand());
        registrar.shutdown();
        assertTrue(registry.isDisabled(), "registry 應標記 disabled");
        assertTrue(registry.findCommand("punish") == null, "registry 應清空");
        assertTrue(nodes.registered.isEmpty(), "平台節點應清空");
        AtomicReference<String> dispatched = new AtomicReference<>("unset");
        // shutdown 後再註冊應被拒（沿用 REGISTRY_DISABLED 語意）
        assertThrows(CommandException.class, () -> registrar.register(banCommand()));
        registrar.shutdown();
    }

    @Test
    @DisplayName("平台註冊失敗 → 內部 registry 回滾（不殘留半註冊）")
    void platformFailure_rollsBackRegistry() {
        NodeRegistrar failing = new NodeRegistrar() {
            @Override
            public void register(String name, String description,
                                 java.util.Collection<String> aliases,
                                 java.util.function.Supplier<LiteralCommandNode<CommandSourceStack>> nodeSupplier) {
                throw new IllegalStateException("lifecycle unavailable");
            }

            @Override
            public void unregister(String name) {
            }
        };
        BrigadierRegistrar fragile = new BrigadierRegistrar(registry,
            failing, new TestArgs.TestTypes());
        assertThrows(IllegalStateException.class, () -> fragile.register(banCommand()));
        assertTrue(registry.findCommand("punish") == null,
            "平台失敗時內部 registry 不可殘留半註冊");
    }
}
