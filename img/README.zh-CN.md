# 图片资源

[English](README.md) · [简体中文](README.zh-CN.md)

这个目录保存 AirLyrics 的源图片资源。

## 主要资源

```text
airlyrics_icon_official.png
```

这是应用图标的透明背景源图。当前图标使用“小猫趴在云上”的设计。

## 生成的 Android 资源

生成后的启动图标资源位于：

```text
app/src/main/res/mipmap-*/ic_launcher.webp
app/src/main/res/mipmap-*/ic_launcher_round.webp
app/src/main/res/drawable-nodpi/airlyrics_launcher_foreground.png
```

项目目前不维护自定义 splash / 启动画面。AirLyrics 会从启动图标直接进入 `MainActivity`。
