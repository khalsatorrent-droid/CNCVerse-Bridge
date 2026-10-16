# CNCVerse Bridge (personal fork)

CNCVerse Bridge runs Cloudstream extensions as a Stremio / Nuvio add-on on your own Android device or computer. Compared with the original it is built for links that start fast, seek well and stay in a sensible order.

- **Media server:** links that need headers, a Referer or cookies are downloaded by the device, kept on disk and served to the player with seeking and a read-ahead buffer, so playback keeps going while the source is slow. HLS playlists and segments are cached too.
- **Better lists:** sorted by real resolution, then fast links first (FSL, Pixeldrain, workers, HLS), with similar links grouped. Names keep their full details; dead and non-seekable links are hidden.
- **FORCE REFRESH:** the first entry of every list clears the saved results of a title and searches again.
- **Anime:** every known title name is searched, with season and episode numbers translated between databases; hardsub / dub / softsub are labelled.
- **Cloudflare:** checks are solved once in a WebView and the cookie is reused for later requests.
- **Upstream add-ons:** merge another Stremio add-on, including self-hosted ones on your home network, which are served through the bridge so they play away from home.
- **Tunnel:** free relay link or your own Cloudflare Tunnel for HTTPS in Stremio.
- **History tab:** every request, timing and the exact list sent, copyable as text.
- **Backup & updates:** one-file backup of all settings and extensions; in-app updates from this repository's releases.

Why use it: fewer dead links, less buffering, a tidier list, and one place for Stremio, Nuvio and desktop. See [CHANGELOG.md](CHANGELOG.md) for every change.

> **Personal fork.** This is a personal fork kept for the maintainer's own use. All credit for the original app goes to its original developer, **NivinCNC**: without his CNCVerse Bridge, none of the features added here would have been possible. This fork only builds on his work.

> **Made with AI, no responsibility.** The changes in this fork were written with AI (Claude) and are provided **as is**, with no warranty of any kind. Expect bugs. The maintainer takes no responsibility for any damage, data loss, account problems or other consequences of using it, or for the content of any extension, add-on or website it connects to. Use it at your own risk and only for content you have the right to access.

## Downloads

CNCVerse Bridge is available for Android and Desktop (Windows/Linux).

Go to the **[Releases](../../releases)** page to download:
- **Android:** Download the `.apk` file.
- **Desktop:** Download the `.msi`/`.exe` for Windows or the `.deb` for Linux (Debian/Ubuntu and derivatives).

---

## Getting Started (Android)

1. **Install and Run:** Install the downloaded `.apk` and open the CNCVerse Bridge app.
2. **Start Server:** The app will run a local server in the background and display an addon URL on the screen (e.g., `http://127.0.0.1:8080/manifest.json`).

### Usage with Stremio

1. Copy the addon URL provided in the CNCVerse Bridge app.
2. **Important:** Stremio needs HTTPS: set up your Cloudflare Tunnel (see [Stremio Mode](#stremio-mode-https-through-your-own-cloudflare-tunnel)) and enable the "Stremio Mode" toggle.
3. Open the **Stremio** app and go to the **Addons** section.
4. Paste the copied URL into the search bar or addon URL field.
5. Tap **Install** to add the CNCVerse Bridge addon.

### Usage with Nuvio

1. Ensure the CNCVerse Bridge app is running in the background.
2. Open the **Nuvio** app.
3. Nuvio will automatically detect the local CNCVerse Bridge addon—no manual URL pasting is required!

---

## Getting Started (Desktop — Windows & Linux)

1. **Install and Run:** Install the `.msi` (Windows) or `.deb` (Linux), or run the desktop app from the distribution folder.
2. The app will launch and display the server status and the local addon URL.
3. **Same-Device Streaming:** If you run Stremio on the same computer, you can click the **Add to Stremio** button or manually add `http://127.0.0.1:8080/manifest.json` in Stremio.
4. **Local Network Streaming:** You can also use the Desktop app to host the bridge for other devices on your Wi-Fi network. Simply use the local IP address shown in the app (e.g., `http://192.168.1.100:8080/manifest.json`) on your TV or phone.

### ⚠️ Desktop Limitations

Currently, the Desktop version lacks full **WebView support**. This means:
- Features relying on Cloudflare bypass will use the system browser (Chrome/Chromium/Edge/Brave are auto-detected on Linux) instead of a hidden WebView.
- FebBox / ShowBox login flows that require a web interface open in the system browser.
For full compatibility with these specific providers, please use the Android version.

---

## Stremio Mode (HTTPS through your own Cloudflare Tunnel)

Stremio (especially Stremio Web) needs an HTTPS addon URL. Stremio Mode serves the bridge through **your own** Cloudflare Tunnel and domain:

1. Add your domain to Cloudflare (the free plan is enough).
2. In the Cloudflare dashboard open **Zero Trust → Networks → Tunnels → Create a tunnel** (Cloudflared).
3. Copy the token from the install command (the long text after `--token`).
4. Under **Public Hostname** add e.g. `bridge.yourdomain.com` → service **HTTP**, URL `localhost:8080`.
5. In the app open **Settings → Cloudflare Tunnel**, paste the token and the hostname, and save.
6. Turn on **Stremio Mode** on the Server tab. Your addon URL is `https://bridge.yourdomain.com/manifest.json`.

The app downloads `cloudflared` on first use.

---

## What's in the app

- **Server** — start/stop, addon URLs for Nuvio (LAN) and Stremio (HTTPS), Stremio Mode
- **Extensions** — repositories, search & install, update all, install a whole repo, switch extensions on/off, per-extension settings
- **Logs** — live log with copy/clear
- **Settings** — Cloudflare Tunnel, streams (resolutions, CAM, size limits, non-seekable files, subtitles, sorting, extension priority), catalogs (hide all or pick which ones; genres come from each home page, refreshed every 30 min), stream formatter (on by default, presets and preview), stream cache

---

## Changes since the fork

What this fork adds or fixes compared with the original CNCVerse Bridge (it forked on 10 Oct 2026; releases 1.2.0 → latest). The full per-release list is in [CHANGELOG.md](CHANGELOG.md).

### New features

- **Media server** - links that need a Referer / cookies / headers are downloaded by the device with those headers, cached on disk and served to the player with seeking while the download runs. HLS playlists and segments are cached and fetched ahead; links are signed (no open proxy). Files are kept in 2 MB pieces and only a window around the playing position stays on disk; read-ahead up to 2 GB (default 512 MB), cache up to 5 GB (default 2 GB), up to 8 parallel connections per file. The playback position is checked every 5 s; when half of the read-ahead window or less is ready, every connection is brought back to refill it. Many non-seekable links (Pixeldrain mirrors and others) are made seekable; extension-local m3u8 links (GoTaku hardsub and similar) are cached on the device.
- **Streams and sorting** - results are sorted by real resolution ("Hide links below", default 720p); adaptive HLS links get their real resolution (or their best rendition); every link shows where it came from (matched extension title, file name, episode used); Sub / Dub / Hardsub labels for anime with hardsub first and an *Anime audio* setting; identical links listed once; new formatter fields and reworked default layouts; Upstream add-on links keep their own names.
- **FORCE REFRESH entry** - first entry of every list: clears the saved results of that title and searches again, to get rid of stale results (can be hidden in Settings).
- **Anime** - every known name of a show (AniList, Kitsu, MyAnimeList, TMDB) is searched with fuzzy matching and season checks; TMDB season / episode numbers are translated to AniList numbers; faster lookup that no longer cuts the search short.
- **Cloudflare and network** - Cloudflare checks are solved in a hidden WebView (shown in a window when you need to tap), the cookie is saved with its User-Agent and reused; sites with a challenge are queued and listed in Settings with their status; real DNS-over-HTTPS fallback (Cloudflare, Google, Quad9); free Cloudflare quick tunnel without a token; optional **fixed link** through the pencarimovie relay (`<id>-tunnel.pencarimovie.com`) with a random per-device id and a reset button. Challenges are recognised the way the Raghav Nepu extension does (cf-mitigated header or the "Just a moment" page), the saved cookie is sent on every request to the site and shared by its other hosts, and the link probes use it too.
- **Pixeldrain** - file links go through the fast mirror, and its redirect is resolved to the real CDN link before sharing.
- **Upstream add-ons** - merge the streams of another Stremio add-on (for example your pencarimovie server for Telegram). Links to a home-network address (192.168.x.x and the like) are served through the bridge so they also play away from home, and the add-on's full descriptions are kept.
- **History tab** - every request with counts, timing and extensions; open one to see the exact list that was sent, with links; long press a result to copy it as text.
- **Backup & import** - every setting, repository and installed extension in one JSON file.
- **Updates and releases** - update dialog that follows this fork's releases (Update / Later / Skip); a one-click Release workflow builds APK, MSI, EXE and DEB; a Check workflow compiles both apps and runs the tests on pull requests; six extension repositories are added by default; the Android server is kept alive (wake locks, battery-optimisation exemption, restart after swipe-away).
- **Switches** - "Hide links that need headers", "Load the next episode in advance", "Show results once", "Show refresh results button", "Hide non-seekable / non-playable files".

### Bugs fixed

- Movies with no results (a cut-off emoji in a link name made the whole list fail to send); lists that failed to send are retried without subtitles.
- "0 links" although extensions had found links; slow or empty answers in Nuvio (the search now runs on its own and keeps filling the cache).
- Sorting by quality (the quality of each link was never passed on, so everything sorted alphabetically).
- AnimePahe and other extensions with saved settings (settings were unreadable to them; the ksoup library was missing); anime searches that gave up after the first spelling.
- Free tunnel failing on the second start, and the "api.trycloudflare.com" error.
- Media server: TV buffering that never ended (several player connections overwrote one "last read" position), failed extra connections not being replaced, a probe at the end of the file pulling the download away, heavy cache-size checks.
- Seekable links wrongly hidden by the non-seekable filter (the check ran before Pixeldrain links were switched to the fast mirror); Google Drive download-only links now recognised and hidden.
- Cloudflare checks looping on a stale cookie.
- GoTaku hardsub (extension-local) links starting a complete new search on every request, and being shown as dead links after a restart.
- Log warnings: `authUser`, `AniListApi$CoverImage`, `WebViewResolver`.

## Building the APK on GitHub

Upload the project to a GitHub repository (branch `main`). The *Build APK* workflow (`.github/workflows/build-apk.yml`) builds an installable APK; get it from the run's *Artifacts* or from the *latest* release. To sign with your own key, add the repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; without them the sideload key in `ci/` is used.

## Building from source

```bash
./gradlew :androidApp:assembleRelease                        # Android apk (signed when a keystore is configured)
./gradlew :desktopApp:packageReleaseDistributionForCurrentOS # Windows .msi/.exe or Linux .deb
./gradlew :desktopApp:createDistributable                    # desktop portable folder
```

### Releases

Every release gets a new version number. Start **Actions -> Release -> Run workflow**: it raises the version by one patch step (`1.5.9` -> `1.5.10`, the Android `versionCode` +1; choose *minor* or *major* for a bigger step), turns `## Unreleased` in `CHANGELOG.md` into `## 1.5.10`, commits that, tags `v1.5.10`, builds everything with GitHub Actions (`.github/workflows/release.yml`) and publishes it. The release notes are the changelog section plus the commit list, so **write what changed under `## Unreleased` in `CHANGELOG.md` before releasing**.

You can also bump by hand and push the tag yourself - the tag must match `version.properties`:

```bash
scripts/bump-version.sh           # patch step; or: minor / major
git commit -am "Release v1.5.10" && git tag v1.5.10 && git push origin main v1.5.10
```

### Updates inside the app

On start the app looks at the releases of this repository. If a newer version with a file for your platform exists, a dialog shows its release notes and offers **Update**, **Later** or **Skip this version**. The rolling *latest* build is not a version and is never offered.

---

## License

The original CNCVerse Bridge code is **all rights reserved** by its original author (see the [LICENSE](LICENSE) file). This fork does not change that license and grants no extra rights: it is shared only as a personal project, and any use, copying or redistribution of the original code needs the original author's permission.

Files that come from the Cloudstream project keep their own licenses and copyright notices and are not covered by the license above. Other third-party libraries keep their own licenses too.

This fork's additions were produced with AI assistance; the AI-generated parts carry no warranty and no promise of fitness for any purpose.
