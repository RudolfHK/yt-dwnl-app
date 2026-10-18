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
import io.github.rudolfhk.audiosaver.ui.MainScreen
import io.github.rudolfhk.audiosaver.ui.MainViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory { initializer { MainViewModel(audioSaverApp.controller) } }
    }
    private var askedForNotifications = false

    /** Asked once, on the first download. Declining only hides the notification. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.startDownload(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val input by viewModel.input.collectAsStateWithLifecycle()
            val parsed by viewModel.parsed.collectAsStateWithLifecycle()
            val download by viewModel.download.collectAsStateWithLifecycle()

            AudioSaverTheme {
                MainScreen(
                    input = input,
                    parsed = parsed,
                    download = download,
                    onInputChanged = viewModel::onInputChanged,
                    onDownload = ::onDownloadClicked,
                    onCancel = viewModel::cancel,
                    onRetry = { viewModel.retry(this, it) },
                    onPlay = { FileActions.play(this, it) },
                    onShare = { FileActions.share(this, it) },
                    onOpenFolder = { FileActions.openFolder(this, it) },
                )
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
