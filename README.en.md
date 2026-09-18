# ServerBootstrap 2.1

[Русский](README.md) | English

Install and update a Minecraft server from ZIP profiles with SHA-256 verification, size limits, transaction journals, backups, and rollback.

The target range is **Paper/Spigot 1.20.1–1.21.11, 26.1.x, 26.2, and 26.3**. A single JAR uses Bukkit API 1.20.1 and Java 17 bytecode; the Java runtime needed to start the server depends on the server version. Paper 26.1+ requires Java 25. See [compatibility](docs/en/COMPATIBILITY.md) for verification status and the limitations of 26.3.

## Features

- GitHub and GitLab: repository archives pinned to a ref, or direct release ZIP assets.
- Direct HTTPS and Google Drive links that return a ZIP without sign-in or confirmation pages.
- Configuration validation at startup and artifact checks without installation.
- SHA-256, ZIP CRC checks, download/extraction/entry limits, and path validation.
- Backups, atomic replacement of individual files, rollback, and interrupted transaction recovery.
- Profile binding and installed version saved only after successful application.
- Console commands, an explicit administrative permission, and an optional HTTP API.
- A separate offline tool for stopped servers, including world installation.
- World replacement on shared hosting using only the plugin: a temporary maintenance world, two restarts, and complete backups of the previous worlds.

## Quick start

1. Back up the server. Place `ServerBootstrap-2.1.0.jar` in `plugins/`.
2. Start and stop the server to create `plugins/ServerBootstrap/config.yml`.
3. Add a profile from [examples/config.yml](examples/config.yml), replacing the URL and SHA-256 with those of your artifact.
4. Start the server and run these commands in its console:

   ```text
   serverbootstrap check lobby
   setup-server lobby
   serverbootstrap current
   serverbootstrap status
   ```

5. For the next version, change the ref/URL, version, and SHA-256, restart the server, then run `serverbootstrap update`.

New installations never start automatically on boot. An already requested maintenance operation resumes according to its saved journal. The default configuration has no profiles and disables HTTP.

`check` downloads and fully validates the archive, prepares a change list, and checks destination paths without modifying installed files or the node's profile binding. To replace worlds on hosting, use [maintenance mode](docs/en/MAINTENANCE.md); it needs no external programs or restart script. Offline mode is recommended for replacing configuration files belonging to active plugins.

## Documentation

- [Configuration reference and ZIP structure](docs/en/CONFIGURATION.md)
- [Artifact sources, releases, tokens, and Google Drive](docs/en/SOURCES.md)
- [Commands, HTTP, offline installation, rollback, and troubleshooting](docs/en/OPERATIONS.md)
- [World replacement without external programs](docs/en/MAINTENANCE.md)
- [Migration from the original version](docs/en/MIGRATION.md)
- [Original code audit](docs/en/AUDIT.md)
- [Building and testing](docs/en/TESTING.md)
- [Minecraft and Java compatibility](docs/en/COMPATIBILITY.md)
- [Verification report](docs/en/VERIFICATION.md)
- [Changelog](CHANGELOG.en.md)

## Build

Use JDK 17 or 21 to run Gradle 8.8:

```sh
./gradlew clean build
```

On Windows, use `gradlew.bat clean build`. Outputs: `build/libs/ServerBootstrap-2.1.0.jar` and `build/distributions/ServerBootstrap-2.1.0-offline.zip`. Gradle 8.8 cannot run on JDK 25; tests for Java 25 run in a separate process, as described in the testing guide.

## Scope and guarantees

An archive contains code and configuration trusted by the administrator: an installed plugin can execute arbitrary code on the server. SHA-256 verifies that an artifact matches the expected bytes; it does not establish that its publisher is trustworthy.

In direct mode, existing files absent from the archive are preserved. Explicit maintenance mode replaces each listed world directory completely and retains its previous contents in a backup. There is no command for switching a bound profile, plugin hot reload, server software updates, world format conversion, orchestration, control panel, or Folia support. ServerBootstrap and NodeMetrics files are protected from archive contents.

Transactions provide backups and file recovery, but do not isolate files from other writers or atomically replace the entire server directory. For predictable file updates, stop the server and use offline mode. See the operations guide for details.
