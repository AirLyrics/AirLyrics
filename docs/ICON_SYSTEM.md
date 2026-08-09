# AirLyrics icon system

Functional UI icons use local Android `VectorDrawable` resources derived from
[Material Symbols Rounded](https://github.com/google/material-design-icons).

- Render size: 24dp
- Weight: 300
- Fill: 0 (outlined/default variant)
- Optical size (`opsz`): 24
- Resource prefix: `ic_air_`
- On-accent tint: use `colorOnAccent` at 80% alpha; do not bake theme colors into paths
- Accessibility: decorative icons have no description, while icon-only actions provide one
- Brand assets such as the launcher artwork and GitHub mark remain unchanged

When adding a functional icon, use the official Rounded 24px Android vector, give it a
semantic resource name, and render it through the helpers in `ui/components/AirIcons.kt`.
The vendored Material Symbols are licensed under the Apache License 2.0; see the
[upstream license](https://github.com/google/material-design-icons/blob/master/LICENSE).
