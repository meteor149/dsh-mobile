package ai.meteor.dshmobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.LaunchedEffect
import android.widget.Toast
import ai.meteor.dshmobile.download.DownloadViewModel
import ai.meteor.dshmobile.download.DownloadProgress
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import ai.meteor.dsh.runtime.RuntimeManager
import ai.meteor.dsh.runtime.RuntimePhase
import ai.meteor.dshmobile.runtime.RuntimeService
import ai.meteor.dsh.runtime.RuntimeStateStore
import ai.meteor.dshmobile.ui.DshMobileApp
import ai.meteor.dshmobile.ui.DshMobileTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var downloads: DownloadViewModel
    private val saveDownload = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        downloads.destinationSelected(if (result.resultCode == RESULT_OK) result.data?.data else null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloads = ViewModelProvider(this)[DownloadViewModel::class.java]
        lifecycleScope.launch {
            val manager = RuntimeManager.get(this@MainActivity)
            manager.probe()
            lifecycle.withResumed {
                if (manager.claimRememberedLaunch()) launchRuntimeAction(RuntimeService.ACTION_START)
            }
        }

        setContent {
            DshMobileTheme {
                val downloadState = downloads.state.collectAsStateWithLifecycle().value
                LaunchedEffect(downloadState.saveRequest?.id) {
                    downloadState.saveRequest?.let { request ->
                        downloads.pickerLaunched()
                        runCatching {
                            saveDownload.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = request.mime
                                putExtra(Intent.EXTRA_TITLE, request.filename)
                            })
                        }.onFailure { downloads.reportFailure(it) }
                    }
                }
                LaunchedEffect(downloadState.message) {
                    downloadState.message?.let {
                        Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                        downloads.dismissMessage()
                    }
                }
                val state = RuntimeStateStore.state.collectAsStateWithLifecycle().value
                val webUrl = state.webUrl
                var showWebView = androidx.compose.runtime.remember(state.webUrl) { state.webUrl != null }

                if (state.phase == RuntimePhase.Running && webUrl != null && showWebView) {
                    RuntimeWebView(
                        url = webUrl,
                        downloads = downloads,
                        onBackToBackground = ::sendTaskToBackground,
                    )
                } else {
                    DshMobileApp(
                        state = state,
                        onInstall = { launchRuntimeAction(RuntimeService.ACTION_INSTALL) },
                        onStart = { launchRuntimeAction(RuntimeService.ACTION_START) },
                        onOpen = { showWebView = true },
                        onStop = { launchRuntimeAction(RuntimeService.ACTION_STOP) },
                        onModeChange = { mode ->
                            RuntimeManager.get(this@MainActivity).selectRuntimeMode(mode)
                        },
                        onRememberModeChange = { remember ->
                            RuntimeManager.get(this@MainActivity).setRememberRuntimeMode(remember)
                        },
                        onRequestRoot = {
                            lifecycleScope.launch {
                                RuntimeManager.get(this@MainActivity).requestRootAccess()
                            }
                        },
                    )
                }
                DownloadProgress(downloadState, downloads::cancel)
            }
        }
    }

    private fun launchRuntimeAction(action: String) {
        ContextCompat.startForegroundService(this, RuntimeService.intent(this, action))
    }

    private fun sendTaskToBackground() {
        moveTaskToBack(true)
    }
}

@androidx.compose.runtime.Composable
private fun RuntimeWebView(
    url: String,
    downloads: DownloadViewModel,
    onBackToBackground: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val expected = androidx.compose.runtime.remember(url) { url.toUri() }
    val webView = androidx.compose.runtime.remember(url) {
        createLockedDownWebView(context, expected, downloads).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            doOnLayout { loadUrl(url) }
        }
    }

    BackHandler { webView.navigateBackOr(onBackToBackground) }
    androidx.compose.runtime.DisposableEffect(webView) {
        onDispose {
            downloads.detach()
            webView.stopLoading()
            webView.destroy()
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { webView },
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .imePadding(),
    )
}

private const val WEB_HISTORY_BACK_SCRIPT =
    "(() => { if (window.navigation && window.navigation.canGoBack) { " +
        "window.history.back(); return true; } return false; })()"

private fun WebView.navigateBackOr(onNoHistory: () -> Unit) {
    if (canGoBack()) {
        goBack()
        return
    }
    evaluateJavascript(WEB_HISTORY_BACK_SCRIPT) { handled ->
        if (handled != "true") onNoHistory()
    }
}

internal fun createLockedDownWebView(context: Context, expected: Uri, downloads: DownloadViewModel): WebView = WebView(context).apply {
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setSupportZoom(false)
    settings.builtInZoomControls = false
    settings.displayZoomControls = false
    settings.loadWithOverviewMode = false
    settings.useWideViewPort = true
    settings.textZoom = 100
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    downloads.attach(this, "http://127.0.0.1:${expected.port}")
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) {
            downloads.pageFinished(view)
        }
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url
            if (target.scheme == "blob" || target.scheme == "data") return false
            val isRuntimeOrigin = target.scheme == "http" &&
                target.host == "127.0.0.1" &&
                target.port == expected.port
            if (isRuntimeOrigin) return false
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return true
        }
    }
}
