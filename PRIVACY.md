# Privacy Policy

[English](PRIVACY.md) · [简体中文](PRIVACY.zh-CN.md)

AirLyrics is designed to show floating synced lyrics on Android. Its developer does not receive personal data from the app. Optional online features connect directly to the selected services.

## Data collection

AirLyrics has no server that collects personal data.

AirLyrics does not use analytics, advertising SDKs, tracking SDKs, its own account system, or crash-reporting services. Apple Music sign-in is optional.

## Permissions

AirLyrics asks for the following Android permissions only when they are needed for app features:

| Permission / access     | Purpose                                                                              |
|-------------------------|--------------------------------------------------------------------------------------|
| Display over other apps | Shows the floating lyrics window above other apps                                    |
| Notification access     | Detects current media playback information from local notifications / media sessions |
| Notifications           | Keeps the foreground floating lyrics service visible to Android                      |
| Usage access            | Checks whether apps selected in Display scope are visible (Android 10+)               |
| Internet access         | Searches lyrics online and signs in to Apple Music when those features are used       |
| File picker             | Lets the user import or export local lyrics files                                    |

Notification access is used locally on the device to detect media playback metadata such as the playing app, title, artist, album, playback state, and available media controls. AirLyrics does not upload notification content.

Usage access is optional and used locally only for Display scope. AirLyrics does not read app content or upload usage data.

## Local data

AirLyrics may store app settings, selected media source information, Display scope app selections, local lyrics records, cached lyrics, and recent lyrics download records on the device. If you sign in to Apple Music, it also stores an account token and storefront in private app data. Apple's sign-in page may store cookies in Android WebView.

This data is used for app functionality. Online lyrics requests send the data described below directly to the selected services. Other local data stays on the device unless you export, share, back up, or otherwise transfer it.

## Online lyrics search

When the user searches lyrics online, AirLyrics sends the search request required by the lyrics provider. This may include song-related metadata such as title, artist, album, and duration.

Word-by-word searches connect directly to NetEase Cloud Music, Kugou, or QQ Music. Requests may include your query, the selected song ID or hash, and the provider's lyric ID and access key.

Except for Apple Music account requests, AirLyrics does not add account tokens, analytics identifiers, or advertising identifiers to lyrics searches.

Apple Music sign-in takes place on Apple's page inside the app. AirLyrics does not read your Apple ID password. It sends the saved account token directly to Apple to validate the account and request lyrics. Signing out of AirLyrics removes its saved token and storefront; Apple's WebView cookies may remain on the device. App backup is disabled.

When Spicy Lyrics is selected, AirLyrics uses a Spotify track ID supplied by the selected Spotify player or entered by you to request lyrics directly from the Spicy Lyrics API. Without an ID, it sends the song title to Spotify to identify the recording first. For pasted IDs, it also asks Spotify for the track title. Spicy requests use the app's publishable API key. These services receive normal network information such as the requesting IP address. No AirLyrics server is involved.

Spicy lyrics and any edits are stored in private app storage excluded from backup, for at most 30 days of use after acquisition. Expired content is unavailable and is removed on access, app startup or scheduled cleanup. Android may delay scheduled cleanup while the app is stopped. AirLyrics does not offer file export or sharing for this content.

## Third-party services

Online lyrics providers receive the requests needed to return lyrics. Apple also receives account requests when you sign in to Apple Music. Each service controls its own privacy practices.

## Children

AirLyrics is not designed to collect personal data from anyone, including children.

## Changes

This privacy policy may be updated when AirLyrics changes how permissions, local storage, or online lyrics search work.

## Contact

For questions or issues, please use GitHub Issues:

https://github.com/AirLyrics/AirLyrics/issues
