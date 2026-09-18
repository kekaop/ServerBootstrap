# Original project audit

[Русский](../AUDIT.md) | English · [Documentation](../../README.en.md#documentation)

The audited baseline is commit `bdf63ba6f50986c057e569425dd9bd5ca5f3a511` of kekaop/ServerBootstrap.

| Claimed behavior | Actual behavior in the original code |
|---|---|
| ZIP from GitHub/GitLab | API requests exist, but HttpClient does not follow redirects; a typical GitHub zipball returning 302 cannot be installed |
| Plugins and configuration | Copies the discovered plugins directory; configuration outside plugins is not applied |
| Worlds | Searches for world, world_nether, and world_the_end and copies them into a running server |
| Persistent binding | Writes node-profile before the download finishes; first-install is forcibly reset in onEnable |
| Safe extraction | Passes entry names directly to resolve; Zip Slip is not blocked |
| Reliable results | Suppresses individual copy/deletion errors, making success messages unreliable |
| Transactions | No validated staging, journal, backups, or rollback |
| Versions and integrity | No SHA-256, size limits, ref, or installed version tracking |
| HTTP | Always enabled; /update prefix matching, JSON secret, no method validation, body limit, or concurrent-operation lock |
| Permissions | setup-server is console-only; no dedicated permission |
| Self-update | Skips ServerBootstrap and NodeMetrics paths and protects their JARs |
| NodeMetrics | Changes nodeName after installation, separately from file copying, when NodeMetrics is installed |
| Java/server | Java 21 compilation, Paper API 1.21.4, api-version 1.21; runServer starts 1.21 |
| Tests | No automated tests |

The baseline builds on JDK 21. Gradle 8.8 does not start on the installed JDK 25 (`Unsupported class file major version 69`).

Compatibility changes are described in [migration](MIGRATION.md). The single bound profile model, legacy GitHub/GitLab templates, `setup-server`, and protection of the plugin's own files remain. Suppressed copy errors and unsafe replacement of loaded worlds are not preserved.
