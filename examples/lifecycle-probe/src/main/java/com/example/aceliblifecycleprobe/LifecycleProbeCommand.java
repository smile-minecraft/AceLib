package com.example.aceliblifecycleprobe;

import java.util.Objects;
import java.util.Optional;

record LifecycleProbeCommand(Action action) {

    enum Action {
        RELOAD,
        STATUS
    }

    LifecycleProbeCommand {
        Objects.requireNonNull(action, "action");
    }

    static Optional<LifecycleProbeCommand> parse(String[] args) {
        Objects.requireNonNull(args, "args");
        if (args.length != 1 || args[0] == null) {
            return Optional.empty();
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            return Optional.of(new LifecycleProbeCommand(Action.RELOAD));
        }
        if ("status".equalsIgnoreCase(args[0])) {
            return Optional.of(new LifecycleProbeCommand(Action.STATUS));
        }
        return Optional.empty();
    }
}
