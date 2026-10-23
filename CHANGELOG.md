# Changelog

## Unreleased

- **Website player: hardcode the subtitles into the picture (optional).** New switch "Hardcode subtitles into the picture" in the player's More menu and in Subtitles > Look of the subtitles. When it is on, the subtitle text is painted onto the video itself in the classic movie style: white bold letters with a black outline at the bottom centre (size, colour and background still follow your Look settings). Off by default.
- **Website player: English subtitles by default, closed captions for dubs.** English is picked on its own (as before, unless you chose another language or Off). When an anime plays with its English dub (the link says dub, or the English track is playing in a file that also has Japanese), the player prefers the English closed-caption / SDH file, which also describes the sounds; with the original audio it prefers the normal English subtitles. Switching the audio switches the pick too, unless you chose a subtitle by hand.

## 1.7.28

- **Website player: switch the audio inside MKV and MP4 files.** A file with two or more audio tracks (Hindi / English, ...) now plays through the player's own engine, which opens the file in pieces and gives the browser the picture plus the audio track you pick. The Audio menu lists every track by language, switching keeps your place, and seeking, the 30 second buffer and resume still work. It needs the browser to support the codecs (H.264, HEVC, VP9, AV1 with AAC, Opus, MP3, FLAC; Dolby Digital only where the browser has it). Files with one track play as before.

## 1.7.27

- **Media server in "Every link" mode serves more.** The clear-key DASH channels (the `/proxy/mpd/manifest.m3u8` links) and the links of Stremio add-ons now go through the media server too; the media server reads the converted playlists from this device, never through the internet tunnel.
- **Website player: clearer errors, better DASH-to-HLS lists.** When a link does not play, the message now ends with the exact reason (for example `manifestParsingError` or `HTTP 403`), and the converted playlists also name the audio codec, which browsers need.
- **Extension report card.** A "Report card" button on the Extensions screen grades every loaded extension A to F from how often it gave links, how many, how fast it answered and when it last worked, so you can see which ones to keep and which to switch off. A "Switch off N dead" button in the card turns off, in one tap, every extension graded D or F after at least 3 asks.

## 1.7.23

- **Server starts at once:** the web server no longer waits for the extensions to load (that took up to a minute). It starts first, the extensions load in the background and the plugin count and home pages fill in as they finish; a search that arrives meanwhile waits for them. The DNS warm-up of every extension's site no longer holds the loading back either (it runs in the background, 8 at a time), and 6 extensions load at once instead of 4.
- **History tab:** it stays light with long result lists: a request keeps at most 120 results, only the 12 newest keep their links, the list in an open card shows 30 at a time ("Show more"), and the history file is written in the background (before, every request wrote the whole file while answering).
- **Website player: links without sound or picture switch by themselves.** When the player finds that a movie / episode link has no sound or no picture in this browser (AC3, EAC3, DTS, HEVC ...), or HLS reports a format it cannot play, it takes the same title from another link that this browser can play (same language and resolution if there is one), keeps your place and shows "Switched to another link" with an Undo button. It can be turned off in Settings. A plain file still plays only one audio track in most browsers, so other languages are other links (Audio menu).
- **Website player: live TV is sturdier.** A live link is tried as HLS, MPEG-TS and plain file in turn (for links without a file ending), a picture that freezes for 15 seconds or ends is reopened up to three times before the next link of the channel is tried.
- **Website: Browse page.** New "Browse" tab with Movies / Series, genre, year and sort (most popular, newest, top rated) filters, "Show more", and a "Surprise me" button that opens a random well-rated title for the filters you set.
- **Website: recent searches.** The search box suggests your last 8 searches.
- **Website: watched marks.** Every episode has a tick (hover, or always on touch screens) to mark it watched or not, and each season has "Mark season watched / not watched".
- **Website player: sleep timer** (15 / 30 / 45 / 60 minutes or end of the episode), **Skip intro** button for the first 5 minutes of an episode (+85 s, also the S key and in the More menu) and **Cast to a TV or speaker** in the More menu where the browser supports it (Chrome / Edge).

## 1.7.22

- **Website: a new video player.** Its own control bar with a seek bar that shows what is buffered, a time preview, +/-10 s buttons (double-tap the sides of the picture on a phone), volume, speed, quality, audio, subtitles, picture in picture, full screen, a "Stats for nerds" box, lock-screen / media-key controls and many keyboard keys. Streams (HLS / DASH / live) keep only about 30 seconds buffered ahead. Where you stopped is remembered and offered next time ("Resumed at ...", "Start over").
- **Website: subtitles from all extensions for every video.** The subtitle menu lists every language any extension found for the title (versions of the same language can be tried one after another), with delay, size, colour and background settings, your last language is chosen automatically, and you can load your own .srt / .vtt file. ASS / SSA subtitles (used by many anime releases) are converted so they show too.
- **Website: audio and codecs.** The Audio menu switches the audio tracks inside HLS / DASH streams and, for plain files (where a browser can only play one track), lists the other links of the same title by language (Hindi, English, Tamil ...) and switches to one while keeping your place. Links that mention formats this browser cannot play (HEVC, DTS, Dolby Digital Plus, AV1) are tagged "May not play here" and listed after the others of the same resolution (a switch in Settings); if a file still has no sound or no picture, the player says why and offers VLC. Live .ts / .flv streams are played with mpegts.js and .mpd with dash.js (both loaded only when needed).
- **Website: live TV.** A LIVE badge, a "Go live" button and a 90 second rewind window, a channel list inside the player, and links that stall are skipped for the next one.
- **Website: library.** "Continue watching" and "My list" rows, Resume / Continue buttons, watched marks and progress bars on episodes, the next episode starts by itself after a 10 second countdown (Settings), a trailer button, cast, "More like this", more home shelves (genres, anime, Hindi cinema, new releases) and a Settings window.
- **Stremio Mode fixes itself.** A watchdog checks the Cloudflare tunnel every 30 seconds. If it dies, never gets a link or stops answering, Stremio Mode is switched off and on again (with longer waits after repeated failures).
- **Extensions load faster and no longer stay at 0.** On Android four extensions load at a time instead of one by one, and the app no longer waits forever for a screen when it runs in the background (that wait was what left it at 0 extensions).

## 1.7.21

- **Warm-up is now optional:** the bridge no longer opens the first 2 links of every result list on its own. It only does so when the new "Warm up the first links" switch (Settings, Media server) is on, which it is not by default.

## 1.7.20

- **Stays working overnight:** a watchdog now checks every 2 minutes. If extensions are installed but none is loaded (which left a tablet running overnight with a working website but dead extensions), it reloads them by itself, and it restarts the web server if that ever stops. A failed plugin load no longer leaves things half-way, and the "N plugins" count on the Server screen now follows what is really loaded.

## 1.7.19

- **Website player: "no sound" notice:** some Cloudflare R2 and Pixeldrain files carry audio the browser cannot decode (AC3, EAC3, DTS), so they played silently. The player now notices this a few seconds in and shows a notice with Open in VLC and Copy link buttons, so you can play the file with sound in a media player.
- **Switch link while playing:** the player now has a Links button for movies and episodes too. Pick another link and it carries on from the same point.

## 1.7.18

- **Website player upgrade:** new Quality menu (Auto or a fixed resolution such as 1080p / 720p, remembered), Captions menu with English subtitles switched on automatically when the link has them (or your last choice; `C` toggles them), Audio menu as before, and a Speed menu. Volume is remembered. Buffering is faster to start and reads further ahead (up to 90 s, retries on slow segments) and plain video files preload.

## 1.7.17

- **History tab fixed:** the History entry is now always written, even when the player hangs up before the answer. The first answer is back to waiting at most 38 s (the longer wait of 1.7.16 made players give up first, so nothing was recorded); extensions still search up to 150 s and keep loading in the background.

## 1.7.16

- **Anime: many more names searched:** an anime is now looked up under all its names - more AniList synonyms, the best few AniList matches (not just the first), TMDB's alternative titles, and the usual respellings ("Lv999" / "Lv 999" / "Level 999", without a leading "The"). Titles known by many spellings (e.g. The Villager of Level 999) now find far more links.
- **Extensions get more time to search:** each extension may now search up to 150 s (was 80 s) and finishes on its own; the first answer waits a little longer (up to 45 s, at least 20 s for the extensions), and slower extensions keep loading in the background for up to 170 s so their links show on the next refresh.

## 1.7.15

- **Default repository eclipsia/cloudstream: working address:** the Gitea address added in 1.7.14 did not work. The default is now `https://cs.eclipsia.dpdns.org/repo.json`; an install that got the old address has it replaced once at the next start (new installs just have the new one).

## 1.7.14

- **New default repository: eclipsia/cloudstream** (`https://gitea.com/eclipsia/cloudstream/raw/branch/main/repo.json`). New installs have it from the start; an existing install gets it once when it is updated (if you remove it later it stays removed).

## 1.7.13

- **Your own id for the fixed free-tunnel link:** in Settings, under the fixed link switch, type an id of your choice (4-12 lowercase letters or digits, e.g. `merimarzi`) and press "Use this id": the link becomes `https://merimarzi-tunnel.pencarimovie.com`. The relay is shared by everyone, so pick an id nobody can guess. The random id and the "New fixed link" button still work.

## 1.7.12

- **Media server, "only links that need headers": the headers are now tested, not assumed:** almost every extension attaches a Referer / User-Agent to its links whether or not the host checks it, so most of a list still went through the media server (150 of 244 links in one list). The bridge now asks the host: it requests the link the way a plain player does, without the extension's headers - for a stream the playlist, the variant playlist and the first segment, for a file a one-byte range - and links of hosts that answer are left to play directly. Hosts that refuse (403, a login page, a refused segment) or do not answer within 3.5 s are still served by the media server, so a check can only cost speed, never a working link. The answer is remembered per host (2 hours when no headers are needed, 20 minutes when they are) and shows in the log as `[Headers] host: no headers needed` or `headers needed`. In "HLS" mode the same test applies to the direct files. The first list after a start may still send more links through the media server while the hosts are being tested.

## 1.7.11

- **Self-hosted add-on on the same device now goes through the media server:** a link to another service on this device's own address (the Telegram-backed PencariMovie server at 192.168.x.x:8088 next to the bridge on :8080) was taken for the bridge itself and left untouched, so the media server settings never applied to it. Links are now compared by address and port. The seek check, the quality check and the host label in the link list had the same mistake.
- **Media server: links with brackets or spaces in the file name were skipped:** a link such as `…/[Fibwatch.Com]VisionQuest.S01E01.1080p.mkv` or `…/VisionQuest S01E01 [Hindi DDP 5.1] x265.mkv` (FibWatch, UHDMovies / Driveseed workers) made Java's strict URL parser fail, so the bridge could not read its host and left it out of the media server even with "every link" set. Hosts are now read with the same parser the downloads use. Not affected by design: DASH (.mpd) links (they use the bridge's own DASH proxy) and links whose address is a placeholder like `hdstream4u.*`.

## 1.7.10

- **Media server: slow streams start sooner:** when a list of links is shown, the first two direct-file links are opened in the background and their first 8 MB are saved (then the connection is let go, and a video that is already playing is never disturbed). By the time a link is chosen, the slow part of its start - a source that first has to fetch the file (a Telegram-backed server), redirects, the TLS handshake - is already over, and playback begins from disk.
- **Media server: a cold file gets time to answer:** the first request of a file now waits up to 100 s for the source's first byte (it was 45 s), so a self-hosted server that has to resolve a cold Telegram file first no longer ends in "The source did not answer in time". For a slow ("heavy") source the extra connections also wait up to 10 s for the main one, so a cold file is not asked for by several connections at once.
- **Media server log:** a line like `[MediaServer] host answered HTTP 206 after 3.2 s: 1158 MB, seekable, heavy source` shows how fast and how seekable each source is.

## 1.7.9

- **Website: Live TV no longer loads for ever:** a link that does not start within 8 seconds, fails, or freezes for 12 seconds while playing is skipped for the channel's next link (and when no other link has answered yet it moves on as soon as one does). If none of them work, the player says so instead of showing the loading note.

## 1.7.8

- **Media server: playback starts before the read-ahead is filled:** at the start of a video (and after a seek) the extra download connections used to share the bandwidth with the main one while filling the whole read-ahead window, so the first piece the player needed arrived slowly. Now the main connection gets all the bandwidth until about 6 MB are ready (the others wait at most 3 seconds), playback begins, and the extra connections then fill the read-ahead window while it plays.

## 1.7.7

- **Media server: the 30-minute home page refresh waits while you watch:** it asks every extension's site for its catalogs at once (35 home pages, many timing out after 15 s), which took bandwidth and CPU from the video. It now waits until nothing has been streamed for 20 seconds (at most 15 minutes).
- **Media server: no more wrongly slowed hosts:** since 1.7.1 a host whose requests took over 1.5 s to start answering was treated as a "heavy" source and limited to 3 connections with long requests. That time included DNS and TLS set-up (eight new connections at once on a phone), so ordinary fast hosts were slowed down, and the flag hardly ever went away. The first requests to a host are no longer counted, at least 4 real answers are needed before a host is judged, one slow answer counts for little, and the judgement is forgotten after 5 minutes without requests. Hosts on the local network are still always treated as heavy.

## 1.7.6

- **Website, search on a phone:** the search box now has a Search button (and the keyboard's search key works), and the header wraps on a narrow screen: logo, Movies & series, Live TV and Log out on the first row, the search box on its own row below, so nothing is pushed off the screen.
- **Website, Live TV keeps loading:** HLS channels are now always served through this bridge for the website's player. A browser only lets the player read a playlist from a server that sends CORS headers, which almost no channel host does, so the player waited for ever on direct links (media server mode "headers" / "hls" or HLS caching off made it worse). The player also retries a stalled network or media error before giving up on a link, and says "Buffering" when a stream stays empty for 4 seconds.

## 1.7.5

- **Website, Live TV starts much faster:** a channel starts playing as soon as its first source answers (the other sources keep loading as fallbacks, no more waiting for the slowest one); the quality and seek checks are skipped for live channels; links are looked up in advance while you hover over a channel; a link that has not started after 9 seconds is skipped for the next one; the player buffers less for live streams.
- **Website: links appear as the extensions find them:** opening a movie or episode on the website now shows the first links as soon as one extension answers and keeps adding more while the search runs, with a line like "Still searching: 12 of 30 sources answered, 9 links so far" that turns into "Search complete" at the end. Only the website works this way; Stremio and Nuvio get the same answer as before.

## 1.7.4

- **Website, Live TV:** channels with the same name from several sources are one tile (with a "2 sources" tag); landscape logo tiles; a star on each tile adds it to Favourites; "Recently watched" and Favourites rows on top; clicking a channel starts it straight away (no list first) and falls back to the next link on its own if one does not play; Prev / Next buttons (keys P and N) switch channels without leaving the player; "Links" opens the full link list.
- **Website, audio language:** a new "Audio" button in the player lists the audio languages of HLS streams (and of files whose audio tracks the browser can list); the pick is remembered for next time (key A opens it). A plain MP4 / MKV file with two audio tracks cannot be switched by Chrome or Edge: use its HLS link, or the link labelled with the language you want.

## 1.7.3

- **Live TV tab in the website version (/home):** a "Live TV" button next to the logo opens every channel of the "All TV channels (A-Z)" catalog in alphabetical order, with a channel search, A-Z / # letter filters and a "Show more" button; click a channel to play it (a choice of links appears when it has several). "Movies & series" goes back to the TMDB catalog.

## 1.7.2

- **"📺 All TV channels (A-Z)" catalog:** one catalog that merges the channels of every TV catalog in the manifest (the ones you have not switched off) into a single list in alphabetical order, leading symbols and emoji ignored, so Stremio (web too) shows all live channels in one place and search works across all of them. It sits in front of the other TV catalogs and can be switched off in Settings -> Catalogs like any other.

## 1.7.1

- **Website redesign:** new look for /home with a featured title on top, rows you scroll with arrows, a "Recently opened" row, season tabs and episode cards, a side panel for links with resolution filters, a player with auto-hiding controls and keyboard keys (space, arrows, F, M, Esc), and a back button that works through the browser history. Works on phone width too.

## 1.7.0

- **Website version (/home):** a new "Website version" card in Settings switches on a small website served by the bridge on this device: open `your-tunnel-address/home`, enter the password you set, and you get the TMDB catalog (trending, popular, top rated), search, title pages with seasons and episodes, the same link list the add-on gives, and a built-in player (MP4 and HLS, subtitles shown bold white with an outline). Off by default; five wrong passwords lock that address out for a minute. Links that need a header or cookie play best with the media server on.

## 1.6.3

- **"Clean subtitle look" removed:** Nuvio and most players draw subtitles in their own style, so the option did nothing useful. Subtitles are only re-served by the bridge when a delay is set; set the look in the player's own subtitle settings.

## 1.6.2

- **Clean subtitle look now works in more players:** with "Clean subtitle look" on, subtitles are handed to the player as ASS files (bold off-white text, thin dark outline and soft shadow, bottom centre, no box) instead of WebVTT with a `::cue` style, which Nuvio and other players ignore. Timing offset and language filters still apply.
- **FORCE REFRESH entry:** "20 with none" now reads "20 found nothing" (that many of the sources answered but had no result for the title).

## 1.6.1

- **Subtitles:** set your subtitle languages (Settings > Streams, e.g. `English, Hindi`) and they come first in every list; "Only those subtitle languages" drops the rest (kept when none match). Copies of the same subtitle file from different extensions are removed. A subtitle delay in milliseconds (negative = earlier) moves every cue: the bridge fetches the file, shifts it and serves it as WebVTT (SRT is converted; ASS files are left as they are). "Clean subtitle look" (on by default) serves subtitles as WebVTT with bold white text, a black outline and no box, in players that follow WebVTT styling; if the file cannot be read here the player gets the original link.

## 1.5.37

- **The real resolution is shown instead of "Auto":** a link whose own label carries no resolution (HubCloud / Driveseed style `[WEB-DL] [392.69MB]`) now takes it from the file name in its address (`...480P.Web-Dl...mkv`), so the title, sorting and the resolution filters see 480p / 720p.
- **No more subtitle spam:** a result with more than three subtitle languages says `Subs: 63 languages` instead of listing three and "+60 more".

## 1.5.36

- **"FORCE REFRESH" entry shows the state of the search:** the first entry of every list now says how many results were found (and how many are shown), whether the search is complete or still running (`⏳ 15/22 source(s) done - reload the list`), and how many results each extension / upstream add-on found.
- **Heavy sources no longer buffer (self-hosted PencariMovie / Telegram, NAS...):** a source on your home network, or one whose requests take over 1.5 s to start, gets at most 3 connections instead of 8, requests that end by themselves (64 MB, extra connections take 4 segments = 32 MB in one request) and is restarted for a refill only when the player is about to run dry. Eight parallel requests plus restarts used up the few workers of such a server (each request also starts a Telegram download), so the playback stalled.

## 1.5.35

- **Media server refills earlier:** when half of the read-ahead window (it was an eighth) or less is ready after the player's position, every extra connection is brought back and the main download is pointed at the first missing piece after the ready data, so the window is full again long before the player reaches it. Checked every 5 seconds.
## 1.5.34

- **Media server checks the player every 5 seconds** (was 20) and, when less than an eighth of the read-ahead window is ready right after the playback position, points the download at the hole under the player and brings every extra connection back ("catching up" in the log), so playback does not wait for the sequential download to reach it. HLS segments after the current one are queued on the same beat.
- **Cloudflare checks use the Raghav Nepu extension's logic:** a challenge is recognised by the `cf-mitigated` header, or by a 403 / 503 / 429 whose page says "Just a moment" / challenge-platform (not only by the Server header), the old challenge cookies (`cf_chl_rc_ni`, `cf_chl_prog`) are wiped before a new check, and a saved clearance is sent on every request to the site.
- **Saved Cloudflare cookies reach more requests:** one clearance now serves every host of the same site (`cdn.`, `api.`, `www.`), and the link-quality and seek probes send it too.
- **README** now says this is a personal fork, credits the original developer, lists what this fork adds, and carries the license and "made with AI, no responsibility" notes; the original project's community and donation links are gone.
## 1.5.33

- **Links that arrive JSON-escaped now play.** Some extensions (Anizone) hand back a link like `https:\/\/host\/path\/master.m3u8` with backslashes. It was neither recognised as an m3u8 nor opened by players; the backslashes are removed as soon as the result comes in, so such links go through the media server like any other.
## 1.5.32

- **Sort: FSL links written in fancy letters are grouped too.** HubCloud names such as "𝗙𝗦𝗟 𝗦𝗲𝗿𝘃𝗲𝗿" (mathematical bold letters) were not recognised as FSL and fell into the workers group; the letters are folded to plain ones before matching, and Cloudflare R2 links in a `/hub/` or `/hub2/` bucket count as FSL.

## 1.5.31

- **Upstream add-on results keep their full details.** The `description` text of an upstream stream (file name, size, languages... the multi-line part newer add-ons send) and its `filename` / `videoSize` hints were dropped, leaving only a short name like "telegram • PencariMovie 1080p". They are kept and shown now.
- **Upstream add-ons on your home network now play away from home.** A self-hosted add-on that answers with links like `http://192.168.x.x:8088/...` used to hand those links to the player unchanged, which only works on the home Wi-Fi. Links to a private address (10.x, 172.16-31.x, 192.168.x, *.local) are now served by this bridge through the media server, so they play through the tunnel / relay link too.

## 1.5.30


- **Default sort: fast links first inside each resolution.** fsl, then pixeldrain, then workers, then adaptive m3u8 (fast but lower quality), then other direct links; similar links stay together. The resolution order is unchanged.
- **Cloudflare cookies are actually used now.** The saved cookie (and the User-Agent it was solved with) is sent on every request of every extension to that site, not only for extensions that bring their own Cloudflare handling. A challenge on any site now lands in Settings -> Cloudflare checks so it can be solved ahead of time, and a refused cookie is dropped and the site marked expired.
- **Links of sites with a saved Cloudflare cookie go through the media server** in every mode, since only it can send the cookie to the site (a player cannot).

## 1.5.29

- **Sort grouping: fsl links are found by their name too.** Links whose address says nothing (`r2.dev`, `r2.cloudflarestorage.com`) but whose name says FSL ("Hub-Cloud[FSL Server]", "FSLv2 1080p") now go into the fsl group; other r2.dev / cloudflarestorage links join the workers group.

## 1.5.28

- **History: long press on a request copies the whole list** (title, then every result with its link) as text; a long press on a single result still copies just that one.

## 1.5.27

- **Default sort: quality, then similar links together.** Inside each resolution the links are now grouped by kind: all fsl links, then all Pixeldrain, then all workers.dev, then other direct files (grouped by site), then Telegram / the bridge's own relay, then adaptive m3u8 links (grouped by site), then torrents. Inside a group the size and extension-priority settings still apply. Settings → Streams → Sort: "Quality, then similar links together" (default) and "Quality, link type, biggest file"; "By extension" is unchanged.

## 1.5.26

- **Android: the bridge no longer crashes after running for hours:** its background service was declared as "data sync", and since Android 15 the system stops such a service after about 6 hours and crashes the app if it does not stop in time. It is now declared as a "special use" service (no time limit). The app also asks for a larger memory heap (long lists of 500+ links from 20+ extensions are heavy), writes an uncaught error to the log on the next start and logs why Android ended the previous run (crash, out of memory, ...), so a remaining crash can be traced.

## 1.5.25

- **"FORCE REFRESH" button:** the first entry of every stream list (not a video: tapping it clears the saved results of that title and starts a complete new search that saves the fresh results; then reload the list). Gets rid of stale results. Can be hidden in Settings → streams ("Show refresh results button").

## 1.5.24

- **Gotaku hardsub (extension-local) links no longer make every request search again:** a result list containing a `127.0.0.1` link used to be left out of the stream cache completely, so each request started a new full search (and the problem came back each time). Those links are now cached in memory for up to 45 minutes together with the rest of the list, never saved to disk, left out when they no longer answer and have no copy on this device, and removed one by one (not the whole list) when the cache is loaded after a restart.

## 1.5.23

- **New fixed link (Settings → tunnel):** a button gives this device a new random id for the pencarimovie fixed link, so the address changes (use it when the old link got out). The old address stops leading here; install the Stremio add-on again with the new one.

## 1.5.22

- **Media server fixes after a review:** extra download connections that ended after an error are now replaced one by one (before, they were restarted only when all of them were gone, so playback could fall back to a single slow connection and buffer); a failed segment no longer ends an extra connection; a probe connection further on in the file (a player reading the end of the file) can no longer pull the download away from the playing part; the cache-size check no longer walks every piece file for each 8 MB downloaded (less CPU on a TV box).

## 1.5.21

- **Extension-local m3u8 links are cached on the device (gotaku hardsub and similar `http://127.0.0.1:port/.../master.m3u8`):** the playlist is downloaded the moment the result is made, while the extension's own server is running, saved on the device and served by the media server (kept in memory for hours, with a copy on disk), together with the rendition playlists and the first segments. The link no longer depends on the extension's proxy staying reachable. When it can't be cached it still goes out through the plain relay (Logs tab shows why).
- **Media server checks the playback position every 20 seconds** (files: the position of the player's connections; HLS: the last segment asked for) and tops up the read-ahead after it: a stopped downloader is started again, extra connections are restored, the next segments are queued.

## 1.5.20

- **Media server: no more endless buffering on a TV:** a TV player reads a file over several connections at once (the video, plus the end of the file for the index). The server kept one "last read" position that these connections overwrote in turn, so it deleted the data behind the wrong position and downloaded it again and again - playback stalled at some point and kept buffering. It now tracks every connection and keeps the data around the lowest recently active one. The file is still downloaded only within the read-ahead window, never as a whole.
- **Media server limits:** the cache can now be set up to 5 GB (default stays 2 GB); read-ahead defaults to 512 MB and can be set up to 2 GB. A saved 1 GB read-ahead from 1.5.16 / 1.5.17 moves to 512 MB once.
- **History shows the links:** every result in an opened history entry now lists its link, and a long press on a result copies it as text (name, description and link). "Copy list" includes the links. Links are kept for the newest 40 entries.
- **Links into an extension's own server no longer "stop working" after a restart (gotaku hardsub and similar):** those links (127.0.0.1:port) die when the app or extension restarts, but they were saved in the stream cache and shown again. Lists that contain them are no longer cached (and old ones are dropped), so they are fetched fresh. The relay now writes what went wrong into the log (Logs tab).
- **Sub / Dub / Hardsub only for anime:** movies and ordinary series no longer get a Sub / Dub / Hardsub label in the link description.
- **Download-only links are hidden by "Hide non-seekable / non-playable files":** Google Drive download addresses (video-downloads.googleusercontent.com, drive.google.com/uc?export=download) are recognised from the address, without a network check that Google often refuses.
- **New switch "Hide links that need headers":** with the media server off, links that play only with a Referer / cookie / custom header are removed (your player cannot send them). With the media server on they stay, since it sends the headers itself.

## 1.5.18

- **Cloudflare checks: a stale cookie is no longer taken for a solved check:** the in-app browser keeps its cookies between checks, so when a site refused the saved cookie and the check was solved again, the old `cf_clearance` (e.g. issued 23 h ago) was found at once and saved as the "new" answer - the site showed "Solved" and "challenged" at the same time and the loop repeated. Now the old cookie is removed before the check starts, a cookie that is the same as before or was issued long before the check is ignored, and a fresh cookie that the site still refuses is not marked "Solved" (it stays in the queue and the log says so). The log now also says when a check was solved, and the card shows "Solved 5 min ago" once and warns when the cookie is older than the check.
- **Defaults follow the settings you use:** "Load the next episode in advance" is off by default; the media server read-ahead default is 128 MB again (an installed 1 GB value from 1.5.16 / 1.5.17 moves back to 128 MB once). Connections stay at 8.

## 1.5.17

- **Faster media server downloads:** the extra connections now fetch 8 MB at a time instead of 2 MB (each request costs a round trip, so small pieces left connections idle part of the time). Together with the changes since 1.5.16: up to 8 parallel connections per file (new "Connections" setting, 1-20; Pixeldrain bypass sites suggest 4-20 / 8 or 16 for download managers), 2 MB pieces on disk so watched parts are freed sooner, and a 1 GB read-ahead window by default.

## 1.5.14

- **Fixed link id is unique to each install:** new ids are "cb" + 10 random characters from a secure random source (was cb + 6), so two devices can never end up with the same fixed link; existing ids stay as they are.
- README states that this is a personal fork whose new features were added with AI.

## 1.5.13

- **Fixed link for the free temporary tunnel (optional):** Settings → Cloudflare Tunnel has a new switch. When on, the temporary `….trycloudflare.com` link is registered with the pencarimovie-server relay and you get a permanent address `https://<your id>-tunnel.pencarimovie.com` that redirects to it - the Stremio add-on URL no longer changes between runs. The fixed address is only shown once it really answers; otherwise the temporary link stays. The relay's owner allows this for personal use only (no monetising), so it is off by default.

- **Media server: many more links now seek instead of being downloaded whole:** when a link answers the open-ended range with the whole file, the media server now also tries (in order) the file its redirects end at (a CDN node with its own address), and a range in the middle of the file (some servers only ignore ranges starting at 0). Where the server honours closed ranges only, downloads and seeks ask for "bytes=N-(size-1)" instead. Only if none of this works is the link downloaded in order, and the log now says `NOT SEEKABLE` with the full link.

- **"Hide non-seekable / non-playable files" no longer hides links that do seek:** the check ran on the address the extension gave, *before* Pixeldrain links were switched to the fast mirror. A `pixeldrain.com/u/ID` address is a web page, so it was judged "not a file" and hidden, although the mirror it becomes (`cdn.pixeldrain.eu.cc/ID`, which redirects to a changing `cdnNN.pixeldrain.eu.cc/api/file/ID`) is a seekable file. The mirror is now applied first and the check follows the redirect to the file it leads to. Also, a server that answers the one-byte test with the whole file but says `Accept-Ranges: bytes` is kept (the media server checks again when it plays); only a server that gives no sign of seeking is hidden.

## 1.5.12

- **Episode shown with the file name:** the "File" line of every result now starts with the episode the extension used for that link - its number and name as the extension lists it, e.g. `Ep S2E8 · The Demon King • File: Clevatess.S02E08.1080p.mkv` - so you can see at a glance whether the right episode was picked. All four built-in layouts carry it; a saved copy of the previous default layout moves to the new one automatically. New variables for your own layouts: `{stream.fileline}` (episode and file) and `{stream.matchepisode}` (the episode alone).
- **Seeking in Pixeldrain links (media server):** the media server asked for the file with an open-ended range ("bytes=0-"), and a server may answer that with the whole file (allowed) and still seek fine - such a link was wrongly downloaded in order, so jumping ahead had to wait for the download. Now a plain answer is checked with a two-byte range request first. If the fast mirror (cdn.pixeldrain.eu.cc) really cannot seek, the media server switches to Pixeldrain's own address of the same file (pixeldrain.com / .dev / .net `/api/file/ID`), which can: seeking works and only the part that is watched is downloaded. A source that cannot seek at all is still downloaded in order (the log says so).

## 1.5.11

- **Each link shows its file name:** next to the matched extension entry, the link description now has the name of the video file the link plays ("📄 File: Clevatess.S02E08.1080p.WEB-DL.mkv"), so you can check that it is the right file. It is read from the end of the link, from a `file=` / `filename=` part of the link, or from the link's own name when that is a file name; adaptive streams (HLS) and links without a file name get no file line. Built into all four layouts, and a new formatter variable `{stream.file}` for your own layouts. A saved copy of the 1.5.10 built-in layout is moved to the new one; custom layouts are left as they are.

## 1.5.10

- **More results from anime-specific extensions:** the anime name lookup (AniList, Kitsu, MyAnimeList, TMDB) is no longer cut off after 8 s - in the log that showed this it needed about 37 s, so extensions that search by title (and do not use TMDB) were only searched with the plain title and found less. Now: (1) the lookup is faster - the prequel and sequel walk of a franchise and the Kitsu / MyAnimeList name requests run side by side instead of one after the other; (2) it gets 25 s before the extensions are started with the plain title; (3) if it still finishes later, every extension is searched again with all the anime names and the exact season entry, and the new links are added to the answer (if it is still open) or to the cache, so reloading shows them.
- **Each link shows where it came from:** the name of the entry the extension matched is now part of the link description ("🔎 Matched: Clevatess Season 2" in the CNCVerse layout, "Matched: ..." / "Match: ..." in the Torrentio, Essentials and MediaFusion layouts), so you can see at a glance whether the extension found the right title. New formatter variable `{stream.match}` for your own layouts. A saved copy of the previous built-in layout is moved to the new one; custom layouts are left as they are.
- **Faster media server:** a file now downloads over six connections at once (was four) in 8 MB parts (was 16 MB), so the first seconds of playback and seeks fill up sooner; connections to a site are kept open and reused (before, all but five were closed after each part, costing a new connection handshake every time); the player and the downloader notice new data after 10-15 ms (was 40-50 ms); up to 12 HLS segments are fetched ahead at once (was 8); a failed or repeated HLS segment download no longer leaves an entry behind in the segment lock list.
- **Faster list for HLS links:** the quality check of adaptive HLS links reads 32 playlists at once (was 8). With 40+ such links the answer used to wait for several rounds (3.6 s in the log); now it waits for one.

- **Update dialog (follows the khalsatorrent-droid fork):** when a newer release of the fork is published, the app (Android and desktop) now shows "Update available" with the release notes and three choices: **Update** (downloads the installer for your platform and starts it), **Later** (asks again at the next start) and **Skip this version** (never asks for that version again; a newer one is still offered). Only real version releases count - the rolling "latest" build can no longer hide them. The app now knows its real version (it was stuck at 1.1.0 in the code); it is generated from `version.properties`.
- **Every release gets a new version:** the *Release* workflow (Actions -> Release -> Run workflow) raises the version by one patch step (1.5.9 -> 1.5.10, versionCode +1; or minor / major), commits it, tags it and publishes the APK, MSI/EXE and DEB. The release notes are this changelog's section of the version plus the commit list. `scripts/bump-version.sh` does the bump, `scripts/release-notes.sh` the notes.
- **Check workflow:** pull requests now compile both apps and run the unit tests.

- **"0 links" although the extensions found links - cause found:** the anime name / episode lookups (AniList, Kitsu, MyAnimeList, TMDB) that run before the extensions are started had no time limit. When one was slow (32 s in the log that showed this) it used up the whole 32 s answer time, so the extensions had no waiting time left and the request was answered with 0 links at once, while the 235 links they found a moment later only reached the cache. Now: (1) these lookups get 8 s, after that the search goes on with the plain title (the lookup keeps running in the background and is cached for the next try); (2) the extensions are always given at least 12 s before the answer cap can end the wait, and the wait ends 2 s before the 38 s hard limit so a late answer is still sent.

## 1.5.9

- **Faster media downloads:** a file now downloads over four connections at once (the main one and three extra ones, each taking the next missing 16 MB part inside the read-ahead window, starting at the playing position). Pieces on disk are 128 MB each, and pieces more than 128 MB behind the position are deleted. Only for sources that can seek; others still use one connection.

## 1.5.8

- **Media server keeps only what is needed:** a downloaded video is now stored in 8 MB pieces. Only a window around the playing position stays on disk: pieces more than 128 MB behind the position are deleted while you watch, and the download runs only as far ahead as "Read-ahead MB" (default now 256 MB, was 1 GB). A file bigger than the whole cache no longer has to fit - it plays from the moving window (only a source that cannot seek still needs the full file to fit). Seeking back into a deleted part simply fetches it again.
- **Old partial downloads are deleted by time:** entries not used for the "Delete unused after (hours)" setting (default 24) are removed, checked every 10 minutes (before, only after a download finished or the app started). The 2 GB cap still applies, oldest unused first.

## 1.5.7

- **Movies showed no results - cause found:** one link's name contained a cut-off emoji (half of a character), which cannot be written as text, so the whole list failed with "MalformedInputException". Such broken characters are now removed from every link before the list is sent (and the movie list is no longer lost).

## 1.5.6

- **Movies showed no results:** the list was found (hundreds of links) but never sent. Preparing and sending the list is now fail-safe: a link that cannot be prepared goes out as it is, a failed send is retried without subtitles, and any error is written to the log with its cause ("[Request] ... failed").
- **Pixeldrain:** `pixeldrain.com / .dev / .net` file links (`/u/ID`, `/api/file/ID?download`) are sent as `https://cdn.pixeldrain.eu.cc/ID` (Settings -> Streams -> "Pixeldrain fast mirror", on by default).

## 1.5.5

- **Cloudflare blocks (themoviesflix.forum and similar):** many of these have no check box at all - the site just refuses requests that do not look like a browser, and a "cf_clearance" cookie never appears. Now: (1) the request is first repeated with the full header set of a Chrome page load (as Raghav's TheMoviesFlix does); (2) if that is still refused, a WebView loads the page and the check counts as solved as soon as the page is a real page (not just when a cookie shows up); (3) if the site only lets the WebView in, the extension gets the page the WebView loaded. The tap-the-box window still appears after 15 s for real checks.

## 1.5.4

- **Empty answers fixed:** 1.5.3 gave up after 24 s counted from the request, but looking up the title took the first seconds and the extensions were slow (Cloudflare checks), so the player got 0 links although 176 were found a little later. Now: the answer goes out as soon as the first links are found (waiting 4 s more so quick ones join), and otherwise keeps waiting up to 32 s from the request (hard limit 38 s) instead of answering empty. Anything found later is added to the cache; reloading shows the full list.

## 1.5.3

- **Slow / missing answers in Nuvio (LAN):** the stream search now runs on its own, so a player that gives up (or the new 24 s limit) no longer cancels it - it finishes into the cache and the next try is instant. A request is never held longer than 24 s. The quality check of HLS links is limited to 4 s. The log shows where the time went for each request ("search ..s, quality probe ..s, seek check ..s").
- **Maximum results:** the first answer still goes out early (as soon as something is found, at most 18 s), but slow extensions keep working in the background for up to 90 s (was 45 s) and add their links to the cache as they finish. Reloading the title in the player shows the longer list at once.

## 1.5.2

- **Free tunnel:** no longer tries to keep the same link. Free quick tunnels cannot keep one, and reusing the old one made the second start fail. Every start now makes a fresh tunnel (retried once if Cloudflare is slow); the new link is shown in the app. For a fixed link use your own tunnel token and domain.
- **Nuvio over Wi-Fi:** the first answer is now sent after at most 18 s (was 26 s) in case Nuvio gives up earlier; the late results are in the cache for the next try.
- **Request log:** every manifest and stream request now shows in the log ("[Request] streams for series/tt...: from <address>" and "sent N link(s)") so it is clear whether the player's request reaches the bridge and what it got back.

## 1.5.1

- **Real DNS-over-HTTPS:** the "DoH fallback" only asked the phone's own DNS again. It now really asks Cloudflare (1.1.1.1 / 1.0.0.1), Google (8.8.8.8 / 8.8.4.4) and Quad9 over HTTPS by IP when the phone's DNS fails or answers 0.0.0.0 / 127.0.0.1 (DNS-blocked sites like desicinemas.to), IPv4 first. Applies to extensions, the media server and the tunnel.
- **Cloudflare challenges (as in Raghav's extensions):** the check is first tried in a hidden WebView; if it does not pass in 15 s (a "tap the box" check), the same WebView is shown in a window over the app so you can finish it. The clearance cookie is saved with the WebView's exact User-Agent and reused for every later request to that site (6 h). The window is shown at most once per site every 5 minutes.

## 1.5.0

- **Sorting fixed at the root (Android):** the quality of each link was never passed on, so every link looked like "unknown quality" and was sorted alphabetically. Quality, link type and server are now passed on, so the list goes from the highest resolution to the lowest. Links are also read for their resolution from the name when nothing else says.
- **Minimum resolution setting:** links known to be below the chosen resolution are hidden (default 720p, still the minimum of 1.4.3) (Settings -> Streams -> "Hide links below": show all / 480p / 720p / 1080p). Links of unknown quality stay and are listed after the known ones. The list is never emptied by this filter.
- **Season checks (anime):** for a later season, the names tried include "Title Season 2", "Title 2nd Season", "Title II" and "Title 2", and the season is checked against the name. The entry found on a site must also have the right number of episodes (from TMDB for the season, or from the AniList / Kitsu entry) and, when the extension reports it, the same AniList / MyAnimeList id. A wrong-season entry is skipped and the next best entry is tried; if none fits, that extension shows nothing rather than the wrong season.
- **TMDB numbers -> AniList numbers (anime):** a TMDB season/episode (tt / tmdb ids) is translated to the AniList entry and the episode number inside it, by reading the franchise's TV entries (prequels / sequels) and comparing them with TMDB's season sizes. When both split the show the same way, season N is entry N; otherwise the running episode number decides (for example TMDB season 2 episode 14 = episode 2 of the second AniList part). The names and AniList / MyAnimeList ids of that exact entry are used to find it on each site, and a site that lists the episodes as one running list is read by running number. If the translated number is not found, TMDB's own numbers and the running number are tried.
- **Better names:** `{stream.host}` (the site a link comes from) tells apart links that looked identical; the name line shows resolution, extension and Sub / Dub / Hardsub; the default layout was reworked (an old saved default is upgraded automatically). Links from Upstream add-ons keep their own names and are no longer reformatted.
- **Free tunnel link:** the quick tunnel of the last run is reused on the next start, so the link stays the same for as long as Cloudflare keeps it alive. A new link is only made when the old one is gone. Cloudflare does not promise a fixed link for free tunnels without an account; for a link that never changes use your own tunnel token and domain (Settings -> Cloudflare Tunnel).

- **History tab:** every answered request is listed (title, season / episode, how many links were found and shown, the count per extension, how long it took). Tap one to see the exact list the player received (name and description of each link); "Copy list" copies it.
- **Results that did not show up:** a request that arrives while the extensions are still loading now waits for them (up to 20 s) instead of answering with 0 links, and the first answer is never held longer than 18 s (clients give up around 30 s); links that arrive later are still put into the cache for the next try.
- **Free tunnel, second start:** the saved link's credentials were overwritten by a new tunnel request before the saved link had been tried, so the second start always failed. The new tunnel is now only requested if the saved one did not connect.
- **AnimePahe and other extensions:** the ksoup HTML library (needed by AnimePahe, "NoClassDefFoundError ... Ksoup") is now included.

## 1.4.3

- **Free Cloudflare tunnel:** cloudflared is now started the way pencarimovie-server starts it - transport auto (QUIC, falling back to HTTP/2), a writable HOME, the system CA store (`/system/etc/security/cacerts`) on Android, an ingress config file, loopback origin (`127.0.0.1`) and many retries. The free link is read from cloudflared's local metrics endpoint (`/quicktunnel`) instead of only scraping the log. The app-created quick tunnel is still tried first, the metrics-based start is the fallback; each attempt is given up after 40 s.
- **Sorting:** whatever the settings, the list sent to Stremio / Nuvio is now ordered by resolution (highest first) as the very last step, so grouping, provider order, hardsub-first and anime-audio no longer break it. Inside one resolution the order from those settings is kept. Links with a known resolution below 720p are never shown; links whose resolution cannot be found are listed after the known ones.

## 1.4.2

- **Anime search no longer gives up:** when an extension's search errors on the first spelling of a title (AnimePahe breaks on some punctuation), the other names of the show are still tried.
- **Free tunnel on Android:** (the old link shown "api.trycloudflare.com" was an error, not a tunnel) the quick tunnel is now created by the app itself (cloudflared cannot look up Cloudflare's address on Android), then run with those credentials; if that does not connect in 50 s, cloudflared's own quick tunnel is tried. The log shows each step.
- **AnimePahe:** its subbed links are recognised as hardsub (burned-in subtitles) and listed first. Episode lists without episode numbers (as in AnimePahe) are picked by position.
- **Free https link now reachable:** "Enable Stremio Mode" on the Server screen no longer demands a tunnel token and domain first. Turn it on (the app downloads cloudflared once) and the free https link appears as the Stremio URL; add your own token in Settings -> Cloudflare Tunnel only for a fixed link.

## 1.4.1

- **Anime search (AnimePahe and others):** every known name of the show (romaji, English, synonyms from AniList / Kitsu / MyAnimeList) is searched, also without season words, without the subtitle and by its first words. The results of all spellings are pooled and the best fit wins, with the season checked against the name ("2nd Season", "S2", "II").
- **Fuzzy search for movies and series:** when no result matches the exact title, a close spelling is accepted (same year, same season), and the title is retried without a subtitle / leading "The".
- **Hardsub first (anime):** links with the subtitles burned in are listed on top; inside each group the best quality stays first.
- **Formatter:** anime episodes (kitsu / MAL / AniList ids, any type) now fill the season/episode fields, new fields `{stream.audiokind}` (Sub / Dub) and `{stream.subtype}` (Hardsub / Softsub), and a "Sub / Dub / Hardsub" line in the default layout. A saved copy of the old default layout is upgraded automatically.
- **Media server:** links that cannot seek are always downloaded completely instead of pausing the download. Headers (Referer, User-Agent, Cookie) from the extension are sent on every request of the media server - verified end to end. Sites whose Cloudflare check was solved are sent their saved cookie and the matching User-Agent.
- **Cloudflare:** a solved check is saved on the device (6 hours) and reused for later requests instead of solving again; one solver per site at a time; longer wait for the check to finish.
- **Free https link:** with no tunnel token set, Stremio Mode starts a free Cloudflare quick tunnel (random https://...trycloudflare.com link, no account). Your own token and domain still work as before.
- Streams from Upstream add-ons now read their quality from the add-on's names, so they sort properly.

## 1.4.0

- **Always the best resolution:** adaptive (auto-resolution) streams are now looked into and replaced by their highest-resolution rendition when the audio is inside the video, so they are labelled with the real maximum (e.g. 1080p) and play at it every time instead of starting low. Streams that keep audio in a separate track are left adaptive so sound is never lost. Through the media server, master playlists are narrowed to the best rendition too. Setting: Streams -> best HLS quality.
- **Telegram / pencarimovie streaming:** new "Upstream add-ons" card in Settings. Paste the link of your pencarimovie server (or any Stremio add-on) and its streams are merged into every result list. Its Telegram files already stream with seeking, so they are passed through untouched. Included in settings backups.
- AnimePahe: the earlier settings fix (1.3.0) is the intended fix; the older AnimePahe copy in the reference repository was not adopted because the installed extension is newer.

## 1.3.0

- **AnimePahe and other extensions with saved settings:** plain-text settings (domain, user agent, Cloudflare cookie, provider lists) were handed to extensions in a form they could not read, so AnimePahe found nothing and logged JSON errors. They now read correctly.
- **Media server:** new default mode "Those + all HLS", more segments fetched ahead (30), 8 downloads in parallel, retries on failed downloads, playlists behind plain links (no ".m3u8") are recognised, and the renditions of a master playlist are loaded as soon as it is opened. Aimed at hard-sub / slow-host streams that kept buffering.
- **Results shown once:** by default the full list is shown after all extensions have answered (up to 45 s) instead of an early partial list. Setting: Streams -> "Show results once, after all extensions answered".
- **Next episode in advance:** after an episode's links are loaded, the next episode's links are loaded in the background. Setting: Streams -> "Load the next episode in advance".
- **Sorting:** links of equal quality no longer change places between loads (ties are broken by provider, name and link); adaptive streams get a longer time to have their quality detected so they are not ranked last at random.
- **Anime names:** for kitsu / MAL / AniList / AniDB ids the titles of AniList, Kitsu and MyAnimeList are all collected; for tt / tmdb / tvdb ids of anime the title is looked up on AniList and all its other names are tried too.
- **Log warnings:** fixed "No virtual method authUser()", "Failed resolution of AniListApi$CoverImage" and "No base context in WebViewResolver".

## 1.2.3

- Streams whose link is exactly the same as another result are listed once (the best ranked one is kept). Links that differ in any way are never merged.

## 1.2.2

- Six repositories are added by default: CNC, phisher98, SaurabhKaperwan (CSX), KSHITIJ8473 (raghav), RVRBEAST76 (allforu) and Faisal0786 (Desi). A fresh install starts with all of them; an existing install gets the missing ones added once (a repository you remove afterwards stays removed).
- Releases and the README now carry a notice that the app is vibe coded and provided as is, with no responsibility taken by the developer.

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
