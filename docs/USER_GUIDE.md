# User Guide

[English](USER_GUIDE.md) · [简体中文](USER_GUIDE.zh-CN.md)

Thank you for reading this guide!

## Table of Contents

1. [First Launch](#first-launch)
2. [Select a Player](#select-a-player)
3. [Use Floating Lyrics](#use-floating-lyrics)
4. [Lyrics and Local Files](#lyrics-and-local-files)
5. [Permissions](#permissions)
6. [Troubleshooting](#troubleshooting)

## First Launch

1. Open **Settings > System** and enable **Overlay** and **Notification access**. If you want to use
   notification controls, also enable **Notifications**.
2. Start playing music, open **Media**, and manually select the player AirLyrics should follow.
3. Open **Floating**, then tap **Show** in the bottom bar. Granting overlay permission alone does not
   display the floating window.

## Select a Player

Use the **Media** page to choose the player AirLyrics follows. AirLyrics remembers this choice and
uses that player's current song when searching for, importing, or removing lyrics.

AirLyrics only attempts to select a player automatically when it starts. After granting notification
access, please select the player manually, even if it already appears on the **Media** page.

If no player appears, start playing music, check that notification access is enabled, and tap
**Refresh**.

## Use Floating Lyrics

- When **Click-through** is enabled, use **Adjustment mode** in the notification to make the window
  draggable and responsive to touch again.
- **Hide when paused** hides the window when playback is paused and shows it again when playback
  resumes.
- **Display scope** (Android 10+) shows the window only while a selected app is visible. Tap
  **Grant usage access**, choose apps with **Choose apps**, then tap **Enable**. Apps in split-screen,
  freeform windows, and picture-in-picture also count as visible.
- **Display blocked** means floating lyrics are still enabled, but current conditions prevent the
  window from appearing. It will appear automatically when those conditions clear. While display is
  blocked, tapping the notification opens AirLyrics, but display and adjustment controls are unavailable.
- **Lyrics offset** is saved for each song and does not change the original lyrics file.
- To use **Word-by-word lyrics**, import a local lyrics file. When this option is enabled and the
  current song has word-by-word lyrics, **Highlight color** marks the part of the current line that
  has already played.

## Lyrics and Local Files

Manage lyrics in **Settings > Lyrics**. AirLyrics always looks for lyrics in this order:
manual import > local cache > online search.

- Use **Current song lyrics** to import or remove lyrics. **Search online** skips the current
  plain lyrics cache.
- Open lyrics from **Recent local lyrics** to check their format, make changes, and save.
- Uninstalling AirLyrics also deletes the default lyrics folder. To use a custom folder, select it
  through the system file picker.

Plain and word-by-word lyrics for the same song cannot be managed independently. Remove any existing
plain lyrics before importing word-by-word lyrics. AirLyrics then generates a plain LRC file and
updates or removes it when the word-by-word lyrics are edited or removed.

See [Lyrics Format](LYRICS_FORMAT.md) for supported LRC and TTML examples.

## Permissions

- **Overlay** allows AirLyrics to display the floating window over other apps.
- **Notification access** allows AirLyrics to detect media playback on the device.
- The **Notifications** setting enables the floating lyrics notification and its controls. It is separate from
  notification access.
- **Usage access** is used only when **Display scope** is enabled, to check whether the selected apps
  are visible. AirLyrics does not read or upload app content.

## Troubleshooting

**The floating window does not appear:** Check that a player is selected and overlay permission is
enabled, then open **Floating** and tap **Show** in the bottom bar. Granting permission alone does not
display the window.

**The notification says “Display blocked”:** Common causes include the current app being outside
**Display scope**, playback being paused with **Hide when paused** enabled, the screen being off or the
device being locked, or missing **Overlay** or **Usage access** permissions. Open a selected app,
resume playback, and check the relevant permissions.
If **Display scope** is the cause, turning it off allows the window to appear in all apps again.
If **Display scope** is still unreliable, set AirLyrics battery usage to **Unrestricted** in Android
settings.

For other known issues, search [GitHub Issues](https://github.com/AirLyrics/AirLyrics/issues).
