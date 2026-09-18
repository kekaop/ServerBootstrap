# World replacement on hosting without external programs

[Русский](../MAINTENANCE.md) | English · [Documentation](../../README.en.md#documentation)

`apply.mode: maintenance` replaces **entire world directories** through a temporary maintenance world and two restarts. It requires only the server, ServerBootstrap, and permission to change `level-name` in server.properties. The plugin does not launch a shell, another Java process, or a restart script.

## Configuration

Template: [examples/maintenance.yml](../../examples/maintenance.yml). Replace the URL and SHA-256 with your ZIP's values, add the profile to `plugins/ServerBootstrap/config.yml`, and restart the server.

```yaml
config-version: 1
profiles:
  lobby:
    source:
      type: https
      url: https://example.org/lobby-worlds-v1.zip
    version: "v1"
    sha256: "REPLACE_WITH_64_HEX_CHARACTERS"
    archive-root: "."
    apply:
      mode: maintenance
      worlds: [world, world_nether, world_the_end]
      include: [world, world_nether, world_the_end]
      required-files: [world/level.dat, world_nether/level.dat, world_the_end/level.dat]
    restart: true
```

This example uses the traditional Bukkit/Paper layout before 26.1. Specify **the actual directories used by your server software**. [Paper 26.1+ changed dimension storage](https://papermc.io/news/26-1/): for snapshots with dimensions inside the main world, use `worlds: [world]`, `include: [world]`, and `required-files: [world/level.dat]`. Do not list nonexistent legacy `_nether`/`_the_end` directories. ServerBootstrap neither migrates dimensions between formats nor validates NBT compatibility with the server software.

Every listed world must contain `level.dat`. Create the ZIP from a stopped server with the same version/format. Include all required chunks, entities, POI, datapacks, playerdata, advancements, stats, and other data. World UIDs and player data come from the snapshot; changes absent from it remain only in the backup. Each world's root `session.lock` is excluded from the new directory so the server can create its own.

Maintenance installs worlds only; `include` must exactly match `worlds`. Plugins and configuration use direct mode. The node remains bound to one profile name: keep that name when changing modes. Changing the name requires a separate node migration.

## Workflow

1. Make an external backup and wait for players to leave. Disable third-party plugins that automatically load working worlds during maintenance. Run `serverbootstrap check lobby`, then `setup-server lobby`.
2. The plugin downloads the ZIP, verifies SHA/CRC/paths, prepares world copies, and creates a separate SHA manifest. Working worlds and the installed version remain unchanged. It saves WAITING_FOR_MAINTENANCE and switches level-name to a unique `sb_maintenance_<id>`.
3. **First restart.** The server reads the maintenance world's metadata. During onLoad, the plugin confirms that the current JVM holds its session.lock and that all target worlds are unlocked. It moves previous directories to backup and new directories into place. Version and profile binding commit in the same transaction.
4. The plugin restores the original level-name, saves WAITING_FOR_RETURN, and requests a stop if restart=true. Players cannot join the maintenance-world boot.
5. **Second restart.** The server reads the new working worlds. The plugin verifies the return, saves the result in the transaction journal, and ends maintenance. Check `serverbootstrap current` and inspect the world.

With `restart: true`, the plugin uses only `Bukkit.shutdown()`. Hosting auto-start or an administrator pressing **Start** in the panel starts the server again. Not every host restarts after a normal shutdown. With `restart: false`, both restarts are manual; player logins and new installations remain blocked throughout. Both restarts are still necessary when restart=false.

The ZIP, staging, and backups require free disk space. Extraction and preparation run on a worker thread. Directory moves and recovery run synchronously during the maintenance-world boot before players are admitted. Interrupting a replacement requires subsequent recovery.

## Failures and recovery commands

Use the hosting console. Other command senders need `serverbootstrap.admin`.

| Command/phase | Behavior |
|---|---|
| `serverbootstrap maintenance-status` | State, original/maintenance world names, and error message |
| MAINTENANCE_FAILED | Logins remain blocked; there is no automatic retry/shutdown loop |
| `serverbootstrap maintenance-retry` | Prepare a maintenance-world boot for rollback/retry; after commit, only return to the working world |
| `serverbootstrap maintenance-cancel` | Cancel an uncommitted installation; after partial application, a maintenance-world boot must roll it back first |
| WAITING_FOR_RETURN | Files were applied or cancellation completed; start with the original world |

Retry/cancel never replace worlds on the running server. With restart=true they request shutdown; otherwise restart manually. Cancellation is refused after commit: use retry to return. Installing an older snapshot after a successful operation is a separate installation with its own backup.

If the host overrides the world name with a startup argument, rewrites server.properties, or uses an external world-container, maintenance-world verification fails without changing working directories. Correct the hosting settings or cancel. `level-name` must name a direct subdirectory of the server root. The filesystem must support session.lock and atomic directory moves within one volume.

If another plugin has already loaded a target world, replacement is refused. Disable its automatic loading and retry. ServerBootstrap does not isolate directories from third-party threads/plugins that ignore session.lock. `/reload`, disabling/re-enabling ServerBootstrap without stopping the JVM, and running two servers in one directory are unsupported.

During a maintenance-world boot, the HTTP listener stays closed and other profile installations are unavailable. Recovery commands work even if config.yml has changed or become invalid. An unfinished direct transaction requires offline recover; ordinary onLoad cannot safely recover it.

## Retained data

- `plugins/ServerBootstrap/maintenance.properties`: the current operation and switching phase. Do not manually edit or delete it.
- `transactions/<id>/prepared-worlds/`: prepared snapshots; maintenance-manifest.properties contains their SHA-256 values.
- `transactions/<id>/world-attempt-<id>/`: plan/phase/rollback.properties, previous node state, and backup/0, backup/1, etc. containing entire previous worlds. plan.properties maps backup numbers to world names.
- `transactions/<id>/maintenance-result.properties`: the result of a successful return or cancellation.

A crash between renames is recovered from the journal during the next **confirmed maintenance-world** boot. Incomplete rollback retains backups and a list of affected worlds. Correct disk space, permissions, or locks, then retry or cancel. Do not delete files to bypass a recovery error.

Old worlds, completed journals, and `sb_maintenance_*` directories are never removed automatically. After confirming the return, checking backups, and stopping the server, you may archive/remove them manually. Do not remove the original working world or an unfinished transaction directory.

Ordinary Bukkit onLoad runs after main-world metadata has been read, which is why a maintenance-world boot is needed. See the order in Paper's [Main.java](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-server/patches/sources/net/minecraft/server/Main.java.patch) and [DedicatedServer.java](https://github.com/PaperMC/Paper/blob/ver/1.21.11/paper-server/patches/sources/net/minecraft/server/dedicated/DedicatedServer.java.patch). Lock probing accounts for [FileLock platform behavior](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileLock.html): on some systems, closing an additional channel releases another lock held by the same JVM. Such channels are retained until the lock is released or the process exits.
