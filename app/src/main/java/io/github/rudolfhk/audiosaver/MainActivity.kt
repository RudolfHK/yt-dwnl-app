package io.github.rudolfhk.audiosaver

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.Modifier
import io.github.rudolfhk.audiosaver.core.update.UpdateStatus
import io.github.rudolfhk.audiosaver.ui.SettingsScreen
import io.github.rudolfhk.audiosaver.ui.TWO_PANE_MIN_WIDTH
import io.github.rudolfhk.audiosaver.ui.TagReviewDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.rudolfhk.audiosaver.core.history.HistoryEntry
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.pipeline.SavedAudio
import io.github.rudolfhk.audiosaver.core.video.VideoQuality
import io.github.rudolfhk.audiosaver.ui.AudioSaverTheme
import io.github.rudolfhk.audiosaver.ui.FileActions
import io.github.rudolfhk.audiosaver.ui.FormatPreferences
import io.github.rudolfhk.audiosaver.ui.HistoryActions
import io.github.rudolfhk.audiosaver.ui.HistoryScreen
import io.github.rudolfhk.audiosaver.ui.MainActions
import io.github.rudolfhk.audiosaver.ui.MainScreen
import io.github.rudolfhk.audiosaver.ui.MainUiState
import io.github.rudolfhk.audiosaver.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = audioSaverApp
                MainViewModel(app.controller, FormatPreferences(this@MainActivity), app.settings, app.updates)
            }
        }
    }
    private var askedForNotifications = false

    private val actions = object : MainActions {
        override fun onInputChanged(text: String) = viewModel.onInputChanged(text)
        override fun onDownload() = onDownloadClicked()
        override fun onCancel() = viewModel.cancel()
        override fun onRetry(videoId: VideoId) = viewModel.retry(this@MainActivity, videoId)
        override fun onPlay(saved: SavedAudio) = FileActions.play(this@MainActivity, saved)
        override fun onShare(saved: SavedAudio) = FileActions.share(this@MainActivity, saved)
        override fun onOpenFolder(saved: SavedAudio) = FileActions.openFolder(this@MainActivity, saved)
        override fun onJustThisSong(value: Boolean) = viewModel.setJustThisSong(value)
        override fun onToggle(videoId: VideoId) = viewModel.toggle(videoId)
        override fun onSelectAll(all: Boolean) = viewModel.selectAll(all)
        override fun onLoadMore() = viewModel.loadMore()
        override fun onReloadCollection() = viewModel.reloadCollection()
        override fun onRetryFailed() = viewModel.retryFailed(this@MainActivity)
        override fun onDismissBatch() = viewModel.dismissBatch()
        override fun onSaveAudio(value: Boolean) = viewModel.setSaveAudio(value)
        override fun onSaveVideo(value: Boolean) = viewModel.setSaveVideo(value)
        override fun onVideoQuality(quality: VideoQuality) = viewModel.setVideoQuality(quality)
        override fun onRetryVideo(videoId: VideoId) = viewModel.retryVideo(this@MainActivity, videoId)
        override fun onTrimStart(text: String) = viewModel.setTrimStart(text)
        override fun onTrimEnd(text: String) = viewModel.setTrimEnd(text)
        override fun onOpenUpdate(url: String) = openUrl(url)
        override fun onDismissUpdate(status: UpdateStatus) = viewModel.dismissUpdate(status)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    private val historyActions = object : HistoryActions {
        override fun onClose() = Unit // replaced per layout below
        override fun onPlay(saved: SavedAudio) = FileActions.play(this@MainActivity, saved)
        override fun onOpenFolder(saved: SavedAudio) = FileActions.openFolder(this@MainActivity, saved)
        override fun onRetry(entry: HistoryEntry) = viewModel.retryFromHistory(this@MainActivity, entry)
        override fun onClear() = viewModel.clearHistory()
    }

    /** Asked once, on the first download. Declining only hides the notification. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.startDownload(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val input by viewModel.input.collectAsStateWithLifecycle()
            val parsed by viewModel.parsed.collectAsStateWithLifecycle()
            val target by viewModel.target.collectAsStateWithLifecycle()
            val justThisSong by viewModel.justThisSong.collectAsStateWithLifecycle()
            val download by viewModel.download.collectAsStateWithLifecycle()
            val collection by viewModel.collection.collectAsStateWithLifecycle()
            val batch by viewModel.batch.collectAsStateWithLifecycle()
            val saveVideo by viewModel.saveVideo.collectAsStateWithLifecycle()
            val videoQuality by viewModel.videoQuality.collectAsStateWithLifecycle()
            val saveAudio by viewModel.saveAudio.collectAsStateWithLifecycle()
            val alreadySaved by viewModel.alreadySaved.collectAsStateWithLifecycle()
            val history by viewModel.history.collectAsStateWithLifecycle()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val trimStart by viewModel.trimStart.collectAsStateWithLifecycle()
            val trimEnd by viewModel.trimEnd.collectAsStateWithLifecycle()
            val update by viewModel.update.collectAsStateWithLifecycle()
            val updateHint by viewModel.updateHint.collectAsStateWithLifecycle()
            val pendingReview by viewModel.pendingReview.collectAsStateWithLifecycle()
            var showHistory by rememberSaveable { mutableStateOf(false) }
            var showSettings by rememberSaveable { mutableStateOf(false) }

            AudioSaverTheme {
                pendingReview?.let { review ->
                    TagReviewDialog(
                        review.proposal,
                        onSave = { viewModel.answerReview(review, it) },
                        onCancelDownload = { viewModel.answerReview(review, null) },
                    )
                }
                val mainState = MainUiState(
                    input, parsed, target, justThisSong, download, collection, batch,
                    saveVideo = saveVideo,
                    videoQuality = videoQuality,
                    saveAudio = saveAudio,
                    alreadySaved = alreadySaved,
                    audioFormat = settings.audioFormat,
                    trimEnabled = settings.manualTrim,
                    trimStart = trimStart,
                    trimEnd = trimEnd,
                    update = update,
                    updateSummary = update?.let(viewModel::updateSummary),
                    updateHint = updateHint,
                )
                BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH
                    when {
                        showSettings -> {
                            BackHandler { showSettings = false }
                            SettingsScreen(
                                settings,
                                viewModel.buildDescription,
                                onChange = viewModel::changeSettings,
                                onReset = viewModel::resetSettings,
                                onClose = { showSettings = false },
                            )
                        }
                        twoPane -> Row(Modifier.fillMaxSize()) {
                            // Tablets / unfolded phones: the history stays visible next to the downloader.
                            MainScreen(
                                mainState, actions,
                                onOpenSettings = { showSettings = true },
                                showHistoryButton = false,
                                modifier = Modifier.weight(1f),
                            )
                            VerticalDivider()
                            HistoryScreen(history, historyActions, showBack = false, modifier = Modifier.weight(1f))
                        }
                        showHistory -> {
                            BackHandler { showHistory = false }
                            HistoryScreen(
                                entries = history,
                                actions = object : HistoryActions by historyActions {
                                    override fun onClose() { showHistory = false }
                                    override fun onRetry(entry: HistoryEntry) {
                                        showHistory = false
                                        historyActions.onRetry(entry)
                                    }
                                },
                            )
                        }
                        else -> MainScreen(
                            mainState, actions,
                            onOpenHistory = { showHistory = true },
                            onOpenSettings = { showSettings = true },
                        )
                    }
                }
            }
        }
        // Only on a fresh launch: after rotation the ViewModel already holds the text.
        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.checkForUpdates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: return
        viewModel.onSharedText(text)
    }

    private fun onDownloadClicked() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        if (needsPermission && !askedForNotifications) {
            askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startDownload(this)
        }
    }
}
