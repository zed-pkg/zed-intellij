# Agent instructions

## Repository purpose

This repository owns the IntelliJ Platform integration for Zed package insights. Keep package-manager semantics in `zed-cli` and cross-client data contracts in `zed-interfaces`.

## Non-negotiable behavior

- Scanning and refresh must be non-mutating.
- Never execute a mutating Zed command without showing the exact command and working directory and receiving explicit confirmation.
- Never read, print, persist, or transmit registry tokens, refresh tokens, passwords, or service-role credentials.
- Run filesystem work and external commands off the Event Dispatch Thread.
- Publish UI changes on the Event Dispatch Thread.
- Treat nested manifests as first-class workspace packages.
- Prefer stable IntelliJ Platform APIs; do not depend on internal APIs.
- Keep model and analysis code free of IntelliJ dependencies where practical.

## Branching and CI

Use `dev` as the integration branch and strive for Gitflow-style feature branches. Pull requests must run unit tests, plugin build, and Plugin Verifier before merge.

## Repository-local Git worktrees

- Create or use a Git worktree only when the human operator explicitly authorizes it for the current task. Concurrency or a dirty checkout is not permission by itself.
- Put every authorized worktree at `<repository-root>/tmp/worktrees/<name>`; from the repository root, use `./tmp/worktrees/<name>`. Never place worktrees beside repositories or organization directories.
- Keep `tmp`, `temp`, `tmp/worktrees`, and `temp/worktrees` ignored in the repository-root `.gitignore`. Do not commit files from those directories.
- Relocate or remove a worktree only when the operator explicitly requests it. Before removal, preserve and publish intended changes, verify its commit is represented on the target branch, and confirm there are no tracked, untracked, ignored-sensitive, or in-use files that must survive. Remove it with `git worktree remove <path>` without `--force`; never delete a worktree directory with `rm`.
