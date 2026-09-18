# Artifact sources

[Русский](../SOURCES.md) | English · [Documentation](../../README.en.md#documentation)

All adapters implement `ArtifactSource` and return a URL and authentication parameters. Shared download, verification, and installation components handle the rest of the pipeline.

Only HTTPS is supported, with certificate and hostname verification. Up to five redirects are allowed; redirects to HTTP are rejected. Authentication stays on the original origin (scheme + host + port). Once a redirect crosses origins, the token is not restored even if a later redirect returns to the original origin. Cookies, login forms, and interactive confirmation are not supported.

## GitHub

Repository archive:

```yaml
source:
  type: github
  mode: repository
  url: https://github.com/YOUR_ORG/server-lobby
  ref: "v1.0.0"
  # auth:
  #   token-env: GITHUB_TOKEN
```

This resolves to `https://api.github.com/repos/OWNER/REPO/zipball/REF`. A `.git` suffix is supported. For Enterprise, the default API base is `https://HOST/api/v3`; set `api-base` to override it.

Release asset:

```yaml
source:
  type: github
  mode: release
  url: https://github.com/YOUR_ORG/server-lobby/releases/download/v1.0.0/lobby.zip
```

For a private asset, use `https://api.github.com/repos/OWNER/REPO/releases/assets/ASSET_ID` with `token-env`. The downloader sends `Accept: application/octet-stream`. An HTML release page is not an artifact, and assets are not automatically located by name.

A private repository token needs read access to its contents. Public repositories do not require a token; GitHub API rate limits still apply.

References: [repository archives](https://docs.github.com/en/rest/repos/contents#download-a-repository-archive-zip), [release asset downloads](https://docs.github.com/en/rest/releases/assets#get-a-release-asset).

## GitLab

```yaml
source:
  type: gitlab
  mode: repository
  url: https://gitlab.com/YOUR_GROUP/subgroup/server-lobby
  ref: "v1.0.0"
  # auth:
  #   token-env: GITLAB_TOKEN
```

Request: `https://HOST/api/v4/projects/URL_ENCODED_PROJECT/repository/archive.zip?sha=REF`. Subgroups are encoded in the project ID. For installations under a URL subpath, set `api-base` explicitly; `source.url` represents the project path relative to that API base.

The default header is `PRIVATE-TOKEN` without a prefix. For CI job tokens, set `header: JOB-TOKEN` and `scheme: ""`. API access for job tokens depends on GitLab version and configuration; verify it with `check`.

Direct release asset:

```yaml
source:
  type: gitlab
  mode: release
  url: https://gitlab.com/YOUR_GROUP/server-lobby/-/releases/v1.0.0/downloads/lobby.zip
```

The URL must be configured as a direct release asset and return a ZIP. HTML release pages and metadata endpoints are not supported.

References: [GitLab archive API](https://docs.gitlab.com/api/repositories/#get-file-archive), [permanent links to release assets](https://docs.gitlab.com/user/project/releases/release_fields/#permanent-links-to-release-assets).

## HTTPS

```yaml
source:
  type: https
  url: https://downloads.example.org/lobby-v1.zip
```

Direct ZIP downloads with HTTP 200 are supported, including signed URLs. Content-Type is not treated as proof of the format: the archive itself is validated. HTML, JSON error responses, gzip content encoding, and partial HTTP 206 responses are rejected. The URL does not need to end in `.zip`.

## Google Drive

```yaml
source:
  type: google-drive
  url: "https://drive.google.com/uc?export=download&id=YOUR_FILE_ID"
```

Another possible form is `https://drive.usercontent.google.com/download?id=YOUR_FILE_ID&export=download`, provided it actually returns a ZIP to an unauthenticated client.

`google-drive` uses the shared HTTPS downloader. `/file/d/ID/view` links are not converted and are not download links. Enabling “anyone with the link” does not guarantee a direct download: quotas, large-file checks, confirmation pages, and service rules may return HTML or an error. In that case, the plugin stops before applying files. It does not bypass authentication, bot checks, quotas, or confirmation pages. For predictable production updates, use an artifact accessible without an interactive session.

## Adding a source

Add an `ArtifactSource` adapter and its type to configuration validation. Keep installation, extraction, and SHA verification in the shared pipeline. Unit tests should cover URL construction, authentication headers, and use of that pipeline. New authentication methods need a separate review of secret handling across redirects.
