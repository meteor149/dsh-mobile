package ai.meteor.dshmobile.download

import android.app.Application
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import ai.meteor.dshmobile.R

data class SaveRequest(val id: String, val filename: String, val mime: String)
data class DownloadState(
    val filename: String = "", val active: Boolean = false, val received: Long = 0,
    val total: Long? = null, val saveRequest: SaveRequest? = null, val message: String? = null,
)

/** Owns transfers across Activity recreation. Bytes never pass through an external downloader. */
class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(DownloadState())
    val state = mutableState.asStateFlow()
    private class Transfer(val id: String, val file: File) {
        val ready = CompletableDeferred<File>()
        var output: FileOutputStream? = null
        var sequence = 0
        var size = 0L
        var expected = 0L
        var blob = false
        var producer: Job? = null
        var writer: Job? = null
    }
    private var transfer: Transfer? = null
    private var cancelledId: String? = null
    private val app get() = getApplication<Application>()
    private val directory get() = File(app.cacheDir, "web-downloads").apply { mkdirs() }

    init {
        synchronized(DownloadViewModel::class.java) {
            if (!cacheCleaned) {
                directory.listFiles().orEmpty().forEach { it.delete() }
                cacheCleaned = true
            }
        }
    }
    private companion object { var cacheCleaned = false }

    fun attach(web: WebView, origin: String) {
        val script = app.assets.open("web-download.js").bufferedReader().use { it.readText() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "__dshDownload", setOf(origin)) { _, message, source, mainFrame, reply ->
                if (!mainFrame || source.toString().trimEnd('/') != origin) return@addWebMessageListener
                val input = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull() ?: return@addWebMessageListener
                val id = input.optString("id")
                val cookie = CookieManager.getInstance().getCookie(origin).orEmpty()
                viewModelScope.launch {
                    val response = JSONObject().put("id", id)
                    try { handleMessage(input, origin, cookie, web.settings.userAgentString) }
                    catch (error: Exception) {
                        if (error !is CancellationException && transfer?.id == id) reportFailure(error)
                        response.put("error", error.message ?: "Download failed")
                    }
                    runCatching { reply.postMessage(response.toString()) }
                }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, script, setOf(origin))
            }
        }
        web.setDownloadListener { url, agent, disposition, mime, _ ->
            when {
                isDownloadOrigin(url, origin) -> http(url, origin, CookieManager.getInstance().getCookie(url).orEmpty(),
                    agent, responseFilename(disposition, URLUtil.guessFileName(url, disposition, mime)), mime)
                url.startsWith("blob:") || url.startsWith("data:") -> if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) web.evaluateJavascript(
                    "window.__dshExportDownload && window.__dshExportDownload(${JSONObject.quote(url)}, ${JSONObject.quote(URLUtil.guessFileName(url, disposition, mime))})", null)
                    else reportFailure(IllegalStateException("Please update Android System WebView"))
                else -> mutableState.value = mutableState.value.copy(message = app.getString(R.string.download_invalid_source))
            }
        }
    }

    fun pageFinished(web: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            web.evaluateJavascript(app.assets.open("web-download.js").bufferedReader().use { it.readText() }, null)
        }
    }

    private fun begin(id: String, name: String, mime: String, size: Long? = null, deferPicker: Boolean = false): Transfer {
        check(transfer == null) { app.getString(R.string.download_busy) }
        val next = Transfer(id, File.createTempFile("export-", ".part", directory))
        transfer = next
        val filename = safeDownloadName(name)
        val type = mime.substringBefore(';').takeIf { it.matches(Regex("[\\w.+-]+/[\\w.+-]+")) } ?: "application/octet-stream"
        mutableState.value = DownloadState(filename, true, total = size,
            saveRequest = if (deferPicker) null else SaveRequest(id, filename, type))
        return next
    }

    private suspend fun handleMessage(input: JSONObject, origin: String, cookie: String, agent: String) {
        when (input.getString("type")) {
            "busy" -> mutableState.value = mutableState.value.copy(message = app.getString(R.string.download_busy))
            "http" -> http(input.getString("url"), origin, cookie, agent, input.optString("filename"), "")
            "begin" -> {
                val size = input.getLong("size")
                require(size >= 0)
                val current = begin(input.getString("id"), input.optString("filename"), input.optString("mime"), size)
                current.blob = true
                current.expected = size
                withContext(Dispatchers.IO) { synchronized(current) {
                    check(!current.ready.isCancelled)
                    current.output = FileOutputStream(current.file)
                } }
            }
            "chunk" -> {
                val current = requireNotNull(transfer)
                check(input.getString("id") == current.id && input.getInt("sequence") == current.sequence)
                val data = input.getString("data")
                require(data.length <= 90_000)
                val bytes = Base64.decode(data, Base64.NO_WRAP)
                require(bytes.size <= 65_536 && current.size + bytes.size <= current.expected)
                withContext(Dispatchers.IO) { synchronized(current) { requireNotNull(current.output).write(bytes) } }
                current.sequence++
                current.size += bytes.size
                mutableState.value = mutableState.value.copy(received = current.size)
            }
            "end" -> {
                val current = requireNotNull(transfer)
                check(input.getString("id") == current.id && current.size == current.expected)
                withContext(Dispatchers.IO) { current.output?.close(); current.output = null }
                current.ready.complete(current.file)
            }
            "error" -> {
                if (transfer == null && input.optString("id") != cancelledId) reportFailure(IllegalStateException(input.optString("error")))
                else if (transfer?.id == input.optString("id")) error(input.optString("error"))
            }
        }
    }

    fun http(url: String, origin: String, cookie: String, agent: String, name: String, mime: String?) {
        if (transfer != null) {
            mutableState.value = mutableState.value.copy(message = app.getString(R.string.download_busy)); return
        }
        if (!isDownloadOrigin(url, origin)) {
            mutableState.value = mutableState.value.copy(message = app.getString(R.string.download_invalid_source)); return
        }
        val current = begin(java.util.UUID.randomUUID().toString(), name.ifBlank { URLUtil.guessFileName(url, null, mime) }, mime.orEmpty(), deferPicker = true)
        current.producer = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    var target = url
                    var redirects = 0
                    while (true) {
                        require(isDownloadOrigin(target, origin)) { "Download redirect left the local gateway" }
                        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = false
                            connectTimeout = 15_000; readTimeout = 30_000
                            setRequestProperty("Cookie", cookie)
                            setRequestProperty("User-Agent", agent)
                            setRequestProperty("Accept-Encoding", "identity")
                        }
                        try {
                            val status = connection.responseCode
                            if (status in listOf(301, 302, 303, 307, 308)) {
                                check(++redirects <= 5)
                                target = URL(URL(target), requireNotNull(connection.getHeaderField("Location"))).toString()
                                continue
                            }
                            check(status in 200..299) { "HTTP $status" }
                            val total = connection.contentLengthLong.takeIf { it >= 0 }
                            val filename = responseFilename(connection.getHeaderField("Content-Disposition"), mutableState.value.filename)
                            val type = connection.contentType?.substringBefore(';')?.takeIf { it.matches(Regex("[\\w.+-]+/[\\w.+-]+")) }
                                ?: "application/octet-stream"
                            withContext(Dispatchers.Main) {
                                ensureActive()
                                if (transfer === current) mutableState.value = mutableState.value.copy(
                                    filename = filename, total = total, saveRequest = SaveRequest(current.id, filename, type))
                            }
                            connection.inputStream.use { source -> current.file.outputStream().use { output ->
                                val buffer = ByteArray(65_536)
                                while (true) {
                                    ensureActive()
                                    val count = source.read(buffer)
                                    if (count == -1) break
                                    output.write(buffer, 0, count)
                                    current.size += count
                                    withContext(Dispatchers.Main) {
                                        if (transfer === current) mutableState.value = mutableState.value.copy(received = current.size, total = total)
                                    }
                                }
                            } }
                            check(total == null || total == current.size) { "Incomplete download" }
                            break
                        } finally { connection.disconnect() }
                    }
                }
                current.ready.complete(current.file)
            } catch (error: Exception) { if (error !is CancellationException && transfer === current) reportFailure(error) }
            finally { if (transfer !== current) withContext(NonCancellable + Dispatchers.IO) { current.file.delete() } }
        }
    }

    fun pickerLaunched() { mutableState.value = mutableState.value.copy(saveRequest = null) }

    fun destinationSelected(uri: Uri?) {
        val current = transfer ?: return
        if (uri == null) { cancel(); return }
        current.writer = viewModelScope.launch {
            try {
                val file = current.ready.await()
                withContext(Dispatchers.IO) {
                    val output = requireNotNull(app.contentResolver.openOutputStream(uri, "wt"))
                    output.use { target -> file.inputStream().use { source ->
                        val bytes = ByteArray(65_536)
                        while (true) {
                            ensureActive()
                            val count = source.read(bytes)
                            if (count == -1) break
                            target.write(bytes, 0, count)
                        }
                    } }
                }
                cleanup(current)
                mutableState.value = DownloadState(message = app.getString(R.string.download_saved))
            } catch (error: Exception) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
                }
                if (error !is CancellationException && transfer === current) reportFailure(error)
            }
        }
    }

    fun detach() {
        if (transfer?.let { it.blob && !it.ready.isCompleted } == true) {
            reportFailure(IllegalStateException("Download page was closed"))
        }
    }

    fun dismissMessage() { mutableState.value = mutableState.value.copy(message = null) }
    fun cancel() {
        transfer?.let { current ->
            cancelledId = current.id
            current.producer?.cancel(); current.writer?.cancel(); current.ready.cancel()
            cleanup(current)
        }
        mutableState.value = DownloadState()
    }
    fun reportFailure(error: Throwable) {
        val message = app.getString(R.string.download_failed, error.message.orEmpty().take(160))
        cancel()
        mutableState.value = DownloadState(message = message)
    }
    private fun cleanup(current: Transfer) {
        synchronized(current) { runCatching { current.output?.close() }; current.output = null; current.file.delete() }
        if (transfer === current) transfer = null
    }
    override fun onCleared() { cancel(); super.onCleared() }
}
