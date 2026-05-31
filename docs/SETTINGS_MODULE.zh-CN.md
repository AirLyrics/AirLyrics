# 设置模块

[English](SETTINGS_MODULE.md) · [简体中文](SETTINGS_MODULE.zh-CN.md)

设置集中放在 `settings/model/` 和 `settings/store/`。

## 模型

```text
settings/model/FloatingLyricsStyle.kt   悬浮窗视觉样式
settings/model/LyricsSettings.kt        歌词来源、显示与查询行为
settings/model/ThemeSettings.kt         浅色 / 深色主题状态
```

## Store

```text
FloatingLyricsStyleStore.kt   预设、字号、颜色、阴影、背景、宽度、对齐、锁定和位置
LyricsSettingsStore.kt        来源、联网 fallback、自动保存、内容模式、行范围、动画、逐字开关
LyricsOffsetStore.kt          按歌曲保存的时间偏移
MediaSourceStore.kt           选中的播放器包名，位于 media 模块
LanguageSettingsStore.kt      应用语言偏好辅助
QuickFloatingStore.kt         中间 Tab 的悬浮窗状态记忆
ThemeSettingsStore.kt         主题模式持久化
```

## 规则

- UI 通过 Store 读写设置。
- 服务和控制器也从同一批 Store 读取设置。
- 不要在多个模块重复定义 preference key。
- 模型类保持 data-only。
- enum 值展示给用户时，通过 `i18n/` 辅助补充本地化标签。

## 添加新设置

1. 判断是否属于已有模型。
2. 在对应 Store 中添加稳定 key。
3. 提供默认值和安全迁移行为。
4. 在正确页面添加 UI 控件。
5. 在运行时模块应用它。
6. 必要时更新文档和测试。
