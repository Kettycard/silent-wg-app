<div align="center">

<img src="docs/logo.png" width="128" height="128" alt="SilentWG Logo" style="border-radius: 28px; box-shadow: 0 4px 20px rgba(0,0,0,0.3);" />

# SilentWG

**基于 Linux 内核的原生静默 WireGuard 管理器与网络接管方案**  
*专为 Android 17 / HyperOS 4 (Linux 6.12+ GKI) 深度定制*

[![GitHub Release](https://img.shields.io/github/v/release/Kettycard/silent-wg-app?style=flat-square&color=06B6D4)](https://github.com/Kettycard/silent-wg-app/releases)
[![Android](https://img.shields.io/badge/Android-17%20%7C%20HyperOS%204-3DDC84?style=flat-square&logo=android&logoColor=white)](https://android.com)
[![KernelSU](https://img.shields.io/badge/Root-KernelSU%20%2F%20APatch-orange?style=flat-square)](https://kernelsu.org)
[![Xposed](https://img.shields.io/badge/Hook-LSPosed-8A2BE2?style=flat-square)](https://github.com/mywalkb/LSPosed_mod)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)

</div>

---

## 📖 项目简介 (Overview)

传统 Android VPN 工具（如 WireGuard 官方客户端、Clash、V2Ray 等）均依赖系统的 `VpnService` API。这会导致状态栏强制常驻 VPN 钥匙图标、系统通知常驻、部分银行与车机互联应用（如小米 CarLink/CarLife）因检测到 VPN 隧道而拒绝工作或连接中断。

**SilentWG** 彻底跳过 Android `VpnService` 框架，在 Linux 内核层直接调用原生 `wireguard` 模块，配合自定义策略路由与 LSPosed 底层系统调用拦截，实现**完全隐形、零系统标记、高吞吐、低延迟**的全局静默隧道。

---

## ✨ 核心特性 (Key Features)

- 🥷 **真正的内核级静默 (Silent Kernel Tunnel)**  
  不占用 `VpnService`，状态栏绝无 VPN 钥匙/盾牌图标，系统网络设置完全透明，应用无法通过传统方式检测 VPN 环境。
- 📶 **WiFi SSID 场景化无感启停**  
  内置轻量守护进程实时监听网络状态。当连入家庭/公司等受信任 WiFi（如配置的免代理 SSID）时，自动休眠下线；离开指定 WiFi 或切换蜂窝网络时，秒级静默拉起。
- 🎯 **Per-App UID 精确策略分流**  
  采用 Linux 策略路由 `uidrange` 机制，支持黑/白名单分流模式。车机通信、本地局域网投屏、特定银行应用等可彻底排除在隧道之外，互不干扰。
- 🔄 **多节点导入与快速热切**  
  支持导入标准 `.conf` 配置文件或从剪贴板粘贴添加多个 WireGuard 节点；支持列表内一键热切换、设为开机默认节点，自动持久化。
- 🛡️ **LSPosed + 内核 DNAT 双向 DNS 闭环**  
  双层拦截 Android 系统的 DNS 逃逸行为，强制将出站 DNS 查询重定向至内网安全 DNS / Fake-IP 地址（如 198.18.x.x），根治海外应用在移动蜂窝网络下的解析死锁与 GFW 投毒。
- ⚡ **主动 QUIC 逼退加速**  
  内置 UDP 443 智能阻断策略，逼退移动端应用（如 X / Google Play）极速回落至稳定的 TCP TLS 链路，消除冷启动卡顿。
- 🎨 **现代 Material Design 3 原生界面**  
  完全基于 Jetpack Material3 构建，沉浸式深色模式、实时流量握手仪表、圆角矩形自适应图标，告别迟钝的 WebView。

---

## 🛠️ 技术经验与避坑复盘 (Engineering Insights)

本项目在 Xiaomi 17 Pro Max (HyperOS 4 / Android 17 / 内核 6.12 GKI) 上的攻坚过程中，沉淀了以下关键技术结论：

### 1. 规避 Android netId 冲突：从 iptables MARK 到 uidrange 策略路由
Android 系统高度依赖 `fwmark`。在 Android 10+ 架构中，`fwmark` 的高 16 位被底层 `netd` 保留用作网络接口的唯一 `netId`。若粗暴使用 `iptables MARK` 引导流量，会破坏系统网络流元数据，导致手机直接物理断网。  
**解法**：全面采用原生策略路由：
```bash
ip rule add uidrange <start>-<end> lookup 51820 pref 1000
```
保持了系统 `fwmark` 机制的绝对纯净。

### 2. 本地 DNS 逃逸与单向死包：DNAT + MASQUERADE 闭环
仅在 `OUTPUT` 链对 53 端口执行 DNAT，数据包源 IP 依然保留为蜂窝物理网卡 IP（如 `rmnet_data`）。WireGuard 对端与内网 DNS 收包后由于源 IP 属于非 VPN 网段，无法路由回包，导致本地报 `No address associated with hostname`。  
**解法**：必须补充 `POSTROUTING MASQUERADE`：
```bash
ip rule add to $DNS_IP pref 99 lookup 51820
iptables -t nat -I OUTPUT 1 -p udp --dport 53 -j DNAT --to-destination $DNS_IP:53
iptables -t nat -I OUTPUT 2 -p tcp --dport 53 -j DNAT --to-destination $DNS_IP:53
iptables -t nat -I POSTROUTING 1 -o wg0 -j MASQUERADE
```

### 3. CI/CD 签名一致性与直接覆盖升级
GitHub Actions 默认使用随机临时密钥签名，导致每次构建 APK 安装时报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，用户每次必须卸载重装。  
**解法**：在工程中固化独立的 Release Keystore (`app/release.jks`)，从 v2.1+ 起后续所有更新版本签名指纹严格一致，无缝支持覆盖安装。

---

## 📥 安装与快速上手 (Installation)

### 1. 前置条件
- 已通过 **KernelSU**、**KernelSU-Next** 或 **APatch** 获取 Root 权限；
- 设备内核需支持 WireGuard（现代 GKI 5.10 / 5.15 / 6.1 / 6.6 / 6.12 内核均已内置）；
- （可选，推荐）安装 **LSPosed** 框架以实现应用层 DNS 强制注入。

### 2. 安装步骤
1. 前往 [Releases](https://github.com/Kettycard/silent-wg-app/releases) 页面下载最新安装包；
2. 在 KernelSU 管理器中刷入 `silent-wg-vX.X.zip` 模块并重启设备；
3. 安装 `SilentWG-X.X-MD3.apk` 并授予 Root 权限；
4. 若启用了 LSPosed，在 LSPosed 作用域中勾选“系统框架”；
5. 打开应用，导入你的 WireGuard `.conf` 配置文件，点击启动即可畅享无感网络！

---

## 📄 开源协议 (License)

本项目基于 [MIT License](LICENSE) 协议开源。
