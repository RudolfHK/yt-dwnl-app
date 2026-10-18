# Audio Saver

A small, single-purpose Android app: **paste or share a YouTube link → an audio file appears in your phone's Music folder.**
No library, no queue, no accounts, no in-app playback, no browsing.

> **Personal use only.** Use this app only for content you have the right to download: your own uploads, content under a
> licence that allows it (e.g. Creative Commons), or where personal copies are permitted by your local law. It does not
> and will not bypass paid or DRM-protected content. This is the same stance NewPipe takes; the app shows this notice too.

- Kotlin + Jetpack Compose, minSdk 29 (Android 10), compile/target SDK 37 (Android 17)
- Extraction: [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), behind a swappable interface
- Output: AAC in M4A (YouTube's own audio stream, no re-encoding), with embedded title/artist/year tags, saved to
  `Music/AudioSaver` through MediaStore — **no storage permission**
- License: **GPL-3.0-or-later** (required by NewPipeExtractor, see below)

## Building

- **Android Studio** (a version supporting AGP 9.3): open the project folder, let it sync, run the `app` configuration.
- **Command line:** `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- **CI:** every push runs `.github/workflows/build.yml` (unit tests, lint, debug APK). The APK is attached to the run as
  the `audiosaver-debug-apk` artifact, so you can install without building locally.

NewPipeExtractor is only published on JitPack. If JitPack ever fails to serve the pinned commit, do what NewPipe does:
change the hash in `gradle/libs.versions.toml` (e.g. use a different, newer commit, or add/remove a character so JitPack
rebuilds it).

## Project layout

```
core/            Pure Kotlin/JVM, no Android. Unit-tested on the JVM.
  link/          YouTubeLinkParser: paste/share text -> validated 11-char VideoId, or a specific reason why not
  engine/        ExtractionEngine interface + models + FailureReason   <-- the seam
  download/      HttpAudioDownloader: ranged, resumable, cancellable HTTP download with progress
  tag/           Mp4Tagger: writes iTunes-style tags into M4A in one streaming pass (no FFmpeg)
  pipeline/      AudioDownloadPipeline: resolve -> download -> tag -> save -> verify, emits DownloadState
engine-newpipe/  The ONLY module that imports NewPipeExtractor (pure JVM)
app/             Android: Compose UI, share target, foreground service, notifications, MediaStore sink
```

**Swapping the extraction engine** (this *will* be needed one day — YouTube breaks extractors on purpose): write a new
`ExtractionEngine` implementation (resolve a `VideoId` to title/uploader/year + one audio stream URL, throw
`AudioSaveException` with the most specific `FailureReason`), put it in its own module, and change the single line in
`AudioSaverApp.kt` that constructs `NewPipeExtractionEngine`. Nothing else in the app knows which library is used.

---

## Research notes: choosing the extraction approach (October 2026)

### What I checked

| Candidate | State (Oct 2026) | Verdict |
|---|---|---|
| **NewPipeExtractor** | Active. Last release v0.26.5 (2026-08-15); `dev` has commits through 2026-10-13. Pure Java, no native code, no Python. Used by NewPipe itself and by most FOSS YouTube clients (LibreTube, Tubular, …; PipePipe uses a fork). | **Chosen** |
| yt-dlp on Android (youtubedl-android / Chaquopy) | The most robust extractor overall and updated fastest, but needs an embedded Python runtime (+ usually FFmpeg): tens of MB per ABI, slow cold start, extra moving parts. | Rejected by the constraints (no embedded Python, APK size). Could be dropped in later as a second `ExtractionEngine`. |
| PipePipeExtractor (NewPipeExtractor fork) | Active, has experimental SABR work. Smaller user base, same GPL licence. | Good fallback engine if upstream falls behind. |
| Invidious / Piped APIs | Require a third-party server instance. | Rejected: "no backend" constraint, and public instances come and go. |

### Why NewPipeExtractor, and which version

- **Fit:** plain Java library, already proven on Android at large scale, exposes exactly what this app needs: the
  audio-only stream list with format, bitrate and byte length, plus title/uploader/upload date, and typed exceptions
  for age-restricted / private / geo-blocked / paid / "sign in to confirm you're not a bot" / unavailable — which map
  directly onto this app's specific error messages.
- **Current YouTube situation:** YouTube now gates many clients behind attestation tokens ("PO tokens", from BotGuard /
  DroidGuard) and is moving playback to its SABR protocol. In August 2026 NewPipeExtractor removed the ANDROID, IOS and
  WEB_EMBEDDED clients from its code (PR #1529, "remove broken unused clients", in `dev` only, not in the v0.26.5
  release) because nobody can generate tokens for them; stream URLs now come from a client that doesn't need one.
  Consequences that matter here: regular public videos work without a token provider; kids-only videos and
  age-restricted videos do not (no login), and YouTube may show "confirm you're not a bot" to some IPs — the app
  reports these specifically rather than as a generic failure.
- **Pinned version:** commit `65cabc2` on `dev` (2026-10-05) — the exact extractor the NewPipe app's own `dev` branch
  builds against (checked 2026-10-17), so it carries the client cleanup above and is exercised daily by NewPipe's
  users. It's served by JitPack (`com.github.TeamNewPipe:NewPipeExtractor:<commit>`), the project's official channel.
  The v0.26.5 release is the conservative alternative; switching is a one-line change in `libs.versions.toml`.
- **No PoTokenProvider:** NewPipe ships a WebView-based BotGuard runner for the WEB client. It's not needed for the
  streams this app uses, and it's a sizeable chunk of complexity, so it's left out. If YouTube starts requiring it for
  all clients, that's the first thing to add (inside `engine-newpipe`, nothing else changes).
- **Install requirements:** JitPack repository (restricted to the `com.github.TeamNewPipe` group in
  `settings.gradle.kts`), core library desugaring with `desugar_jdk_libs_nio` because minSdk < 33, and NewPipe's R8
  keep rules for Rhino/protobuf (`app/proguard-rules.pro`).

### License: GPL-3.0-or-later

NewPipeExtractor is GPL-3.0-or-later, so any APK that includes it must be distributed under GPL-3.0 too, with source.
For a personal tool (or a FOSS app) that's fine, and the project is licensed accordingly (`LICENSE`). It would **not** be
fine for a closed-source or proprietary app — in that case the `engine-newpipe` module would have to be replaced by a
non-GPL engine (the interface makes that mechanically easy, but no comparable non-GPL extractor exists today).

### Audio format: why M4A/AAC as-is, no transcoding, no FFmpeg

YouTube offers audio-only streams as AAC-LC ~128 kbps in MP4 (itag 140), HE-AAC 48 kbps (139) and Opus in WebM
(249/250/251). The app picks the original-language, highest-bitrate **M4A** stream and saves it **without re-encoding**:

- It plays natively everywhere (Android, iOS, cars, Navidrome/Jellyfin without server-side transcoding).
- Any transcode (e.g. to MP3) would be lossy-to-lossy, slower, battery-hungry, and need FFmpeg or LAME in the APK —
  roughly 10–30 MB per ABI. That cost buys nothing here, so it was rejected.
- **Tags:** ID3 is the MP3 tag format; MP4/M4A files carry the same information as iTunes-style `ilst` atoms
  (`©nam` title, `©ART` artist, `aART` album artist, `©day` year, `©cmt` comment = the source URL). That is what
  Android's media scanner, TagLib (Navidrome), Jellyfin, foobar2000, iTunes etc. read for M4A, so that's what is written.
  The uploader's YouTube "Artist - Topic" suffix is stripped, so label uploads file under the real artist.
- YouTube's M4A is DASH *fragmented* MP4 with brand `dash`. `Mp4Tagger` grows `moov` with the tags and fixes every
  absolute offset behind it (`stco`/`co64`, `tfhd.base_data_offset`, `tfra`), and rewrites the brand to `M4A ` (what
  yt-dlp's FFmpeg "FixupM4a" step does), all in one streaming pass. Its tests round-trip four real ffmpeg-produced MP4
  layouts and check with ffprobe/ffmpeg that the tags read back and the **decoded audio is bit-identical**.
- If a video has no M4A stream (rare), the Opus/WebM stream is saved as `.webm` without embedded tags, and the app says so.
  If tagging fails for an unexpected file layout, the untagged audio is still saved and the app says so.

### Background work and storage

- **Foreground service, type `dataSync`**, started from the visible activity. The download survives leaving the app or
  locking the screen; the notification shows the same phase/progress and has a Cancel action. On Android 15+ dataSync
  services have a daily time budget; `onTimeout` is handled and reported as a specific error. (Android 14+'s
  "user-initiated data transfer jobs" would be the long-term alternative if `dataSync` is ever restricted further;
  WorkManager was not needed for a single, user-started, non-deferrable job.)
- **Scoped storage:** `MediaStore.Audio.Media` insert with `RELATIVE_PATH = Music/AudioSaver` and `IS_PENDING`, so no
  `READ/WRITE_EXTERNAL_STORAGE` permission at all (minSdk 29 is what makes this true on every supported device).
  Permissions requested: `INTERNET`, `FOREGROUND_SERVICE(_DATA_SYNC)`, and optional `POST_NOTIFICATIONS`.

### Design decisions carried over from the server-side (yt-dlp/FastAPI) implementation

| Lesson | Here |
|---|---|
| Validate before extracting | `YouTubeLinkParser` runs on every keystroke with no network; the button is disabled until there's an 11-char ID, and invalid input says *why* (not YouTube, playlist link, ID has 10 characters, …). |
| Background job with real phases | Foreground service; `DownloadState` = Resolving → Downloading → Saving → Done / Failed / Cancelled. |
| Percent only when known | Determinate bar only when the byte length is known (from the extractor or `Content-Length`); otherwise indeterminate with bytes received — never a guessed number. |
| Tag the file | Title, artist, album artist, year, source URL embedded; file named `Artist - Title.m4a`. |
| Confirm the file that landed | After saving, the size is read back from the stored file itself and must equal what was written; the actual (possibly de-duplicated) name and folder are read back from MediaStore. A zero/mismatched size is reported as a storage error, not success. |
| Surface the real error | Each failure shows a specific reason (age-restricted, private, region-locked, unavailable, bot check, rate-limited, network, extractor broken, …) plus the underlying message, selectable for bug reports. "Extractor broken" also names the extractor version. |

---

## Test plan

### Automated (JVM, run anywhere: `./gradlew :core:test :engine-newpipe:test`)

| Suite | Covers |
|---|---|
| `YouTubeLinkParserTest` (10) | watch / youtu.be share links (`?si=`) / m. / music. / shorts / live / embed / nocookie / bare IDs / links inside shared text; empty, garbage, other hosts, look-alike hosts, wrong-length IDs, playlist-only and channel links, with reasons |
| `Mp4TaggerTest` (7, needs ffmpeg on PATH) | YouTube-style DASH fMP4, fragmented+`mfra`, faststart (`stco`), moov-at-end; re-tagging replaces tags; junk and truncated input rejected. Verified with ffprobe + bit-identical decoded-audio MD5 |
| `HttpAudioDownloaderTest` (10) | chunked Range download, determinate vs indeterminate progress, server ignoring Range, resume after mid-chunk disconnect, 403 / 429, persistent network failure, empty responses, prompt cancel of a stalled read |
| `AudioDownloadPipelineTest` (10) | phase order, tagged output, temp-file cleanup, extractor error pass-through (no download attempted), untaggable → saved with warning, WebM path, lying/failing storage → STORAGE error, cancel → Cancelled, file-name sanitising |
| `NewPipeExtractionEngineTest` (5) | NewPipe exception → `FailureReason` mapping keeps the original message; stream choice (original track, M4A over Opus, bitrate, progressive only); "- Topic" cleanup |
| `LiveYouTubeTest` (3, opt-in: `LIVE_YOUTUBE=1`) | Real YouTube: resolves a Creative-Commons video, full pipeline to a tagged file, unavailable ID → `UNAVAILABLE`. **Run this first when "downloads stopped working"** — it tells you whether YouTube/the extractor broke. |

### Manual, on a device or emulator

Use content you're allowed to download, e.g. Blender's *Big Buck Bunny* (CC BY): `https://youtu.be/aqz-KE-bpKQ`.

1. **Valid link.** Paste the link (or tap *Paste*). Expect instantly "Valid link · video ID aqz-KE-bpKQ" and the button
   enabled. Tap *Download audio*: card shows *Resolving stream…* (indeterminate) → *Downloading* with a percentage and
   "x MB of y MB" → *Tagging and saving…* → *✓ Saved* with the file name, `Music/AudioSaver` and size. The notification
   mirrors the progress, then shows "Saved to Music/AudioSaver". *Play* opens a music player, *Share* opens the share
   sheet, *Open folder* opens the Files app on that folder (or explains where the file is if no app can). In a music
   app, the track shows the right title, artist ("Blender") and year rather than "Unknown artist".
2. **Invalid / garbage input.** Try `hello`, `https://vimeo.com/123`, `https://youtu.be/abc`,
   `https://www.youtube.com/playlist?list=PL…`, a channel URL. Each shows a specific inline reason, the field is marked
   as an error and the button stays disabled. Works identically in airplane mode (no network involved).
3. **Share intent.** In the YouTube app: *Share → Audio Saver*. The app opens with the link pre-filled and validated
   (also try sharing from a browser, where the text may include the page title). Repeat with the app already open in
   the background — the new link must replace the old one.
4. **Cancelled download.** Start a long video, tap *Cancel* at ~30 %: the card shows "Download cancelled." with *Retry*,
   the notification disappears, and **no** file (and no half-written entry) appears in `Music/AudioSaver`. Repeat using
   the notification's *Cancel* action, and once while still *Resolving*.
5. **Failure case.** `https://youtu.be/aaaaaaaaaaa` (valid format, no such video): fails with "Video unavailable" and
   the extractor's own message, plus *Retry*. Then turn on airplane mode and download a valid link: "Network error"
   with the underlying exception; turn the network back on and *Retry* succeeds.
6. **Robustness extras.** Lock the screen / switch apps mid-download (it continues); rotate during a download (progress
   survives); deny the notification permission (download still works, just without a notification).

## Known limitations

- Age-restricted, members-only, kids-only and private videos can't be downloaded (no login, by design).
- YouTube may answer "Sign in to confirm you're not a bot" for some networks/IPs; the app reports exactly that.
- Saved M4A keeps YouTube's fragmented MP4 layout (tags and brand fixed up, audio untouched). All common players and
  taggers handle it; a few very old players may show the duration only after a full scan.
- One download at a time, by design.
