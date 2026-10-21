# AirLyrics 跨语言歌词匹配 —— 第一阶段源码审查报告

仓库：https://github.com/AirLyrics/AirLyrics　审查版本：`82cd0f5`（`prepare v1.2.7`，main）
本地路径：`/opt/hermes/work/airlyrics-174430`

---

## 1. 语言、构建方式与主要模块

| 项 | 事实 |
|---|---|
| 语言 | Kotlin（341 个 `.kt`，Android 应用）+ Rust（`lyrics-core`，9 个 `.rs`，约 7,986 行）+ Python（`scripts/lyrics_benchmark`） |
| 构建 | Gradle（Kotlin DSL，单模块 `:app`）；Rust 由 `cargo ndk` 编成 `libairlyrics_lyrics.so` 打进 `app/src/main/jniLibs/`，Gradle 任务 `buildRustLyricsCore`（可用 `-Pairlyrics.skipRustBuild=true` 跳过） |
| SDK | `compileSdk=37`、`targetSdk=36`、`minSdk=26`、Java/JVM 17、NDK `26.3.11579264` |
| 模块/包 | `app`（依赖装配/VM）、`ui`、`media`（MediaSession/播放器选择）、`lyrics`（查找/导入/解析/存储）、`floating`（前台服务/悬浮窗）、`settings`、`core`、`i18n` |
| 测试 | Kotlin 单测 99 个文件（`app/src/test`，JVM 单测 + Robolectric 风格）；Rust `#[test]`：`matching.rs` 15 个、`lrclib.rs` 22 个，另有 `native_result_contract_tests.rs` |
| 架构文档 | `docs/ARCHITECTURE.md`、`docs/lyrics-matching.md`（匹配策略的权威说明，明确“改规则只改 matching.rs”） |

运行时链路（来自 `docs/ARCHITECTURE.md`）：

```
Music app → media → Main UI / FloatingLyricsService
                    → LyricsRepository → Local storage / JNI → Rust providers
                    → FloatingLyricsRenderer → Overlay
```

## 2. Apple Music 的播放信息从哪来

- `media/MediaNotificationListenerService.kt`：NotificationListenerService（`getActiveSessions` 需要它作为组件）。
- `media/CurrentMediaReader.kt`：用 `MediaSessionManager.getActiveSessions(component)` 拿 `MediaController`，从 `controller.metadata` 读：
  - `METADATA_KEY_TITLE` → title
  - `METADATA_KEY_ARTIST`（回退 `METADATA_KEY_ALBUM_ARTIST`）→ artist
  - `METADATA_KEY_ALBUM` → album
  - `METADATA_KEY_DURATION` → durationMs
  - 播放位置由 `PlaybackState` + 速度外推（`estimatedPositionMs`）
- `media/MediaSongMetadata.kt`：仅对网易云（`com.netease.cloudmusic`）做 DISPLAY_TITLE/DISPLAY_SUBTITLE 特判（蓝牙歌词会把 TITLE 换成歌词）。**Apple Music 走标准字段，没有做任何特殊处理**。
- `media/SongIdentityAdapters.kt` + `core/model/SongIdentity.kt`：身份 = title/artist/album/duration，`storageKey()` = `sha1(normalize(title)|normalize(artist)|durationSeconds)`，`normalize` 只做 `trim + lowercase + 合并空白`。

## 3. 支持的歌词源与数据结构

歌词源枚举 `core/model/LyricsSettings.kt`：`LOCAL_ONLY / NETEASE / MUSIXMATCH / LRCLIB / SPICY`，默认 `NETEASE`，在线顺序 `NETEASE → MUSIXMATCH → LRCLIB → SPICY`（用户可调）。

| 源 | 实现 | 检索接口 | 备注 |
|---|---|---|---|
| 本地 | `LocalPlainLyricsProvider` | 本地文件/导入 | 优先级最高，永远先查 |
| 网易云 | `NeteasePlainLyricsProvider` → JNI | Rust `netease`（free-text 关键词，最多 5 轮，`build_search_keywords`） | 中文曲库覆盖最好 |
| Musixmatch | `MusixmatchPlainLyricsProvider` → JNI | Rust `musixmatch`（原生匹配器 + 共享结构化查询） | 字幕匹配器不再暴露候选元数据 |
| LRCLIB | `LrclibPlainLyricsProvider` → JNI | `lrclib.net/api/get`（精确元数据）+ `/api/search` | 先用精确 get，再走共享查询计划 |
| Spicy | `SpicyLyricsProvider` | Spotify 搜索（需要 `sl_pk_` 客户端 key） | 支持逐字时间轴 |

统一结果结构：`LyricsProviderResult(plainProviderId, plainProviderName, plainLrc, translatedLrc, matchedTitle, matchedArtist, matchedAlbum, matchedDurationMs, wordByWordLines, access, selectedManually, pendingSpicyLyrics)`。请求结构：`PlainLyricsSearchRequest(title, artist, album, durationMs, searchMode, originalSong, cancellationToken)`。

## 4. 候选如何排序、最终如何选取

**两层，且两层都有问题：**

### 4.1 源内排序（Rust `lyrics-core/src/matching.rs`）

- 归一化：`clean_query_part` / `normalize_identity` / `normalize_words` / `match_text`（OpenCC 繁→简）；`title_variants` 生成原文、`simplify_title`、括号注释拆分、简繁变体。
- 打分权重（`TITLE_WEIGHT 0.54`、`ARTIST 0.30`、`DURATION 0.13`、`ALBUM 0.03`），`total = 加权和 / 已比较权重 - version_penalty(≤0.12)`。
- 门槛：`MIN_TITLE_SIMILARITY = 0.55`；跨文字标题（CJK vs 非 CJK）可走 `localized_title` 例外，但**要求 artist 相似度 ≥ 0.85 + 时长差 ≤ 2s + 无版本冲突**，命中后 title_confidence 记 0.80。
- 早停：`is_confident_early_match` = total ≥ 0.70 且 title ≥ 0.80 且 artist ≥ 0.35。
- **最终兜底 `take_best_candidate(early_only=false)`：只要过了标题门槛，就返回得分最高的那个，不再看置信度。**（`lrclib.rs:943`、`best_candidate_or_error`）

### 4.2 源间选取（Kotlin `lyrics/LyricsRepository.kt`）

`findOnlinePlainLyrics()` 按用户配置的顺序逐个源请求，**第一个返回“可用歌词”（`hasUsableLyrics()`）的结果直接 return**——没有跨源比较、没有统一置信度、没有候选列表。

### 4.3 国际标题解析（Rust `international.rs`）

- 仅在 `LyricsSearchMode.International`（用户点“国际标题搜索”）时触发；自动搜索不走。
- 搜 iTunes：永远搜 US；只有输入含假名/谚文/汉字时才加搜 JP/KR/CN（`region()`）。
- 用 `score_metadata_alias`（无标题门槛）选最高分，再用 Apple ID 回 US lookup。
- 文档自述：**“A US title may remain untranslated. No candidate confidence gate is added.”**

## 5. 为什么错误英文歌词会被接受（以“Radio Love Song - Karen Mok”为例）

按数据流逐层定位：

1. **别名解析根本没机会拿到中文名。** `region()` 只对日/韩/中文字符判区；`"Radio Love Song" + "Karen Mok"` 全是拉丁字母 → `region()=None` → 只查 US storefront。US 结果里最像的是那首英文《Radio Love Song(s)》（标题高度相似、艺人不同/缺失）→ `score_metadata_alias` 选中它 → 作为“别名”返回，标题仍是英文。文档也承认 US 标题可能不被翻译。
2. **跨语言例外对“英文→中文”反而不生效。** 如果某源返回的是莫文蔚《电台情歌》，`title_similarity≈0`，要走 `localized_title` 必须满足 `artist_similarity ≥ 0.85`——但查询艺人是 `Karen Mok`、候选艺人是 `莫文蔚`，`text_similarity` 约为 0（只有 OpenCC 简繁转换，没有任何艺人罗马音/中英对照表）→ 例外不成立 → 标题门槛直接淘汰正确候选。
3. **错误英文候选轻松过门槛。** 英文候选标题相似度 ≈0.8–0.9 → 过 `MIN_TITLE_SIMILARITY`；艺人比较失败时，`compared_weight` 只算标题（权重被重新归一化），`total ≈ title_similarity` → 普遍 ≥0.7，甚至满足 `is_confident_early_match`。
4. **没有最终置信度闸门。** `take_best_candidate(early_only=false)` 会返回“最高分”而不是“够格的结果”；`LyricsRepositoryEngine` 又只取第一个可用结果。于是错误歌词被当成成功匹配直接渲染到悬浮窗。
5. **没有候选展示/纠正入口。** `ManualLyricsSelectionStore` 只记录“用户手动选择的那份歌词文件/Spicy 缓存”，不是“歌曲→别名/录音”的映射；UI 没有“候选歌词列表”概念。

**一句话根因：** 标题相似度单独就能决定结果（艺人不可比时权重被归一化吞掉），跨语言正确候选被“艺人必须 ≥0.85”卡死，而错误候选没有任何全局置信度闸门，最后“首个可用结果”直接上屏。

## 6. 需要改动的文件与最小改动方案

### 新增（可独立单测，不依赖 Android）

| 文件 | 内容 |
|---|---|
| `lyrics-core/src/aliases.rs` | 别名实体与匹配：`SongAlias { original_title, original_artist, alias_title, alias_artist, source, confidence }`；规范化（大小写/标点/空格/艺人分隔符）；别名仅作为**候选检索词**，不覆盖原始元数据；别名必须有来源与置信度 |
| `lyrics-core/src/confidence.rs` | 统一决策：`MatchDecision::{Accept, Review, Reject}`；可配置阈值（高/中/低）；证据项（标题、别名、艺人、专辑、时长、版本、语言）分别计分，**缺失元数据只降权不补分**；跨脚本文本相似度不得单独支撑 Accept |
| `lyrics-core/src/recording.rs` | 录音版本指纹：title+artist+album+duration 归一化；版本标记（live/remix/instrumental/acoustic/cover）从标题/专辑抽取，冲突时不自动接受 |

### 修改

| 文件 | 改动 | 理由 |
|---|---|---|
| `lyrics-core/src/matching.rs` | 1) `localized_title` 的艺人条件改为“艺人相似 **或** 别名艺人命中”；2) 新增 `MatchScore` 字段 `evidence`（缺艺术家/时长标记）；3) 新增 `has_minimum_evidence()`（标题+至少一项独立证据）；4) 保留旧权重与门槛常量，新增常量不破坏现有测试 | 最小侵入，保持既有得分口径 |
| `lyrics-core/src/lrclib.rs` / `musixmatch.rs` / `netease`（`lib.rs` 内） | `take_best_candidate` 之后加 `confidence::decide()` 闸门：Reject 不返回、Review 标记为“需确认” | 阻断“最高分即真理” |
| `lyrics-core/src/international.rs` | 1) `region()` 之外增加“拉丁字母标题也可能有中文版”的路径：先查 CN storefront（term 用原词），拿到候选后要求艺人/时长/版本证据；2) 别名解析必须输出 `confidence` 与 `evidence`，低置信度不返回别名 | 直接修复本案 |
| `lyrics-core/src/lib.rs` | 新增 JNI：`resolve_aliases_json`（只读别名库）、`score_pairs_json`（给 Kotlin 侧做候选展示） | 支撑 UI |
| `app/.../lyrics/PlainLyricsRequest.kt` | 增加 `aliasTitle/aliasArtist/aliasSource`（**不覆盖** title/artist） | 保留原始元数据 |
| `app/.../lyrics/providers/InternationalTitleResolver.kt` | 解析结果带 `confidence/evidence`；低置信度时不改 title，只附候选 | 防止瞎翻译 |
| `app/.../lyrics/LyricsRepository.kt` | `findOnlinePlainLyrics` 改为“收集所有源的候选 → 统一 `confidence::decide` 排序 → Accept 直接用 / Review 返回候选 / Reject 继续下一个源”；无 Accept 时返回 `null` + 候选列表，而不是随便给一个 | 阶段 C/D 核心 |
| `app/.../lyrics/LyricsResult.kt`（或新增 `LyricsMatchOutcome.kt`） | 结果增加 `outcome: Accept/Review/Reject` + `candidates` | 让 UI 能区分 |
| `app/.../lyrics/storage/ManualLyricsSelectionStore.kt` | 增加“别名映射”持久化：`{recordingKey, aliasTitle, aliasArtist, source, createdAt, confirmedByUser}`，与现有“选中歌词文件”分开；`forget` 同时支持清别名 | 阶段 3 |
| `app/.../ui/...`（歌词页） | 最小 UI：候选列表（标题/艺人/专辑/时长/来源/置信度）+“使用这个”+“手动搜索”+“清除映射”；沿用现有 i18n（`values/strings.xml` 及 `-zh-rCN`） | 阶段 3，不改悬浮窗 |
| `app/src/test/...`、`lyrics-core/src/*.rs` | 新增回归测试（见 §7） | 验收 |

**不做的事**：不重写悬浮窗、播放器监听、歌词解析/同步；不引入机器翻译；不因语言直接判错。

## 7. 测试计划（对应任务第 6 节）

Rust（`cargo test`，无 Android 依赖）：
1. `radio_love_song_alias_resolves_cn`：英文标题+罗马音艺人 → 别名候选（电台情歌/莫文蔚），判定 Accept。
2. `wrong_english_lyrics_not_auto_accepted`：英文候选仅标题相似 → Review/Reject，不 Accept。
3. `same_title_different_artist_rejected`。
4. `version_markers_block_mix`（live/remix/acoustic 不混用）。
5. `cross_language_official_version_allowed`（中/英正式版都可通过，不因语言排除）。
6. `missing_metadata_lowers_confidence`（缺艺人/时长 → 最多 Review）。
7. `provider_failure_is_not_crash`（模拟网络失败）。
Kotlin（`./gradlew testDebugUnitTest`）：
8. 手动确认映射可复用/覆盖/清除（`ManualLyricsSelectionStore`）。
9. 切歌/暂停/恢复时歌词状态更新（现有 `FloatingLyricsServiceLatestMediaTest` 扩展）。

## 8. 构建环境现状（必须先解决才能给真实构建结果）

当前机器：**无 JDK、无 Gradle（仅 wrapper）、无 Android SDK、无 Rust 工具链**。

- 磁盘：189 GB 可用；`dl.google.com`、`services.gradle.org`、`static.rust-lang.org`、Maven Central 均可达（HTTP 200）。
- 需要安装：JDK 17/21、Android cmdline-tools + `platforms;android-37` + `build-tools;36.x` + `ndk;26.3.11579264`、Rust（rustup + `aarch64-linux-android` 等 target + `cargo-ndk`）、Gradle 依赖（首次约 1–2 GB）。
- 预计磁盘 4–6 GB，时间 20–40 分钟（取决于网速）。
- **未开始实现前不得声称任何测试/构建通过**；当前状态：源码审查完成，未改一行代码，未运行任何测试。

## 9. 已知限制

- 小米 HyperOS 真机悬浮窗/后台保活无法在本环境验证（无设备、无 adb）。
- 网易云/Spicy 需要账号/客户端 key，联调受限于凭证。
- 别名来源：iTunes storefront 只覆盖有对应上架的录音；LRCLIB/网易云可作为补充来源，但需逐条校验（不能凭标题字面翻译）。
- 判定阈值需要用真实样本调（建议先跑 `scripts/lyrics_benchmark` 的固定输入做可用性基线，再谈正确率）。
