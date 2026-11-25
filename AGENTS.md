# AGENTS.md — Contributor & AI Agent Guide for Magisk REtailscaled

This repository (`DR34M3R-T/magisk-retailscaled`) packages an up-to-date, userspace-networking Tailscale daemon for rooted Android (Magisk / KernelSU / APatch) together with a zero-Root Material 3 companion app (**REtailscaled**, `io.github.dr34m3rt.retailscaled`) and a minimal local HTTP control bridge (`tailscaled-bridge`).

---

## 1. Repository Layout

```text
magisk-REtailscaled/
├── bridge/                      # Zero-dependency Go local HTTP bridge (127.0.0.1:41688)
│   ├── go.mod
│   └── main.go                  # Bearer-token auth, LocalAPI status proxy, daemon/CLI control
├── gui/                         # Android Kotlin + Jetpack Compose + Material 3 companion app
│   └── app/src/main/java/io/github/dr34m3rt/retailscaled/
│       ├── MainActivity.kt
│       ├── core/
│       │   ├── BridgeClient.kt          # Sole transport to tailscaled-bridge (NO su!)
│       │   ├── TailscaleRepository.kt   # High-level status/up/down/login/ping/exit-node/webui
│       │   ├── TailscaleModels.kt       # kotlinx.serialization models
│       │   └── SettingsStore.kt         # SharedPreferences config
│       ├── tile/
│       │   └── TailscaleTileService.kt  # Android Quick Settings Tile (API 29–35)
│       └── ui/                          # HomeScreen, SettingsScreen, WebUiScreen, Theme
├── tailscale/
│   ├── settings.sh              # Default daemon flags (userspace-networking + SOCKS5 127.0.0.1:1055)
│   └── scripts/
│       ├── start.sh             # Boot & postinstall launcher (starts tailscaled + tailscaled-bridge)
│       ├── tailscaled.service   # Service lifecycle manager (start/stop/restart/status/log)
│       └── tailscaled.inotify   # Magisk module disable/enable watcher
├── customize.sh                 # Magisk/KSU flash installer (installs binaries, CLI wrapper, APK)
├── service.sh                   # Magisk late_start service entrypoint
├── uninstall.sh                 # Cleans /data/adb/tailscale on module removal
├── module.prop                  # Magisk module metadata (versionCode must stay pure integer)
├── update*.json                 # Magisk Manager online update manifests
└── .github/
    ├── workflows/release.yml    # CI: cross-compile Tailscale + bridge + build APK + publish Release
    └── tools/
        ├── build-tailscale.sh   # Fetches upstream tailscale/tailscale & builds arm64/arm + bridge
        ├── build.sh             # Packages lightweight & full Magisk zips + standalone APK
        └── version-bump.sh      # 4-part version bumper (strips -tsX.Y.Z suffix cleanly)
```

---

## 2. Hard Architectural Invariants (DO NOT BREAK)

1. **Zero `VpnService` in Android App**:
   - Never declare `BIND_VPN_SERVICE` or create an Android `VpnService`. The entire point of this project is coexisting with FlClash / Mihomo and other Android VPN clients via `userspace-networking + local SOCKS5 (127.0.0.1:1055)`.
2. **Zero `su` / Root Calls in Android App**:
   - The app (`io.github.dr34m3rt.retailscaled`) is **strictly bound** to `tailscaled-bridge`. Do NOT reintroduce `su -c` or `RootShell` fallbacks.
   - Authentication uses a 32-byte random Bearer token written by `tailscaled-bridge` to `/data/user/0/io.github.dr34m3rt.retailscaled/files/bridge_auth.json` with `chown <app_uid>:<app_uid>`, `chmod 0600`, and `restorecon`.
   - If you ever change `applicationId` in `gui/app/build.gradle.kts`, you **MUST** update the default `--app` flag in `bridge/main.go` to match.
3. **Never Commit Binaries or Build Outputs**:
   - Upstream `tailscale/tailscale`, `tailscaled-bridge`, `jq`, and `REtailscaled.apk` are built/fetched exclusively in GitHub Actions CI. Never commit `.apk`, `.zip`, `.tgz`, or compiled ELF binaries to Git.
4. **Cross-Compilation Must Use `GOOS=linux` (NOT `GOOS=android`)**:
   - In `tailscale/tailscale`, `GOOS=android` activates CGO/JNI code paths for the official Play Store APK.
   - Rooted CLI/daemon binaries MUST be built with `CGO_ENABLED=0 GOTOOLCHAIN=auto GOOS=linux GOARCH=arm64` (and `GOARCH=arm GOARM=7`) with `-tags ts_include_cli,ts_omit_*` and `-ldflags="-s -w"`.
   - Because `GOOS=linux` reads Android's `/proc/sys/kernel/hostname` (`localhost`), both the `/data/adb/tailscale/bin/tailscale` wrapper (in `customize.sh`) and `TailscaleRepository.kt` automatically pass `--hostname=<brand-model>` when running `up`/`login`.
5. **Line Endings (LF Only for Shell & Gradle Scripts)**:
   - `.gitattributes` enforces LF for `*.sh` and `gradlew`. Never commit CRLF in `customize.sh`, `service.sh`, `tailscale/**`, or `.github/tools/*.sh` — Android `/system/bin/sh` and Ubuntu CI runners will fail on `\r`.
6. **Non-Interactive Module Flashing**:
   - Keep `customize.sh` 100% non-interactive (no `getevent` volume-key prompts that hang CLI installs like `magisk --install-module`).
   - Install `REtailscaled.apk` as a normal user app via `pm install -r` (never drop modern Compose APKs into `/system/priv-app`).
7. **Versioning Rules**:
   - `module.prop` `version` uses `vMAJOR.MINOR.PATCH.BUILD-ts<tailscale_ver>` (e.g., `v1.1.0.0-ts1.104.7`).
   - `module.prop` `versionCode` MUST remain a pure 8-digit integer (`01010000`) for Magisk update compatibility.

---

## 3. Common Development & Build Commands

### Build Android GUI locally
Requires JDK 17 and Android SDK 35 (`ANDROID_HOME` set, or `gui/local.properties` — ignored by Git):
```bash
cd gui
./gradlew assembleDebug assembleRelease --no-daemon
```

### Build Go Bridge locally (requires Go 1.22+)
```bash
cd bridge
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o tailscaled-bridge-arm64 .
```

### Trigger a Cloud Release
Run **GitHub Actions → Build & Release → Run workflow**:
- `bump`: `none` | `patch` | `minor` | `major` | `build`
- `tailscale_version`: `latest` (queries `https://pkgs.tailscale.com/stable/?mode=json`) or an explicit version such as `1.104.7`.

---

## 4. Real-Device Verification Checklist (ADB + Magisk)

When testing changes on a rooted Android device:
1. Push & flash the full zip: `adb push Magisk-REtailscaled-*.zip /sdcard/Download/` → `adb shell "su -c 'magisk --install-module /sdcard/Download/Magisk-REtailscaled-*.zip'"` → reboot.
2. Verify both daemons are running:
   - `adb shell "su -c 'pidof tailscaled tailscaled-bridge'"`
   - `adb shell "su -c '/data/adb/tailscale/bin/tailscale version'"`
3. Verify zero-Root bridge handshake:
   - Confirm `/data/user/0/io.github.dr34m3rt.retailscaled/files/bridge_auth.json` exists and is owned by the app's UID (`0600`).
   - Confirm the app has **NO** Magisk superuser grant, yet opens cleanly, shows `"免 Root 模块直连 · 无 VPN 冲突"`, lists peers, runs streaming Ping, toggles Deep Power-Save mode, and updates the Quick Settings Tile.
4. Verify no VPN conflict:
   - `adb shell "dumpsys package io.github.dr34m3rt.retailscaled | grep -i vpn"` returns nothing.
   - `tailscale status --json` shows `"TUN": false`.
