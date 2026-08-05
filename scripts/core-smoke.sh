#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/zed-intellij-core-smoke"
rm -rf "$OUT"
mkdir -p "$OUT"
kotlinc \
  "$ROOT/src/main/kotlin/tech/zpkg/intellij/model/ZedModels.kt" \
  "$ROOT/src/main/kotlin/tech/zpkg/intellij/analysis/ZedTomlScanner.kt" \
  "$ROOT/src/main/kotlin/tech/zpkg/intellij/analysis/ZedProjectAnalyzer.kt" \
  "$ROOT/scripts/core-smoke.kt" \
  -include-runtime -d "$OUT/smoke.jar"
java -jar "$OUT/smoke.jar"
