# 🌉 CNCVerse Bridge (personal fork)

> **In one sentence:** this app turns your phone, tablet or computer into a little **movie-and-TV link finder** that Stremio and Nuvio can use, and this fork makes it faster, tidier and a lot more reliable. 🍿

**New here?** Jump to [🚀 Quick start](#-quick-start-5-minutes). **Curious what changed?** Read [✨ What this fork adds](#-what-this-fork-adds-in-plain-words).

> 🙏 **Personal fork.** All credit for the original app goes to its original developer, **NivinCNC**: without his CNCVerse Bridge, none of the features added here would have been possible. This fork only builds on his work.
>
> 🤖 **Made with AI, no responsibility.** The changes in this fork were written with AI (Claude) and are provided **as is**, with no warranty of any kind. Expect bugs. The maintainer takes no responsibility for any damage, data loss, account problems or other consequences of using it, or for the content of any extension, add-on or website it connects to. Use it at your own risk and only for content you have the right to access.

---

## 🤔 What is this, really?

Think of it as a **waiter** 🧑‍🍳:

1. You tell Stremio or Nuvio "I want to watch *that movie*".
2. The Bridge (the waiter) runs many small helper programs, called **extensions**, which each go and look on a different website.
3. It collects all the links they find, throws away the dead and bad ones, puts the best on top, and hands you a **neat menu** 📋.
4. You tap one and it plays.

The fork adds a **kitchen** 🍳 (the *media server*) that can prepare a link for you in advance so the video starts fast and does not stutter, plus a **website** 🌐 you can open on a laptop, and many small things that make the menu nicer.

---

## 🚀 Quick start (5 minutes)

### 📱 On an Android phone or tablet (the easiest way)

1. Open the **[Releases](../../releases)** page and download the `.apk` file. 📥
2. Install it and open **CNCVerse Bridge**. (Android may ask you to allow installs from your browser: that is normal for apps outside the Play Store.)
3. Tap **Start**. The app shows an address like `http://127.0.0.1:8080/manifest.json`. That is your "add-on address". 🏠
4. Add some extensions: open the **Extensions** tab, pick a repository, and install what you like. (Eight repositories are already added for you, including Watchuu.) 🧩
5. **Using Nuvio?** Just open it. It finds the Bridge by itself, nothing to paste. ✅
6. **Using Stremio?** It wants a secure (`https`) address. Turn on **Stremio Mode** on the Server tab. With no setup the app gives you a free temporary `https` link. Copy it into Stremio under **Add-ons**, and tap **Install**. ✅

### 💻 On Windows or Linux

Download the `.msi` / `.exe` (Windows) or the `.deb` (Linux) from [Releases](../../releases), install it, and open it. The window shows the address to use. On the same computer, the **Add to Stremio** button does it for you. For a TV or phone on your Wi-Fi, use the computer's local address shown in the app (like `http://192.168.1.100:8080/manifest.json`). 📺

> ⚠️ The desktop version has no built-in web view, so a few things (Cloudflare checks, FebBox / ShowBox logins) use your normal browser instead. For those, the Android app is smoother.

### 🕰️ Keeping it running all day

An old tablet 📱 that stays plugged in is perfect. Turn off battery optimisation for the app, keep it charging, and (if you want to reach it away from home) use the tunnel settings below. The app already asks Android to keep it alive.

---

## 🌍 Using it away from home: tunnels

A **tunnel** is a private doorway from the internet to your device, so Stremio can reach it anywhere. You have four choices:

| Option | Effort | Link stays the same? |
|---|---|---|
| 🆓 **Free temporary tunnel** | none, just turn on *Stremio Mode* | no, it changes every start |
| 📌 **Free tunnel + fixed link** | one switch | yes, with the relay |
| 🏷️ **Free tunnel + *your own* name** | one switch and type a name | yes, e.g. `https://merimarzi-tunnel.pencarimovie.com` |
| 🔐 **Your own Cloudflare Tunnel** | a few steps (below) | yes, on your own domain |

> ℹ️ The fixed-link relay belongs to the pencarimovie-server author and is for **personal use only**. It is shared by everyone, so choose a name nobody can guess.

### 🔐 Your own Cloudflare Tunnel (for people with a domain)

1. Add your domain to Cloudflare (the free plan is enough).
2. In the Cloudflare dashboard open **Zero Trust → Networks → Tunnels → Create a tunnel** (Cloudflared).
3. Copy the token from the install command (the long text after `--token`).
4. Under **Public Hostname** add e.g. `bridge.yourdomain.com` → service **HTTP**, URL `localhost:8080`.
5. In the app open **Settings → Cloudflare Tunnel**, paste the token and the hostname, and save.
6. Turn on **Stremio Mode** on the Server tab. Your add-on address is `https://bridge.yourdomain.com/manifest.json`.

The app downloads `cloudflared` on first use.

---

## 🧭 A tour of the app

| Tab | What it is for |
|---|---|
| 🖥️ **Server** | Start / stop, your add-on addresses, Stremio Mode |
| 🧩 **Extensions** | Find, install, update and switch extensions on / off; per-extension settings |
| 📜 **Logs** | A live diary of what the app is doing (copy it when you need help) |
| 🕘 **History** | Every request, how long it took and exactly what was sent |
| ⚙️ **Settings** | Everything else: tunnel, streams, subtitles, media server, website, catalogs, formatter, backup |

---

## ✨ What this fork adds, in plain words

### 🍿 Better link lists
- 🥇 **Sorted by real quality**: the best resolution first, and inside each, the fast kinds of links first. Links that only *claim* to be HD are checked and shown with their real resolution.
- 🗑️ **Dead links hidden**: broken, download-only and non-seekable files can be hidden, and a "hide links below 720p" setting keeps junk out.
- 🎚️ **Only the kind of link you want**: "Hide HLS links" and "Hide direct files" remove every m3u8 link or every plain video file from movie and series results (both off by default; live TV keeps its links).
- 🏷️ **Every link says where it came from**: the extension, the matched title, the file name and the episode, so you can tell similar links apart.
- 🧹 **No clutter**: the same link is listed once, and giant subtitle lists become a short "63 languages".
- 🔄 **FORCE REFRESH entry**: the first item of every list shows how many results were found, how many *each* extension found, and whether the search is still running. Tap it to clear that title and search again.
- 🎛️ **Stream formatter**: choose how every line looks, with presets and a live preview.
- ⏭️ **Next episode ready in advance** (optional) and **"show results once"** when you prefer one complete list.

### ⚡ The media server: smooth playback
Many links only play with special "headers" or cookies, and many websites are slow. The Bridge can fetch the video **for** your player:
- 💾 It downloads ahead (a "read-ahead" buffer) and keeps only what is needed on disk, so seeking and skipping work.
- 🚦 **Playback starts after the first few MB**, then the buffer keeps filling while you watch.
- 🔌 Several connections download at once, and it speeds up by itself when the buffer runs low.
- 🧪 **It tests whether a link really needs headers.** Most extensions attach headers to everything, so the Bridge now asks the website with a plain request; only links that really need help go through the media server.
- 🏠 Slow self-hosted sources (a Telegram server, a NAS at home) get gentler settings so they don't choke.
- 🔥 Optional (off by default): the first couple of links in a list are "warmed up" in the background so they start sooner (switch: *Warm up the first links*).
- 🔐 Links are signed, so it is not an open proxy for strangers.
- 🔓 In **"Every link"** mode it also serves the bridge's clear-key DASH channels and the links of other Stremio add-ons.

### 🌐 The website version (`/home`)
Don't want an app on the laptop? The Bridge can host its **own website** 💻:
- 🔑 Switch it on in **Settings → Website version** and set a password.
- 🎬 Browse a TMDB catalogue, search, open a title and play it in the browser.
- ⏱️ **Links appear as each extension answers**, with a "Still searching: 12 of 30 sources" line.
- 📺 **Live TV tab**: every TV channel from all your catalogues in one A-Z list, with favourites, recently watched, letters, search, channel up / down, and quick start. If a link is stuck, it moves on to the next by itself.
- 🎞️ **Short dramas page**: every short-drama extension in one list, a chip per extension, and one search across all of them.
- 🎵 **Music page**: lossless music from the Monochrome extension (Watchuu repository), with song, album and artist search.
- 🧩 **Search inside every extension**: switch the search box to "In: Extensions" to see each extension's own results, open a title and get its links from that extension only.
- 🔊 **Audio switching**: picks the audio track inside HLS / DASH streams and inside MKV / MP4 files with several audio tracks (its own engine plays those files when the browser supports their codecs).
- 🎛️ **Its own player**: seek bar with buffer and preview, quality menu, subtitles from every extension, sleep timer, skip intro, link switching while playing, tap to show or hide the controls.
- 📚 **Library**: Browse page with filters, Continue watching, My list, watched marks and recent searches.
- 📱 Works on phones too (with a proper search button).
- 🎨 A cleaner look: featured title, rows you scroll, "recently opened", season tabs and episode cards.
- ⌨️ Keys: space, arrows, F (full screen), M (mute), A (audio), N / P (next / previous channel), Esc.

### 📺 Tidier catalogues in the add-on
- 📺 **"All TV channels (A-Z)"**: one catalogue that merges the channels of every TV catalogue. The separate TV catalogues are no longer listed (switch: *One combined TV catalog*). Pick which TV extensions go into it, and the ones you leave out keep their own catalogues (**Settings → Catalogs → Which extensions go into the TV catalog**); the website's Live TV follows the same choice.
- 🎞️ **"Short dramas"**: one catalogue that takes turns between all short-drama extensions (pick one as its genre). Short-drama extensions are found from their name and description and can be changed by hand in **Settings → Catalogs**.

### 💬 Subtitles
- 🌍 Pick your languages (e.g. `English, Hindi`): they come first, or are the *only* ones.
- 🧽 Duplicates removed.
- ⏲️ Optional **delay** if subtitles are out of sync.
- ✍️ **Styled English subtitles**: the first 10 English subtitles are downloaded by the bridge and sent as ASS files with one look (white bold text, black outline, bottom centre). They are also kept on the device, so they still load after a long pause.
- 📱 **Nuvio mobile**: it cannot show ASS from add-ons, so each styled track also comes as a bold SRT copy ("· Nuvio mobile") that Nuvio draws with its own style.
- 🏷️ **Readable names** like `#2 English · CC · ASS styled · Show.S01E01.1080p`, and for anime the CC tracks come first.

### 🎌 Anime made easier
- 🔎 Every known name of a show (AniList, Kitsu, MyAnimeList, TMDB) is searched, with fuzzy matching and season checks.
- 🔢 Season and episode numbers are translated between databases, so "S2 E5" finds the right episode.
- 🏷️ **Sub / Dub / Hardsub** labels (only for anime), hardsub first, and an *Anime audio* setting.

### 🛡️ Cloudflare and network
- 🧩 Cloudflare "checking your browser" pages are solved once in a hidden web view, and the cookie is reused for later requests (also across a site's other hosts).
- 🌐 A real **DNS-over-HTTPS** fallback (Cloudflare, Google, Quad9) when your network's DNS fails.
- 🔒 **DNS over HTTPS for every request** (Settings → DNS over HTTPS): 16 resolvers to pick from (Cloudflare, Google, Quad9, AdGuard, OpenDNS, NextDNS, CleanBrowsing, Mullvad, DNS.SB, Control D, LibreDNS and their filtered versions) or your own address, with an optional fallback to normal DNS.

### 🔗 Other add-ons and special hosts
- ➕ **Upstream add-ons**: merge another Stremio add-on (like your own Telegram server). Links to a home-network address are served through the Bridge so they also play away from home.
- 💧 **Pixeldrain** links use the fast mirror and are made seekable.

### 🧰 Handy extras
- 🗂️ **Backup & import**: all settings, repositories and extensions in one file.
- 📊 **Extension report card** (Extensions screen): grades every extension A to F from how often it gave links, how many, how fast it answered (search to links) and when it last worked. Titles an extension simply does not have are not held against it. One tap switches off the dead ones (D or F), and *Reset* starts the counting over.
- 📶 **Bitrate in the results**: each link with a known size shows its bitrate (size / running time).
- 🚫 **No popups**: the donation popup follows the "support the project" switch (off by default), and popups that extensions open on their own (donation / Telegram ads) are closed at once (switch: *Block extension popups*).
- 🩺 **Stays up 24/7**: a watchdog restarts the Stremio Mode tunnel when it dies, extensions load several at a time and never stay at 0, and the notification shows the live status.
- 🔔 **In-app updates**: a dialog shows the release notes with *Update / Later / Skip*.
- 🕘 **History tab** with a long-press "copy as text".
- 🔋 Android: the server is kept alive (wake locks, battery-optimisation exemption, restarts after swipe-away).
- 🤖 One-click release workflow and automatic checks for every change (for the maintainer).

> 📚 Want every detail, release by release? See **[CHANGELOG.md](CHANGELOG.md)**.

---

## 🆘 Something is wrong?

| Problem | Try this |
|---|---|
| 🟡 List is empty or short | Wait a little (some sites answer late), then tap **FORCE REFRESH** |
| 🧩 Extension finds nothing | Update extensions; check the **Logs** tab for errors |
| 🐢 A video starts slowly | Turn on the **media server** in Settings; pick *"links that need headers"* |
| 🔁 Buffering on a slow source | Raise the read-ahead a bit in the media server settings |
| 🔒 Stremio refuses the address | It needs `https`: turn on **Stremio Mode** |
| 🌐 Website won't open | The device must be awake and the tunnel running; use the address shown in *Website version* |
| 🧯 Anything else | Open **Logs**, copy the lines, and share them when you ask for help |

---

## 🛠️ For developers: building the APK on GitHub

Upload the project to a GitHub repository (branch `main`). The *Build APK* workflow (`.github/workflows/build-apk.yml`) builds an installable APK; get it from the run's *Artifacts* or from the *latest* release. To sign with your own key, add the repository secrets `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; without them the sideload key in `ci/` is used.

## 🛠️ Building from source

```bash
./gradlew :androidApp:assembleRelease                        # Android apk (signed when a keystore is configured)
./gradlew :desktopApp:packageReleaseDistributionForCurrentOS # Windows .msi/.exe or Linux .deb
./gradlew :desktopApp:createDistributable                    # desktop portable folder
```

### Releases

Every release gets a new version number. Start **Actions -> Release -> Run workflow**: it raises the version by one patch step (`1.6.0` -> `1.6.1`, the Android `versionCode` +1; choose *minor* or *major* for a bigger step), turns `## Unreleased` in `CHANGELOG.md` into `## 1.6.1`, commits that, tags `v1.6.1`, builds everything with GitHub Actions (`.github/workflows/release.yml`) and publishes it. The release notes are the changelog section plus the commit list, so **write what changed under `## Unreleased` in `CHANGELOG.md` before releasing**.

You can also bump by hand and push the tag yourself - the tag must match `version.properties`:

```bash
scripts/bump-version.sh           # patch step; or: minor / major
git commit -am "Release v1.6.1" && git tag v1.6.1 && git push origin main v1.6.1
```

### Updates inside the app

On start the app looks at the releases of this repository. If a newer version with a file for your platform exists, a dialog shows its release notes and offers **Update**, **Later** or **Skip this version**. The rolling *latest* build is not a version and is never offered.

---

## 📄 License

The original CNCVerse Bridge code is **all rights reserved** by its original author (see the [LICENSE](LICENSE) file). This fork does not change that license and grants no extra rights: it is shared only as a personal project, and any use, copying or redistribution of the original code needs the original author's permission.

Files that come from the Cloudstream project keep their own licenses and copyright notices and are not covered by the license above. Other third-party libraries keep their own licenses too.

This fork's additions were produced with AI assistance; the AI-generated parts carry no warranty and no promise of fitness for any purpose.
