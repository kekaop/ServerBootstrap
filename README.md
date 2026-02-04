# ServerBootstrap

ServerBootstrap is designed for clustered Minecraft setups with multiple identical servers.
It automates first-time provisioning and deterministic updates using profile-based deployments.
This makes it a strict, repeatable bootstrap and lifecycle tool rather than a general updater.

## Features

- Choose GitLab or GitHub as the source provider
- Download and install a profile ZIP
- Update worlds and plugins from the profile
- Lightweight HTTP listener for remote update triggers

## What is a profile?

A profile is a ZIP archive that represents a complete server state:

- plugins
- configs
- optional worlds

Each server node is permanently bound to a single profile.

## Lifecycle

### First install (bootstrap)

- Performed only once
- Binds the server to a profile
- Downloads and installs the initial server state

### Updates

- Triggered via HTTP or CI
- Always reuse the stored `node-profile`
- Deterministic and repeatable

## How it works

1. You run `/setup-server <profile>` from the console.
2. The plugin validates the profile exists on the selected provider (GitLab/GitHub).
3. The profile archive is downloaded as a ZIP into the server `plugins/` directory.
4. The ZIP is extracted into a temporary folder (`update_temp`).
5. Files are copied into the server root:
   - `plugins/` directory from the profile overwrites existing plugins.
   - Worlds (`world`, `world_nether`, `world_the_end`) are copied if present.
   - The plugin skips copying `ServerBootstrap.jar` (to avoid replacing itself).
6. The temporary folder is deleted.
7. The plugin updates `node-profile`, marks `first-install` as `true`,
   writes `nodeName` into `plugins/NodeMetrics/config.yml` (if installed),
   and restarts the server.

## Non-goals

- Not intended for single-server setups
- No partial updates
- No per-file syncing
- No dynamic profile switching at runtime

## Commands

- `/setup-server <profile>` (console only)

## Configuration

Edit `src/main/resources/config.yml` (or the generated `plugins/ServerBootstrap/config.yml`):

```
first-install: false
secret: "change-me"
port: 8080
node-profile: "none"
source:
  provider: "gitlab" # gitlab | github
  gitlab:
    api-base: "https://gitlab.com/api/v4"
    project-path-template: "your-group/your-projects/%s"
    token: "your-gitlab-token"
  github:
    api-base: "https://api.github.com"
    owner: "your-org"
    repo-template: "%s"
    token: "your-github-token"
```

### Provider notes

- **GitLab** uses `project-path-template`, for example `my-group/prod/%s`.
- **GitHub** uses `owner` + `repo-template`, for example `owner: my-org` and `repo-template: "%s"`.
- Tokens are optional for public repos/projects but recommended to avoid rate limits.

## UpdateListener

`UpdateListener` starts a lightweight HTTP server and listens for update requests.
It is useful for triggering a profile update remotely (for example, from a panel or CI).

- Listens on the configured `port`
- Accepts `POST /update` with JSON body `{ "secret": "..." }`
- Validates the `secret` from `config.yml`
- Starts the download and install process for the configured `node-profile`
- Returns JSON response `{ "status": "update-started" }` on success

Example request:

```
POST http://<server-ip>:<port>/update
Content-Type: application/json

{ "secret": "change-me" }
```

## Flow diagram (text)

CI / Panel
   ↓
UpdateListener (HTTP)
   ↓
ServerBootstrap
   ↓
Artifact download
   ↓
Install
   ↓
Restart

## Build

```
./gradlew build
```

## Run a test server

```
./gradlew runServer
```
