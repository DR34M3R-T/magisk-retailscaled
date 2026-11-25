#!/bin/bash
set -euo pipefail

# Cross-compiles upstream https://github.com/tailscale/tailscale for rooted Android (arm64 & arm)
# using static GOOS=linux binaries + ts_include_cli (single multi-call binary), no binaries committed to git.

BIN_DIR="${1:-tailscale/bin}"
DIST_DIR="${2:-dist}"
REQUESTED_VER="${TAILSCALE_VERSION:-latest}"

mkdir -p "$BIN_DIR" "$DIST_DIR"

if [ "$REQUESTED_VER" = "latest" ] || [ -z "$REQUESTED_VER" ]; then
  TS_VER=$(curl -fsSL "https://pkgs.tailscale.com/stable/?mode=json" | jq -r '.Version')
else
  TS_VER="${REQUESTED_VER#v}"
fi

TS_TAG="v${TS_VER}"
echo "Building upstream tailscale/tailscale ${TS_TAG} (static linux/arm64 & linux/arm for Android)..."

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

git clone --depth 1 --branch "$TS_TAG" https://github.com/tailscale/tailscale.git "$WORK_DIR/tailscale"

cd "$WORK_DIR/tailscale"

BUILD_TAGS="ts_include_cli,ts_omit_aws,ts_omit_bird,ts_omit_c2n,ts_omit_kube,ts_omit_synology,ts_omit_tap,ts_omit_tpm,ts_omit_wakeonlan"
LDFLAGS="-s -w -X tailscale.com/version.longStamp=${TS_VER}-android -X tailscale.com/version.shortStamp=${TS_VER}"

for ARCH in arm64 arm; do
  echo "- Cross-compiling tailscaled (${TS_TAG}) for linux/${ARCH}..."
  OUT_FILE="${OLDPWD}/${BIN_DIR}/tailscaled-${ARCH}"
  if [ "$ARCH" = "arm" ]; then
    CGO_ENABLED=0 GOTOOLCHAIN=auto GOOS=linux GOARCH=arm GOARM=7 \
      go build -trimpath -tags "$BUILD_TAGS" -ldflags "$LDFLAGS" \
      -o "$OUT_FILE" ./cmd/tailscaled
  else
    CGO_ENABLED=0 GOTOOLCHAIN=auto GOOS=linux GOARCH=arm64 \
      go build -trimpath -tags "$BUILD_TAGS" -ldflags "$LDFLAGS" \
      -o "$OUT_FILE" ./cmd/tailscaled
  fi

  if command -v upx >/dev/null 2>&1; then
    echo "  Compressing tailscaled-${ARCH} with UPX..."
    upx --best --lzma "$OUT_FILE" || true
  fi

  # Also package standalone tgz for lightweight module installs
  STAGE_DIR="$WORK_DIR/stage-${ARCH}"
  mkdir -p "$STAGE_DIR"
  cp "$OUT_FILE" "$STAGE_DIR/tailscaled"
  tar -czf "${OLDPWD}/${DIST_DIR}/tailscale_${TS_VER}_${ARCH}.tgz" -C "$STAGE_DIR" tailscaled
  ls -lh "$OUT_FILE" "${OLDPWD}/${DIST_DIR}/tailscale_${TS_VER}_${ARCH}.tgz"
done

echo "$TS_VER" > "${OLDPWD}/${DIST_DIR}/tailscale-version.txt"
echo "Finished building Tailscale ${TS_TAG}!"
