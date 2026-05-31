<div align="center">

<img src="docs/assets/airlyrics-logo.png" width="120" alt="AirLyrics Logo" />

# AirLyrics

Android 手机端悬浮歌词应用。

一个轻量的 Android 悬浮歌词软件，可以自动检测当前播放媒体，并在可自定义的悬浮窗中显示同步歌词。

[English](README.md) · [简体中文](README.zh-CN.md)

<br />

[下载](https://github.com/AndSi-327/AirLyrics/releases) · [文档](docs/) · [反馈问题](https://github.com/AndSi-327/AirLyrics/issues)

<br />

[![Platform](https://img.shields.io/badge/platform-Android-brightgreen?style=flat-square)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-blueviolet?style=flat-square)]()
[![Rust](https://img.shields.io/badge/Rust-lyrics--core-orange?style=flat-square)]()
[![Release](https://img.shields.io/github/v/release/AndSi-327/AirLyrics?style=flat-square)](https://github.com/AndSi-327/AirLyrics/releases)

</div>

---

<div align="center">

<img src="docs/assets/screenshot-floating-lyrics.jpg" width="720" alt="AirLyrics 悬浮歌词" />

</div>

---

## 状态

AirLyrics 正在持续开发中。

应用已经可以使用，但兼容性可能会受到 Android 版本、设备厂商和音乐应用的影响。

---

## 下载

从 GitHub Releases 下载最新 APK：

https://github.com/AndSi-327/AirLyrics/releases

安装后，请授予必要权限，并选择需要跟随的音乐应用。

---

## 功能特性

- 自动检测当前播放的媒体流
- 在其他应用上方显示同步悬浮歌词
- 提供联网歌词搜索
- 支持本地导入歌词
- 支持导入逐字歌词 / 增强歌词
- 支持自定义悬浮窗样式
- 支持歌词偏移调整
- 支持原文 / 翻译歌词显示
- 支持浅色 / 深色主题

---

## 快速开始

1. 安装 AirLyrics。
2. 授予悬浮窗权限。
3. 授予通知访问权限。
4. 选择当前播放的媒体源。
5. 启动悬浮歌词。
6. 在音乐应用中播放歌曲。
7. 联网搜索歌词，或手动导入本地歌词。

---

## 截图

<div align="center">

<table>
  <tr>
    <td align="center">
      <img src="docs/assets/screenshot-media-source.jpg" width="240" alt="媒体流检测" />
      <br />
      <sub>媒体流检测</sub>
    </td>
    <td align="center">
      <img src="docs/assets/screenshot-lyrics-source.jpg" width="240" alt="歌词获取与本地歌词设置" />
      <br />
      <sub>歌词获取</sub>
    </td>
    <td align="center">
      <img src="docs/assets/screenshot-floating-settings.jpg" width="240" alt="悬浮窗自定义" />
      <br />
      <sub>悬浮窗自定义</sub>
    </td>
  </tr>
</table>

</div>

---

## 权限说明

AirLyrics 需要以下 Android 权限才能正常工作。

| 权限 | 用途 |
| --- | --- |
| 显示在其他应用上层 | 显示悬浮歌词窗口 |
| 通知访问权限 | 检测当前播放媒体信息 |
| 文件选择器 | 导入本地歌词文件 |

---

## 文档

更多说明可以查看 docs 目录。

| 文档 | 说明 |
| --- | --- |
| [LYRICS_FORMAT.md](docs/LYRICS_FORMAT.md) | 歌词格式与本地导入说明 |
| [LOCALIZATION.md](docs/LOCALIZATION.md) | 翻译与本地化指南 |
| [TESTING.md](docs/TESTING.md) | 测试清单 |
| [CONTRIBUTING.md](docs/CONTRIBUTING.md) | 贡献指南 |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | 项目架构说明 |

---

## 从源码构建

### 环境要求

- Android Studio
- JDK 17
- Android SDK
- Android NDK
- Rust toolchain
- cargo-ndk

### 克隆仓库

```bash
git clone https://github.com/AndSi-327/AirLyrics.git
cd AirLyrics
```

### 构建

```bash
./gradlew assembleDebug
```

APK 会生成在：

```txt
app/build/outputs/apk/debug/
```

Rust lyrics core 构建细节见 [RUST_NETEASE_LYRICS.md](docs/RUST_NETEASE_LYRICS.md)。

---

## 贡献

欢迎提交贡献。

适合贡献的方向：

- Bug 反馈
- 兼容性测试
- 翻译改进
- 文档改进
- UI 文案润色

提交修改前，请先阅读 [CONTRIBUTING.md](docs/CONTRIBUTING.md)。

