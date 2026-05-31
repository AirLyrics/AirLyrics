# 测试指南

[English](TESTING.md) · [简体中文](TESTING.zh-CN.md)

AirLyrics 应该从三个层级测试：单元测试、Android instrumentation tests 和真机手动检查。

## 单元测试

运行 JVM 快速测试：

```bash
./gradlew :app:testDebugUnitTest -Pairlyrics.skipRustBuild=true
```

重点覆盖：

- 普通 LRC 解析。
- 紧凑单行 LRC 导出格式。
- 一行多个时间戳。
- 原文 / 翻译合并行为。
- 当前行查找。
- 增强 / 逐字 LRC 解析。
- Karaoke codec 往返。
- 本地歌词 fallback 行为。

## Instrumentation tests

在真机或模拟器上运行：

```bash
./gradlew :app:connectedDebugAndroidTest -Pairlyrics.skipRustBuild=true
```

重点覆盖：

- 本地歌词保存 / 读取 / 列表 / 删除。
- 歌词索引持久化。
- Karaoke 保存 / 读取 / 删除行为。
- 覆盖保护。
- Storage Access Framework 文件夹行为。

## 构建检查

只检查 Kotlin：

```bash
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

包含原生核心的完整构建：

```bash
./gradlew :app:assembleDebug
```

## 发布前手动清单

公开发布前，至少在一台 Android 真机上检查：

- 首次启动。
- 悬浮窗权限流程。
- 通知访问权限流程。
- 媒体源检测。
- 手动选择媒体源。
- 悬浮窗启动 / 停止。
- 锁定和触摸穿透。
- 悬浮窗样式修改。
- 歌词偏移调整和保存。
- 网易云联网搜索。
- 可用时测试 Musixmatch 联网搜索。
- 本地普通 LRC 导入。
- 本地 enhanced / word-by-word LRC 导入。
- 最近本地歌词列表。
- 主题切换。
- 语言切换。
- 修改设置后重启应用。

## 歌词测试样例

手动测试样例文件位于：

```text
docs/test-lyrics-samples/
```
