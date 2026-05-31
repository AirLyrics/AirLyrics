# 项目结构

[English](PROJECT_STRUCTURE.md) · [简体中文](PROJECT_STRUCTURE.zh-CN.md)

改代码前可以先看这份速查图。

```text
app/src/main/java/com/andsi/airlyrics/
  app/                         MainActivity 外壳、渲染辅助、Action 和控制器
    controller/                媒体、歌词、悬浮窗协调器
  common/                      共享广播 / Action 常量
  floating/                    前台服务、窗口控制器和歌词渲染器
    model/                     悬浮服务使用的当前媒体数据
  i18n/                        本地化标签与格式化辅助
  lyrics/                      查询 Repository、Provider、解析、显示格式化和存储
    display/                   原文 / 翻译显示模式格式化
    parser/                    LRC 解析与当前行查找
    providers/                 本地、网易云、Musixmatch Provider
    storage/                   本地歌词文件、索引、路径与逐字歌词 Codec
  media/                       通知监听器与选中媒体源 Store
  settings/
    model/                     设置数据契约
    store/                     持久化设置 Store
  ui/
    components/                可复用 View 辅助和弹窗
    model/                     UI Action 契约
    navigation/                页面枚举和底部 Tab
    pages/                     媒体页和悬浮窗页
      settings/                设置首页、歌词、系统、关于页面
    theme/                     调色板和主题辅助
    tokens/                    间距、字号、动画常量
    widgets/                   自定义 View 小组件
```

## 原生模块

```text
lyrics-core/                   Rust 原生歌词核心
```

Android 应用从 `app/src/main/jniLibs/` 加载 `libairlyrics_lyrics.so`。

## 资源重点

```text
app/src/main/res/values/strings.xml          英文 fallback 文案
app/src/main/res/values-zh-rCN/strings.xml   简体中文文案
app/src/main/assets/changelog.txt            关于页展示的英文 changelog
scripts/check_localization.sh                资源 key 检查脚本
```

## 简单规则

- UI 页面布局放在 `ui/pages/`。
- 持久化设置逻辑放在 `settings/store/`。
- 悬浮窗运行时行为放在 `floating/`。
- 歌词查询与解析放在 `lyrics/`。
- 媒体源检测放在 `media/`。
