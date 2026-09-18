# Changelog

[Русский](CHANGELOG.md) | English

## 2.1.0

- Complete world directory replacement through a maintenance world and two restarts, without external programs.
- A durable journal for `level-name` changes, verification of the current JVM's `session.lock`, staging SHA-256 checks, and blocked player logins.
- Deferred version commit, complete backups of previous worlds, and rollback after errors or process interruption.
- `maintenance-status`, `maintenance-retry`, and `maintenance-cancel` commands; no automatic retry loop after failure.
- Interrupted direct transactions require offline recovery: ordinary `onLoad` runs after main-world metadata has already been read.
- Tests for the maintenance lifecycle, failures between directory renames, and preservation of operating system locks.

## 2.0.0

- Shared artifact handling for GitHub, GitLab, HTTPS, Google Drive, and direct release ZIP assets.
- Versioned configuration, refs, artifact versions, SHA-256, and authentication through environment variables.
- Download and extraction limits, safe paths, CRC checks, and rejection of ambiguous ZIP archives.
- Transaction journals, backups, rollback, and recovery after interrupted application.
- Installed version and profile binding committed in the same transaction.
- Global operation serialization and an interprocess node lock.
- HTTP disabled by default; Bearer authentication, exact methods and paths, and operation status.
- `install`, `update`, `check`, `current`, `status`, and `help`, with the compatible `setup-server` command.
- A separate offline tool; refusal to modify loaded worlds or apply files while players are online.
- Bukkit API 1.20.1 and Java 17 bytecode, with API compatibility tests through 26.3 on Java 25.
- Migration documentation and tests for network errors, archives, secret handling, and transactions.

No GitHub Release has been published.
