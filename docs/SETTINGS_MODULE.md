# Settings module

This package is the single home for user preferences.

- `ThemeSettingsStore` stores app-wide visual mode, such as dark mode.
- `FloatingLyricsStyleStore` stores floating-window style, position, lock, and click-through behavior.
- `LyricsSettingsStore` stores lyrics lookup source and local-save behavior.
- `QuickFloatingStore` stores the center navigation button's remembered floating-window state.
- `model/` contains data-only setting contracts shared by UI, services, and controllers.

When adding a new setting, prefer this flow:

1. Add a field to a model in `model/` when the setting belongs to a grouped config.
2. Add read/write methods in the matching `*Store`.
3. Let UI pages call the store instead of touching `SharedPreferences` directly.
4. Let services/controllers load the same model instead of duplicating keys.
