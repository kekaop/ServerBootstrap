# Verification report

[Русский](../VERIFICATION.md) | English · [Documentation](../../README.en.md#documentation)

Local verification was performed on Windows on September 18, 2026. The same five-version matrix also passed on Linux in [GitHub Actions for f59592f](https://github.com/kekaop/ServerBootstrap/actions/runs/35341829364).

- Original project baseline: commit bdf63ba6f50986c057e569425dd9bd5ca5f3a511.
- Gradle Wrapper 8.8, launched with JDK 21.
- Production: Bukkit/Spigot API 1.20.1, --release 17.
- Complete matrix: API 1.20.1 / Java 17, 1.20.6 / Java 21, 1.21.11 / Java 21, 26.2 / Java 25, and 26.3 / Java 25.
- Each combination on both platforms: 91 tests, 0 failures/errors, 0 skipped.
- Maintenance: complete replacement of two worlds, backup retention, refusal with held session.lock/ignored level-name, staging SHA checks, cancellation, retry, and deferred version commit; failures and crashes before/after renames and state writes.
- A separate JVM process confirms that session.lock probing preserves the server's native lock.
- The built offline distribution was additionally exercised against local HTTPS: check without application, install v1, update v2, reject an incorrect SHA while retaining v2, and recover.
- JAR/offline ZIP builds and configuration example parsing were verified.

Full Minecraft/Paper servers were not launched. The matrix verifies API/JVM behavior; the offline distribution test exercises real downloads and file changes in an isolated directory. Production servers were not touched, and no GitHub Release was published.

Local matrix XML/JSON reports are saved in build/compatibility. The prepared delivery also includes verification.zip with Windows/Linux reports. See [testing](TESTING.md) for repeatable procedures.
