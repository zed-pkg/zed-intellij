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
