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

# Inject an Android hostinfo hook (uses upstream RegisterHostinfoNewHook, no patches to upstream files)
cat <<'EOF' > cmd/tailscaled/android_hostinfo.go
package main

import (
	"os"
	"os/exec"
	"strings"
	"sync"

	"tailscale.com/hostinfo"
	"tailscale.com/tailcfg"
)

func init() {
	var (
		once          sync.Once
		androidOS     string
		androidVer    string
		androidDevice string
	)
	load := func() {
		androidOS = strings.TrimSpace(os.Getenv("TS_HOSTINFO_OS"))
		if androidOS == "" {
			androidOS = "android"
		}
		androidVer = strings.TrimSpace(os.Getenv("TS_HOSTINFO_OS_VERSION"))
		if androidVer == "" {
			if out, err := exec.Command("getprop", "ro.build.version.release").Output(); err == nil {
				androidVer = strings.TrimSpace(string(out))
			}
		}
		var brand, model string
		if out, err := exec.Command("getprop", "ro.product.brand").Output(); err == nil {
			brand = strings.TrimSpace(string(out))
		}
		if out, err := exec.Command("getprop", "ro.product.model").Output(); err == nil {
			model = strings.TrimSpace(string(out))
		}
		if model != "" {
			if brand != "" && !strings.HasPrefix(strings.ToLower(model), strings.ToLower(brand)) {
				androidDevice = brand + " " + model
			} else {
				androidDevice = model
			}
		}
	}

	hostinfo.RegisterHostinfoNewHook(func(hi *tailcfg.Hostinfo) {
		once.Do(load)
		hi.OS = androidOS
		if androidVer != "" {
			hi.OSVersion = androidVer
		}
		if androidDevice != "" {
			hi.DeviceModel = androidDevice
		}
		hi.Distro = ""
		hi.DistroVersion = ""
		hi.DistroCodeName = ""
	})
}
EOF

# Inject Android VPN bypass support (SO_MARK=0x20000 / FWMARK_PROTECTED_FROM_VPN)
# so tailscaled's outbound traffic bypasses Android VPN tun0 (e.g. FlClash) when TS_BYPASS_VPN=1.
cat <<'EOF' > net/netns/netns_android_bypass.go
//go:build linux && !android

package netns

import (
	"log"
	"os"
	"strconv"
	"strings"
	"sync"

	"golang.org/x/sys/unix"
)

const defaultAndroidVpnBypassMark = 0x20000 // AOSP FWMARK_PROTECTED_FROM_VPN

var (
	androidBypassOnce    sync.Once
	androidBypassEnabled bool
	androidBypassMark    int
)

func loadAndroidVpnBypassConfig() {
	raw := strings.TrimSpace(strings.ToLower(os.Getenv("TS_BYPASS_VPN")))
	switch raw {
	case "1", "true", "yes", "on":
		androidBypassEnabled = true
	default:
		androidBypassEnabled = false
	}

	androidBypassMark = defaultAndroidVpnBypassMark
	if markStr := strings.TrimSpace(os.Getenv("TS_BYPASS_VPN_MARK")); markStr != "" {
		if v, err := strconv.ParseInt(markStr, 0, 32); err == nil && v > 0 {
			androidBypassMark = int(v)
		}
	}

	if androidBypassEnabled {
		log.Printf("netns: Android VPN bypass enabled (SO_MARK=0x%x)", androidBypassMark)
	}
}

func isAndroidVpnBypassEnabled() bool {
	androidBypassOnce.Do(loadAndroidVpnBypassConfig)
	return androidBypassEnabled
}

func getAndroidVpnBypassMark() int {
	androidBypassOnce.Do(loadAndroidVpnBypassConfig)
	return androidBypassMark
}

func setAndroidVpnBypassMark(fd uintptr) error {
	mark := getAndroidVpnBypassMark()
	cur, err := unix.GetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK)
	if err != nil {
		cur = 0
	}
	return unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK, cur|mark)
}
EOF

python3 - <<'PYEOF'
from pathlib import Path
import sys

def replace_exact(path_str: str, old: str, new: str) -> None:
    p = Path(path_str)
    content = p.read_text(encoding="utf-8")
    count = content.count(old)
    if count != 1:
        sys.exit(f"Expected 1 occurrence in {path_str}, found {count}")
    p.write_text(content.replace(old, new, 1), encoding="utf-8")

# 1. Keep netns enabled in userspace-networking mode when TS_BYPASS_VPN=1
replace_exact(
    "net/netns/netns.go",
    """func SetEnabled(on bool) {
\tdisabled.Store(!on)
}""",
    """func SetEnabled(on bool) {
\tif !on && isAndroidVpnBypassEnabled() {
\t\tdisabled.Store(false)
\t\treturn
\t}
\tdisabled.Store(!on)
}""",
)

# 2. Apply Android FWMARK_PROTECTED_FROM_VPN (0x20000) in setBypassMark when TS_BYPASS_VPN=1
replace_exact(
    "net/netns/netns_linux.go",
    """func setBypassMark(fd uintptr) error {
\tif err := unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK, tsconst.LinuxBypassMarkNum); err != nil {
\t\treturn fmt.Errorf("setting SO_MARK bypass: %w", err)
\t}
\treturn nil
}""",
    """func setBypassMark(fd uintptr) error {
\tif isAndroidVpnBypassEnabled() {
\t\tif err := setAndroidVpnBypassMark(fd); err != nil {
\t\t\treturn fmt.Errorf("setting Android VPN bypass SO_MARK: %w", err)
\t\t}
\t\treturn nil
\t}
\tif err := unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_MARK, tsconst.LinuxBypassMarkNum); err != nil {
\t\treturn fmt.Errorf("setting SO_MARK bypass: %w", err)
\t}
\treturn nil
}""",
)
print("Successfully patched net/netns for Android VPN bypass (SO_MARK=0x20000).")
PYEOF

BUILD_TAGS="ts_include_cli,ts_omit_aws,ts_omit_bird,ts_omit_c2n,ts_omit_kube,ts_omit_synology,ts_omit_tap,ts_omit_tpm,ts_omit_wakeonlan"
LDFLAGS="-s -w -X 'tailscale.com/version.longStamp=${TS_VER} (Magisk)' -X tailscale.com/version.shortStamp=${TS_VER}"

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

  # Also cross-compile local HTTP control bridge (zero-Root GUI communication)
  BRIDGE_OUT="${OLDPWD}/${BIN_DIR}/tailscaled-bridge-${ARCH}"
  if [ -d "${OLDPWD}/bridge" ]; then
    echo "- Cross-compiling tailscaled-bridge for linux/${ARCH}..."
    (
      cd "${OLDPWD}/bridge"
      if [ "$ARCH" = "arm" ]; then
        CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 go build -trimpath -ldflags="-s -w" -o "$BRIDGE_OUT" .
      else
        CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o "$BRIDGE_OUT" .
      fi
    )
    if command -v upx >/dev/null 2>&1; then
      upx --best --lzma "$BRIDGE_OUT" || true
    fi
  fi

  # Also package standalone tgz for lightweight module installs
  STAGE_DIR="$WORK_DIR/stage-${ARCH}"
  mkdir -p "$STAGE_DIR"
  cp "$OUT_FILE" "$STAGE_DIR/tailscaled"
  [ -f "$BRIDGE_OUT" ] && cp "$BRIDGE_OUT" "$STAGE_DIR/tailscaled-bridge"
  tar -czf "${OLDPWD}/${DIST_DIR}/tailscale_${TS_VER}_${ARCH}.tgz" -C "$STAGE_DIR" .
  ls -lh "$OUT_FILE" "${OLDPWD}/${DIST_DIR}/tailscale_${TS_VER}_${ARCH}.tgz"
done

echo "$TS_VER" > "${OLDPWD}/${DIST_DIR}/tailscale-version.txt"
echo "Finished building Tailscale ${TS_TAG}!"
