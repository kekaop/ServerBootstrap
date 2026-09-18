# Minecraft and Java compatibility

[Русский](../COMPATIBILITY.md) | English · [Documentation](../../README.en.md#documentation)

The plugin uses public Bukkit/Spigot API 1.20.1 without NMS, CraftBukkit internals, MiniMessage, or direct Adventure API calls. plugin.yml declares `api-version: 1.20`, and production classes use major version 61 (Java 17). The same production JAR targets the entire range.

| Minecraft Java versions | Server Java runtime | Support status |
|---|---|---|
| 1.20.1–1.20.4 | Java 17 or Java 21 as recommended by the server software | Target range; baseline API 1.20.1 |
| 1.20.5–1.20.6 | Java 21 | Target range |
| 1.21–1.21.11 | Java 21 | Target range |
| 26.1.x–26.2 | Java 25 for Paper | Target range |
| 26.3 | Java 25 for Paper | Common API support; Paper builds were alpha on the verification date |

Verification date: September 18, 2026. [Paper recommends Java 21 for 1.20–1.21.11 and Java 25 for 26.1+](https://docs.papermc.io/paper/getting-started/). Java 17 is the minimum bytecode level of ServerBootstrap itself, not a claim that newer server software runs on Java 17.

## What was verified

The matrix runs the complete automated suite against versions of **Spigot API**, the common API used by the plugin:

| Test API | Test JVM | Coverage |
|---|---|---|
| 1.20.1-R0.1-SNAPSHOT | 17 | Configuration, sources, TLS/HTTP, archives, transactions, maintenance, locks, permissions, descriptor |
| 1.20.6-R0.1-SNAPSHOT | 21 | Same suite |
| 1.21.11-R0.1-SNAPSHOT | 21 | Same suite |
| 26.2-R0.1-SNAPSHOT | 25 | Same suite |
| 26.3-R0.1-SNAPSHOT | 25 | Same suite |

Production code always compiles against API 1.20.1. The matrix changes the test classpath API and test JVM. Results and test counts are in the [verification report](VERIFICATION.md). These are not full Minecraft/Paper launches or proof of behavior for every intermediate release or third-party plugin.

The [Paper Downloads Service](https://docs.papermc.io/misc/downloads-service/) confirmed these builds: Paper 1.20.1 build 196 STABLE, 1.20.6 build 151 STABLE, 1.21.11 build 132 STABLE, 26.2 build 124 STABLE, and 26.3 build 16 ALPHA. Availability does not mean that a build was launched during verification.

## Limitations

- Targets Java Edition Paper/Spigot servers. Fabric, Forge, NeoForge, Bedrock, and Folia are unsupported.
- Administrators must verify third-party JARs inside a profile against their Minecraft version.
- ServerBootstrap does not change the server software version or convert worlds.
- Stop/restart requests use the standard API; starting the server again depends on the environment.
- Test Paper 26.3 in a separate sandbox first: alpha server builds and APIs may change.
- New Minecraft branches require another matrix run and a trial server launch even if the common API is unchanged.

See [testing](TESTING.md) for a trial launch procedure.
