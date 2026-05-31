# 歌词存储重构

[English](LYRICS_STORAGE_REFACTOR.md) · [简体中文](LYRICS_STORAGE_REFACTOR.zh-CN.md)

本地歌词存储已经拆成更小的辅助类。`LyricsStorage` 仍然是调用方使用的公开门面，文件 IO、索引处理和路径解析则放到专门文件中。

## 当前存储文件

```text
lyrics/storage/LyricsStorage.kt        app 代码使用的公开门面
lyrics/storage/LyricsStoragePaths.kt   SAF tree URI、受管理歌词目录和索引文件解析
lyrics/storage/LyricsFileStore.kt      文件读取 / 写入 / 删除辅助
lyrics/storage/LyricsIndexStore.kt     JSON 索引持久化
lyrics/storage/LocalLyricsLister.kt    最近 / 本地歌词列表
lyrics/storage/KaraokeLyricsCodec.kt   增强歌词序列化
lyrics/storage/SongIdentity.kt         稳定歌曲身份归一化
lyrics/storage/StorageConstants.kt     存储 key 和文件名常量
```

## 存储模型

AirLyrics 会优先使用用户选择的受管理歌词目录。导入或自动保存的歌词会存入该目录，并配合索引文件管理。

```text
lyrics/
  lyrics_index.json
  *.lrc
  enhanced / karaoke 数据由 storage codec 处理
```

实际文件名会经过歌曲身份辅助归一化，避免不安全路径字符。

## 查询优先级

本地存储不只是缓存，它也是用户修正歌词的地方，因此优先级高于联网查询。

通常优先级：

1. 手动导入。
2. 本地已保存歌词。
3. 设置允许时进行联网查询。
4. 设置允许时自动保存联网结果。

## 重构规则

- 保持 `LyricsStorage` 作为稳定门面。
- 路径和 SAF 细节放在 `LyricsStoragePaths`。
- 原始文件操作放在 `LyricsFileStore`。
- 索引 JSON 逻辑放在 `LyricsIndexStore`。
- 增强歌词编码放在 `KaraokeLyricsCodec`。
- 不要在存储类里加入 UI 文案或弹窗。
