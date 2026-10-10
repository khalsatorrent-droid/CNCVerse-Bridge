# Changelog

## 1.2.1

- The app no longer shows update dialogs (update checks are switched off).
- Android: wake locks, a one-time battery-optimisation exemption request and a restart after swiping the app away, so the system stops closing the server.
- Release workflow signs with the sideload key when no signing secrets exist, and can create its own tag when started by hand.

## 1.2.0

### Anime
- Kitsu, MAL, AniList and AniDB ids are resolved to every title a show is known by (English, romaji, synonyms), so extensions such as AniPM and Anidap find more shows.
- Fuzzy title matching with season handling (e.g. "Season 2", "2nd Season") and flexible episode numbering, so the right episode is picked, including absolute numbering.
- New setting, Settings -> Streams -> *Anime audio*: subbed first, dubbed first, or hide the other kind.

### Stream quality
- Adaptive HLS links get their real resolution by reading the master playlist, instead of being listed as "Auto" at the bottom.
- Optional one-entry-per-resolution listing for adaptive links (1080p / 720p / 480p ...), with the adaptive original kept last.
- New resolution cap ("Best quality for this device"): links above it are listed last.
- New option to hide streams of unknown quality.
- Resolution tiers are detected from width and height, so letterboxed films (e.g. 1920x800) count as 1080p; playlists without a resolution fall back to their bitrate.

### Backup and import
- Settings -> Backup & import saves everything to one JSON file and restores it, also on another device: stream filters and sorting, quality and anime options, catalogs, formatter templates, stream-cache and media-server settings, repositories, enabled / disabled extensions, extension settings and the list of installed extensions.
- Option to leave logins, tokens and cookies out of the backup.
- After an import, missing extensions are reinstalled automatically.
- Android uses the system file picker; other platforms can copy and paste.

### Media server (new)
- Settings -> Media server. Links that need a Referer, cookies or other headers are downloaded by this device with those headers, kept on disk, and served to the player from there, so videos that would not play directly now work.
- Seeking works while the download is still running; the download does not run far ahead of what is being watched.
- HLS playlists and segments are cached and fetched ahead; cookies set by the source are kept per source.
- Stream links are signed, so other devices cannot use the server to fetch arbitrary URLs.
- Cache limit: never above 2 GB (adjustable between 256 MB and 2048 MB). When space is needed, previously cached items are deleted, least recently used first; items older than the "keep hours" setting are also removed. The item being played is never deleted; a file too big for the cache is relayed live instead.
- Modes: only links that need headers, or all links.

### Build
- New GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds an installable APK. It uses your signing secrets if set, otherwise the sideload key in `ci/`.
- Version raised to 1.2.0 (Android version code 10).

### Notes
- This release has not been compiled by its author; the first CI run is its first build.
