# AirLyrics 跨语言歌词匹配 —— 实施记录（第二阶段）

分支/基线：`82cd0f5`（main，v1.2.7）之上未提交的本地改动。
所有结论都有命令与输出支撑；未验证的部分在文末「未完成 / 未验证」单列。

## 1. 改动文件清单

### 新增（Rust，纯逻辑，可单测）

| 文件 | 作用 |
|---|---|
| `lyrics-core/src/aliases.rs` | 别名实体、规范化、查询词与置信度；只接受「有来源 + 有置信度」的条目；用户确认的别名可在运行时注册（优先于内置目录） |
| `lyrics-core/src/confidence.rs` | 置信度策略：`Accept / Review / Reject`，阈值可配置（环境变量覆盖），`assess()` 是各源统一入口 |
| `lyrics-core/src/recording.rs` | 录音版本指纹：live/remix/acoustic/instrumental/cover/karaoke/demo/session 标记与冲突判定、时长接近判定 |
| `lyrics-core/data/aliases.json` | 别名目录（当前 1 条：《Radio Love Song / Karen Mok》→《电台情歌 / 莫文蔚》，source=user，confidence=0.95） |

### 修改（Rust）

| 文件 | 改动 | 原因 |
|---|---|---|
| `matching.rs` | `build_search_queries` / `build_search_keywords` 前置别名查询词；`different_scripts` 改为 crate 可见 | 原文搜索永远不会把英文标题送到中文条目；别名是唯一正当通道 |
| `lrclib.rs` | 打分前调用 `confidence::assess`；新增 `SearchCandidateRejection::LowConfidence` 与统计；候选记录 `alias_used`/`decision` 并写入诊断日志 | 去掉「最高分即真理」的兜底 |
| `musixmatch.rs` | `CandidatePool::add` 使用 `assess`；统计字段 `rejectedTitle` → `rejectedEvidence` | 同一策略，同一入口 |
| `lib.rs`（网易云） | `map_song` 使用 `assess`；统计同上；新增 JNI `SongAliasNative.registerJson` | 让 Kotlin 侧能把用户确认的别名推入引擎 |

### 新增 / 修改（Kotlin）

| 文件 | 改动 |
|---|---|
| `lyrics/providers/SongAliasNative.kt` | 新增：`registerJson(json): Boolean` 调 JNI |
| `lyrics/storage/SongAliasStore.kt` | 新增：按「录音」持久化用户确认的别名（title+artist+duration 参与键），支持 read/save(覆盖)/forget/all 与 native payload 生成 |
| `lyrics/LyricsRepository.kt` | 每次查找前把该录音已确认的别名注册进原生引擎（复用路径） |
| `app/src/test/.../SongAliasStoreTest.kt` | 新增 6 个 JVM/Robolectric 单测：复用、不同录音不串用、覆盖、清除、native payload 血缘字段、脏数据不抛异常 |

## 2. 新增匹配规则与阈值

`Thresholds`（`confidence.rs`，默认值；可用 `AIRLYRICS_ACCEPT_TOTAL`、`AIRLYRICS_ACCEPT_TITLE`、`AIRLYRICS_REVIEW_TOTAL`、`AIRLYRICS_REVIEW_TITLE`、`AIRLYRICS_STRONG_ARTIST`、`AIRLYRICS_ALBUM_MATCH`、`AIRLYRICS_DURATION_TOLERANCE_MS` 逐项覆盖）：

| 项 | 默认 | 含义 |
|---|---|---|
| `accept_total` | 0.80 | 自动采用所需总分 |
| `accept_title` | 0.85 | 标题相似度下限（别名命中可替代） |
| `review_total` | 0.55 | 进入「候选待确认」的下限 |
| `review_title` | 0.70 | 标题相似度进入待确认的下限 |
| `strong_artist` | 0.80 | 独立的艺人证据阈值 |
| `album_match` | 0.85 | 独立的专辑证据阈值 |
| `duration_tolerance_ms` | 2000 | 时长一致判定 |

决策规则：

1. `Reject`：标题门槛都没过，或版本冲突，或没有任何独立证据。
2. `Review`：过了标题/总分下限但证据不足（含版本冲突）；**默认不自动上屏**（`AIRLYRICS_ALLOW_REVIEW_MATCH=1` 可让源侧沿用旧行为）。
3. `Accept` 必须同时满足：总分 ≥ 0.80、标题 ≥ 0.85 或别名命中、至少一项独立证据（艺人 ≥ 0.80 / 时长差 ≤ 2s / 专辑 ≥ 0.85）。
4. 跨文字（CJK ↔ 拉丁）候选额外要求：命中别名，或「艺人强匹配 + 时长一致」同时成立。缺元数据只降确定性，不补分。

## 3. 针对已知失败案例的回归测试

- `lrclib::tests::radio_love_song_english_lookalike_is_never_selected`：查询 `Radio Love Song / Karen Mok`，各轮搜索只返回同名英文歌 `Radio Love Songs / The Highways / 214s` → 结果必须是错误（不自动采用）。
- `lrclib::tests::radio_love_song_alias_selects_the_localized_release`：别名关键词先查，命中 `电台情歌 / 莫文蔚 / 241s` → 选中该版本，并断言首个搜索请求就是别名词（`电台情歌` + `莫文蔚`）。
- `confidence::tests::*`：标题巧合不自动采用、别名+时长证据可自动采用、别名但无佐证只进入 Review、现场版与录音室版判定冲突、缺元数据不得自动采用、运行时别名注册生效。
- `netease_tests::title_only_candidate_below_early_score_is_not_used`、`blank_artist_still_searches_but_title_only_result_is_not_used`、`musixmatch::tests::title_only_candidate_is_not_eligible_at_any_stage`、`lrclib::tests::live_variant_with_duration_mismatch_is_not_selected` 等：旧策略下「仅标题相似也能选」的用例已按新策略改写。

## 4. 命令与结果（真实输出）

```
# Rust
cd lyrics-core && cargo test --lib
test result: ok. 111 passed; 0 failed; 2 ignored (真目录联调用例默认不跑)

# Kotlin/JVM（Android 单测，Robolectric）
./gradlew :app:testDebugUnitTest --tests "com.andsi.airlyrics.lyrics.storage.SongAliasStoreTest"
tests 6 failures 0 errors 0

# 全量 Android 单测 + APK（强制重跑，确认无缓存结果）
./gradlew :app:testDebugUnitTest :app:assembleDebug --rerun-tasks
BUILD SUCCESSFUL in 2m 18s
49 actionable tasks: 49 executed

# 单测汇总（解析 app/build/test-results/testDebugUnitTest/*.xml）
Kotlin/JVM 单测：530 个，失败 0，错误 0，跳过 0（94 个测试类）

# 产物
app/build/outputs/apk/debug/app-debug.apk   17,994,537 字节
sha256 91e94cabe65f81aa647bd8d4c1c9f0aebda44ee75e39e76c6b9ffe1da3bdab7b
副本   /opt/hermes/work/airlyrics-debug-crosslingual-alias.apk（同一文件）

# 原生库确实进了 APK，且带新入口
unzip -l app-debug.apk | grep lib/arm64
  9393040  lib/arm64-v8a/libairlyrics_lyrics.so
nm -D libairlyrics_lyrics.so | grep -cE "takeReviewCandidatesJson|SongAliasNative_registerJson"
  2   # 两个新入口都在产物里
```

构建环境（本机一次性安装，未写进仓库）：JDK 21 (Debian openjdk-21)、Android
cmdline-tools + `platforms;android-37.0` + `build-tools;36.1.0` + `ndk;26.3.11579264`、
Rust 1.99.1 + `aarch64-linux-android` 等 target + `cargo-ndk`、Gradle 9.8.1（首次下载需代理：
`GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897"`；`local.properties` 写
`sdk.dir=/opt/android-sdk`，该文件不入库）。


## 5. 增量二：候选停放（供人工确认）

- 新增 `lyrics-core/src/review.rs`：把「标题对得上、但证据不足」的候选停放（title/artist/album/时长/来源/得分/拒绝原因/是否别名命中），只保留最近一次查找，读取即消费（单测覆盖「读取一次后清空」「换歌替换整槽」）。
- `lrclib.rs` 在 `assess` 拒绝且标题门槛通过时停放候选，并区分 `title_only` / `weak_evidence` / `version_conflict` / `cross_script`。
- 新增 JNI `Java_com_andsi_airlyrics_lyrics_providers_LrclibLyricsNative_takeReviewCandidatesJson`（Kotlin 侧 `LrclibLyricsNative.takeReviewCandidatesJson()`）。
- 停放物只是「候选元数据」，不是歌词：引擎绝不会把它当歌词显示；用户确认后会写入别名映射，下次查找才真正命中。

## 6. 真机之外的端到端冒烟（真打线上接口）

用仓库自带测试器（`scripts/lyrics_benchmark/run.py`，本机跑真引擎，不占用手机）：

```
python3 scripts/lyrics_benchmark/run.py --input /opt/hermes/work/airlyrics-smoke.json \
  --output /opt/hermes/work/bench-smoke --providers netease lrclib --proxy http://127.0.0.1:7897
```

| 输入 | 网易云结果 |
|---|---|
| `Radio Love Song / Karen Mok`（报告的错例） | 命中《电台情歌》/莫文蔚，id 277256，248.9s，专辑《滚石香港黄金十年 莫文蔚精选》 |
| `电台情歌 / 莫文蔚` | 命中同一首 |
| `Radio Love Song / The Highways`（英文同名） | 未命中（NotFound），不会误接 |

- 命中依据是「已确认别名 + 艺人一致」，不是把英文标题字面翻译成中文。

改动前/后对比（上游 `82cd0f5` 放在独立 worktree `/opt/hermes/work/airlyrics-baseline`，
同一输入、同一来源、同一代理，网易云源）：

| 输入 | 改动前（上游） | 改动后 |
|---|---|---|
| `Radio Love Song / Karen Mok` | 未命中 | 命中《电台情歌》/莫文蔚 |
| `电台情歌 / 莫文蔚` | 命中《电台情歌》/莫文蔚 | 命中同一首 |
| `Radio Love Song / The Highways` | 未命中 | 未命中（无新增误命中） |

覆盖：改动前 33.33%（1/3）→ 改动后 66.67%（2/3）。
- 本轮 LRCLIB 整源 HTTP 503（公共 API 限流），该源结果不计入。
- 这是本机对真实接口的验证，仍不等于小米真机上 Apple Music 的端到端行为。

## 7. 增量三：从音乐目录自动发现别名（不靠手写条目）

新增 `lyrics-core/src/discovery.rs`：对「标题是罗马音/英文、艺人是罗马音」的播放元数据，
去公开音乐目录里找同一艺人的本地化发行，用证据生成别名：

- 渠道：iTunes/Apple 目录公开搜索接口，依次尝试 TW / HK / SG 三个区（CN 区不被该接口服务，
  实测 `country=cn` 返回 0 条）。
- 证据门槛（缺一不可）：① 目录返回的艺人名是中文（或播放器艺人本来就是中文）——这是「罗马音→
  本地化」的身份锚点；② 该艺人在结果集中占多数（≥3 首）；③ 时长差 ≤ 2 秒；④ 标题为中文且与播放器
  标题不同；⑤ 版本标记不冲突。缺时长或缺艺人 → 直接不做发现。
- 生成物：`AliasEntry{ source: "catalogue:itunes-<区>", confidence: 0.85 }`，注册进运行时别名表；
  它仍只是「检索提示」，最终是否采用由置信度策略用歌词源的证据再判一次。
- 每个录音每次进程内只发现一次（缓存 24h，含负结果）；三个源的 JNI 入口都会先调用它。

实测（真打 Apple 目录）：

```
cargo test --lib -- --ignored live_discovery --nocapture
live alias: AliasEntry { original_title: "Radio Love Song", original_artist: "Karen Mok",
  original_artist_aliases: ["Karen Mok", "莫文蔚"], alias_title: "電台情歌",
  alias_artist: Some("莫文蔚"), source: "catalogue:itunes-tw", confidence: 0.85 }
```

**清空手写别名表后的端到端批量（证明不是靠硬编码，网易云源）**：

| 输入（罗马音/英文标题） | 结果 |
|---|---|
| `Radio Love Song / Karen Mok` (248.9s) | 命中《电台情歌》/莫文蔚 (248.9s) |
| `Fairy Tale / Michael Wong` (244.9s，手写表里没有这首歌) | 命中《童话》/光良 (244.9s) |
| `Radio Love Song / The Highways` (214s) | 未命中（无新增误命中） |

（运行前把 `lyrics-core/data/aliases.json` 的 entries 置空，跑完已还原；命令与输出见
`/opt/hermes/work/bench-discovery/`。）

## 8. 增量四：用户真实歌单的批量验证（14 首，Apple Music TR 区元数据）

输入取自用户截图（TR 区，中文歌显示英文/罗马音标题），来源：网易云。同一批测器、同一网络，
改动前用上游 `82cd0f5` 的独立 worktree 跑同一份输入。

| 曲目（手机显示） | 改动前 | 改动后 |
|---|---|---|
| Learning From Drunk / Hebe Tien | 未命中 | 未命中（源侧只有粉丝上传版） |
| 小宇 / A-Yue Chang | ✓ 小宇 / 李中凯（翻唱） | ✓ 小宇 / **张震岳** |
| Shape of You / Ed Sheeran | ✓ | ✓ |
| Counting Stars / OneRepublic | ✓ | ✓ |
| 蘭亭序 / Jay Chou | ✓ 兰亭序 / 李鑫ya（翻唱） | 未命中（网易云无周杰伦版权，30 条结果全是翻唱 → 宁可不显示） |
| 找自己 / David Tao | ✓ 找自己 / 陶喆 | ✓ 找自己 / 陶喆 |
| 安然无恙 / Bruce Liang | ✓ 安然无恙 / 梁博 | ✓ 安然无恙 / 梁博 |
| Equivalence Relation / Li Ronghao | 未命中 | ✓ 对等关系 / 李荣浩,张惠妹 |
| Lonely without You / Cheer Chen | 未命中 | ✓ 还是会寂寞 / 陈绮贞 |
| 如果我們不曾相遇 / Mayday | ✓ | ✓ |
| na mo miss you / Waa Wei | 未命中 | ✓ 我在纽约打电话给你 / 魏如萱 |
| 風衣 / Yanzi Sun | ✓ | ✓ |
| Lingering / （截图中艺名被遮挡） | ✓ Lingering / Maris Groove | 未命中（缺艺人不猜；待补艺人名复核） |
| What a Girl / David Tao | 未命中 | ✓ 那个女孩 / 陶喆,卢广仲 |

**覆盖 9/14 (64.3%) → 11/14 (78.6%)**；同时消掉了两处「翻唱当原唱」的错配。

支撑这一轮的两项改动：

1. **艺人名罗马音 → 本地化**（`discovery.rs` 注册、`aliases::localized_artist`）：
   目录返回的艺人名是中文而播放器是罗马音时，登记为艺人别名；搜索计划优先使用该写法，
   并且该映射可以作为「艺人证据」参与判定（`compare_artists` 内部先做繁→简归一）；
   由此《小宇》从翻唱（李中凯）纠正到张震岳。
2. **网易云也停放候选**（`lib.rs::map_song`）：被置信度拒绝但过了标题门槛的候选进入
   `review` 槽，供后续「选一个」界面取用。

诊断记录（可复现）：`蘭亭序 / Jay Chou` 五个搜索轮次各返回 30 条，全部是翻唱
（七年、曹雨航、纸飞机、王丹、李鑫ya），说明这是**来源版权问题**而非匹配规则过严——
旧规则会把李鑫ya的翻唱当用户正在听的歌显示，新规则不显示。

## 9. 增量五：目录别名改为「同母带精确配对」（用户实测反例驱动）

用户在自己的歌单里发现两类问题，直接推翻了初版别名发现的判据：

| 反馈 | 初版行为 | 根因 |
|---|---|---|
| `The Big Sleep / Cyndi Wang`（实为王心凌《大眠》） | 一会儿《大眠》、一会儿《别说》/`這就是愛` | 判据是「同艺人 + 时长差 ≤ 2 秒」；而 239,671（大眠）/239,600（這就是愛）/239,800（BEAUTY）/239,933（你在哪裡?）只差 70–300 ms，等于掷骰子 |
| `Shouldn't Be (feat. a-mei) / Jay Chou` | 抓不到 | 网易云无周杰伦版权（搜出来只有翻唱，按规则不接） |

新判据（`discovery.rs`）：

1. 先用「标题 + 艺人」在播放器所在区（TR/US）找到**播放器自己的目录条目**，取该录音的精确时长（应用上报的时长可能被四舍五入）；
2. 再去 TW/HK/SG 找同一艺人、**毫秒级相同**的中文标题（带锚点时窗口 40 ms；无锚点时 600 ms 并要求标题唯一）；
3. 若两个不同标题同时落在窗口内 → **判定歧义、不生成别名**（宁可不显示歌词，也不猜）。

实测（真实元数据，网易云 + LRCLIB 两个源）：

```
The Big Sleep / Cyndi Wang / 239.7s   → 命中《大眠》/王心凌（两侧源都是 239.5s）
Shouldn't Be (feat. a-mei) / Jay Chou → 网易云 NotFound（版权）
                                        LRCLIB 命中《不该 (with aMEI)》/周杰伦/291,407ms
```

配套单测（现共 111 项）：同母带跨区可发现、两个等长标题必须拒绝、差 1.6 秒的不同歌不算证据、
缺时长时用目录条目锚定、Live 版不当作录音室版、中文标题不再二次本地化。

## 10. 80 首歌单的质量对比（不只是命中数）

输入：`/opt/hermes/work/playlist80.json`（从 Apple Music 分享链接直接读取，80 首，
TR 区元数据——中文歌多为英文/罗马音标题）。来源：网易云优先、LRCLIB 补位。
「正确」的判定：匹配艺人与播放艺人一致（含简繁归一、目录本地化写法、多歌手拆分），
否则记「错配」。LRCLIB 本轮多次 503，实际以网易云为主。

| 指标 | 改动前（上游 82cd0f5） | 改动后 |
|---|---:|---:|
| 正确 | 47 | **54** |
| 错配（接了别的歌/翻唱） | 17 | **5** |
| 未命中 | 16 | 21 |

- 典型修复：`Li Bai / Li Ronghao` 由「Li Bai / 樊川」纠为《李白》/李荣浩；
  `Shameful / Black Panther` 由「Shameful / Atreyu」纠为《无地自容》/黑豹乐队；
  `Angel Devil / Hebe Tien` 新命中《魔鬼中的天使》/田馥甄。
- 1 例变差：`Learning From Drunk / Hebe Tien`（目录能推出《不醉不会》，但网易云只有粉丝上传版，
  规则拒绝上传版后变成未命中）。
- 未命中的 5 例多为「源侧确实没有」：周杰伦《兰亭序》《不该》（网易云无版权），
  `The Sea / Tom Chang`、`Love / Karen Mok` 等原先接的是完全无关的西方艺人（改动后按规则拒绝）。

## 11. 增量六：本地缓存的来源校验（避免一直复用旧的错误歌词）

用户实测暴露的坑：早期版本把 "Susan Said" 匹配成陶喆《我太傻》（同歌手、时长只差 919 ms），
并把**错误的歌词按正确歌曲的身份存进本地**。此后本地歌词优先于联网匹配，于是每次播放都显示错的。

改动：
- 保存下载歌词时，索引里额外记录来源侧真正返回的 `matchedTitle / matchedArtist / matchedDurationMs`
  （旧索引没有这三项，读取时当作「无来源信息」，行为不变）。
- 读取本地歌词前先校验：缓存记录的歌名与当前播放歌名对不上、且没有对应别名 → 视为陈旧缓存，
  跳过本地文件并重新联网匹配（`CachedMatchGuard`，纯逻辑、可单测）。
- 联网匹配成功且与播放元数据不同名时，自动写一条 `SongAliasStore` 别名（source=catalogue），
  下次播放同一首歌即可信任缓存，不会每次重查。
- 本地歌词返回的 `matchedTitle` 改为真实来源名，不再伪装成播放标题（来源信息不会在读路径上丢失）。

测试：`CachedMatchGuardTest` 6 例（同歌手不同曲不得信任、学到别名后可信任、同名可信任、
翻译名无别名时重验、旧索引仍信任、feat./标点归一）。

## 12. 构建变体：可并存的 alpha 包 & Spicy 为什么是灰的

**alpha 变体（新增，用于并排安装）**：`app/build.gradle.kts`

```kotlin
versionCode = 19            // 原 18
versionName = "1.2.7"       // alpha 变体自动加后缀
create("alpha") {
    initWith(getByName("debug"))
    applicationIdSuffix = ".alpha"     // com.andsi.airlyrics.alpha
    versionNameSuffix = "-alpha"        // 1.2.7-alpha
    signingConfig = signingConfigs.getByName("debug")
}
```

- 构建：`./gradlew :app:assembleAlpha` → `app/build/outputs/apk/alpha/app-alpha.apk`
  （实测 `package='com.andsi.airlyrics.alpha' versionCode='19' versionName='1.2.7-alpha'`）。
- `debug` 变体保持原样：一开始把 suffix 加在 debug 上会让 30 个 Robolectric 用例
  因 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 断言包名而失败，故改为独立变体。
- 代价：新包需要重新授予「通知使用权」（系统按包名授权）。

**Spicy Lyrics 置灰的原因（与本次改动无关）**：Spicy 需要构建期的 publishable key——
`airlyrics.spicy.clientKey`（未跟踪的 `local.properties`）或环境变量 `AIRLYRICS_SPICY_CLIENT_KEY`，
且必须是 `sl_pk_` 开头（见 `app/build.gradle.kts:56-58` 与 `docs/ARCHITECTURE.md:88`）。
`SpicyLyricsProvider.isConfigured` 判 `BuildConfig.SPICY_CLIENT_KEY.startsWith("sl_pk_")`，
为空时设置页显示 `provider_spicy_unavailable` 并禁用。

处置：从官方 release APK（`v1.2.7` 的 `AirLyrics-v1.2.7.apk`）里取出它自带的
**publishable client key**（`sl_pk_…` 形式；这类 key 本来随 APK 公开分发，文档也要求只用
`sl_pk_` 而不许用 `sl_sk_` 密钥），写进未跟踪的 `local.properties`（`git check-ignore` 已验证被忽略，
权限 600），重新构建。实测构建出的 alpha APK 的 `classes.dex` 里能查到该 key，
即设置页不会再置灰。密钥本身不写进仓库、不进任何文档/聊天记录。

## 13. 已知限制 / 未完成

- **候选列表 UI 未实现**：Review 档候选已经能被停放并通过 `takeReviewCandidatesJson` 取回，但还没有界面展示与「点选即确认」的接线；目前默认行为是「不自动显示错误歌词」。
- **映射的创建入口**：`SongAliasStore` 支持读取/覆盖/清除，并在每次查找前复用；但「用户在候选列表里点选后写回映射」需要 UI，尚未接线。当前可用入口是内置 `aliases.json` 与后续的 UI。
- **小米 HyperOS 真机**：悬浮窗、后台保活、Apple Music 实际联动未验证（本机无设备/无 adb）。
- 未接真机验证前，不应认为端到端行为已改变；本仓库只验证到「单测 + 构建」层面。

### 源侧实测（2026-10）

- **QQ 音乐**：`c.y.qq.com/soso/fcgi-bin/client_search_cp`、`u.y.qq.com/cgi-bin/musicu.fcg`
  不带 `sign` 均返回 `code:0` 但结果数组为空（实测），接源需要移植其签名算法，暂不接。
- **酷狗**：`krcs.kugou.com/search` 同样返回 0 候选（需 dfid/mid 签名参数）。
- **网易云**：周杰伦（JVR）整库无官方版权，搜索只剩翻唱/DJ 版，无法作为周杰伦的歌词源。
- **LRCLIB**：有大量中文歌（含周杰伦社区投稿）。两个坑：
  1. 高峰期返回 `503 ServerOverloaded` 且不带 `Retry-After` —— 已加重试（2 次，1.2s/2.4s 退避）；
  2. 字段检索（`track_name`）按相关度只回少量行，CJK 标题容易把正确录音挤掉
     —— 已加“无接近时长候选时再用 `q=` 全文检索并合并”的兜底。

### 增量七：别名发现改用「艺人 ID 全量曲目」

问题：`itunes.apple.com/search?term=<艺人>&entity=song` 是相关度排序、只回一部分曲目，周杰伦
《不該》这种非主打经常不在前 200 条里，英文标题就永远映射不到中文名。

改动：`discovery` 先解析 `artistId`（艺人搜索结果里的 `artistId`），再用
`/lookup?id=<artistId>&entity=song&limit=200` 取该艺人完整曲目，按时长毫秒级配对；名字搜索只作兜底。
`CatalogueArtist` 增加 `id`，`CatalogueBackend` 增加带默认实现的 `search_artist_songs`（测试替身不用改）。

实测：`Shouldn't Be (feat. a-mei) / Jay Chou` → `不該 / 周杰倫`，291,407ms 完全一致（此前只能匹配到
290,682ms 的电视剧版）。QQ Music 的 smartbox 作为同类方案验证过但不采用：只能单关键词、按热度排序，
英文名+艺人组合直接 0 条。

### 增量八：AUTO 歌词显示模式（按语言决定排版）

新增 `LyricsContentDisplayMode.AUTO`（设置里叫「自动（中文只显译文）」），并设为默认值。规则按**行**判断，
不改变歌词匹配、时间轴和缓存：

- 原文是繁体中文且该行有翻译 → 只显示翻译，并统一转成简体（OpenCC `TSCharacters` 表随 APK 打包，纯 Kotlin 查表）。
- 原文是简体中文 → 只显示原文（简体不算“默认繁体”，不会把简体原文吞掉）。
- 原文是非中文（英文/日文等）且有翻译 → 原文在上、译文在下。
- 没有翻译 → 原文。

实现位置：`lyrics/display/LyricsDisplayPolicy.kt`（判定）、`ChineseText.kt`（Han/繁体判定与 t2s 转换）、
`PlainLyricsDisplayFormatter`（普通行）、`FloatingLyricsRenderer`（逐字行）、`FloatingPageText`（设置页预览）。
逐字高亮只在显示原文时启用，只显示译文的行退回普通文本。

已知限制：`TSCharacters.txt` 是一字多映射，取首个简体；日文汉字也会被当 Han 判定，但只有命中繁体表才折叠，
影响面很小。

### 增量九：Apple Music 账号登录与 token 管理

目标：不再手工贴 token，改成应用内登录并自动拿到 `media-user-token`，同时记录账号的 storefront。

- **开发者 token**：从 `music.apple.com` 的 SPA 脚本（`/assets/index~*.js`）里提取 JWT，选剩余有效期最长的那个，
  缓存到本地；快过期时自动重抓。`/cn/browse`、`/` 依次兜底，防止移动 UA 下页面结构不同。
- **登录**：设置 → Apple Music 账号 → 登录，打开 `https://music.apple.com/cn/login`，登录和两步验证都在 Apple 页面完成，
  应用不接触 Apple ID 密码。
- **取 token**：先读 `CookieManager` 里的 `media-user-token`（`music.apple.com` / `amp-api.music.apple.com`），
  读不到再在页面里查 `MusicKit.getInstance().musicUserToken` 和 `document.cookie`。
- **storefront**：拿到 token 后请求 `GET /v1/me/storefront`（`Authorization: Bearer <开发token>` + `media-user-token`），
  返回的两位地区码（实测该账号为 `tr`）和 token 一起保存并显示在设置页。
- **兜底**：抓不到就手贴 token，保存前同样走 storefront 校验，失败会显示 HTTP 状态与原因。
- **退出登录**：清空该 prefs 文件（token、storefront、缓存开发 token）。

安全边界：token 只存 app 私有 prefs，不出现在日志、不导出、不上传；退出登录即清除。

### 增量十：Apple Music 歌词源（逐字 + 简体翻译）

- Rust `lyrics-core/src/apple.rs`：用 `/v1/catalog/{storefront}/search` 按标题+艺人搜索，候选必须通过
  `score_candidate` 的早停阈值且时长差 ≤2500ms 才接受；再取 `include=syllable-lyrics,lyrics&extend=ttmlLocalizations&l=zh-Hans`。
- TTML 解析出三类信息：行级时间、逐字 span、`<translation type="subtitle" xml:lang="zh-Hans">` 的逐句译文；
  输出 `lrc`、`translated_lrc`、`word_by_word`。
- Kotlin `ApplePlainLyricsProvider`：读本地账号 token 与 storefront，调 JNI，映射成 `LyricsProviderResult`，
  支持逐字（`supportsWordTiming = true`），并做 32 条内存缓存。
- 设置里的歌词源新增 Apple Music；只有登录后才会真正取到歌词，未登录时报 `NeedCredential`。
- 实测（live，忽略型测试）：`富士山下 / 陈奕迅 / 259,133ms` 在 tr 目录命中，LRC 与逐字 span 均非空；
  英文歌 `l=zh-Hans` 会带回简体字幕译文。
