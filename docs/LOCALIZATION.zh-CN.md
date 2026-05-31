# 本地化指南

[English](LOCALIZATION.md) · [简体中文](LOCALIZATION.zh-CN.md)

AirLyrics 使用英文作为 fallback，简体中文是当前维护的第一种翻译。

## 字符串资源

短 UI 文案放在 Android 资源中：

```text
app/src/main/res/values/strings.xml          英文 fallback
app/src/main/res/values-zh-rCN/strings.xml   简体中文
```

添加新语言时创建新的 Android 资源目录，例如：

```text
app/src/main/res/values-ja/strings.xml
```

只翻译 value，不要修改 string name。

## 代码侧本地化标签

部分设置标签由 `i18n/` 下的辅助文件生成，例如：

```text
i18n/Localization.kt
i18n/LyricsSettingsText.kt
i18n/FloatingStyleText.kt
i18n/I18nFormatters.kt
```

添加新的 enum 选项时，需要同时更新 enum 和对应的本地化标签辅助。

## Changelog 策略

应用内 changelog 从这里读取：

```text
app/src/main/assets/changelog.txt
```

目前只维护英文版本。

## 文档语言策略

仓库文档成对维护：

```text
SOME_DOC.md          英文
SOME_DOC.zh-CN.md    简体中文
```

英文 README 链接英文文档，中文 README 链接中文文档。

## 检查

运行：

```bash
./scripts/check_localization.sh
```

检查脚本应在本地化资源缺少 key、出现多余 key，或使用类似生成出来的 key 名时失败。

## Placeholder 与格式

保持 placeholder 完全一致：

```text
%1$s
%2$d
%%
```

如果字符串本身需要换行，不要随意改变换行结构。

## 术语

| English | 简体中文 |
| --- | --- |
| Floating lyrics | 悬浮歌词 |
| Media source | 媒体源 |
| Online lyrics search | 联网歌词搜索 |
| Local lyrics import | 本地歌词导入 |
| Enhanced lyrics | 增强歌词 / 逐字歌词 |
| Word-by-word lyrics | 逐字歌词 |
| Lyrics offset | 歌词偏移 |
| Touch-through | 触摸穿透 |
| Auto-save | 自动保存 |

不要翻译应用名、包名、文件名、歌曲名、歌手名、专辑名、歌词、路径和用户选择的文件夹。
