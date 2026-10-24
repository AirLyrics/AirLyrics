# OpenCC dictionaries

The following files are from [BYVoid/OpenCC](https://github.com/BYVoid/OpenCC), revision
`3ac34aa439a9908dd49fa92b5174b46314787ac2`, licensed under Apache-2.0 (see `LICENSE`):

| File | Role |
| --- | --- |
| `TSCharacters.txt` | character-level traditional → simplified mapping |
| `TSPhrases.txt` | phrase overrides for characters that are ambiguous on their own (乾隆, 瞭解) |
| `TWVariantsRevPhrases.txt` | Taiwan variant phrases that must not be rewritten (著名, 著作) |
| `TWVariants.txt` | variant pairs; inverted at runtime to map 著 → 着 |

Together they reproduce OpenCC's `tw2s` configuration. Only the dictionaries are bundled, not the
OpenCC engine: lyrics are short strings, so a longest-match lookup over these tables is enough and
keeps the APK small.

They are used to compare Chinese text (is this translation a script conversion of the original?)
and to render the simplified/traditional display preference.
