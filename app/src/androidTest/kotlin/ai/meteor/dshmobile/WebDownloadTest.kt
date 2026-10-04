package ai.meteor.dshmobile

import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.WebView
import ai.meteor.dshmobile.download.DownloadViewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real WebView bridge and authenticated HTTP exporter without touching Ubuntu/user files. */
@RunWith(AndroidJUnit4::class)
class WebDownloadTest {
    @Test fun launchesSystemSavePickerAndWritesSelectedDocument() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "dsh-picker-test-${System.nanoTime()}.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DSH-tests")
        })!!
        var pickerIntent: android.content.Intent? = null
        val monitor = object : android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.content.Intent.ACTION_CREATE_DOCUMENT) return null
                pickerIntent = intent
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, android.content.Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        val server = ServerSocket(0)
        val body = "Export regression: 文件导出测试\n".toByteArray()
        thread(isDaemon = true) {
            (runCatching { server.accept() }.getOrNull() ?: return@thread).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
            }
        }
        lateinit var model: DownloadViewModel
        try {
            instrumentation.runOnMainSync {
                context.startActivity(android.content.Intent(context, MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            var activity: MainActivity? = null
            withTimeout(30_000) {
                while (activity == null) {
                    instrumentation.runOnMainSync {
                        activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                    }
                    delay(50)
                }
            }
            instrumentation.runOnMainSync {
                model = androidx.lifecycle.ViewModelProvider(activity!!)[DownloadViewModel::class.java]
                val origin = "http://127.0.0.1:${server.localPort}"
                model.http("$origin/file", origin, "", "test", "export.txt", "text/plain")
            }
            withTimeout(30_000) { while (pickerIntent == null || model.state.value.active) delay(50) }
            assertEquals("text/plain", pickerIntent!!.type)
            assertEquals("export.txt", pickerIntent!!.getStringExtra(android.content.Intent.EXTRA_TITLE))
            assertArrayEquals(body, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            instrumentation.removeMonitor(monitor)
            server.close()
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test fun exportsAuthenticatedHttpAndChunkedBlobAndRejectsRedirect() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // Remove only files left by interrupted runs of this test suite.
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("Download/DSH-tests/"), null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1).matches(Regex("dsh-(?:picker-test|test|live-verification)-[0-9]+\\.(?:txt|bin)"))) {
                    context.contentResolver.delete(android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0)), null, null)
                }
            }
        }
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val server = ServerSocket(0)
        val origin = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    val headers = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers += line }
                    val authenticated = headers.any { line -> line.startsWith("Cookie:", true) && line.contains("dsh_mobile_session=test") }
                    val body = if (path == "/file") bytes else "<!doctype html><title>Download test</title>".toByteArray()
                    val status = if (path == "/file" && !authenticated) "401 Unauthorized" else if (path == "/redirect") "302 Found" else "200 OK"
                    val extra = when (path) {
                        "/file" -> "Content-Type: application/octet-stream\r\nContent-Disposition: attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.bin\r\n"
                        "/redirect" -> "Location: https://example.com/private\r\n"
                        else -> "Content-Type: text/html\r\n"
                    }
                    runCatching { it.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\n${extra}Content-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    } }
                }
            }
        }
        lateinit var model: DownloadViewModel
        lateinit var web: WebView
        instrumentation.runOnMainSync {
            model = DownloadViewModel(context.applicationContext as Application)
            web = createLockedDownWebView(context, android.net.Uri.parse(origin), model)
            CookieManager.getInstance().setCookie(origin, "dsh_mobile_session=test; Path=/; HttpOnly")
            web.loadUrl(origin)
        }
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(30_000) { while (!condition()) delay(50) }
        suspend fun script(code: String): String {
            val result = CompletableDeferred<String>()
            instrumentation.runOnMainSync { web.evaluateJavascript(code) { result.complete(it) } }
            return withTimeout(5_000) { result.await() }
        }
        suspend fun save(expected: ByteArray, filename: String) {
            waitFor { model.state.value.saveRequest != null }
            assertEquals(filename, model.state.value.saveRequest!!.filename)
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "dsh-test-${System.nanoTime()}.bin")
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/DSH-tests")
            })!!
            try {
                instrumentation.runOnMainSync { model.pickerLaunched(); model.destinationSelected(uri) }
                waitFor { !model.state.value.active }
                assertTrue(model.state.value.message.orEmpty(), model.state.value.message?.contains("failed", true) != true)
                assertArrayEquals(expected, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            } finally { context.contentResolver.delete(uri, null, null) }
        }
        try {
            withTimeout(30_000) { while (script("Boolean(window.__dshExportDownload)") != "true") delay(100) }
            script("const a=document.createElement('a');a.href='$origin/file';a.download='fallback.bin';a.click();")
            save(bytes, "报告.bin")
            script("(()=>{const a=document.createElement('a');const b=new Uint8Array(${bytes.size});for(let i=0;i<b.length;i++)b[i]=i%251;const u=URL.createObjectURL(new Blob([b]));a.href=u;a.download='blob.bin';a.click();URL.revokeObjectURL(u)})()")
            save(bytes, "blob.bin")
            delay(100)
            script("(()=>{const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([]));a.download='empty.txt';a.click()})()")
            save(byteArrayOf(), "empty.txt")
            instrumentation.runOnMainSync { model.http("$origin/redirect", origin, "dsh_mobile_session=test", "test", "private", null) }
            waitFor { !model.state.value.active }
            assertTrue(model.state.value.message.orEmpty().contains("redirect"))
            assertNull(model.state.value.saveRequest)
            script("(()=>{const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([new Uint8Array(8*1024*1024)]));a.download='cancel.bin';a.click()})()")
            waitFor { model.state.value.active }
            instrumentation.runOnMainSync { model.cancel() }
            delay(500)
            assertFalse(model.state.value.active)
            assertTrue(java.io.File(context.cacheDir, "web-downloads").listFiles().orEmpty().isEmpty())
        } finally {
            instrumentation.runOnMainSync { model.cancel(); web.destroy() }
            server.close()
        }
    }
}
