# Proposed machine-readable diagnostics contract

The plugin currently performs a deterministic local scan. The canonical end state is a CLI command such as:

```bash
zed inspect --format json --no-network
```

The command should be guaranteed non-mutating and should return a versioned envelope:

```json
{
  "schemaVersion": 1,
  "zedVersion": "0.1.0",
  "workspace": "/workspace",
  "packages": [
    {
      "root": "/workspace/packages/example",
      "identity": { "org": "acme", "name": "example", "version": "1.2.3" },
      "manifest": { "path": ".zpkg.toml", "digest": "sha256:..." },
      "lockfile": { "path": ".zpkg.lock", "version": 1, "digest": "sha256:..." },
      "dependencies": [],
      "diagnostics": []
    }
  ]
}
```

Each diagnostic should include:

- Stable code, severity, summary, and detailed explanation.
- Package root and optional manifest/lockfile source range.
- Evidence used to reach the conclusion.
- One or more recommended actions.
- Exact command arguments, working directory, whether the command mutates state, whether it may use the network, and whether user confirmation is required.
- A confidence field derived from deterministic checks, not an opaque model estimate.

The JSON schema should be published from `zed-interfaces` and consumed by `zed-intellij`, `zed-vscode`, CI annotations, and future IDE integrations.
