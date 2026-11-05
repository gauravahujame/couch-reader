package dev.couchreader.importer

import dev.couchreader.data.BookRepository
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** One short-lived pairing session. Raw single-file bodies avoid multipart memory amplification. */
class UploadServer(private val books: BookRepository, private val importer: ImportBook,
                   private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
                   private val onState: (String, ImportResult?) -> Unit) : NanoHTTPD(0) {
    private data class Pairing(val token: String, val expires: Long)
    private fun newPairing() = Pairing(
        ByteArray(24).apply { SecureRandom().nextBytes(this) }.joinToString("") { "%02x".format(it) },
        now() + 10 * 60 * 1000
    )
    @Volatile private var pairing = newPairing()
    val token: String get() = pairing.token
    private val busy = AtomicBoolean(false)
    @Volatile private var closed = false
    fun remainingSeconds() = ((pairing.expires - now()).coerceAtLeast(0) / 1000).toInt()
    @Synchronized fun refreshPairing(): Boolean {
        if (closed || busy.get()) return false
        pairing = newPairing()
        return true
    }
    @Synchronized fun refreshIfExpired(): Boolean = now() >= pairing.expires && refreshPairing()
    override fun stop() { closed = true; super.stop() }
    private fun authorized(session: IHTTPSession): Boolean {
        val supplied = session.parameters["token"]?.singleOrNull() ?: return false
        val current = pairing
        return !closed && now() < current.expires && MessageDigest.isEqual(current.token.toByteArray(), supplied.toByteArray())
    }
    override fun serve(session: IHTTPSession): Response {
        if (!authorized(session)) return response(Response.Status.FORBIDDEN, "Pairing expired or invalid. Open Add book on your TV and scan the new code.")
        if (session.method == Method.GET && session.uri == "/") return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE).secure()
        if (session.method != Method.POST || session.uri != "/upload") return response(Response.Status.NOT_FOUND, "Not found")
        // Browser uploads are same-origin. Reject cross-site forms even if a pairing URL leaks.
        val origin = session.headers["origin"]
        if (origin != null && origin != "http://${session.headers["host"]}") return response(Response.Status.FORBIDDEN, "Use the paired upload page.")
        val length = session.headers["content-length"]?.toLongOrNull()
            ?: return response(Response.Status.LENGTH_REQUIRED, "Transfer needs a known file size. Try another browser.")
        if (length <= 0 || length > EpubValidator.MAX_COMPRESSED) return response(Response.Status.PAYLOAD_TOO_LARGE, "Choose one EPUB or PDF smaller than 50 MiB.")
        if (session.headers["content-type"]?.substringBefore(';') !in setOf("application/epub+zip", "application/pdf", "application/octet-stream"))
            return response(Response.Status.UNSUPPORTED_MEDIA_TYPE, "Choose an EPUB or PDF file.")
        if (!busy.compareAndSet(false, true)) return response(Response.Status.CONFLICT, "Another book is importing. Please wait.")
        val temp = try { File.createTempFile("upload-", ".book", books.staging) } catch (_: Exception) {
            busy.set(false)
            val message = "Not enough storage to receive a book. Free space on your TV and try again."
            onState(message, null)
            return response(Response.Status.INTERNAL_ERROR, message)
        }
        try {
            if (books.root.usableSpace < length + 16L * 1024 * 1024) throw ImportFailure("Not enough storage. Remove a book from the TV, then try again.")
            onState("Receiving your book…", null)
            temp.outputStream().use { out ->
                val buffer = ByteArray(8192)
                var left = length
                while (left > 0) {
                    if (closed || now() >= pairing.expires) throw ImportFailure("Pairing ended. Open Add book and retry the transfer.")
                    val n = session.inputStream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) throw ImportFailure("Transfer interrupted. Keep this page open and try again.")
                    out.write(buffer, 0, n); left -= n
                }
            }
            if (!authorized(session)) throw ImportFailure("Pairing ended. Open Add book and try again.")
            onState("Checking your book and adding it to your library…", null)
            val name = session.parameters["name"]?.firstOrNull()?.take(300) ?: "Book.epub"
            val result = importer.execute(temp, name)
            val message = if (result.duplicate) "Already in library" else "Book added"
            onState(message, result)
            return response(Response.Status.OK, "$message. You can now read it on your TV.")
        } catch (e: Exception) {
            val message = if (e is ImportFailure) e.message!! else "Transfer interrupted or storage unavailable. Free space and try again."
            onState(message, null)
            return response(Response.Status.BAD_REQUEST, message)
        } finally { temp.delete(); busy.set(false) }
    }
    private fun response(status: Response.Status, message: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", message).secure()
    private fun Response.secure(): Response = apply {
        addHeader("Cache-Control", "no-store")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Connection", "close")
        addHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'none'; frame-ancestors 'none'; base-uri 'none'")
    }
    companion object {
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }.firstOrNull {
                    it is java.net.Inet4Address && it.isSiteLocalAddress && !it.isLoopbackAddress
                }?.hostAddress
        }.getOrNull()
        private val PAGE = """
<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Add a book · Couch Reader</title><style>
:root{color-scheme:light;font-family:system-ui,sans-serif;color:#24372f;background:#f4f1e9}*{box-sizing:border-box}body{margin:0;min-height:100vh;min-height:100dvh;padding:clamp(16px,4vw,40px);display:flex;justify-content:center;align-items:center;font-size:1rem;line-height:1.5}main{width:100%;max-width:32rem;min-width:0;padding-block:12px}.mark{font-size:.75rem;letter-spacing:.16em;font-weight:700}h1{font-family:Georgia,serif;font-weight:400;font-size:clamp(2rem,7vw,3rem);line-height:1.12;margin:clamp(24px,5vw,36px) 0 16px;text-wrap:balance}p{color:#53645a;overflow-wrap:anywhere}form{margin:24px 0;padding:clamp(16px,5vw,28px);border:1px solid #b8c3b9;border-radius:16px;background:#fffdf7;min-width:0}label{display:block;font-weight:650;margin-bottom:12px}input[type=file]{display:block;box-sizing:border-box;width:100%;min-width:0;max-width:100%;min-height:48px;font:inherit;font-size:.9375rem;overflow:hidden}input::file-selector-button{min-height:44px;max-width:100%;margin:0 12px 8px 0;padding:10px 12px;border:1px solid #b8c3b9;border-radius:6px;background:#f4f1e9;color:#24372f;font:inherit;cursor:pointer}button{display:block;width:100%;min-height:48px;margin-top:20px;border:0;border-radius:8px;padding:14px;background:#294d3b;color:#fff;font:inherit;font-weight:650;cursor:pointer;white-space:normal}button:disabled{opacity:.55}button:focus-visible,input:focus-visible{outline:3px solid #9b632c;outline-offset:4px}progress{display:block;width:100%;margin-top:18px;accent-color:#294d3b}progress[hidden]{display:none}small{display:block;font-size:.8125rem;line-height:1.5;color:#53645a;overflow-wrap:anywhere}#status{min-height:3em;margin:16px 0}#file-help{margin-top:8px}@media(max-width:360px){body{padding:16px}form{padding:16px}h1{font-size:2rem}}@media(max-height:650px){body{align-items:flex-start}}
</style>
<main><div class="mark">COUCH READER</div><h1>Your next chapter, on the big screen.</h1><p>Choose a book from this device. It goes straight to your TV, over your home network.</p>
<form id="upload" action="/upload" method="post"><label for="file">Choose an EPUB or PDF</label><input id="file" name="file" type="file" accept=".epub,.pdf,application/epub+zip,application/pdf" aria-describedby="file-help" required><small id="file-help">DRM-free EPUB or unlocked PDF · up to 50 MiB</small><button id="send" type="submit">Send to TV</button><progress id="progress" hidden aria-label="Upload progress" max="100"></progress></form>
<p id="status" role="status" aria-live="polite"></p><small>Keep Add book open on your TV. The TV code refreshes automatically every 10 minutes. If pairing expires, scan the new code. Your book stays on your TV; no cloud or account.</small></main>
<script>
const form=document.getElementById('upload'),file=document.getElementById('file'),button=document.getElementById('send'),status=document.getElementById('status'),progress=document.getElementById('progress');
form.addEventListener('submit',event=>{event.preventDefault();const book=file.files[0];if(!book)return;const name=book.name.toLowerCase(),isPdf=name.endsWith('.pdf')||book.type==='application/pdf',isEpub=name.endsWith('.epub')||book.type==='application/epub+zip';if((!isEpub&&!isPdf)||book.size>52428800||!book.size){status.textContent='Choose a non-empty EPUB or PDF smaller than 50 MiB.';return;}
button.disabled=true;progress.hidden=false;progress.removeAttribute('value');status.textContent='Sending your book… Keep this page open.';
const url=new URL('/upload',location.href);url.searchParams.set('token',new URL(location.href).searchParams.get('token'));url.searchParams.set('name',book.name);
const request=new XMLHttpRequest();request.open('POST',url);request.timeout=180000;request.setRequestHeader('Content-Type',isPdf?'application/pdf':'application/epub+zip');
request.upload.onprogress=e=>{if(e.lengthComputable){progress.value=e.loaded/e.total*100;if(e.loaded===e.total)status.textContent='Checking your book on your TV…';}};
request.onload=()=>{status.textContent=request.responseText||'Transfer failed. Please try again.';button.disabled=false;progress.hidden=true;};
request.onerror=request.ontimeout=()=>{status.textContent='Cannot reach your TV. Keep Add book open, check both devices use the same Wi-Fi, and try again. Guest networks may block connections.';button.disabled=false;progress.hidden=true;};request.send(book);});
</script></html>
""".trimIndent()
    }
}
