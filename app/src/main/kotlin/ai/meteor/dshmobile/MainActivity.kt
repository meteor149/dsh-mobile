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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import ai.meteor.dshmobile.runtime.RuntimeManager
import ai.meteor.dshmobile.runtime.RuntimePhase
import ai.meteor.dshmobile.runtime.RuntimeService
import ai.meteor.dshmobile.runtime.RuntimeStateStore
import ai.meteor.dshmobile.ui.DshMobileApp
import ai.meteor.dshmobile.ui.DshMobileTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            RuntimeManager.get(this@MainActivity).probe()
        }

        setContent {
            DshMobileTheme {
                val state = RuntimeStateStore.state.collectAsStateWithLifecycle().value
                val webUrl = state.webUrl
                var showWebView = androidx.compose.runtime.remember(state.webUrl) { state.webUrl != null }

                if (state.phase == RuntimePhase.Running && webUrl != null && showWebView) {
                    RuntimeWebView(
                        url = webUrl,
                        onBackToBackground = ::sendTaskToBackground,
                    )
                } else {
                    DshMobileApp(
                        state = state,
                        onInstall = { launchRuntimeAction(RuntimeService.ACTION_INSTALL) },
                        onStart = { launchRuntimeAction(RuntimeService.ACTION_START) },
                        onOpen = { showWebView = true },
                        onStop = { launchRuntimeAction(RuntimeService.ACTION_STOP) },
                    )
                }
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
    onBackToBackground: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val expected = androidx.compose.runtime.remember(url) { url.toUri() }
    val webView = androidx.compose.runtime.remember(url) {
        createLockedDownWebView(context, expected).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            doOnLayout { loadUrl(url) }
        }
    }

    BackHandler(onBack = onBackToBackground)
    androidx.compose.runtime.DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { webView },
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing),
    )
}

private fun createLockedDownWebView(context: Context, expected: Uri): WebView = WebView(context).apply {
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url
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
