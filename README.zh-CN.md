<div align="center">

<img src="docs/assets/airlyrics-logo.png" width="120" alt="AirLyrics Logo" />

# AirLyrics

Android 手机端悬浮歌词应用。

一个轻量的 Android 悬浮歌词软件，可以自动检测当前播放媒体，并在可自定义的悬浮窗中显示同步歌词。

[English](README.md) · [简体中文](README.zh-CN.md)

<br />

[下载](https://github.com/AirLyrics/AirLyrics/releases) · [文档](docs/README.zh-CN.md) · [反馈问题](https://github.com/AirLyrics/AirLyrics/issues)

<br />

[![Platform](https://img.shields.io/badge/platform-Android-brightgreen?style=flat-square)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-blueviolet?style=flat-square)]()
[![Rust](https://img.shields.io/badge/Rust-lyrics--core-orange?style=flat-square)]()
[![Release](https://img.shields.io/github/v/release/AirLyrics/AirLyrics?style=flat-square)](https://github.com/AirLyrics/AirLyrics/releases)

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

https://github.com/AirLyrics/AirLyrics/releases

安装后，请您授予必要权限，并选择需要跟随的音乐应用。

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
2. 授予必要权限。
3. 选择当前播放的媒体源。
4. 显示悬浮歌词。

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

AirLyrics 会用到以下 Android 权限：

| 权限 | 用途 |
| --- | --- |
| 显示在其他应用上层 | 显示悬浮歌词窗口 |
| 通知访问权限 | 检测当前播放媒体信息 |
| 文件选择器 | 导入本地歌词文件 |

---

## 文档

项目文档见 docs 目录。

| 文档 | 说明 |
| --- | --- |
| [文档首页](docs/README.zh-CN.md) | 中文文档索引 |
| [使用说明书](docs/USER_GUIDE.zh-CN.md) | 项目使用说明 |
| [歌词格式](docs/LYRICS_FORMAT.zh-CN.md) | 本地导入、普通 LRC 与逐字歌词格式 |
| [贡献指南](docs/CONTRIBUTING.zh-CN.md) | 开发环境、提交流程与代码位置 |
| [项目架构](docs/ARCHITECTURE.zh-CN.md) | 模块划分与运行流程 |

---

## 从源码构建

### 环境要求

- JDK 21
- Android SDK
- Android NDK `26.3.11579264` 推荐
- Rust stable via `rustup`
- `cargo-ndk`
- Rust Android target：
  - 默认 `arm64-v8a` 构建需要 `aarch64-linux-android`
- 可选 Rust Android target：
  - 仅在使用 `-Pairlyrics.buildX86_64=true` 构建时需要 `x86_64-linux-android`

构建 SDK 相关配置时，推荐使用 Android Studio。

### 克隆仓库

```bash
git clone https://github.com/AirLyrics/AirLyrics.git
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


---

## 贡献

欢迎您提交贡献。

适合贡献的方向：

- Bug 反馈
- 兼容性测试
- 翻译改进
- 文档改进
- UI 文案润色

贡献说明：[CONTRIBUTING.zh-CN.md](docs/CONTRIBUTING.zh-CN.md)。

---

## Credit

- [waylyrics](https://github.com/waylyrics/waylyrics)

---

## 许可证

AirLyrics 使用 MIT License 授权。

见 [LICENSE](LICENSE)。

