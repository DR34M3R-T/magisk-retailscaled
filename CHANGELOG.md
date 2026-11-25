## v1.1.0.0

- 7f80df1 docs: add AGENTS.md contributor and AI agent guide

# Changelog

## v1.0.0.0

- Initial release of **Magisk REtailscaled** (inspired by `anasfanani/magisk-tailscaled`).
- GitHub Actions CI automatically cross-compiles upstream `tailscale/tailscale` (`GOOS=linux`, `ts_include_cli`, UPX-compressed) for Android `arm64` & `arm`.
- Bundled **RootTailscaleGUI** (Jetpack Compose + Material 3 + Quick Settings Tile) installed automatically via `pm install -r`.
- Default `userspace-networking` + local SOCKS5 (`127.0.0.1:1055`) mode for seamless coexistence with FlClash and Android VPNs.
