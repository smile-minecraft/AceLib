English · [繁體中文](README.zh-TW.md)

# AceLib

AceLib is a shared foundation library for Paper and Folia plugins. It provides safe scheduling, thread-context checks, configuration, messaging, commands, events, data, player state, world operations, GUI, items, external integrations, and diagnostics.

The source version in this checkout is **1.3.1**. The GitHub repository is a **public repository**; releases use the [GitHub Release](https://github.com/smile-minecraft/AceLib/releases) process. The v1.3.1 GitHub Release provides a downloadable `AceLib-1.3.1.jar`, so operators can download it directly, or still build the server JAR from the `v1.3.1` tag with `./gradlew clean build --no-daemon --console=plain`. The JitPack coordinate `com.github.smile-minecraft:AceLib:v1.3.1` corresponds to the `v1.3.1` tag (local verification: `./gradlew publishToMavenLocal` with `com.smile:acelib:1.3.1`). See CHANGELOG for history.

## Supported Versions

| Item | Version |
| --- | --- |
| Java | 25 |
| Paper | 26.1.2, 26.2 |
| Folia | 26.1.2, 26.2 |

Paper and Folia 26.2 are officially supported as of AceLib 1.2.1: Paper 26.2-120 and Folia 26.2-7 passed the lifecycle smoke and capability gate, and Folia 26.2-4 additionally passed status, scheduler, context, and message-fallback checks with real-player Bedrock verification. See [Compatibility](docs/consumer/compatibility.md) for details.

## Adding AceLib to Your Plugin

Add the JitPack repository and the AceLib API to `build.gradle.kts`:

```kotlin
repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("com.github.smile-minecraft:AceLib:v1.3.1")
}
```

This JitPack coordinate `com.github.smile-minecraft:AceLib:v1.3.1` corresponds to the `v1.3.1` tag; the source version in this checkout is 1.3.1.
To verify locally before relying on the released artifact, run `./gradlew publishToMavenLocal` (`com.smile:acelib:1.3.1`). See [Quick Start](docs/consumer/quickstart.md) for a complete, compilable Gradle setup.

## Release status

Version `1.3.1` is released: the `v1.3.1` GitHub Release provides the downloadable `AceLib-1.3.1.jar`, and the public JitPack coordinate above resolves to the `v1.3.1` tag.
Contributors validating the current sources can run `./gradlew publishToMavenLocal` (`com.smile:acelib:1.3.1`).

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
| Getting started | [How AceLib is released](docs/reference/release-artifacts.md) | Verify the public repository status and copy the JitPack coordinate `com.github.smile-minecraft:AceLib:v1.3.1` |
| Daily integration | [Module Guide](docs/modules/) | Look up a specific subsystem — scheduler, context, config, messages, commands, events, data, player, world, GUI, items, externals |
| Daily integration | [Provider Lifecycle](docs/consumer/provider-lifecycle.md) | Handle reload and disable correctly for long-running plugins |
| Daily integration | [Error Codes](docs/reference/error-codes.md) | Look up `ACELIB-<AREA>-<CODE>` and the five required fields in each message |
| Operations | [Operator Guide](docs/operator/README.md) | Build the server plugin jar from source and deploy it |
| Operations | [Compatibility](docs/consumer/compatibility.md) | Check the supported baseline (Java 25 / Paper 26.1.2, 26.2 / Folia 26.1.2, 26.2) and the verification scope and limitations |
| Reference | [Contributor Guide](docs/contributor/README.md) | Contribution workflow, verification gates, and style rules |
| Reference | [Changelog](CHANGELOG.md) | Version history, release notes, and upgrade guidance |

## Important Limitations

- The v1.3.1 GitHub Release includes a downloadable `AceLib-1.3.1.jar`. Operators can download it directly, or [build from source](docs/operator/README.md) at the `v1.3.1` tag with `./gradlew clean build --no-daemon --console=plain`.
- AceLib does not support Bukkit `/reload`. The reload documented in AceLib is the library's own lifecycle operation — not the same as `/reload`.
- MockBukkit tests cannot replace real region-scheduler verification on a Folia server.
- External errors in logs use the `ACELIB-<AREA>-<CODE>` format — see the [error codes](docs/reference/error-codes.md).
- Bedrock (Geyser/Floodgate) players have platform constraints — chat links are not clickable and GUI cannot distinguish left/right clicks; see the [Bedrock module page](docs/modules/bedrock.md).

## MIT License

AceLib is released under the [MIT License](LICENSE).
