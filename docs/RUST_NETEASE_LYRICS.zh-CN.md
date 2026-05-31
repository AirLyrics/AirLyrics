# Rust 歌词核心

[English](RUST_NETEASE_LYRICS.md) · [简体中文](RUST_NETEASE_LYRICS.zh-CN.md)

AirLyrics 使用 Rust 原生库处理联网歌词 Provider 相关工作。Android 端通过 JNI 包装类调用它。

## 原生模块

```text
lyrics-core/
  src/lib.rs
  src/lrc.rs
  src/musixmatch.rs
  Cargo.toml
```

Android 应用加载：

```text
libairlyrics_lyrics.so
```

位置：

```text
app/src/main/jniLibs/
```

## Android 桥接

```text
lyrics/providers/NeteaseLyricsNative.kt
lyrics/providers/NeteaseLyricsProvider.kt
lyrics/providers/MusixmatchLyricsNative.kt
lyrics/providers/MusixmatchLyricsProvider.kt
```

Provider 类负责把 Kotlin 请求转成 native 调用，并把 native JSON / 结果转换成 `LyricsProviderResult`。

## 构建要求

- Rust toolchain
- Android NDK
- `cargo-ndk`

安装 target 和工具：

```bash
rustup target add aarch64-linux-android
cargo install cargo-ndk
```

正常构建应用：

```bash
./gradlew :app:assembleDebug
```

如果本地已经有 native libraries，只做 Kotlin 开发检查时可以跳过 Rust 构建：

```bash
./gradlew :app:assembleDebug -Pairlyrics.skipRustBuild=true
```

跳过参数不能替代真正的 release 构建。

## Provider 行为

联网 Provider 路径由 `LyricsSettingsStore` 选择，并通过 `LyricsRepository` 路由。

本地歌词仍然会在 native 联网查询之前优先命中。

## 调试

常用过滤：

```bash
adb logcat | grep -E 'AirLyricsLyrics|Netease|Musixmatch|airlyrics'
```

常见失败点：

- 当前设备 ABI 缺少 native library。
- NDK 或 Rust target 没有安装。
- Provider 网络失败。
- 在线来源没有返回合适匹配。
- native 调用超时，或被更新请求取消。
