package io.github.rudolfhk.audiosaver.core.pipeline

import io.github.rudolfhk.audiosaver.core.audio.AudioAnalyzer
import io.github.rudolfhk.audiosaver.core.audio.TimeRange
import io.github.rudolfhk.audiosaver.core.engine.AudioContainer
import io.github.rudolfhk.audiosaver.core.mp3.Mp3Exporter
import io.github.rudolfhk.audiosaver.core.opus.WebmOpusRemuxer
import io.github.rudolfhk.audiosaver.core.tag.Mp4Tagger
import io.github.rudolfhk.audiosaver.core.tag.TrackMetadata
import io.github.rudolfhk.audiosaver.core.video.DashMp4Muxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/** The finished audio file, ready to be saved. */
internal data class FinishedAudio(
    val file: File,
    val extension: String,
    val mimeType: String,
    val metadata: TrackMetadata,
    /** Things that didn't go as asked but didn't cost the song, shown next to the result. */
    val warnings: List<String>,
)

/**
 * Turns the downloaded stream into the file the user asked for: optional analysis (loudness,
 * silence), optional trim, then M4A tagging, Opus remuxing or MP3 encoding.
 *
 * Rule: post-processing never costs the song. If a step fails, the best file that can still be
 * made is saved and a warning says what was skipped.
 */
internal class AudioFinisher(private val toolkit: Toolkit, private val workDir: File) {

    suspend fun finish(
        raw: File,
        container: AudioContainer,
        metadata: TrackMetadata,
        processing: Processing,
        onStep: (ProcessingStep) -> Unit,
    ): FinishedAudio = withContext(Dispatchers.IO) { finishHere(raw, container, metadata, processing, onStep) }

    private suspend fun finishHere(
        raw: File,
        container: AudioContainer,
        metadata: TrackMetadata,
        processing: Processing,
        onStep: (ProcessingStep) -> Unit,
    ): FinishedAudio {
        val warnings = mutableListOf<String>()
        val decoder = toolkit.decoder
        var range = processing.trim
        var md = metadata

        if (processing.needsAnalysis) {
            if (decoder == null) {
                warnings += "Loudness and silence trimming need an audio decoder, which isn't available."
            } else {
                onStep(ProcessingStep.ANALYZING)
                try {
                    val analyzer = AudioAnalyzer()
                    runInterruptible { decoder.decode(raw, analyzer) }
                    currentCoroutineContext().ensureActive()
                    if (processing.trimSilence) {
                        range = range.intersect(analyzer.audibleRange()) ?: range
                    }
                    if (processing.replayGain) {
                        md = md.copy(replayGain = analyzer.replayGain(range))
                        if (md.replayGain == null) warnings += "No loudness tag: the track is silent."
                    }
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive() // an interrupted cancel surfaces as an IOException
                    warnings += "Couldn't analyse the audio (${e.message}); saved without loudness/silence processing."
                }
            }
        }

        var format = processing.audioFormat
        if (format == AudioFormat.MP3 && decoder == null) {
            warnings += "MP3 export needs an audio decoder, which isn't available; saved as ${container.name}."
            format = if (container == AudioContainer.WEBM_OPUS) AudioFormat.OPUS else AudioFormat.M4A
        }
        if (format == AudioFormat.OPUS && container != AudioContainer.WEBM_OPUS) {
            warnings += "This video has no Opus stream; saved as M4A instead."
            format = AudioFormat.M4A
        }
        if (format == AudioFormat.M4A && container == AudioContainer.WEBM_OPUS) {
            warnings += "This video has no AAC stream; saved as Opus instead."
            format = AudioFormat.OPUS
        }

        return when (format) {
            AudioFormat.MP3 -> {
                onStep(ProcessingStep.ENCODING_MP3)
                val out = File(workDir, raw.nameWithoutExtension + ".mp3")
                try {
                    runInterruptible { Mp3Exporter.export(raw, decoder!!, out, md, range) }
                    currentCoroutineContext().ensureActive()
                    FinishedAudio(out, "mp3", "audio/mpeg", md, warnings)
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive() // an interrupted cancel surfaces as an IOException
                    warnings += "MP3 encoding failed (${e.message}); saved in YouTube's original format instead."
                    finishOriginal(raw, container, md, range, warnings, onStep)
                }
            }
            else -> finishOriginal(raw, container, md, range, warnings, onStep)
        }
    }

    /** M4A: (trim +) tag. Opus: remux to Ogg with tags (+ trim). Untagged original as last resort. */
    private suspend fun finishOriginal(
        raw: File,
        container: AudioContainer,
        md: TrackMetadata,
        range: TimeRange,
        warnings: MutableList<String>,
        onStep: (ProcessingStep) -> Unit,
    ): FinishedAudio {
        if (container == AudioContainer.WEBM_OPUS) {
            onStep(ProcessingStep.REMUXING_OPUS)
            val out = File(workDir, raw.nameWithoutExtension + ".opus")
            return try {
                runInterruptible { WebmOpusRemuxer.remux(raw, out, md, range) }
                FinishedAudio(out, "opus", "audio/ogg", md, warnings)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive() // an interrupted cancel surfaces as an IOException
                warnings += "Saved as WebM without embedded tags: ${e.message}"
                FinishedAudio(raw, container.extension, container.mimeType, md, warnings)
            }
        }
        var source = raw
        if (!range.isWholeTrack) {
            val trimmed = File(workDir, raw.nameWithoutExtension + ".trimmed.m4a")
            try {
                runInterruptible { DashMp4Muxer.trimAudio(raw, trimmed, range) }
                source = trimmed
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive() // an interrupted cancel surfaces as an IOException
                warnings += "Couldn't trim (${e.message}); saved the whole track."
            }
        }
        currentCoroutineContext().ensureActive()
        val tagged = File(workDir, raw.nameWithoutExtension + ".tagged.m4a")
        return try {
            runInterruptible { Mp4Tagger.tag(source, tagged, md) }
            FinishedAudio(tagged, "m4a", container.mimeType, md, warnings)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // an interrupted cancel surfaces as an IOException
            warnings += "Saved without embedded tags: ${e.message}"
            FinishedAudio(source, "m4a", container.mimeType, md, warnings)
        }
    }
}
