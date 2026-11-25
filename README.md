# Magisk REtailscaled

[![GitHub release](https://img.shields.io/github/v/release/DR34M3R-T/magisk-retailscaled?include_prereleases&color=blue)](https://github.com/DR34M3R-T/magisk-retailscaled/releases)
[![Build & Release](https://github.com/DR34M3R-T/magisk-retailscaled/actions/workflows/release.yml/badge.svg)](https://github.com/DR34M3R-T/magisk-retailscaled/actions/workflows/release.yml)

受 [anasfanani/magisk-tailscaled](https://github.com/anasfanani/magisk-tailscaled) 启发重构的 Android Root 版 Tailscale 控制套件：通过 GitHub Actions 直接跟踪编译官方 [tailscale/tailscale](https://github.com/tailscale/tailscale) 最新稳定版，并内置轻量无广告的 **RootTailscaleGUI** 控制端（Jetpack Compose + Material 3 + 系统快捷设置磁贴）。

> **Vibe Coding 说明**：本项目为一个 Vibe Coding 实践项目，由 **Antigravity + Gemini 4 Neon** 协作完成开发与真机调试。

## 核心特性

- **不占用 Android VPN 通道**：底层以 Root 权限运行 `userspace-networking + 本地 SOCKS5 (127.0.0.1:1055)`，可与 **FlClash** 等代理客户端完美共存。
- **紧跟官方最新 Tailscale**：仓库不提交任何二进制文件；GitHub Actions 直接从 [tailscale/tailscale](https://github.com/tailscale/tailscale) 拉取最新稳定版源码交叉编译为 Android 单文件合并二进制（`ts_include_cli` + 服务端特性裁剪 + UPX 压缩）。
- **内置 RootTailscaleGUI 控制端（`gui/` + 免 Root 本地控制桥 `bridge/`）**：
  - 刷入模块时自动通过 `pm install -r` 安装为普通用户应用（支持后续独立升级与卸载）。
  - 模块内置极简本地控制桥（`tailscaled-bridge`，空闲 0% CPU，仅监听 `127.0.0.1:41688` + 随机 Token 鉴权并仅对本 App UID 可读），**App 默认无需申请 Magisk Root 授权即可完成全部控制与极致省电启停**（脱离模块单独安装 APK 时自动回退到 `su -c`）。
  - 顶部状态卡片（总开关、本机 IP / SOCKS5 一键复制、Tailscale 内核与模块版本展示）。
  - 支持浏览器 OAuth 登录与 Auth Key / Headscale 自定义控制服务器登录。
  - 在线置顶设备列表、OS 图标、连接类型（`Direct` / `DERP`）、单设备实时 Ping 测速弹窗。
  - 系统快捷设置磁贴（Quick Settings Tile），支持「轻量模式」与「极致省电模式（联动 `tailscaled.service` 启停守护进程）」。
  - Exit Node 一键切换与内置官方 WebUI (`tailscale web`) 兜底入口。

## 快速安装

1. 前往 [Releases](https://github.com/DR34M3R-T/magisk-retailscaled/releases) 下载 `Magisk-REtailscaled-vX.X.X.X-full.zip`。
2. 在 Magisk / KernelSU / APatch 中刷入模块并重启手机（刷入时会自动安装 **RootTailscale** 应用）。
   > 若此前安装过旧版 `magisk-tailscaled`，请先卸载旧模块并重启一次，再刷入本模块。
3. 打开 **RootTailscale** 应用并授予 Root 权限，按引导完成登录即可使用。
4. 可在系统下拉控制中心编辑面板中添加 **Tailscale** 快捷磁贴，一键启停。

## 配合 FlClash / Mihomo 客户端共存分流（示例扩展脚本）

模块默认在 `127.0.0.1:1055` 暴露本地 SOCKS5 代理。在 **FlClash**（或其它基于 Mihomo 内核的代理客户端）中启用「扩展脚本 / Override Script」，粘贴以下脚本即可在不冲突的前提下将所有 Tailnet 流量转发给本模块，并在**规则模式**中看到独立的 `🔗 Tailscale` 策略组：

```js
function main(config) {
  // 1. 添加本地 Root Tailscale SOCKS5 出站节点
  const tailscaleProxy = {
    name: "🔗 Tailscale SOCKS5",
    type: "socks5",
    server: "127.0.0.1",
    port: 1055,
    udp: true
  };

  if (!config.proxies) config.proxies = [];
  config.proxies.push(tailscaleProxy);

  // 2. 添加独立策略组（使节点在规则模式下可见、可随时切换直连）
  if (!config["proxy-groups"]) config["proxy-groups"] = [];
  config["proxy-groups"].unshift({
    name: "🔗 Tailscale",
    type: "select",
    proxies: ["🔗 Tailscale SOCKS5", "DIRECT"]
  });

  // 3. 将 Tailscale 网段与 MagicDNS 规则插入到最前面
  const tailscaleRules = [
    "IP-CIDR,100.64.0.0/10,🔗 Tailscale,no-resolve",
    "IP-CIDR6,fd7a:115c:a1e0::/48,🔗 Tailscale,no-resolve",
    "DOMAIN-SUFFIX,ts.net,🔗 Tailscale"
  ];
  config.rules = [...tailscaleRules, ...(config.rules || [])];

  return config;
}
```

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
