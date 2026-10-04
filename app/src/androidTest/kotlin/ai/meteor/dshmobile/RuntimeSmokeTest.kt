package ai.meteor.dshmobile

import android.app.KeyguardManager
import android.content.Intent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.meteor.dshmobile.runtime.RuntimeService
import ai.meteor.dsh.runtime.RuntimeManager
import ai.meteor.dsh.runtime.RuntimeMode
import ai.meteor.dsh.runtime.DshEnvironment
import ai.meteor.ubuntu.runtime.UbuntuCommand
import ai.meteor.dsh.runtime.RuntimePhase
import ai.meteor.dsh.runtime.RuntimeStateStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Full app regression test. Deliberately refuses to install/replace the user's production rootfs. */
@RunWith(AndroidJUnit4::class)
class RuntimeSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun installsStartsDisplaysAndStopsBothRootlessBackends() = runBlocking {
        check(context.packageName.endsWith(".smoke")) {
            "Run with -PSMOKE_TEST_APPLICATION_ID_SUFFIX=.smoke to isolate test data"
        }
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        instrumentation.runOnMainSync {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.getSystemService(KeyguardManager::class.java).requestDismissKeyguard(activity, null)
        }
        val manager = RuntimeManager.get(context)
        suspend fun action(name: String, expected: RuntimePhase, timeoutMillis: Long = 120_000) {
            ContextCompat.startForegroundService(context, RuntimeService.intent(context, name))
            val state = withTimeout(timeoutMillis) {
                RuntimeStateStore.state.first { it.phase == expected || it.phase == RuntimePhase.Failed }
            }
            assertEquals("Runtime failure: ${state.logTail.takeLast(10)}", expected, state.phase)
        }
        try {
            manager.probe()
            assertTrue(RuntimeStateStore.state.value.phase != RuntimePhase.Unavailable)
            if (RuntimeStateStore.state.value.phase != RuntimePhase.Ready) {
                Log.i(TAG, "Installing verified image through RuntimeService")
                action(RuntimeService.ACTION_INSTALL, RuntimePhase.Ready, 15 * 60_000)
            }
            val sharedDirectory = context.cacheDir.toPath().resolve("generic-bind-test")
            Files.createDirectories(sharedDirectory)
            Files.write(sharedDirectory.resolve("input.txt"), "mounted-directory".toByteArray())
            val generic = DshEnvironment(context).execute(UbuntuCommand(
                arguments = listOf("/bin/bash", "-lc",
                    "test ! -e /opt/node && test ! -e /opt/dsh && ! command -v node && " +
                    "cat input.txt && printf 'mount-output' > output.txt && python3 --version"),
                bindings = mapOf("/test-tools" to sharedDirectory), workingDirectory = "/test-tools",
            ))
            assertEquals("Ubuntu base must run independently without Node or DSH: ${generic.output}", 0, generic.exitCode)
            assertTrue(generic.output.contains("mounted-directory"))
            assertEquals("mount-output", String(Files.readAllBytes(sharedDirectory.resolve("output.txt"))))
            Log.i(TAG, "Independent Ubuntu command and directory binding verified; base contains no Node or DSH")
            val requestedMode = InstrumentationRegistry.getArguments().getString("runtimeMode")
            val modes = requestedMode?.let { listOf(RuntimeMode.valueOf(it)) }
                ?: listOf(RuntimeMode.Proot, RuntimeMode.Proroot)
            for (mode in modes) {
                manager.selectRuntimeMode(mode)
                Log.i(TAG, "Starting ${mode.name} through foreground service")
                action(RuntimeService.ACTION_START, RuntimePhase.Running)
                val url = requireNotNull(RuntimeStateStore.state.value.webUrl)
                val backendPort = requireNotNull(RuntimeStateStore.state.value.logTail.firstNotNullOfOrNull {
                    Regex("dsh web: http://127\\.0\\.0\\.1:(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull()
                }) { "DSH backend must report its own dynamically allocated port" }
                val handshake = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    instanceFollowRedirects = false
                }
                val cookie = try {
                    assertEquals("Launch token must exchange for a session cookie", 302, handshake.responseCode)
                    requireNotNull(handshake.getHeaderField("Set-Cookie")).substringBefore(';')
                } finally {
                    handshake.disconnect()
                }
                val authenticated = (URL(url.substringBefore('?')).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Cookie", cookie)
                }
                try {
                    assertEquals(200, authenticated.responseCode)
                    val html = authenticated.inputStream.bufferedReader().use { it.readText() }
                    assertTrue("Authenticated gateway must return DSH HTML", html.contains("<html", true))
                    assertTrue("Mobile styles must be included", html.contains("mobile", true))
                } finally {
                    authenticated.disconnect()
                }
                val unauthenticated = (URL(url.substringBefore('?')).openConnection() as HttpURLConnection)
                try {
                    assertEquals("Gateway must reject unauthenticated requests", 401, unauthenticated.responseCode)
                } finally {
                    unauthenticated.disconnect()
                }
                Log.i(TAG, "${mode.name}: gateway HTTP authentication verified")
                val body = withTimeout(60_000) {
                    var text = ""
                    while (!text.contains("DeepSeek", true) && !text.contains("Harness", true)) {
                        val result = CompletableDeferred<String>()
                        instrumentation.runOnMainSync {
                            val webView = findWebView(activity.window.decorView)
                            if (webView == null || webView.url?.substringBefore('?') != url.substringBefore('?')) result.complete("")
                            else webView.evaluateJavascript("document.body ? document.body.innerText : ''") { result.complete(it) }
                        }
                        text = result.await()
                        if (!text.contains("DeepSeek", true) && !text.contains("Harness", true)) delay(500)
                    }
                    text
                }
                assertTrue(body.isNotBlank())
                suspend fun evaluate(script: String): String {
                    val result = CompletableDeferred<String>()
                    instrumentation.runOnMainSync {
                        requireNotNull(findWebView(activity.window.decorView)).evaluateJavascript(script) { result.complete(it) }
                    }
                    return withTimeout(5_000) { result.await() }
                }
                suspend fun waitForPage(script: String) {
                    try {
                        withTimeout(10_000) { while (evaluate(script) != "true") delay(100) }
                    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                        throw AssertionError("WebView condition failed: $script", error)
                    }
                }
                // A real hit test matters: button.click() alone bypasses blocking layers.
                waitForPage("[...document.querySelectorAll('[role=dialog] button')].some(b=>/^(继续|Continue)$|稍后配置|skip|later/i.test(b.textContent.trim()))")
                evaluate("""
                    [...document.querySelectorAll('[role="dialog"] button')]
                      .find(b=>/^(继续|Continue)$/i.test(b.textContent.trim()))?.click();
                """.trimIndent())
                // The API-key dialog mounts after the preview dialog unmounts.
                waitForPage("[...document.querySelectorAll('[role=dialog] button')].some(b=>/稍后配置|skip|later/i.test(b.textContent))")
                evaluate("""
                    [...document.querySelectorAll('[role="dialog"] button')]
                      .find(b=>/稍后配置|skip|later/i.test(b.textContent))?.click();
                """.trimIndent())
                waitForPage("!document.querySelector('[role=dialog]')")
                // Restore the closed drawer if a previous run saved fullscreen mode.
                evaluate("""
                    if(document.querySelector('[data-dsh-mobile-frame]').hasAttribute('data-rightbar-fullscreen'))
                      [...document.querySelectorAll('[data-dsh-mobile-role=details] button')]
                        .find(b=>/^Exit fullscreen$|^退出全屏$/.test(b.getAttribute('aria-label')||''))?.click();
                """.trimIndent())
                waitForPage("""
                    (()=>{const b=document.querySelector('[data-dsh-mobile-ui-menu]');
                    if(!b||b.hidden)return false;const r=b.getBoundingClientRect();
                    return r.width>0&&b.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))})()
                """.trimIndent())
                evaluate("document.querySelector('[data-dsh-mobile-ui-menu]').click()")
                waitForPage("!document.querySelector('[data-dsh-mobile-frame]').hasAttribute('data-sidebar-collapsed')")
                waitForPage("""
                    (()=>{const b=[...document.querySelectorAll('[data-dsh-mobile-role=sidebar] button')]
                    .find(b=>/^(Settings|设置)$/.test(b.getAttribute('aria-label')||''));
                    if(!b)return false;const r=b.getBoundingClientRect();
                    return b.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))})()
                """.trimIndent())
                evaluate("[...document.querySelectorAll('[data-dsh-mobile-role=sidebar] button')].find(b=>/^(Settings|设置)$/.test(b.getAttribute('aria-label')||'')).click()")
                waitForPage("!!document.querySelector('[data-dsh-mobile-settings]')")
                evaluate("[...document.querySelectorAll('[data-dsh-mobile-settings] button')].find(b=>/^(Close|Close settings|关闭|关闭设置)$/i.test(b.getAttribute('aria-label')||b.textContent.trim())).click()")
                waitForPage("!document.querySelector('[data-dsh-mobile-settings]')")
                evaluate("document.querySelector('[data-dsh-mobile-ui-scrim]').click()")
                waitForPage("document.querySelector('[data-dsh-mobile-frame]').hasAttribute('data-sidebar-collapsed')")
                evaluate("[...document.querySelectorAll('button')].find(b=>/^Open right sidebar$|^打开右侧边栏$/.test(b.getAttribute('aria-label')||'')).click()")
                waitForPage("document.querySelector('[data-dsh-mobile-role=details]').getBoundingClientRect().left < innerWidth / 2")
                waitForPage("""
                    (()=>{const b=[...document.querySelectorAll('[data-dsh-mobile-role=details] button')]
                    .find(b=>/^Exit fullscreen$|^退出全屏$/.test(b.getAttribute('aria-label')||''));
                    if(!b)return false;const r=b.getBoundingClientRect();
                    return b.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2))})()
                """.trimIndent())
                evaluate("[...document.querySelectorAll('[data-dsh-mobile-role=details] button')].find(b=>/^Exit fullscreen$|^退出全屏$/.test(b.getAttribute('aria-label')||'')).click()")
                waitForPage("document.querySelector('[data-dsh-mobile-role=details]').getBoundingClientRect().left>=innerWidth")
                Log.i(TAG, "${mode.name}: authenticated HTTP 200, unauthenticated HTTP 401, WebView rendered DSH")
                // Test each mode separately to check backgrounding on devices that block
                // bringing an activity back to the foreground from instrumentation.
                if (mode == modes.last()) {
                    instrumentation.runOnMainSync { activity.moveTaskToBack(true) }
                    delay(2_000)
                    assertEquals("Runtime must survive backgrounding", RuntimePhase.Running, RuntimeStateStore.state.value.phase)
                }
                assertTrue("Backend must remain healthy", RuntimeStateStore.state.value.logTail.none { it.contains("EADDRINUSE") })
                action(RuntimeService.ACTION_STOP, RuntimePhase.Ready)
                val gatewayPort = URL(url).port
                assertTrue("Gateway must stop accepting connections", !canConnect(gatewayPort))
                assertTrue("DSH backend must stop accepting connections", !canConnect(backendPort))
                Log.i(TAG, "${mode.name}: background lifecycle and stop verified")
            }
        } finally {
            manager.stop()
            context.stopService(RuntimeService.intent(context, RuntimeService.ACTION_STOP))
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun canConnect(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    }.getOrDefault(false)
}

private const val TAG = "DshSmokeTest"
