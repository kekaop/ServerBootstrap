# Operations

[Русский](../OPERATIONS.md) | English · [Documentation](../../README.en.md#documentation)

## Commands and permissions

| Command | Action |
|---|---|
| `/setup-server <profile>` | Compatible command to install or update a profile |
| `/serverbootstrap install <profile>` | Install or update |
| `/serverbootstrap update [profile]` | Update; uses the bound profile if omitted |
| `/serverbootstrap check <profile>` | Check the source, SHA, ZIP, structure, and destination paths without installation |
| `/serverbootstrap current` | Profile, version, actual SHA-256, and installation date |
| `/serverbootstrap status` | Latest/current operation, ID, phase, and result |
| `/serverbootstrap help` | Help |
| `/serverbootstrap maintenance-status` | Persisted world replacement state, including startup failure |
| `/serverbootstrap maintenance-retry` | Retry switching/recovery on the next startup |
| `/serverbootstrap maintenance-cancel` | Cancel an uncommitted replacement, rolling back if necessary |

Alias: `/sb`. All commands are available to the console. Other senders need `serverbootstrap.admin`; its default is false, so OP does not automatically grant access. This permission authorizes installation and any restart requested by the profile. Grant it only to trusted administrators.

Commands immediately return an operation ID, with the final result sent later. ZIP download and extraction do not run on the main server thread. Only one operation, including `check`, can run on a node at a time. Repeated requests are rejected instead of being queued indefinitely.

## Updating a running server

Before applying files, remove the node from the load balancer, block incoming connections at the infrastructure level, wait for players to leave, and stop other writers. Application is refused while players are online. The plugin blocks new logins while applying files and waiting for a restart.

Direct mode cannot replace loaded worlds. For hosting without external programs, use [maintenance mode with two restarts](MAINTENANCE.md). Updated plugin JARs load only after a restart. Active plugins may overwrite their configuration during shutdown; use offline mode for those files.

In direct mode, `restart: true` calls the standard `Server.Spigot.restart()`. Configure restart-script in spigot.yml and/or an external process manager. Some environments stop the server without starting it again; startup configuration is the administrator's responsibility. After commit with restart=true, further operations are blocked until restart. Restart manually if the automatic request fails. With restart=false, files are committed without hot reload.

Maintenance mode only stops the server through the Bukkit API; it never launches a restart script. During a maintenance-world boot, the HTTP listener is disabled and maintenance commands remain available independently of config.yml.

## Offline tool

Extract `ServerBootstrap-2.1.0-offline.zip` into **a separate directory**, not plugins. The lib directory contains the plugin and the dependencies of its shared configuration parser. Offline mode does not start Minecraft or require accepting its EULA.

Stop the target server first. Its directory must contain `plugins/ServerBootstrap/config.yml`.

```sh
sh offline.sh /srv/minecraft lobby check --server-stopped
sh offline.sh /srv/minecraft lobby install --server-stopped
```

Windows:

```powershell
.\offline.cmd "D:\Minecraft\lobby" lobby check --server-stopped
.\offline.cmd "D:\Minecraft\lobby" lobby install --server-stopped
```

Offline installation uses `apply.mode: direct`; maintenance profiles are applied by the server plugin. Offline commands are refused while a maintenance operation is pending. Finish or cancel that operation through the plugin first.

The tool runs on Java 17/21/25 and shares the download, verification, transaction, and state pipeline. Offline `restart` means that you must start the Minecraft server manually after completion. Exit codes: 0 for success, 1 for failure, and 2 for invalid arguments.

The tool acquires node.lock and the session.lock files of worlds found inside the server root, excluding plugins. The server plugin holds node.lock while loaded. `--server-stopped` is the operator's confirmation that the server is stopped: do not disable the plugin to bypass a running server's lock or start another server in the same directory. External world-container paths and symbolic links are unsupported.

## HTTP API

Configuration:

```yaml
listener:
  enabled: true
  bind: "127.0.0.1"
  port: 8080
  token-env: SERVERBOOTSTRAP_HTTP_TOKEN
```

Use a random secret of at least 32 characters in the process environment. Requests must have an empty body, with the secret only in the header. Do not use query parameters.

```sh
curl --request POST --header "Authorization: Bearer $SERVERBOOTSTRAP_HTTP_TOKEN" http://127.0.0.1:8080/update
curl --header "Authorization: Bearer $SERVERBOOTSTRAP_HTTP_TOKEN" http://127.0.0.1:8080/status
```

| Response | Meaning |
|---|---|
| 202 + accepted, operation | Request accepted; installation has not completed |
| 200 for GET /status | Latest operation ID, profile, phase, and message |
| 400 | Query strings are forbidden |
| 401 | Missing or invalid Bearer secret |
| 404 | Incorrect path |
| 405 + Allow | Incorrect method |
| 409 | Node unbound, another operation running, restart/recovery pending, or profile unavailable |
| 413 | Request body or Transfer-Encoding supplied |

Example: `{"status":"accepted","operation":"UUID"}`. Direct operation phases include QUEUED, DOWNLOADING, VERIFYING, APPLYING, CHECKED, SUCCEEDED, FAILED, and RESTART_REQUIRED. Maintenance preparation can report WAITING_FOR_MAINTENANCE; subsequent persisted phases and recovery commands are described in [maintenance mode](MAINTENANCE.md). On a normal restart, GET /status starts at IDLE; the installed version remains in installed.properties. History is retained in the server log and transactions directory.

The listener is plain HTTP on loopback, without its own TLS. For remote access, use an HTTPS reverse proxy, firewall/CI allowlist, rate limiting, and client timeouts at the proxy. `bind: 0.0.0.0` opens all IPv4 interfaces. Do not transmit the secret over an unprotected network or expose this port directly. Redact Authorization in proxy logs. HTTP is optional for commands and offline installation.

## Transactions and backups

Directory: `plugins/ServerBootstrap/transactions/<operation-id>/`.

| File/directory | Purpose |
|---|---|
| artifact.zip | Downloaded artifact |
| staging/ | Validated extracted contents |
| preview.properties | Selected files, source, version, size, and SHA |
| backup/0, backup/1, … | Original files; numbers map to paths in plan.properties |
| plan.properties | Complete file list, original-file existence, backup hashes, and new directories |
| phase.properties | PREPARED / APPLYING / COMMITTED / ROLLED_BACK / ROLLBACK_FAILED and recorded write-intent count |
| rollback.properties | failed-count and paths that could not be restored |
| installed.properties | Prepared new node state |

Direct installation order: download → SHA → full extraction/CRC → structure/paths → preview → maintenance checks → backups → journal → replacements → node state → COMMITTED → restart.

Before application, only the staging/transaction area is changed. Each file replacement uses a temporary file beside its destination, fsync, and atomic rename. Filesystems without atomic move support cause failure and rollback. The entire server directory is not switched in one action. Durability during power loss also depends on the filesystem and hardware; this does not replace a complete external backup.

If application fails, original files are restored, newly created files are removed, and newly created empty directories are deleted. Unrelated files in new directories are preserved. If recovery is incomplete, the operation becomes FAILED and new operations are blocked. The message identifies the backup and rollback.properties location.

An unfinished direct transaction blocks installations and player logins at the next startup. Ordinary `onLoad` is too late to blindly replace the main world because the server has already read its metadata. Stop the server and recover offline:

```sh
sh offline.sh /srv/minecraft ignored recover --server-stopped
```

Maintenance recovery runs only during a confirmed maintenance-world boot, controlled by maintenance-retry/maintenance-cancel. See [maintenance mode](MAINTENANCE.md).

For direct transactions, `recover` does not load a profile or need working network configuration and can be repeated. Do not delete an unfinished journal or its backups. For ROLLBACK_FAILED, stop the server, inspect rollback.properties, correct access/locking issues, and retry recover. If a backup is damaged, restore it from an external backup and investigate manually; do not forge a COMMITTED state.

Transaction data is retained for diagnosis. After confirming successful operation, you may manually archive/remove the **entire** completed transaction directory marked COMMITTED or ROLLED_BACK. Checks and failures before application have no phase.properties; those directories can also be removed after the operation ends. Do not clean them while an operation is running. The plugin never automatically removes journals or backups.

## Logs and troubleshooting

Log format:

```text
event=installation operation=UUID profile=lobby version=v1 source=github phase=VERIFYING message=bytes=12345 sha256=... checksum=passed
```

The UUID connects the log entry to its transaction. HTTP events include http-unauthorized, http-rejected, and http-update-accepted; direct recovery uses event=recovered. URLs, query strings, HTTP bodies, and token values are not logged. Backups may contain previous configurations with secrets and need protection.

| Error | What to check |
|---|---|
| Invalid field | Profile/field in the message, YAML type, config-version |
| HTTP 401/403 | Token, header, permissions, API rate limits; cross-origin redirects do not receive the token |
| HTTP 404 | URL/ref/asset ID and repository visibility |
| Download timed out | HTTPS access, DNS, proxy, time limits |
| SHA-256 mismatch | Artifact differs from the expected bytes; investigate before changing verification |
| ZIP corrupt / no installable files | HTML instead of ZIP, corruption, archive-root, include |
| Required file missing | apply.required-files and contents of that exact version |
| Unsafe path / junction | Archive paths or links in the destination |
| Apply failed; rolled back | Disk space, permissions, locked JARs on Windows |
| Rollback incomplete | Stop the server; inspect rollback.properties and retained backups |
| Node is bound | Ordinary commands cannot switch the profile |
| Loaded world / players online | Wait for players to leave; use maintenance or offline mode for worlds |
| No automatic restart | Check spigot.yml and the process manager; files may already be committed |
