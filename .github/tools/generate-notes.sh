#!/bin/bash
set -euo pipefail

# Generates categorized Markdown changelog entries since the previous git tag.
# Usage: generate-notes.sh [current_tag_to_exclude]

EXCLUDE_TAG="${1:-}"

if [ -n "$EXCLUDE_TAG" ]; then
  LAST_TAG=$(git tag --sort=-creatordate | grep -vxF "$EXCLUDE_TAG" | head -n 1 || true)
else
  LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || true)
fi

if [ -n "$LAST_TAG" ]; then
  RANGE="${LAST_TAG}..HEAD"
else
  RANGE="HEAD"
fi

RAW_COMMITS=$(git log "$RANGE" --no-merges --pretty=format:"%h|%s" 2>/dev/null \
  | grep -vE '^[0-9a-f]+\|Bump version ' || true)

if [ -z "$RAW_COMMITS" ]; then
  echo "- Routine build / upstream Tailscale update."
  exit 0
fi

FEAT=""
FIX=""
BUILD=""
DOCS=""
OTHER=""

while IFS='|' read -r HASH SUBJECT; do
  [ -z "$HASH" ] && continue
  LINE="- ${SUBJECT} (\`${HASH}\`)"
  case "$SUBJECT" in
    feat:*|feat\(*\):*) FEAT="${FEAT}${LINE}"$'\n' ;;
    fix:*|fix\(*\):*) FIX="${FIX}${LINE}"$'\n' ;;
    build:*|build\(*\):*|ci:*|ci\(*\):*|chore:*|chore\(*\):*|refactor:*|refactor\(*\):*|perf:*|perf\(*\):*)
      BUILD="${BUILD}${LINE}"$'\n' ;;
    docs:*|docs\(*\):*) DOCS="${DOCS}${LINE}"$'\n' ;;
    *) OTHER="${OTHER}${LINE}"$'\n' ;;
  esac
done <<< "$RAW_COMMITS"

print_section() {
  local TITLE="$1"
  local CONTENT="$2"
  if [ -n "$CONTENT" ]; then
    printf "### %s\n%s\n" "$TITLE" "$CONTENT"
  fi
}

print_section "Features" "$FEAT"
print_section "Bug Fixes" "$FIX"
print_section "Build & Maintenance" "$BUILD"
print_section "Documentation" "$DOCS"
print_section "Other Changes" "$OTHER"
