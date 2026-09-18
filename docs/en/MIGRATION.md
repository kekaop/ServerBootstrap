# Migrating from 1.0-SNAPSHOT

[Русский](../MIGRATION.md) | English · [Documentation](../../README.en.md#documentation)

1. Stop the server and back up both the server and its old config.yml.
2. Remove the old ServerBootstrap JAR and place the new one in plugins. Do not keep two JARs for the same plugin.
3. Configuration without config-version is recognized as legacy. GitHub owner/repo-template and GitLab project-path-template still work, with `%s` replaced by the profile name. Remove unresolved token placeholders; use an empty string for a public repository.
4. When legacy configuration is first read, an existing node-profile is imported into installed.properties with `version=unknown`. The old first-install flag is no longer used or reset. If a previous installation ever failed, manually verify the old binding: the original version recorded it too early.
5. Migrate the profile to schema 1 using [examples/config.yml](../../examples/config.yml). Specify a ref, version label, and SHA-256. Keep installed.properties when changing the configuration format; it preserves the binding.
6. Restart the server, run `check`, then `update`.

To replace legacy config.yml immediately, first import its binding by running the new offline tool's `check` command with the old configuration. Alternatively, transfer a known binding into installed.properties manually while the server is stopped. Do not label an unknown previous version as verified.

## Behavior changes

- `setup-server <profile>` also updates the already bound profile. A different profile is rejected.
- HTTP is always disabled for legacy configuration. Old secret and port values are deliberately not imported automatically.
- The new HTTP API accepts only `POST /update`, an empty body, and `Authorization: Bearer`. Accepted requests return HTTP 202; `GET /status` shows the result. Replace the old JSON secret payload.
- The installation root is the server's working directory, not world-container. Worlds outside that directory are not modified.
- Archives must have their profile contents at the root or inside one wrapper directory. The old heuristic search for plugins within three directory levels has been removed. Set archive-root explicitly for a nonstandard ZIP layout.
- Direct mode preserves existing files absent from the archive. Explicit maintenance mode replaces the listed world directories completely, retaining their previous contents in backup.
- Loaded worlds are not replaced while running. On shared hosting, choose `apply.mode: maintenance` with an explicit world list; see [maintenance mode](MAINTENANCE.md). Alternatively, use the offline tool with direct mode. Interrupted direct transactions require offline recovery.
- NodeMetrics JARs and its data directory remain protected. Automatic nodeName changes after installation were removed: set nodeName yourself in plugins/NodeMetrics/config.yml while the server is stopped. This avoids changing another plugin outside the transaction.
- Grant `serverbootstrap.admin` explicitly; operator status alone does not grant it.
- Configuration outside plugins can be selected through apply.include.
- Backups and staging are retained; the administrator manages their retention.

Changing the bound profile requires manually preparing the node again: stop it, make a complete backup, verify content compatibility, and remove old data and binding state according to your migration plan. There is no profile-switching or automatic-cleanup command.
