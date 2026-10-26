# User Guide

[English](USER_GUIDE.md) · [简体中文](USER_GUIDE.zh-CN.md)

Thank you for reading this guide!

## Table of Contents

1. [First Launch](#first-launch)
2. [Select a Player](#select-a-player)
3. [Use Floating Lyrics](#use-floating-lyrics)
4. [Lyrics and Local Files](#lyrics-and-local-files)
5. [Troubleshooting](#troubleshooting)

## First Launch

1. Open **Settings > System** and enable the required permissions.
2. Start playing music, open **Media**, and manually select the player AirLyrics should follow.
3. Open **Floating**, then tap **Show** in the bottom bar.

## Select a Player

Use the **Media** page to choose the player AirLyrics follows. AirLyrics remembers this choice, and
lyrics operations for the current song also follow the selected player.

AirLyrics only attempts to select a player automatically when it starts. After granting notification
access, please select the player manually, even if it already appears on the **Media** page.

If no player appears, check whether a music app is running in the background or notification access
is enabled.

## Use Floating Lyrics

- When **Click-through** is enabled, use **Adjustment mode** in the notification to make the window
  draggable and responsive to touch again.
- **Auto-hide > When playback is paused** hides the window when playback is paused and shows it again when playback
  resumes. (There may be a slight delay.)
- **Display scope** (Android 10+) shows the window only while a selected app is visible. Tap
  **Grant usage access**, choose apps with **Choose apps**, then tap **Enable**. Apps in split-screen,
  freeform windows, and picture-in-picture also count as visible.
- **Display blocked** means floating lyrics are still enabled, but current conditions prevent the
  window from appearing. See the troubleshooting section at the bottom.
- **Lyrics offset** is saved for each song and does not change the original lyrics file.
- **Word-by-word lyrics** supports local imports and the Spicy Lyrics source. (I am also trying to find
  ways to support other lyrics sources, but it is quite likely that I will not be able to.)

## Lyrics and Local Files

Manage lyrics in **Settings > Lyrics**. AirLyrics always looks for lyrics in this order:
manual import > local cache > online search.

### Manage Saved Lyrics in Batches

Tap **Select multiple** (the top-right button) in **Saved lyrics** (Recent local lyrics -> View all):

- **Export** saves a ZIP. Extract and edit its LRC files, then **Import and replace**. Keep `airlyrics-key` tags to match the original entries.
- Imports match selected songs: export tags take priority, followed by LRC title and artist tags. Without a title tag, filenames are matched; use `Title - Artist.lrc`. One selected song and one file without an export tag map directly.
- Confirming an import replaces the original lyrics and type. Deletion includes plain and word-timed lyrics.
- Unmatched, ambiguous, or duplicate matches are skipped. Source restrictions or read failures can prevent export (lyrics from Spicy Lyrics cannot be exported due to API restrictions).

### Online Lyrics Sources

Multiple online lyrics sources are supported. You can select them and adjust their priority in
Settings > Lyrics. Each source tries the full track information first, then broadens the query and
checks other related candidates if the first one has no synchronized lyrics. Only lyrics with a
valid timeline are used; to improve the hit rate, the returned version, artist tags, or duration may
not exactly match the current track. If you need more accurate lyrics, just adjust them yourself.

### Manual Lyrics Search

In **Settings > Lyrics > Current song lyrics**, tap **Search lyrics**. Edit the title, artist or album,
choose sources, and search. Swipe between sources, open a result to preview it, then tap **Apply lyrics**.
The reset icon at the end of each edited field restores its original value.
Searching and previewing leave existing lyrics intact; applying uses the song shown at the top of the window.

- Uninstalling AirLyrics also deletes the default lyrics folder. To use a custom folder, select it
  through the system file picker.

See [Lyrics Format](LYRICS_FORMAT.md) for supported LRC and TTML examples. I generally do not plan to
add support for more lyrics formats; you can use AI to convert them yourself.

## Troubleshooting

**The floating window does not appear:** Check that a player is selected and overlay permission is
enabled, then open **Floating** and tap **Show** in the bottom bar. Granting permission alone does not
display the window.

**The notification says “Display blocked”:** Common causes include the current app being outside
**Display scope**, playback being paused with **Auto-hide > When playback is paused** enabled, the screen being off or the
device being locked, or missing **Overlay** or **Usage access** permissions. Open a selected app,
resume playback, and check the relevant permissions.
If **Display scope** is the cause, turning it off allows the window to appear in all apps again.
If **Display scope** is still unreliable, set AirLyrics battery usage to **Unrestricted** in Android
settings. (Checking Don't Kill My App would be even better, but simply removing battery restrictions
can solve a lot of problems.)

For other known issues, search [GitHub Issues](https://github.com/AirLyrics/AirLyrics/issues).
