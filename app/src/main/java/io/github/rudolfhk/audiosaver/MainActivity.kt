package io.github.rudolfhk.audiosaver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.rudolfhk.audiosaver.ui.AudioSaverTheme
import io.github.rudolfhk.audiosaver.ui.FileActions
import io.github.rudolfhk.audiosaver.core.link.VideoId
import io.github.rudolfhk.audiosaver.core.pipeline.SavedAudio
import io.github.rudolfhk.audiosaver.ui.MainActions
import io.github.rudolfhk.audiosaver.ui.MainScreen
import io.github.rudolfhk.audiosaver.ui.MainUiState
import io.github.rudolfhk.audiosaver.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel(audioSaverApp.controller) } }
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

            AudioSaverTheme {
                MainScreen(MainUiState(input, parsed, target, justThisSong, download, collection, batch), actions)
            }
        }
        // Only on a fresh launch: after rotation the ViewModel already holds the text.
        if (savedInstanceState == null) handleShare(intent)
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
