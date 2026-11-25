#!/bin/bash
set -euo pipefail

VERSION="${1:-$(grep '^version=' module.prop | cut -d'=' -f2)}"
DIST_DIR="dist"
BIN_DIR="tailscale/bin"
APK_DIR="tailscale/apk"

get_latest_release() {
  curl -fsSL "https://api.github.com/repos/$1/releases/latest" | grep '"browser_download_url"' | grep "$2" | head -n 1 | sed 's/.*"browser_download_url": "\([^"]*\)".*/\1/'
}

echo "Building Magisk-REtailscaled ${VERSION}"
echo "========================================"

mkdir -p "$BIN_DIR" "$APK_DIR" "$DIST_DIR"

# 1. Ensure tailscaled binaries exist (build from upstream tailscale/tailscale if not already built)
if [ ! -f "${BIN_DIR}/tailscaled-arm64" ] || [ ! -f "${BIN_DIR}/tailscaled-arm" ]; then
  ./.github/tools/build-tailscale.sh "$BIN_DIR" "$DIST_DIR"
fi

# 2. Download jq for arm & arm64
echo "Downloading jq binaries..."
for ARCH in arm arm64; do
  case $ARCH in
    arm) F_ARCH=armv7a ;;
    arm64) F_ARCH=aarch64 ;;
  esac
  if [ ! -f "${BIN_DIR}/jq-${ARCH}" ]; then
    echo "- Downloading jq for ${ARCH}..."
    URL=$(get_latest_release "theshoqanebi/jq-build-for-android" "jq-${F_ARCH}-linux-android")
    curl -fL "$URL" -o "${BIN_DIR}/jq-${ARCH}"
  fi
done

# 3. Copy built RootTailscaleGUI APK if present
if [ -f "gui/app/build/outputs/apk/release/app-release.apk" ]; then
  cp "gui/app/build/outputs/apk/release/app-release.apk" "${APK_DIR}/RootTailscaleGUI.apk"
  cp "gui/app/build/outputs/apk/release/app-release.apk" "${DIST_DIR}/RootTailscaleGUI-${VERSION}.apk"
elif [ -f "gui/app/build/outputs/apk/debug/app-debug.apk" ]; then
  cp "gui/app/build/outputs/apk/debug/app-debug.apk" "${APK_DIR}/RootTailscaleGUI.apk"
  cp "gui/app/build/outputs/apk/debug/app-debug.apk" "${DIST_DIR}/RootTailscaleGUI-${VERSION}.apk"
fi

echo ""
echo "Creating lightweight zip (downloads tailscaled on install, includes GUI APK if built)..."
zip -9 -r "${DIST_DIR}/Magisk-REtailscaled-${VERSION}.zip" . \
  -x "*.git*" "dist/*" "gui/*" "*.zip" "*.json" "*.md" "${BIN_DIR}/*" ".shellcheckrc" >/dev/null

echo "Creating full zip (includes tailscaled + jq + RootTailscaleGUI APK)..."
zip -9 -r "${DIST_DIR}/Magisk-REtailscaled-${VERSION}-full.zip" . \
  -x "*.git*" "dist/*" "gui/*" "*.zip" "*.json" "*.md" ".shellcheckrc" >/dev/null

rm -rf "$BIN_DIR"/* "$APK_DIR"/*

echo ""
echo "Build completed successfully!"
echo "========================================"
ls -lh "${DIST_DIR}/"
echo "========================================"
