package io.github.rudolfhk.audiosaver.engine.newpipe

import io.github.rudolfhk.audiosaver.core.download.CallGroup
import io.github.rudolfhk.audiosaver.core.download.cancelOnCancellation
import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.AudioStreamSource
import io.github.rudolfhk.audiosaver.core.engine.CollectionItem
import io.github.rudolfhk.audiosaver.core.engine.CollectionPage
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.engine.PageToken
import io.github.rudolfhk.audiosaver.core.engine.RequestVariant
import io.github.rudolfhk.audiosaver.core.engine.ResolvedAudio
import io.github.rudolfhk.audiosaver.core.engine.VideoStreamSource
import io.github.rudolfhk.audiosaver.core.link.CollectionKind
import io.github.rudolfhk.audiosaver.core.link.CollectionRef
import io.github.rudolfhk.audiosaver.core.link.LinkParseResult
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.link.YouTubeLinkParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.ContentAvailability
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.time.ZoneOffset
import java.util.Locale

/**
 * [ExtractionEngine] backed by NewPipeExtractor (GPL-3.0-or-later), the extractor behind NewPipe.
 * No PoTokenProvider is installed: the extractor then uses clients that don't need one, which is
 * enough for regular public videos.
 */
class NewPipeExtractionEngine(
    client: OkHttpClient,
    locale: Locale = Locale.getDefault(),
) : ExtractionEngine {

    override val description: String = "NewPipeExtractor $EXTRACTOR_VERSION"

    init {
        initOnce(client, locale)
    }

    override suspend fun resolve(videoId: VideoId): ResolvedAudio = extracting {
        val extractor = ServiceList.YouTube.getStreamExtractor(videoId.watchUrl)
        extractor.fetchPage()
        toResolvedAudio(videoId, extractor)
    }

    override suspend fun listCollection(ref: CollectionRef, page: PageToken?): CollectionPage = extracting {
        val continuation = page?.value as? Continuation
        val extractor = continuation?.extractor
            ?: ServiceList.YouTube.getPlaylistExtractor(ref.url).also { it.fetchPage() }
        val itemsPage = if (continuation != null) extractor.getPage(continuation.page) else extractor.initialPage
        val count = extractor.streamCount.takeIf { it >= 0 }
        CollectionPage(
            ref = ref,
            title = cleanCollectionTitle(extractor.name, ref.kind),
            uploader = runCatching { extractor.uploaderName }.getOrNull()?.takeIf { it.isNotBlank() }?.let(::cleanUploader),
            items = itemsPage.items.mapNotNull(::toCollectionItem),
            totalCount = count,
            next = itemsPage.nextPage?.takeIf { itemsPage.hasNextPage() }?.let { PageToken(Continuation(extractor, it)) },
        )
    }

    /** Next page = the same (already fetched) extractor + NewPipe's page cursor. In memory only. */
    private class Continuation(val extractor: PlaylistExtractor, val page: Page)

    /**
     * Runs blocking NewPipeExtractor work on the IO pool such that cancelling the coroutine aborts the
     * HTTP request the extractor is blocked on, and every failure leaves as an [AudioSaveException].
     */
    private suspend fun <T> extracting(block: () -> T): T = withContext(Dispatchers.IO) {
        val calls = CallGroup()
        cancelOnCancellation(calls) {
            runInterruptible {
                OkHttpNewPipeDownloader.activeCallGroup.set(calls)
                try {
                    block()
                } catch (e: AudioSaveException) {
                    throw e
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    throw mapFailure(e)
                } finally {
                    OkHttpNewPipeDownloader.activeCallGroup.remove()
                }
            }
        }
    }

    private fun toResolvedAudio(videoId: VideoId, extractor: StreamExtractor): ResolvedAudio {
        val streamType = extractor.streamType
        if (streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) {
            throw AudioSaveException(FailureReason.LIVE_STREAM, "This is a live stream ($streamType); try again once it has ended.")
        }

        val streams = extractor.audioStreams
        val chosen = chooseStream(streams)
            ?: throw AudioSaveException(
                FailureReason.NO_AUDIO_STREAM,
                "The extractor returned ${streams.size} audio stream(s), none of them a directly downloadable " +
                    "M4A or WebM/Opus file: " + streams.joinToString { "${it.format?.name}/${it.deliveryMethod}" }.ifEmpty { "none" },
            )

        val year = runCatching { extractor.uploadDate }
            .getOrNull()
            ?.takeUnless { it.isApproximation }
            ?.instant?.atOffset(ZoneOffset.UTC)?.year

        return ResolvedAudio(
            videoId = videoId,
            title = extractor.name,
            uploader = runCatching { extractor.uploaderName }.getOrNull()?.let(::cleanUploader),
            uploadYear = year,
            durationSeconds = runCatching { extractor.length }.getOrNull()?.takeIf { it > 0 },
            stream = AudioStreamSource(
                url = chosen.content,
                container = if (chosen.format == MediaFormat.M4A) AudioContainer.M4A else AudioContainer.WEBM_OPUS,
                codec = chosen.codec,
                averageBitrate = chosen.averageBitrate.takeIf { it > 0 },
                contentLength = chosen.itagItem?.contentLength?.takeIf { it > 0 },
                requestHeaders = mapOf("User-Agent" to OkHttpNewPipeDownloader.USER_AGENT),
                requestVariants = requestVariantsFor(chosen.content),
            ),
            // Cheap to collect here (same player response); only downloaded if the user wants the video.
            videoStreams = runCatching { extractor.videoOnlyStreams }.getOrDefault(emptyList()).mapNotNull(::toVideoStream),
        )
    }

    companion object {
        /** Keep in sync with gradle/libs.versions.toml - only used in error messages. */
        const val EXTRACTOR_VERSION = "65cabc2 (dev, 2026-10-05)"

        @Volatile private var initialized = false

        @Synchronized
        private fun initOnce(client: OkHttpClient, locale: Locale) {
            if (initialized) return
            NewPipe.init(
                OkHttpNewPipeDownloader(client),
                Localization.fromLocale(locale),
                ContentCountry(locale.country.ifEmpty { "US" }),
            )
            // YouTube only serves Mixes (and some YouTube Music lists) in the EU when its SOCS consent
            // cookie says "configured cookies"; with the default "rejected all" value mix extraction
            // fails there. The app keeps no cookie jar, so this only changes that one request header.
            YoutubeParsingHelper.setConsentAccepted(true)
            initialized = true
        }

        /**
         * Prefers the original-language track (videos can carry dubbed/descriptive tracks), then
         * M4A over WebM/Opus because only M4A gets embedded tags and plays everywhere, then bitrate.
         * Only progressive (single-file) URLs qualify - DASH/HLS segment lists would need a muxer.
         */
        internal fun chooseStream(streams: List<AudioStream>): AudioStream? =
            streams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .filter { it.format == MediaFormat.M4A || it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.WEBMA }
                .sortedWith(
                    compareByDescending<AudioStream> { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                        .thenByDescending { it.format == MediaFormat.M4A }
                        .thenByDescending { it.averageBitrate },
                )
                .firstOrNull()

        private val PLACEHOLDER_TITLES = setOf("[Deleted video]", "[Private video]", "[Unavailable video]")

        internal fun toCollectionItem(item: StreamInfoItem): CollectionItem? {
            // Our own parser: NewPipe's stream link handler refuses URLs that also carry a list=,
            // which is exactly how mix/playlist entries can be addressed.
            val id = when (val parsed = YouTubeLinkParser.parse(item.url)) {
                is LinkParseResult.Valid -> parsed.videoId
                is LinkParseResult.Collection -> parsed.ref.seedVideoId ?: return null
                is LinkParseResult.Invalid -> return null
            }
            val name = item.name.orEmpty()
            val isLive = item.streamType == StreamType.LIVE_STREAM || item.streamType == StreamType.AUDIO_LIVE_STREAM
            val restricted = item.contentAvailability.let {
                it == ContentAvailability.MEMBERSHIP || it == ContentAvailability.PAID || it == ContentAvailability.UPCOMING
            }
            return CollectionItem(
                videoId = id,
                title = name.ifBlank { id.value },
                uploader = item.uploaderName?.takeIf { it.isNotBlank() }?.let(::cleanUploader),
                durationSeconds = item.duration.takeIf { it > 0 },
                available = name !in PLACEHOLDER_TITLES && !isLive && !restricted,
            )
        }

        /**
         * How to ask YouTube's stream servers for a stream's bytes, most preferred first.
         *
         * Stream URLs from the visionOS client (all of them with this extractor version) are fetched
         * by NewPipe's own player with that client's User-Agent, as a POST with a 2-byte body and the
         * byte range in a `range=` query parameter plus an `rn=` request counter
         * (YoutubeHttpDataSource in TeamNewPipe/NewPipe). That is tried first; a plain GET with a
         * Range header (visionOS, then desktop User-Agent) follows in case YouTube refuses it.
         * The downloader keeps whichever works. See LiveStreamRequestDiagnostic to re-check.
         */
        internal fun requestVariantsFor(url: String): List<RequestVariant> {
            val desktop = RequestVariant("GET, desktop User-Agent", mapOf("User-Agent" to OkHttpNewPipeDownloader.USER_AGENT))
            if (!YoutubeParsingHelper.isVisionOsStreamingUrl(url)) return listOf(desktop)
            val visionOs = mapOf("User-Agent" to YoutubeParsingHelper.getVisionOsUserAgent(null))
            return listOf(
                RequestVariant(
                    "NewPipe player style (POST, range parameter, visionOS User-Agent)",
                    visionOs,
                    rangeAsQueryParameter = true,
                    requestNumberParameter = true,
                    postBody = byteArrayOf(0x78, 0),
                ),
                RequestVariant("GET, visionOS User-Agent", visionOs),
                desktop,
            )
        }

        /** Video-only MP4 streams the core can merge; quality choice (H.264, height) happens in core. */
        internal fun toVideoStream(stream: VideoStream): VideoStreamSource? {
            if (!stream.isUrl || stream.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP || stream.format != MediaFormat.MPEG_4) return null
            // YouTube's itag data carries the size; the "720p60" label is the fallback.
            val label = Regex("""(\d+)p(\d+)?""").find(stream.getResolution().orEmpty())
            val height = stream.height.takeIf { it > 0 } ?: label?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return VideoStreamSource(
                url = stream.content,
                width = stream.width,
                height = height,
                fps = stream.fps.takeIf { it > 0 } ?: label?.groupValues?.get(2)?.toIntOrNull() ?: 30,
                codec = stream.codec,
                averageBitrate = stream.bitrate.takeIf { it > 0 },
                contentLength = stream.itagItem?.contentLength?.takeIf { it > 0 },
                requestHeaders = mapOf("User-Agent" to OkHttpNewPipeDownloader.USER_AGENT),
                requestVariants = requestVariantsFor(stream.content),
            )
        }

        /** YouTube names auto-generated album playlists "Album - <name>". */
        internal fun cleanCollectionTitle(name: String, kind: CollectionKind): String =
            if (kind == CollectionKind.ALBUM) name.removePrefix("Album - ").trim().ifEmpty { name } else name

        /** "Artist - Topic" is YouTube's auto-generated channel for a label upload; the artist is the part before. */
        internal fun cleanUploader(name: String): String? =
            name.removeSuffix(" - Topic").trim().ifEmpty { null }

        internal fun mapFailure(e: Exception): AudioSaveException {
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
            val reason = when (e) {
                is AgeRestrictedContentException -> FailureReason.AGE_RESTRICTED
                is PrivateContentException -> FailureReason.PRIVATE
                is GeographicRestrictionException, is UnsupportedContentInCountryException -> FailureReason.REGION_LOCKED
                is PaidContentException, is YoutubeMusicPremiumContentException -> FailureReason.PAID_OR_MEMBERS_ONLY
                is SignInConfirmNotBotException -> FailureReason.BOT_CHECK
                is ReCaptchaException -> FailureReason.RATE_LIMITED
                is AccountTerminatedException, is ContentNotSupportedException -> FailureReason.UNAVAILABLE
                // Generic "not available" (removed, wrong ID, ...) - must come after its subclasses above.
                is ContentNotAvailableException -> FailureReason.UNAVAILABLE
                is ParsingException -> FailureReason.EXTRACTOR_BROKEN
                is ExtractionException -> FailureReason.EXTRACTOR_BROKEN
                is IOException -> FailureReason.NETWORK
                else -> FailureReason.UNKNOWN
            }
            val suffix = if (reason == FailureReason.EXTRACTOR_BROKEN) {
                " [engine: NewPipeExtractor $EXTRACTOR_VERSION - an app update with a newer extractor is probably needed]"
            } else {
                ""
            }
            return AudioSaveException(reason, detail + suffix, e)
        }
    }
}
