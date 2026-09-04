# User Guide

[English](USER_GUIDE.md) · [简体中文](USER_GUIDE.zh-CN.md)

Thank you for reading this guide!

## Table of Contents

1. [First Launch](#first-launch)
2. [Select a Media Source](#select-a-media-source)
3. [Use Floating Lyrics](#use-floating-lyrics)
4. [Lyrics and Local Files](#lyrics-and-local-files)
5. [System Settings](#system-settings)
6. [Troubleshooting](#troubleshooting)

## First Launch

1. Open **Settings > System**. Enable **Overlay** and **Notif. access**. Enable **Notify** if you
   want notification controls.
2. Start playing music, open **Media**, and select the player AirLyrics should follow.
3. Open **Floating**, then tap **Show** in the bottom bar. Granting overlay permission alone does not
   display the floating window.

## Select a Media Source

The **Media** page selects the player that AirLyrics follows. This selection is saved and also scopes
current-song lyrics actions.

Automatic selection is attempted only when AirLyrics starts. After granting notification access,
select the player manually even if its media session is already visible.

If no player appears, start playback, confirm that notification access is enabled, and tap
**Refresh media status**.

## Use Floating Lyrics

Appearance, lyrics display, animation, behavior, and window position are saved automatically.

- **Display control** provides show/hide, drag lock, and click-through controls. Notification
  **Adjustment mode** makes the window draggable and touchable again.
- **Auto hide/show** hides the window while playback is paused and restores it when playback resumes.
- **Display scope** (Android 10+) limits the window to selected visible apps. Choose apps, grant
  usage access, then enable it. Split-screen, freeform, and picture-in-picture count as visible.
- **Lyrics offset** is saved per song and does not modify the original lyrics files.
- **Word-by-word lyrics** use local imports. When enabled and available, **Highlight color** marks
  the elapsed part of the current line.

## Lyrics and Local Files

Open **Settings > Lyrics** to manage lyrics. Lookup priority is fixed:
manual import > local cache > online search.

- **Search strategy** controls online fallback, auto-save, and the Local only, NetEase, or
  Musixmatch source.
- **Current song lyrics** can import or remove lyrics. **Search online again** bypasses the current
  plain lyrics cache; show the floating window before using it.
- **Recent local lyrics** can be opened to check their format, edit, and save.
- The default lyrics folder is removed when AirLyrics is uninstalled. A custom folder must be
  selected through the system file picker.

Plain and word-by-word lyrics cannot be imported as two independently managed versions of the same
song. Remove existing plain lyrics before importing word-by-word lyrics. AirLyrics then generates a
plain LRC fallback and keeps it synchronized when the word-by-word lyrics are edited or removed.

See [Lyrics Format](LYRICS_FORMAT.md) for supported LRC examples.

## System Settings

- **Overlay** allows AirLyrics to draw the floating window over other apps.
- **Notif. access** allows AirLyrics to detect local media playback.
- **Notify** displays foreground-service controls; it is separate from notification access.
- **Usage access** is optional and only checks whether apps selected in **Display scope** are
  visible. AirLyrics does not read or upload app content.
- **Language** selects system, English, or Simplified Chinese mode.
- **Hide status pop-ups** hides AirLyrics Snackbar and Toast messages.

## Troubleshooting

**The floating window does not appear:** confirm the selected player and overlay permission, then
open **Floating** and tap **Show** in the bottom bar. Permission alone does not show the window.

**Display scope is waiting:** open a selected app, or check usage access. Disabling **Display
scope** restores the normal all-app behavior.

For other known problems, search [GitHub Issues](https://github.com/AirLyrics/AirLyrics/issues).
