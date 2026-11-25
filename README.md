# Magisk REtailscaled

[![GitHub release](https://img.shields.io/github/v/release/DR34M3R-T/magisk-retailscaled?include_prereleases&color=blue)](https://github.com/DR34M3R-T/magisk-retailscaled/releases)
[![Build & Release](https://github.com/DR34M3R-T/magisk-retailscaled/actions/workflows/release.yml/badge.svg)](https://github.com/DR34M3R-T/magisk-retailscaled/actions/workflows/release.yml)

受 [anasfanani/magisk-tailscaled](https://github.com/anasfanani/magisk-tailscaled) 启发重构的 Android Root 版 Tailscale 控制套件：通过 GitHub Actions 直接跟踪编译官方 [tailscale/tailscale](https://github.com/tailscale/tailscale) 最新稳定版，并内置轻量无广告的 **RootTailscaleGUI** 控制端（Jetpack Compose + Material 3 + 系统快捷设置磁贴）。

## 核心特性

- **不占用 Android VPN 通道**：底层以 Root 权限运行 `userspace-networking + 本地 SOCKS5 (127.0.0.1:1055)`，可与 **FlClash** 等代理客户端完美共存。
- **紧跟官方最新 Tailscale**：仓库不提交任何二进制文件；GitHub Actions 直接从 [tailscale/tailscale](https://github.com/tailscale/tailscale) 拉取最新稳定版源码交叉编译为 Android 单文件合并二进制（`ts_include_cli` + 服务端特性裁剪 + UPX 压缩）。
- **内置 RootTailscaleGUI 控制端（`gui/`）**：
  - 刷入模块时自动通过 `pm install -r` 安装为普通用户应用（支持后续独立升级与卸载）。
  - 顶部状态卡片（总开关、本机 IP / SOCKS5 一键复制、Tailscale 内核与模块版本展示）。
  - 支持浏览器 OAuth 登录与 Auth Key / Headscale 自定义控制服务器登录。
  - 在线置顶设备列表、OS 图标、连接类型（`Direct` / `DERP`）、单设备实时 Ping 测速弹窗。
  - 系统快捷设置磁贴（Quick Settings Tile），支持「轻量模式」与「极致省电模式（联动 `tailscaled.service` 启停守护进程）」。
  - Exit Node 一键切换与内置官方 WebUI (`tailscale web`) 兜底入口。

## 快速安装

1. 前往 [Releases](https://github.com/DR34M3R-T/magisk-retailscaled/releases) 下载 `Magisk-REtailscaled-vX.X.X.X-full.zip`。
2. 在 Magisk / KernelSU / APatch 中刷入模块并重启手机（刷入时会自动安装 **RootTailscale** 应用）。
3. 打开 **RootTailscale** 应用并授予 Root 权限，按引导完成登录即可使用。
4. 可在系统下拉控制中心编辑面板中添加 **Tailscale** 快捷磁贴，一键启停。

## 在 GitHub Actions 一键更新 Tailscale

打开本仓库的 **Actions → Build & Release → Run workflow**：
- `bump`：选择模块版本号递增类型（默认 `patch`）。
- `tailscale_version`：保持 `latest` 自动拉取官方最新稳定版，或手动填写指定版本号（如 `1.104.7`）。

云端会自动编译最新 Android `arm64`/`arm` 二进制与 `RootTailscaleGUI.apk`，并发布完整的 Magisk 模块 zip 到 Releases。

## 上游开源致谢

- [tailscale/tailscale](https://github.com/tailscale/tailscale) — Tailscale 开源核心代码与 CLI
- [anasfanani/magisk-tailscaled](https://github.com/anasfanani/magisk-tailscaled) — 原版 Magisk Tailscaled 模块框架
- [theshoqanebi/jq-build-for-android](https://github.com/theshoqanebi/jq-build-for-android) — Android 端 `jq` 二进制
- [topjohnwu/Magisk](https://github.com/topjohnwu/Magisk) — Magisk

## License

Released under [BSD 3-Clause License](/LICENSE).
