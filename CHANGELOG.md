## v1.3.0.0

### Features
- feat(build): report Android OS/version via hostinfo hook and stamp (Magisk) version (`b9c3300`)

### Bug Fixes
- fix(gui): import java.util.Properties to avoid Kotlin DSL java extension clash (`51a7298`)
- fix(gui): prefer Tailscale Machine name (DNSName) over OS hostname (`ecacf3c`)
- fix(gui): prevent PullToRefreshBox sticking on fast local refresh (`9ef5be3`)
- fix(bridge): use 15s login timeout and return immediately once auth URL is captured (`ad22cf6`)
- fix(login,docs): surface browser login errors, clean legacy paths and unify package name (`d3cd98c`)

### Build & Maintenance
- ci(release): auto-categorize release notes with full git history and optional highlights (`0b32fd9`)
- build(gui): sync APK versionCode and versionName from module.prop (`87828c3`)

### Other Changes
- Remove v1.2.0.0 entry from CHANGELOG (`9825508`)

## v1.1.0.0

- 7f80df1 docs: add AGENTS.md contributor and AI agent guide

# Changelog

## v1.0.0.0

- Initial release of **Magisk REtailscaled** (inspired by `anasfanani/magisk-tailscaled`).
- GitHub Actions CI automatically cross-compiles upstream `tailscale/tailscale` (`GOOS=linux`, `ts_include_cli`, UPX-compressed) for Android `arm64` & `arm`.
- Bundled **RootTailscaleGUI** (Jetpack Compose + Material 3 + Quick Settings Tile) installed automatically via `pm install -r`.
- Default `userspace-networking` + local SOCKS5 (`127.0.0.1:1055`) mode for seamless coexistence with FlClash and Android VPNs.
