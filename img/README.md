# Image Assets

[English](README.md) · [简体中文](README.zh-CN.md)

This directory keeps source image assets for AirLyrics.

## Main asset

```text
airlyrics_icon_official.png
```

This is the official app icon source image with a transparent background. The current icon uses the small cat resting on a cloud.

## Generated Android resources

Generated launcher resources live under:

```text
app/src/main/res/mipmap-*/ic_launcher.webp
app/src/main/res/mipmap-*/ic_launcher_round.webp
app/src/main/res/drawable-nodpi/airlyrics_launcher_foreground.png
```

Splash/startup artwork is intentionally not maintained. AirLyrics starts directly from the launcher icon into `MainActivity`.
