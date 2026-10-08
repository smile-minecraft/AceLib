package com.example.aceliblifecycleprobe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("lifecycle 探針指令格式")
class LifecycleProbeCommandTest {

    @Test
    @DisplayName("reload 與 status 指令不區分大小寫")
    void parsesSupportedActions() {
        assertEquals(Optional.of(new LifecycleProbeCommand(LifecycleProbeCommand.Action.RELOAD)),
            LifecycleProbeCommand.parse(new String[] {"reload"}));
        assertEquals(Optional.of(new LifecycleProbeCommand(LifecycleProbeCommand.Action.RELOAD)),
            LifecycleProbeCommand.parse(new String[] {"RELOAD"}));
        assertEquals(Optional.of(new LifecycleProbeCommand(LifecycleProbeCommand.Action.STATUS)),
            LifecycleProbeCommand.parse(new String[] {"status"}));
    }

    @Test
    @DisplayName("空指令、未知子指令與多餘引數均拒絕")
    void rejectsInvalidArguments() {
        assertTrue(LifecycleProbeCommand.parse(new String[0]).isEmpty());
        assertTrue(LifecycleProbeCommand.parse(new String[] {"unknown"}).isEmpty());
        assertTrue(LifecycleProbeCommand.parse(new String[] {"reload", "extra"}).isEmpty());
        assertTrue(LifecycleProbeCommand.parse(new String[] {null}).isEmpty());
        assertThrows(NullPointerException.class, () -> LifecycleProbeCommand.parse(null));
    }
}
