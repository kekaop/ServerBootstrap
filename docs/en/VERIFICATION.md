# Verification report

[Русский](../VERIFICATION.md) | English · [Documentation](../../README.en.md#documentation)

The verification matrix runs on Windows and Linux through GitHub Actions.

- Gradle Wrapper 8.8, launched with JDK 21.
- Production bytecode: `--release 17`, compiled against Bukkit/Spigot API 1.20.1.
- Complete matrix: API 1.20.1 / Java 17, 1.20.6 / Java 21, 1.21.11 / Java 21, 26.2 / Java 25, and 26.3 / Java 25.
- Each combination on both platforms: 91 tests, 0 failures/errors, 0 skipped.
- Maintenance: complete replacement of two worlds, backup retention, refusal with held session.lock/ignored level-name, staging SHA checks, cancellation, retry, and deferred version commit; failures and crashes before/after renames and state writes.
- A separate JVM process confirms that session.lock probing preserves the server's native lock.
- The built offline distribution was additionally exercised against local HTTPS: check without application, install v1, update v2, reject an incorrect SHA while retaining v2, and recover.
- JAR/offline ZIP builds and configuration example parsing were verified.

The matrix verifies API/JVM behavior, while the sandbox exercises real downloads and file changes in an isolated directory. Run a full Paper runtime smoke test separately using the procedure in [testing](TESTING.md).

XML/JSON reports are available in `build/compatibility`; repeatable procedures are documented in [testing](TESTING.md).
