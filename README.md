# 特投屏

<div align="center">

![Version](https://img.shields.io/badge/version-v26090201-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android%2010%2B-orange.svg)
![Projection](https://img.shields.io/badge/feature-Screen%20Projection-22c55e.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-purple.svg)

</div>

**特投屏**是一款面向特斯拉及没有地图、无法安装手机应用的车机屏幕的投屏 App。把手机或 Mac 上的内容投到车内大屏，在熟悉的大屏上使用导航和其他应用。

支持多种投屏方式：

- **安卓手机 App 投屏**：将手机上的地图和其他兼容应用投到车机浏览器，不限于导航类 App。
- **CarPlay 投屏**：将 CarPlay 画面投到目标车机屏幕。
- **AirPlay 投屏**：通过 AirPlay 投屏，主要适用于 Mac。

## 下载

<div align="center">

## [下载最新版 Android 安装包](https://github.com/jixiexiaoge/Map2Tesla/releases)

前往 Releases 页面下载并安装最新版本。

</div>

## 适用场景

- **特斯拉车主**：在车机大屏查看手机上的地图或其他兼容 App。
- **没有地图的车机**：用手机投屏补充车机缺少的地图和应用。
- **希望在车内大屏使用手机 App**：投屏内容不局限于地图导航。
- **Mac 用户**：通过 AirPlay 将 Mac 画面投到车机屏幕。
- **CarPlay 用户**：将 CarPlay 画面投到目标车机屏幕。

## 投屏方式

### 安卓手机 App

特投屏可在手机上创建独立的虚拟屏幕运行目标 App，再将画面通过局域网传到车机浏览器。手机主屏幕不需要一直显示投屏内容；浏览器也支持触摸反控。

```text
安卓手机 App
  -> 手机虚拟屏幕
  -> 手机本地投屏服务
  -> 车机或其他设备浏览器
```

可投屏地图及其他兼容的安卓应用。不同 App 对虚拟屏幕的兼容情况可能不同。

### CarPlay

支持将 CarPlay 画面投到目标车机屏幕，适合希望在车内大屏使用 CarPlay 的场景。

### AirPlay

支持 AirPlay 投屏，主要面向 Mac 用户，可将 Mac 上的内容显示在车机大屏。

## 安卓 App 投屏步骤

1. 在手机上安装并首次打开目标 App，完成必要的初始化和权限授权。
2. 安装并启动 [Shizuku](https://shizuku.rikka.app/download/) 或 [Stellar](https://github.com/roro2239/Stellar/releases)，完成授权。
3. 打开特投屏，选择要投屏的 App 并启动投屏。
4. 在车机浏览器中打开特投屏显示的地址。手机和车机浏览器需要连接同一局域网。
5. 画面出现后，可通过车机浏览器触摸操作目标 App。

投屏服务默认使用端口 `8080`；端口被占用时会尝试 `8081` 到 `8099`。请以 App 中显示的地址为准。

## 安卓 App 投屏要求

| 项目 | 要求 |
|---|---|
| 手机系统 | Android 10 及以上；建议 Android 13 及以上 |
| 特权授权 | 需要 Shizuku 或 Stellar 创建虚拟屏幕并注入触摸事件 |
| 网络 | 手机与车机浏览器设备连接同一局域网，且车机浏览器能访问手机 IP |
| 目标 App | 支持在虚拟屏幕运行的安卓应用；兼容情况因 App 而异 |

> **安全说明**：投屏画面和触摸事件默认仅在用户局域网内传输。请使用可信的局域网，避免在公共网络中暴露投屏服务。投屏内容不替代驾驶员观察、判断或车辆原有安全系统；驾驶员必须始终注意道路并对车辆控制负责。

## 隐私与安全

- `local.properties`、签名密码、支付密钥、API Key、设备令牌等敏感配置不应提交到仓库。
- 本仓库未在根目录声明统一开源许可证。复用、分发或商用前，请确认仓库维护者以及地图 SDK、模型和第三方组件各自的许可条款。
