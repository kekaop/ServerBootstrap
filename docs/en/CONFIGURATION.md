# Configuration and ZIP archives

[Русский](../CONFIGURATION.md) | English · [Documentation](../../README.en.md#documentation)

Configuration path: `plugins/ServerBootstrap/config.yml`. Schema: `config-version: 1`. The schema version and plugin version are separate. Changes take effect at the next startup; there is no reload command. Invalid configuration blocks installation and player logins; `status` reports the reason without exposing secret values. Duplicate YAML keys, unknown fields, and incorrect value types are rejected.

## Top-level fields

| Field | Default | Purpose |
|---|---|---|
| config-version | Required for the new schema | Integer 1 |
| profiles | Required; may be {} | Profile names mapped to their definitions |
| limits.max-download-bytes | 268435456 | Maximum ZIP size, including streams without Content-Length |
| limits.max-extracted-bytes | 1073741824 | Total extracted bytes, including files not selected for installation |
| limits.max-entries | 10000 | ZIP entries, including directories; maximum 1000000 |
| limits.connect-timeout-seconds | 15 | Connection timeout; 1–3600 |
| limits.download-timeout-seconds | 180 | Total download deadline, including response body and redirects; 1–86400 |
| listener.enabled | false | Enable HTTP |
| listener.bind | 127.0.0.1 | Only 127.0.0.1, ::1, or 0.0.0.0 |
| listener.port | 8080 | 1–65535; 0 is not allowed in production configuration |
| listener.token | Empty | HTTP secret; at least 32 characters when the listener is enabled |
| listener.token-env | Empty | Environment variable name instead of token |

Sizes are positive integers in bytes. YAML boolean values must be unquoted `true` or `false`. Quote all version values as strings.

## Profiles

A profile name is a key under `profiles`: 1–64 ASCII letters, digits, `_`, or `-`, starting with a letter or digit. `none` is reserved for an unbound node.

| Profile field | Default | Purpose |
|---|---|---|
| source.type | Required | github, gitlab, https, google-drive |
| source.mode | repository for GitHub/GitLab; direct otherwise | GitHub/GitLab: repository or release; other types: direct |
| source.url | Required | HTTPS repository or ZIP artifact URL |
| source.api-base | Determined by the adapter | GitHub/GitLab repository API base; no query string |
| source.ref | Required for repository mode | Branch, tag, or commit; an immutable commit is recommended |
| source.auth.token | Empty | Token stored in configuration |
| source.auth.token-env | Empty | Environment variable containing the token |
| source.auth.header | PRIVATE-TOKEN for gitlab; Authorization otherwise | Authorization, PRIVATE-TOKEN, or JOB-TOKEN |
| source.auth.scheme | Bearer for Authorization; empty otherwise | Bearer, token, or an empty string |
| version | Required | Build label: 1–128 ASCII characters; starts with a letter/digit, then also permits . _ / + - |
| sha256 | Empty | 64 hexadecimal characters; case-insensitive |
| archive-root | auto | auto, ".", or a relative directory inside the ZIP |
| apply.include | [plugins] | Nonempty list of relative file/directory paths |
| apply.required-files | [] | Specific files that must be selected for installation |
| apply.mode | direct | direct replaces selected files; maintenance replaces entire worlds through a temporary world |
| apply.worlds | [] | Maintenance only: 1–1000 unique world names; must match include |
| restart | true | direct requests a restart; maintenance stops after preparation and replacement, with the host/operator responsible for starting again |

`version` is an administrator-defined label. SHA-256, not the label, verifies the bytes. If `sha256` is absent, size, structure, and ZIP CRC are checked, but the artifact is not authenticated against an expected digest; the log reports `checksum=not-configured`.

`token` and `token-env` are mutually exclusive. The environment variable must exist in the server process. Strings such as `${token}` are rejected rather than interpolated. Restart the process after changing its environment.

`include` contains literal paths, not glob patterns. A directory includes its whole subtree. Direct mode preserves existing files absent from the archive. Maintenance mode completely replaces each listed world with its snapshot, moving previous chunks and data to backup. Required files must be inside `include` and outside protected paths.

Maintenance world names use 1–64 ASCII letters, digits, `_`, or `-`, starting with a letter or digit. System directories, Windows device names, and the `sb_maintenance_` prefix are forbidden. Every world must contain `level.dat`. This mode installs worlds only, without plugins, configuration directories, or server.properties. See [maintenance mode](MAINTENANCE.md).

## Archive structure

```text
plugins/
  ExamplePlugin.jar
  ExamplePlugin/
    config.yml
config/
  paper-global.yml
server.properties
world/
  level.dat
  region/
    r.0.0.mca
```

Include only the paths you need, for example `[plugins, config, server.properties, world]`. This mixed list is valid only for direct mode, with worlds installed offline. On shared hosting, use maintenance mode with a separate world list. The world format must match the server software; ServerBootstrap does not convert or downgrade worlds.

`archive-root: auto` accepts contents at the ZIP root or inside a single wrapper directory added by GitHub/GitLab. For deeper layouts, set the directory explicitly, for example `archive-root: deployment/server`. The plugin does not search the archive for an arbitrary `plugins` directory.

At least one file must be selected for installation. Empty directories alone are not installed. README files and other contents outside `include` are extracted for validation but are not copied into the server.

## Path protection

Absolute paths, `..`, `.`, backslashes, UNC/drive paths, NTFS alternate streams, control characters, Windows device names, trailing spaces/dots, duplicate paths, and ambiguous case/Unicode paths are rejected. Paths are limited to 32 components and 1024 characters. Symbolic links and junctions in destination paths are rejected.

UNIX ZIP attributes are not reproduced: archive symlinks are not created; their contents are treated as regular files. Executable bits are not preserved. Profiles are intended for JARs, configuration, and data.

The installer skips `plugins/ServerBootstrap/**`, `plugins/NodeMetrics/**`, JARs with those prefixes in `plugins/`, and `.git/**`. The actual path of the running ServerBootstrap JAR is also protected even if renamed. Its configuration, journals, and installed state cannot be replaced by an archive.

## Secrets

Restrict access to config.yml, environment variables, and backups to the server account. Do not commit real tokens to Git. Signed URLs may contain secrets; URLs and query strings are not logged. HTTP error bodies and raw network-library exceptions are not logged either.
