package io.github.rudolfhk.audiosaver.engine.newpipe

import io.github.rudolfhk.audiosaver.core.download.CallGroup
import io.github.rudolfhk.audiosaver.core.download.cancelOnCancellation
import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.engine.AudioSaveException
import io.github.rudolfhk.audiosaver.core.engine.AudioStreamSource
import io.github.rudolfhk.audiosaver.core.engine.ExtractionEngine
import io.github.rudolfhk.audiosaver.core.engine.FailureReason
import io.github.rudolfhk.audiosaver.core.engine.ResolvedAudio
import io.github.rudolfhk.audiosaver.core.link.VideoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
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
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamType
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

    override suspend fun resolve(videoId: VideoId): ResolvedAudio = withContext(Dispatchers.IO) {
        val calls = CallGroup()
        cancelOnCancellation(calls) {
            runInterruptible {
                OkHttpNewPipeDownloader.activeCallGroup.set(calls)
                try {
                    val extractor = ServiceList.YouTube.getStreamExtractor(videoId.watchUrl)
                    extractor.fetchPage()
                    toResolvedAudio(videoId, extractor)
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
            ),
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
