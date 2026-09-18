# Building and testing

[Русский](../TESTING.md) | English · [Documentation](../../README.en.md#documentation)

## Local build

You need Git, JDK 17/21, and access to Maven Central, Spigot snapshots, and Gradle distributions. The repository includes the Gradle Wrapper; a separate Gradle installation is unnecessary. Run commands from the repository root.

```sh
./gradlew clean build
```

PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
.\gradlew.bat clean build
```

Plugin: `build/libs/ServerBootstrap-2.1.0.jar`. Offline distribution: `build/distributions/ServerBootstrap-2.1.0-offline.zip`. HTML report: `build/reports/tests/test/index.html`; JUnit XML: `build/test-results/test/`.

Run Gradle 8.8 on Java 17/21. Java 25 is used in a separate test process:

```powershell
.\gradlew.bat test "-PtestApi=26.3-R0.1-SNAPSHOT" "-PtestJavaHome=C:\Program Files\Java\jdk-25"
```

Complete Windows matrix:

```powershell
.\scripts\test-matrix.ps1 -Java17 "C:\Java\jdk-17" -Java21 "C:\Java\jdk-21" -Java25 "C:\Java\jdk-25"
```

Results: build/compatibility/results.json and separate XML reports per version. Linux CI is defined in [.github/workflows/build.yml](../../.github/workflows/build.yml). Snapshot APIs can change upstream; rerun the matrix to verify a new build.

Tests use temporary directories only. HTTPS tests generate a temporary certificate with keytool, trusted only by a dedicated test client; production TLS validation is not disabled. On Windows, safe-path tests use a junction if the OS does not permit symlink creation.

## Automated test coverage

- Configuration schema, incorrect fields/types, required files, and secret redaction.
- Legacy GitHub/GitLab, project/ref encoding, releases, HTTPS, and the Google Drive direct-link adapter.
- Matching/mismatched SHA-256, HTML/corrupt ZIP, CRC, traversal, Windows paths, case/Unicode conflicts, and limits.
- Real local TLS, HTTP statuses, unavailable servers, TLS trust failure, overall deadlines, stalled response bodies, and stream limits.
- Redirects: retaining same-origin authentication, removing it across origins, and rejecting HTTP and loops.
- Failures before/during application and state writes, complete/incomplete rollback, process interruption, and repeated recovery.
- Maintenance: simulated boots, refusal when level-name is ignored or a target world is loaded, staging SHA, cancellation, retry, complete directory replacement, and rollback at each stage.
- A separate JVM process verifies that probing session.lock does not release the server's native lock.
- Global install/check serialization, interprocess node.lock, and version persistence.
- HTTP method/path/auth/body/status/conflict handling and absence of secrets in logs.
- Console/admin permissions, minimum API, and Java 17 bytecode.

## Isolated HTTPS sandbox

The sandbox creates a new directory with a test config.yml rather than starting Minecraft. It contains no executable third-party plugins, live worlds, or production files. The script refuses to reuse an unmarked existing directory.

1. Create a sandbox using Python 3 and JDK 17+:

   ```powershell
   python scripts/create-sandbox.py C:\Temp\sb-demo --java-home "C:\Program Files\Java\jdk-21"
   ```

2. Start local HTTPS in a separate terminal:

   ```powershell
   & "C:\Program Files\Java\jdk-21\bin\java.exe" scripts/SandboxServer.java C:\Temp\sb-demo 18443
   ```

3. Extract the offline ZIP into C:\Temp\sb-tool and run:

   ```powershell
   & "C:\Program Files\Java\jdk-21\bin\java.exe" "-Djavax.net.ssl.trustStore=C:\Temp\sb-demo\sandbox.p12" "-Djavax.net.ssl.trustStorePassword=sandbox-only" -cp "C:\Temp\sb-tool\lib\*" dev.kekaop.ServerBootstrap.OfflineMain C:\Temp\sb-demo\server demo check --server-stopped
   ```

4. Replace `check` with `install`. The file server/plugins/Demo/config.yml should appear with the text Sandbox v1.
5. Prepare an update:

   ```powershell
   python scripts/create-sandbox.py C:\Temp\sb-demo --java-home "C:\Program Files\Java\jdk-21" --version v2 --update
   ```

6. Run install again. The content becomes Sandbox v2 and installed.properties records v2 and its SHA.
7. Replace the SHA in the test config.yml with 64 zeros. Another install must return exit code 1, report SHA-256 mismatch, and preserve v2.
8. Press Enter in the HTTPS terminal to stop the sandbox server.

`sandbox-only` is a public password exclusively for the temporary test keystore. Never use it in production. The truststore is passed only to this Java process; system trust is unchanged. On Linux, use the equivalent python3/java commands and paths.

## Testing a real Paper server

Test versions from [compatibility](COMPATIBILITY.md) separately. Create a new directory, obtain the server build from the official Paper Downloads Service, verify its SHA, and use the appropriate Java version. If needed, read and accept the Minecraft EULA yourself in the test directory; these automated tests do not accept it.

Place the plugin JAR in plugins and start with `java -Xms512M -Xmx2G -jar paper.jar --nogui`. Once configuration is generated, stop the server, add a harmless test profile with restart=false, start again, and exercise help/check/install/current/status. Verify persistence after another startup and denial for an ordinary player without permission. For direct mode with restart=true, configure a separate test restart script. Maintenance needs no restart script: follow [the maintenance guide](MAINTENANCE.md), verifying stops, persisted phases, and removal of stale chunks from the active world after returning.

Do not use production servers or worlds. The old Gradle runServer task was removed because it pinned the sandbox to one version and did not provide the required matrix.
