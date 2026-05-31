# AirLyrics 文档

[English](README.md) · [简体中文](README.zh-CN.md)

这里存放 AirLyrics 的项目文档。主 README 只负责让新用户快速看懂并下载，具体说明放在这里，避免首页变成说明书雪崩。

## 用户文档

| 文档 | 用途 |
| --- | --- |
| [歌词格式](LYRICS_FORMAT.zh-CN.md) | 本地导入、普通 LRC、翻译歌词与逐字歌词格式 |
| [测试指南](TESTING.zh-CN.md) | 发布前手动检查与自动化测试命令 |
| [本地化指南](LOCALIZATION.zh-CN.md) | 添加或改进界面翻译 |

## 贡献者文档

| 文档 | 用途 |
| --- | --- |
| [贡献指南](CONTRIBUTING.zh-CN.md) | 开发流程与贡献规则 |
| [项目架构](ARCHITECTURE.zh-CN.md) | Android UI、媒体检测、歌词查询与悬浮窗服务的关系 |
| [项目结构](PROJECT_STRUCTURE.zh-CN.md) | 源码目录速查 |
| [设置模块](SETTINGS_MODULE.zh-CN.md) | 持久化设置模型与 Store |
| [歌词模块](LYRICS_MODULE.zh-CN.md) | Repository、Provider、解析器、存储与显示格式化 |
| [歌词查询取消](LYRICS_LOOKUP_CANCELLATION.zh-CN.md) | 最新请求优先的查询取消模型 |
| [歌词存储重构](LYRICS_STORAGE_REFACTOR.zh-CN.md) | 当前本地歌词存储拆分与迁移说明 |
| [Rust 歌词核心](RUST_NETEASE_LYRICS.zh-CN.md) | 原生歌词 Provider 的构建与运行桥接 |
| [Musixmatch 测试](MUSIXMATCH_TESTING.zh-CN.md) | Musixmatch 查询与翻译的手动检查 |

## 语言规则

英文文档使用普通文件名，例如 `LYRICS_FORMAT.md`。
简体中文文档使用 `.zh-CN.md` 后缀，例如 `LYRICS_FORMAT.zh-CN.md`。
