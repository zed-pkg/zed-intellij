# zed-intellij

`zed-intellij` is the IntelliJ Platform plugin for the Zed package manager. It gives developers an IDE-native view of Zed package state across an entire workspace and turns detected problems into explicit, reviewable recommended actions.

## Initial capabilities

- Discovers root and nested `.zpkg.toml` packages while excluding generated, VCS, dependency, and build directories.
- Parses package identity and direct Zed dependencies without requiring a TOML language plugin.
- Validates `.zpkg.lock` version and immutable package records.
- Detects missing manifests, lockfiles, materialized dependency trees, malformed dependency names, unsupported lock versions, and unavailable Zed CLI installations.
- Shows findings in the **Zed Packages** tool window with severity, package, explanation, and recommended action.
- Refreshes when `.zpkg.toml` or `.zpkg.lock` changes.
- Offers `zed init`, `zed install`, and `zed install --frozen` only after an explicit confirmation dialog.
- Captures command output and reports success or failure through IntelliJ notifications.

## Safety model

The plugin's scan path is read-only. No package command runs during startup or refresh. A potentially mutating resolution is presented as a recommendation and requires the user to confirm the exact command and working directory before execution.

Registry credentials and tokens are never read, rendered, stored, or logged by the plugin. Commands inherit the IDE process environment, so credential management remains owned by the Zed CLI.

## Build and run

Requirements:

- JDK 21
- Gradle 9.1+

```bash
gradle runIde
gradle check
gradle buildPlugin
gradle verifyPlugin
```

Generate the standard Gradle wrapper once Gradle is available:

```bash
gradle wrapper
```

The distributable plugin ZIP is written below `build/distributions/`.

## Architecture

- `analysis/` — pure filesystem and manifest/lockfile analysis.
- `model/` — immutable snapshot, diagnostic, severity, and action types.
- `service/` — project lifecycle, CLI probing, background refresh, and command execution.
- `ui/` — tool-window presentation and action dispatch.
- `startup/` — project-open registration and initial scan.

The current analyzer is intentionally local and deterministic. The preferred long-term integration is a versioned `zed inspect --json` contract shared by the CLI, IDE plugins, CI, and agent tooling. See [`docs/diagnostics-contract.md`](docs/diagnostics-contract.md).

## Repository boundary

This belongs in its own `zed-pkg/zed-intellij` repository. It has a distinct release lifecycle, JetBrains Marketplace packaging/signing requirements, compatibility matrix, and IDE-specific test surface. Shared diagnostics schemas should live in `zed-interfaces`; the actual package resolution behavior remains in `zed-cli`.
