# AirLyrics image assets

- `airlyrics_icon_official.png`: official app icon source image with transparent background.

Generated Android resources are placed under `app/src/main/res/`:

- launcher icons: `mipmap-* / ic_launcher.webp`, `ic_launcher_round.webp`
- adaptive icon foreground: `drawable-nodpi/airlyrics_launcher_foreground.png`

Splash/startup artwork is intentionally not kept. AirLyrics now starts directly from the launcher icon into `MainActivity` without a custom splash screen.
