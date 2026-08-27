English · [繁體中文](README.zh-TW.md)

# AceLib

AceLib is a shared foundation library for Paper and Folia plugins. It provides safe scheduling, thread-context checks, configuration, messaging, commands, events, data, player state, world operations, GUI, items, external integrations, and diagnostics.

The source version in this checkout is **1.1.2** (adds Adventure Component message APIs and Bedrock click fallback). The GitHub repository is a **public repository**; releases use the [GitHub Release](https://github.com/smile-minecraft/AceLib/releases) process and are built from source — the GitHub Release has no binary asset, so operators build the server JAR from source. The JitPack coordinate `com.github.smile-minecraft:AceLib:v1.1.2` corresponds to the `v1.1.2` tag (local verification: `./gradlew publishToMavenLocal` with `com.smile:acelib:1.1.2`). See CHANGELOG for history.

## Supported Versions

| Item | Version |
| --- | --- |
| Java | 25 |
| Paper | 26.1.2 |
| Folia | 26.1.2 |

Paper and Folia 26.2 have not been verified. See [Compatibility](docs/consumer/compatibility.md) for the full constraints.

## Adding AceLib to Your Plugin

Add the JitPack repository and the AceLib API to `build.gradle.kts`:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.smile-minecraft:AceLib:v1.1.2")
}
```

This JitPack coordinate `com.github.smile-minecraft:AceLib:v1.1.2` corresponds to the `v1.1.2` tag; the source version in this checkout is 1.1.2. To verify locally before relying on the published artifact, run `./gradlew publishToMavenLocal` (`com.smile:acelib:1.1.2`). See [Quick Start](docs/consumer/quickstart.md) for a complete, compilable Gradle setup.

## Configuring `plugin.yml`

Your plugin must declare AceLib as a required dependency:

```yaml
name: MyPlugin
main: com.example.myplugin.MyPlugin
version: 1.1.0
api-version: '26.1.2'
folia-supported: true
depend: [AceLib]
```

`depend: [AceLib]` ensures the server enables AceLib before your plugin. This is the downstream plugin's configuration; AceLib itself has no other required plugin dependencies.

## Getting the API

AceLib exposes `AceLibApi.AceLibProvider` through Bukkit `ServicesManager`:

```java
package com.example.myplugin;

import com.smile.acelib.AceLibApi;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class MyPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        RegisteredServiceProvider<AceLibApi.AceLibProvider> registration =
            getServer().getServicesManager()
                .getRegistration(AceLibApi.AceLibProvider.class);

        if (registration == null) {
            getLogger().severe("AceLib provider not registered; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        AceLibApi api = registration.getProvider().api();
        if (!api.isReady()) {
            getLogger().severe("AceLib not ready; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("AceLib " + api.getVersion()
            + " on " + api.getPlatform().getDisplayName());
    }
}
```

Do not depend directly on `AceLibPlugin`. If your plugin is long-running, see [Provider Lifecycle](docs/consumer/provider-lifecycle.md) for how to re-acquire the API after reload or disable.

## Documentation

| Task group | Document | When to use it |
| --- | --- | --- |
| Getting started | [Quick Start](docs/consumer/quickstart.md) | First time integrating AceLib — set up Gradle, declare dependencies, and obtain `AceLibProvider` |
| Getting started | [How AceLib is released](docs/reference/release-artifacts.md) | Verify the public repository status and copy the JitPack coordinate `com.github.smile-minecraft:AceLib:v1.1.2` |
| Daily integration | [Module Guide](docs/modules/) | Look up a specific subsystem — scheduler, context, config, messages, commands, events, data, player, world, GUI, items, externals |
| Daily integration | [Provider Lifecycle](docs/consumer/provider-lifecycle.md) | Handle reload and disable correctly for long-running plugins |
| Daily integration | [Error Codes](docs/reference/error-codes.md) | Look up `ACELIB-<AREA>-<CODE>` and the five required fields in each message |
| Operations | [Operator Guide](docs/operator/README.md) | Build the server plugin jar from source and deploy it |
| Operations | [Compatibility](docs/consumer/compatibility.md) | Check the verified baseline (Java 25 / Paper 26.1.2 / Folia 26.1.2) and why 26.2 is not yet supported |
| Reference | [Contributor Guide](docs/contributor/README.md) | Contribution workflow, verification gates, and style rules |
| Reference | [Changelog](CHANGELOG.md) | Version history, release notes, and upgrade guidance |

## Important Limitations

- The GitHub Release does not include a downloadable server plugin jar. Operators must [build from source](docs/operator/README.md) to obtain `AceLib-1.1.2.jar`.
- AceLib does not support Bukkit `/reload`. The reload documented in AceLib is the library's own lifecycle operation — not the same as `/reload`.
- MockBukkit tests cannot replace real region-scheduler verification on a Folia server.
- External errors in logs use the `ACELIB-<AREA>-<CODE>` format — see the [error codes](docs/reference/error-codes.md).
- Bedrock (Geyser/Floodgate) players have platform constraints — chat links are not clickable and GUI cannot distinguish left/right clicks; see the [Bedrock module page](docs/modules/bedrock.md).

## MIT License

AceLib is released under the [MIT License](LICENSE).
