<!--suppress HtmlDeprecatedAttribute -->
<div align="center">

<!--suppress CheckImageSize -->
<img src="docs/assets/airlyrics-logo.png" width="120" alt="AirLyrics Logo" />

# AirLyrics

一个轻量的 Android 悬浮歌词软件，可以自动检测当前播放媒体，并在可自定义的悬浮窗中显示同步歌词。

[English](README.md) · [简体中文](README.zh-CN.md)

<br />

[下载](https://github.com/AirLyrics/AirLyrics/releases) · [文档](docs/README.zh-CN.md) ·
[隐私](PRIVACY.zh-CN.md) · [反馈问题](https://github.com/AirLyrics/AirLyrics/issues)

<br />

[![Platform](https://img.shields.io/badge/platform-Android-brightgreen?style=flat-square)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-Android-blueviolet?style=flat-square)]()
[![Rust](https://img.shields.io/badge/Rust-lyrics--core-orange?style=flat-square)]()
[![Release](https://img.shields.io/github/v/release/AirLyrics/AirLyrics?style=flat-square)](https://github.com/AirLyrics/AirLyrics/releases)

</div>

---

<!--suppress HtmlDeprecatedAttribute -->
<div align="center">

<!--suppress CheckImageSize -->
<img src="docs/assets/screenshot-floating-lyrics.jpg" width="720" alt="AirLyrics 悬浮歌词" />

</div>

---

## 状态

AirLyrics 目前处于稳定维护阶段，并仍会持续维护。

当前版本已经适合作为日常悬浮歌词工具使用。后续更新会主要集中在 bug 修复、兼容性改进、文档更新，
以及处理 issue 或 pull request。

应用兼容性仍可能受到 Android 版本、设备厂商和音乐应用的影响。

---

## 快速开始

1. 安装 AirLyrics（需要 Android 8.0 或更高版本）。
2. 授予必要权限。
3. 手动选择当前播放的媒体源。
4. 打开 **悬浮窗** 页面，然后点击底栏的 **显示**。

详细设置和问题排查请参阅[使用说明书](docs/USER_GUIDE.zh-CN.md)。

---

## 功能特性

- 自动检测当前播放媒体，并在其他应用上方显示同步歌词
- 支持联网搜索和本地 LRC 导入
- 支持原文、翻译和本地导入的逐字歌词
- 支持自定义悬浮窗样式和动画
- 按歌曲保存歌词偏移
- 支持浅色和深色主题

---

## 截图

<!--suppress HtmlDeprecatedAttribute -->
<div align="center">

<table>
  <tr>
    <!--suppress HtmlDeprecatedAttribute -->
    <td align="center">
      <!--suppress CheckImageSize -->
      <img src="docs/assets/screenshot-media-source.jpg" width="240" alt="媒体流检测" />
      <br />
      <sub>媒体流检测</sub>
    </td>
    <!--suppress HtmlDeprecatedAttribute -->
    <td align="center">
      <!--suppress CheckImageSize -->
      <img src="docs/assets/screenshot-lyrics-source.jpg" width="240" alt="歌词获取与本地歌词设置" />
      <br />
      <sub>歌词获取</sub>
    </td>
    <!--suppress HtmlDeprecatedAttribute -->
    <td align="center">
      <!--suppress CheckImageSize -->
      <img src="docs/assets/screenshot-floating-settings.jpg" width="240" alt="悬浮窗自定义" />
      <br />
      <sub>悬浮窗自定义</sub>
    </td>
  </tr>
</table>

</div>

---

## 权限说明

AirLyrics 会用到以下 Android 权限和系统访问入口：

| 权限 / 访问    | 用途                 |
|------------|--------------------|
| 显示在其他应用上层 | 显示悬浮歌词窗口          |
| 通知访问权限     | 检测当前播放媒体信息        |
| 通知权限       | 显示前台服务控制入口         |
| 网络访问       | 联网搜索歌词             |
| 文件选择器      | 导入本地歌词和选择歌词保存目录   |

权限、本地数据与联网歌词搜索的说明见 [隐私政策](PRIVACY.zh-CN.md)。

---

## 文档

项目文档见 docs 目录。

| 文档                                  | 说明                  |
|-------------------------------------|---------------------|
| [文档首页](docs/README.zh-CN.md)        | 中文文档索引              |
| [隐私政策](PRIVACY.zh-CN.md)            | 权限、本地数据与联网歌词搜索说明    |
| [使用说明书](docs/USER_GUIDE.zh-CN.md)   | 项目使用说明              |
| [歌词格式](docs/LYRICS_FORMAT.zh-CN.md) | 本地导入、普通 LRC 与逐字歌词格式 |
| [贡献指南](docs/CONTRIBUTING.zh-CN.md)  | 开发环境、提交流程与代码位置      |
| [项目架构](docs/ARCHITECTURE.zh-CN.md)  | 模块划分与运行流程           |

---

## 从源码构建

### 环境要求

- JDK 17
- Android SDK
- Android NDK `26.3.11579264` 必需
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

欢迎您提交贡献。AirLyrics 目前处于稳定维护阶段，因此更推荐小而明确的改动。

适合贡献的方向：

- Bug 反馈
- 兼容性测试
- 翻译改进
- 文档改进
- UI 文案润色

贡献说明：[CONTRIBUTING.zh-CN.md](docs/CONTRIBUTING.zh-CN.md)。

---

## 鸣谢

- [waylyrics](https://github.com/waylyrics/waylyrics)

---

## 许可证

AirLyrics 使用 MIT License 授权。

见 [LICENSE](LICENSE)。
