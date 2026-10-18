package io.github.rudolfhk.audiosaver.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rudolfhk.audiosaver.core.link.LinkParseResult
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.link.YouTubeLinkParser
import io.github.rudolfhk.audiosaver.core.pipeline.DownloadState
import io.github.rudolfhk.audiosaver.download.DownloadController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MainViewModel(private val controller: DownloadController) : ViewModel() {

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    /** Validated locally on every change - instant feedback, no network. */
    val parsed: StateFlow<LinkParseResult> = _input
        .map(YouTubeLinkParser::parse)
        .stateIn(viewModelScope, SharingStarted.Eagerly, YouTubeLinkParser.parse(""))

    val download: StateFlow<DownloadState> = controller.state

    fun onInputChanged(text: String) {
        _input.value = text
        controller.dismissResult()
    }

    /** Text shared from another app (ACTION_SEND). Shared texts often wrap the link in a title. */
    fun onSharedText(text: String) = onInputChanged(text)

    fun startDownload(context: Context) {
        val result = parsed.value as? LinkParseResult.Valid ?: return
        controller.start(context, result.videoId)
    }

    fun retry(context: Context, videoId: VideoId) {
        controller.dismissResult()
        controller.start(context, videoId)
    }

    fun cancel() = controller.cancel()
}
