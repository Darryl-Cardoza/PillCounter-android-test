#!/usr/bin/env bash
# Registers the Gradle toolchain resolver in settings.gradle(.kts) inside Qodana's
# ephemeral checkout. Idempotent. Lives in a file, not inline in qodana.yaml's
# `bootstrap:`, because Qodana's shell hand-off mangles quotes in inline scripts.
# `plugins {}` must come right AFTER `pluginManagement {}` (Gradle rejects it before),
# so the awk pass tracks brace depth to find where that block closes.
set -eu
for f in settings.gradle.kts settings.gradle; do
  [ -f "$f" ] || continue
  grep -q "foojay-resolver-convention" "$f" && continue
  if grep -qE '^[ \t]*pluginManagement[ \t]*\{' "$f"; then
    awk '
      BEGIN { depth = 0; active = 0; inserted = 0; q = sprintf("%c", 34) }
      !inserted && !active && $0 ~ /^[ \t]*pluginManagement[ \t]*\{/ { active = 1; depth = 1; print; next }
      active {
        print
        t = $0; depth += gsub(/\{/, "{", t)
        t = $0; depth -= gsub(/\}/, "}", t)
        if (depth <= 0) {
          active = 0; inserted = 1
          print ""
          print "plugins {"
          print "    id(" q "org.gradle.toolchains.foojay-resolver-convention" q ") version " q "1.0.0" q
          print "}"
        }
        next
      }
      { print }
    ' "$f" > "$f.qodana-tmp"
  else
    { printf 'plugins {\n    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"\n}\n\n'; cat "$f"; } > "$f.qodana-tmp"
  fi
  mv "$f.qodana-tmp" "$f"
done
