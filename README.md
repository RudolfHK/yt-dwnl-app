# Audio Saver

A small, single-purpose Android app: **paste or share a YouTube link → an audio file appears in your phone's Music folder.**
Paste a **mix, album or playlist** link instead and it lists the songs so you can tick which ones to download.
For a single video you choose what to save: **audio, video (MP4), or both**. A **history** lists every download — what was
saved where, and what failed and why.
No library, no accounts, no in-app playback, no browsing.

> **Personal use only.** Use this app only for content you have the right to download: your own uploads, content under a
> licence that allows it (e.g. Creative Commons), or where personal copies are permitted by your local law. It does not
> and will not bypass paid or DRM-protected content. This is the same stance NewPipe takes; the app shows this notice too.

- Kotlin + Jetpack Compose, minSdk 29 (Android 10), compile/target SDK 37 (Android 17)
- Extraction: [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), behind a swappable interface
- Output: AAC in M4A (YouTube's own audio stream, no re-encoding), with embedded title/artist/year tags, saved to
  `Music/AudioSaver` through MediaStore — **no storage permission**
- License: **GPL-3.0-or-later** (required by NewPipeExtractor, see below)

## Building

- **Android Studio** (a version supporting AGP 9.2): open the project folder, let it sync, run the `app` configuration.
- **Command line:** `./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- **CI:** every push runs `.github/workflows/build.yml`: unit tests, lint and the debug APK (attached to the run as
  the `audiosaver-debug-apk` artifact, so you can install without building locally), plus **instrumented tests on
  Android emulators (API 29 and 35)** that check the video and audio files the way a phone reads them. A
  `live-youtube` job (manual: *Run workflow*) runs the real-YouTube tests — but YouTube currently answers every
  GitHub runner with "confirm you're not a bot", so run those from a home network instead (see *Test plan*).

NewPipeExtractor is only published on JitPack. If JitPack ever fails to serve the pinned commit, do what NewPipe does:
change the hash in `gradle/libs.versions.toml` (e.g. use a different, newer commit, or add/remove a character so JitPack
rebuilds it).

## Project layout

```
core/            Pure Kotlin/JVM, no Android. Unit-tested on the JVM.
  link/          YouTubeLinkParser: paste/share text -> VideoId, or a mix/album/playlist CollectionRef, or why not
  collection/    CollectionListing: the pick-your-songs list (selection, load more, de-dup, 200-song cap)
  engine/        ExtractionEngine interface + models + FailureReason   <-- the seam
  download/      HttpAudioDownloader: ranged, resumable, cancellable HTTP download with progress
  tag/           Mp4Tagger: writes iTunes-style tags into M4A/MP4 in one streaming pass (no FFmpeg)
  video/         DashMp4Muxer: merges YouTube's video-only + audio-only streams into one MP4 (no FFmpeg);
                 VideoQuality: picks the H.264 stream for the chosen quality
  pipeline/      AudioDownloadPipeline: resolve -> download -> tag -> save -> verify, emits DownloadState
                 BatchDownloader: runs the selected songs through that pipeline one by one
engine-newpipe/  The ONLY module that imports NewPipeExtractor (pure JVM)
app/             Android: Compose UI, share target, foreground service, notifications, MediaStore sink
```

**Swapping the extraction engine** (this *will* be needed one day — YouTube breaks extractors on purpose): write a new
`ExtractionEngine` implementation (resolve a `VideoId` to title/uploader/year + one audio stream URL, list a
mix/album/playlist page by page, throw
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

## Mixes, albums and playlists

| Link | Recognised as | What happens |
|---|---|---|
| `watch?v=X&list=RD…` ("copy link" while a mix plays), `youtu.be/X?list=RD…`, `playlist?list=RDX` | **Mix** | Songs listed (first page ≈25, *Load more* for more, de-duplicated, max 200). Choice of *Whole mix* or *Just this song*. |
| `music.youtube.com/watch?…&list=RDAMVM…` / `RDCLAK…` | **YouTube Music mix** | Same as a mix. |
| `playlist?list=OLAK5uy_…` | **Album** | Songs listed; saved to `Music/AudioSaver/<Album>/` as `03 - Title.m4a` with album, album-artist and track-number (`3/10`, position in the whole album) tags. YouTube's "Album - " title prefix is removed. |
| `playlist?list=PL…` (and other public lists) | **Playlist** | Songs listed; saved like single songs (a playlist is not an album, so no album tags). |
| `list=LL` / `WL` | rejected | "Liked"/"Watch later" are private and need sign-in; a video link that merely carries such a list still works as a single video. |

- The list loads automatically ~0.4 s after a list link appears (no request per keystroke). Everything selectable is
  ticked; deleted/private/members-only/live entries are shown but can't be ticked.
- **One batch at a time**, songs downloaded one after another in the same foreground service (notification:
  "Song 3 of 12", Cancel all). A failing song is marked with its specific reason and the batch carries on — except on
  *rate-limited* or *confirm you're not a bot*, where it stops and marks the rest "Not attempted" instead of hammering
  YouTube. A 1 s pause between songs keeps the request rate polite. The summary offers *Retry failed (n)* (album
  songs keep their track numbers) and *Open folder*.
- **EU consent cookie:** YouTube only serves mixes in the EU when its `SOCS` consent cookie is set to "configured
  cookies" (NewPipeExtractor's `setConsentAccepted(true)`); with the default "reject all" value mix extraction fails
  there. The app sends that value. It keeps no cookie jar, so nothing is stored or tracked.

## Audio, video or both (single videos)

Two equal checkboxes, both remembered: **Audio (M4A)** and **Video (MP4)**. Any combination works; with neither the
download button is disabled and says why. The button follows the choice (*Download audio* / *Download video* /
*Download audio + video*); the quality chips — *Best (up to 1080p)*, *720p*, *480p*, *360p* — appear when Video is on.
The audio goes to `Music/AudioSaver/`, the MP4 to `Movies/AudioSaver/Artist - Title.mp4` (MediaStore Video
collection, so galleries list it; still no storage permission). Mixes, albums and playlists are always saved as audio
(the list says so when Video is selected).

**Why M4A and not MP3.** "Audio" is YouTube's own AAC stream in an M4A file, tagged, not re-encoded. MP3 would mean
decoding and re-encoding (quality loss, an encoder library in the APK, slower) for no benefit: every phone, car and
music server plays M4A.

**Video only** still downloads the audio stream — it's the MP4's soundtrack — but saves only the MP4. If the video
can't be made, that's a normal failed download (red card, specific reason, *Retry*), not a half success.

**Why it needs merging.** YouTube no longer serves any single file with picture *and* sound: in the extractor's
recorded responses (Aug–Oct 2026) the client NewPipe uses returns only video-only and audio-only DASH streams —
H.264 up to 1080p, plus VP9 and AV1. So the app downloads the **H.264** video-only stream at or below the chosen
height (higher fps, then bitrate, break ties) and merges it with the AAC audio it already downloaded. H.264 because
it plays on every phone, gallery and TV; above 1080p YouTube only offers VP9/AV1, which many devices can't play in
MP4, so that's out of scope.

**How, without FFmpeg.** Both streams are fragmented MP4. `DashMp4Muxer` reads every fragment's sample table
(`trun`, with the `tfhd`/`trex` defaults and all three ways of addressing the data) and writes an **ordinary,
non-fragmented MP4**: `ftyp`, `moov` (both tracks with rebuilt `stts`/`ctts`/`stss`/`stsc`/`stsz`/`stco`|`co64`
tables and real durations), then one `mdat` with the sample data of both tracks interleaved by time. `moov` first,
so it starts playing before it's fully read. Nothing is decoded or re-encoded, so quality is exactly YouTube's and
it's fast. The merged file is then tagged (title, artist, year, source URL) like the audio.

*Why not keep the fragments* (the first version did): Android's own MP4 extractor (MediaExtractor/MediaPlayer)
moves to the next fragment in the file and treats one without its track as the end — so on phones such files
played **one second** of video. ffmpeg, VLC and ExoPlayer cope, which is why only the emulator test caught it.

**How the streams are requested.** With this extractor version every stream URL comes from YouTube's visionOS
client. NewPipe's own player fetches those with the visionOS User-Agent, as a POST with the byte range in a
`range=` query parameter (`YoutubeHttpDataSource`); the app first used a plain GET with a desktop User-Agent and a
`Range` header. Which of these YouTube accepts can't be checked from CI (bot check), so the engine lists the
styles in order — NewPipe player style, GET + visionOS UA, GET + desktop UA — and the downloader moves to the next
when YouTube refuses one (HTTP 400/401/403) and keeps whichever works. A server that ignores the range parameter
and sends the whole file is caught by its `Content-Length`, never written as wrong bytes.
`LiveStreamRequestDiagnostic` (opt-in live test) prints which styles work today.

**Flow and failures.** Resolving → Downloading (one bar over audio + video when both sizes are known) → Saving the
audio → Merging video and audio → saving the video. **With both selected, the audio is saved first**, so a video
problem never costs the song: the result shows "Audio saved, but the video failed: <specific reason>" with **Retry
video**, which downloads only what the video needs and doesn't save a second audio copy. Every *Retry* repeats
exactly what that download asked for, not whatever the checkboxes say now. Free space is checked *before* the video
download (≈ 2 × video + 2 × audio while working); not enough is reported up front. Other specific video failures:
no H.264 stream (*No downloadable MP4 video stream found*), audio only available as Opus/WebM (can't go in an MP4),
HTTP errors, merge errors. Cancel stops everything and leaves no partial video.

## History

The clock icon opens the **history**: every finished download, newest first — single links and each song of a
mix/album batch (with the mix/album name). Each entry shows the title, artist, time and, per requested format,
either where the file was saved (name, folder, size, video quality) or why it failed (the same specific reason and
detail as the error card). Filters: *All / Saved / Failed* (partly saved entries count as failed — something is
missing). Saved entries have *Play* and *Open folder*; failed and partly saved ones have *Retry*, which puts the link
back and asks again **only for what failed** (a partly saved entry retries just the video). Batch songs retry as
single audio downloads. *Clear* (with confirmation) empties the list; files stay on the phone.

- Not recorded: downloads you cancelled, and batch songs a cancelled batch never reached. Batch songs skipped
  because YouTube started rate-limiting *are* recorded as failed ("Not attempted: …") — those are the ones to retry.
- Pasting a link that's already in the history as saved shows "Already saved <when>" under the field — a hint,
  it never blocks saving it again.
- Stored only on the phone: an append-only JSON-lines file in app-private storage (`files/history.jsonl`, excluded
  from backup), newest 1000 entries. A half-written line after a crash is skipped on the next start, not fatal.

## Test plan

### Automated (JVM, run anywhere: `./gradlew :core:test :engine-newpipe:test`)

| Suite | Covers |
|---|---|
| `YouTubeLinkParserTest` (13) | watch / youtu.be share links (`?si=`) / m. / music. / shorts / live / embed / nocookie / bare IDs / links inside shared text; mix (incl. seed derivation, genre mixes), music mix, album and playlist links; private (`LL`/`WL`) and malformed lists; empty, garbage, other hosts, look-alike hosts, wrong-length IDs, channel links, with reasons |
| `Mp4TaggerTest` (9, needs ffmpeg on PATH) | YouTube-style DASH fMP4, fragmented+`mfra`, faststart (`stco`), moov-at-end; album / album-artist / track `3/10` tags; no album fields for mixes; re-tagging replaces tags; junk and truncated input rejected. Verified with ffprobe + bit-identical decoded-audio MD5 |
| `CollectionListingTest` (7) | default selection, unavailable rows, toggle / all / none, load-more de-dup keeping the user's choices, looping mix ends, 200-song cap, album vs mix batch contexts |
| `DashMp4MuxerTest` (9, needs ffmpeg) | YouTube-style DASH (relative offsets, `dash` brand, sidx) and absolute-offset fragments with other fps/timescales/sample rates; audio longer than video; DASH zero-length edit lists; verified with ffprobe/ffmpeg: h264+aac, duration, no decode errors, **decoded video and audio bit-identical to the inputs** (B-frame order included); structural checks: regular `ftyp/moov/mdat` layout, keyframe table for video, chunks interleaved, durations in `mvhd`/`tkhd` (ffmpeg would hide these); merged file taggable; swapped, non-fragmented, junk and truncated inputs rejected. Every check was confirmed to fail when the code it guards is broken. |
| `VideoOnDeviceTest` (5, **Android emulator, API 29 + 35, in CI**) | merges YouTube-style fixtures *on the device* and reads the result like a phone: `MediaMetadataRetriever` (duration, video + audio, 320×240, title/artist), `MediaExtractor` (both tracks demux to the end, seeking lands near the target), `MediaPlayer.prepare`; saves through the real MediaStore sink into `Movies/AudioSaver` and reads it back from there; checks Android reads the audio file's title/artist/album/track tags |
| `VideoQualityTest` (5) | tallest H.264 within the ceiling (never VP9/AV1), 60 fps preferred, smallest if all are taller, no H.264 → none |
| `VideoPipelineTest` (9, needs ffmpeg) | audio + merged, tagged, playable MP4; one progress bar over both, phase order; video HTTP failure keeps the audio; video only saves just the MP4; a failing video-only download is a real failure; no H.264 / not enough space / Opus-only audio reported without downloading the video; cancel mid-video leaves no temp or partial files |
| `HistoryTest` (14) | one entry per finished download however many progress updates; title/artist kept; partial (audio ✓ video ✗), video-only, failures before a title was known; cancelled not recorded; batch songs recorded once each, cancelled ones skipped, retry runs recorded again; survives restart (newest first); corrupt/half-written lines skipped; 1000-entry cap with compaction; clear; "already saved" ignores failures; unknown future reasons read back; retry asks only for what failed |
| `BatchDownloaderTest` (5) | order, carries on after a failure, stops on rate-limiting without further requests, cancel keeps finished songs and cancels the rest, album track numbers/folder/tags, retry-failed keeps album context |
| `HttpAudioDownloaderTest` (15) | chunked Range download, determinate vs indeterminate progress, server ignoring Range, resume after mid-chunk disconnect, 403 / 429, persistent network failure, empty responses, prompt cancel of a stalled read; request styles: NewPipe player style (POST, `range`/`rn` parameters, headers), fallback after a refusal that sticks for later chunks, a server ignoring the range parameter detected, refusal by every style names them, fallback for unknown sizes |
| `AudioDownloadPipelineTest` (10) | phase order, tagged output, temp-file cleanup, extractor error pass-through (no download attempted), untaggable → saved with warning, WebM path, lying/failing storage → STORAGE error, cancel → Cancelled, file-name sanitising |
| `NewPipeExtractionEngineTest` (10) | NewPipe exception → `FailureReason` mapping keeps the original message; stream choice (original track, M4A over Opus, bitrate, progressive only); "- Topic" cleanup; playlist entries → items (incl. `&list=` URLs), non-downloadable entries, album title prefix; only progressive MP4 video-only streams offered, size from itag data or the "1080p60" label |
| `LiveYouTubeTest` (6) + `LiveStreamRequestDiagnostic` (1), opt-in: `LIVE_YOUTUBE=1 ./gradlew :engine-newpipe:test --tests '*Live*'` | Real YouTube: resolves a Creative-Commons video, full pipeline to a tagged file, unavailable ID → `UNAVAILABLE`, lists that video's mix and loads a second page, saves a merged ≤480p MP4 (audio + video, and video only) and checks it with ffprobe/ffmpeg; the diagnostic prints which request styles YouTube's stream servers accept. Needs a network YouTube doesn't bot-check (not GitHub's runners). **Run this first when "downloads stopped working"** — it tells you whether YouTube/the extractor broke. |

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
6. **Mix.** Play any song in the YouTube app, open its *Mix*, use *Share → Copy link* and paste (or *Share → Audio
   Saver*). Expect "Mix link · pick the songs below", the chips *Whole mix* / *Just this song*, and within a second or
   two the song list with everything ticked. Untick a few, tap *Load more* (more songs appear, none repeated, your
   choices kept), then *Download N selected*: the card shows "Song 1 of N" with an overall bar and the current song's
   phase; each row turns ✓ / ✗ with a reason. The notification shows the same with *Cancel all*. Switch to *Just this
   song* and it behaves like a normal single-song link.
7. **Album.** Paste a YouTube Music album link (`music.youtube.com/playlist?list=OLAK5uy_…`). Download 2 of its songs:
   they land in `Music/AudioSaver/<Album>/` as `01 - …`, `05 - …`, and a music app groups them under the album with the
   right track numbers.
8. **Batch cancel and retry.** Start a 10-song batch, tap *Cancel all* during song 3: songs 1–2 stay saved, the rest
   show "cancelled", no partial file remains. Put the phone in airplane mode during a batch: affected songs fail with
   "Network error"; turn it back on and *Retry failed* downloads just those.
9. **Private list.** `https://www.youtube.com/playlist?list=WL` → inline "Watch later … can't be read without signing in".
10. **Video.** Tick *Video (MP4)* (keep *Audio*), pick *720p*, paste `https://youtu.be/aqz-KE-bpKQ`, tap *Download audio +
    video*: one bar runs across both downloads ("Audio + video · …" in the notification), then *Merging video and
    audio…*. The result shows the audio *and* "Video saved (720p)" with its own Play/Share/Open folder; the MP4 is in
    `Movies/AudioSaver`, plays with sound in the gallery, shows the right length, and seeking works. Kill the network
    during the video part: the audio is still saved, the card says why the video failed, *Retry video* fixes it
    without a second audio file. Mix/album links don't show the checkboxes; *Just this song* on a mix link does.
11. **Video only / neither.** Untick *Audio*: the button says *Download video*; only an MP4 appears (nothing new in
    `Music/AudioSaver`). With airplane mode on it fails like any download (red card, *Retry*); *Retry* again saves only
    the video. Untick both: the button is disabled and "Pick audio, video or both." appears.
12. **History.** After the steps above, open the clock icon: saved songs show where they went (and the video quality),
    the failed ones their reason; *Failed* filters them; *Retry* on a partly saved entry downloads just the video;
    *Play* opens the file. Paste a link you saved before: "Already saved …" appears under the field, and downloading
    it again still works. Force-stop the app and reopen: the history is still there. *Clear* empties it, the files
    stay.
13. **Robustness extras.** Lock the screen / switch apps mid-download (it continues); rotate during a download (progress
   survives); deny the notification permission (download still works, just without a notification).

## Known limitations

- Age-restricted, members-only, kids-only and private videos can't be downloaded (no login, by design).
- YouTube may answer "Sign in to confirm you're not a bot" for some networks/IPs; the app reports exactly that.
- Saved M4A keeps YouTube's fragmented MP4 layout (tags and brand fixed up, audio untouched). All common players and
  taggers handle it; a few very old players may show the duration only after a full scan.
- Video: H.264 only, so at most 1080p (YouTube serves higher resolutions only as VP9/AV1).
- Not yet verified against real YouTube: the request style YouTube's stream servers accept (see above; the app
  falls back automatically) and very long videos. Run the live tests from a home network to confirm.
- One download (or one batch) at a time, by design. A batch doesn't survive the app process being killed; downloaded
  songs stay, the rest can be picked again.
- Mixes are generated by YouTube per request, so the song list can differ between two loads of the same link, and
  they're endless: the app stops at 200 songs. Personalised "My Mix" contents need sign-in; without it YouTube returns
  a generic mix for the seed song.
