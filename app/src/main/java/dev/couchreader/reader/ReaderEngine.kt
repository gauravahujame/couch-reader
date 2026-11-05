package dev.couchreader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.couchreader.data.Book
import dev.couchreader.data.BookRepository
import dev.couchreader.data.Locator
import dev.couchreader.data.ReadingPreferences
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/** Only this adapter knows EPUB.js. Virtual HTTPS resources never leave the device. */
interface ReaderEngine {
    val view: android.view.View
    val reflowable: Boolean get() = true
    fun moveVertical(direction: Int) {}
    fun turn(forward: Boolean)
    fun preferences(value: ReadingPreferences)
    fun chapter(href: String)
    fun close()
}
@SuppressLint("SetJavaScriptEnabled")
class EpubReaderEngine(context: Context, books: BookRepository, book: Book, locator: Locator?,
                       prefs: ReadingPreferences, private val onEvent: (String, String) -> Unit) : ReaderEngine {
    private val directory = books.directory(book).canonicalFile
    override val view = WebView(context)
    init {
        view.apply {
            isFocusable = false; isFocusableInTouchMode = false
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false; allowContentAccess = false
                domStorageEnabled = false; databaseEnabled = false
                blockNetworkLoads = true; blockNetworkImage = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = true
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun event(type: String, payload: String) {
                    if (payload.length <= 512 * 1024) post { onEvent(type, payload) }
                }
            }, "ReaderBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    if (uri.scheme != "https" || uri.host != "reader.invalid") return denied()
                    val path = uri.path ?: return denied()
                    return try {
                        val stream = when {
                            path.startsWith("/reader/") -> context.assets.open(path.removePrefix("/"))
                            path.startsWith("/book/") -> {
                                val file = File(directory, path.removePrefix("/book/")).canonicalFile
                                if (!file.path.startsWith(directory.path + File.separator) || !file.isFile) return denied()
                                file.inputStream()
                            }
                            else -> return denied()
                        }
                        val type = when (path.substringAfterLast('.').lowercase()) {
                            "html", "htm" -> "text/html"
                            "xhtml" -> "application/xhtml+xml"
                            "js" -> "application/javascript"
                            "css" -> "text/css"
                            "ttf" -> "font/ttf"
                            "png" -> "image/png"
                            "jpg", "jpeg" -> "image/jpeg"
                            "gif" -> "image/gif"
                            "webp" -> "image/webp"
                            "svg" -> "image/svg+xml"
                            else -> "application/xml"
                        }
                        WebResourceResponse(type, "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), stream)
                    } catch (_: Exception) { denied() }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (url != "https://reader.invalid/reader/index.html") return
                    val config = JSONObject().put("url", "https://reader.invalid/book/${book.contentPath}")
                        .put("preferences", preferencesJson(prefs))
                    locator?.let { config.put("locator", JSONObject(it.encode())) }
                    view.evaluateJavascript("openBook($config)", null)
                }
            }
            loadUrl("https://reader.invalid/reader/index.html")
        }
    }
    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun preferencesJson(value: ReadingPreferences) = JSONObject().put("fontSize", ReadingPreferences.sizes[value.size]).put("dark", value.dark)
    override fun turn(forward: Boolean) { view.evaluateJavascript("turn($forward)", null) }
    override fun preferences(value: ReadingPreferences) { view.evaluateJavascript("preferences(${preferencesJson(value)})", null) }
    override fun chapter(href: String) { view.evaluateJavascript("chapter(${JSONObject.quote(href)})", null) }
    override fun close() { view.stopLoading(); view.removeJavascriptInterface("ReaderBridge"); view.destroy() }
}
