package dev.couchreader.importer

import dev.couchreader.data.BookRepository
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One short-lived pairing session. Raw single-file bodies avoid multipart memory amplification;
 * the page sends several chosen files one request at a time.
 */
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
        if (length <= 0 || length > EpubValidator.MAX_COMPRESSED) return response(Response.Status.PAYLOAD_TOO_LARGE, "Each EPUB or PDF must be smaller than 50 MiB.")
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
            return response(Response.Status.OK, "$message: ${BookMetadata.ellipsize(result.book.title, 120)}")
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
:root{color-scheme:light;font-family:system-ui,sans-serif;color:#24372f;background:#f4f1e9}*{box-sizing:border-box}body{margin:0;min-height:100vh;min-height:100dvh;padding:clamp(16px,4vw,40px);display:flex;justify-content:center;align-items:center;font-size:1rem;line-height:1.5}main{width:100%;max-width:32rem;min-width:0;padding-block:12px}.mark{font-size:.75rem;letter-spacing:.16em;font-weight:700}h1{font-family:Georgia,serif;font-weight:400;font-size:clamp(2rem,7vw,3rem);line-height:1.12;margin:clamp(24px,5vw,36px) 0 16px;text-wrap:balance}p{color:#53645a;overflow-wrap:anywhere}form{margin:24px 0;padding:clamp(16px,5vw,28px);border:1px solid #b8c3b9;border-radius:16px;background:#fffdf7;min-width:0}label{display:block;font-weight:650;margin-bottom:12px}input[type=file]{display:block;box-sizing:border-box;width:100%;min-width:0;max-width:100%;min-height:48px;font:inherit;font-size:.9375rem;overflow:hidden}input::file-selector-button{min-height:44px;max-width:100%;margin:0 12px 8px 0;padding:10px 12px;border:1px solid #b8c3b9;border-radius:6px;background:#f4f1e9;color:#24372f;font:inherit;cursor:pointer}button{display:block;width:100%;min-height:48px;margin-top:20px;border:0;border-radius:8px;padding:14px;background:#294d3b;color:#fff;font:inherit;font-weight:650;cursor:pointer;white-space:normal}button:disabled{opacity:.55}button:focus-visible,input:focus-visible{outline:3px solid #9b632c;outline-offset:4px}progress{display:block;width:100%;margin-top:18px;accent-color:#294d3b}progress[hidden]{display:none}small{display:block;font-size:.8125rem;line-height:1.5;color:#53645a;overflow-wrap:anywhere}#status{min-height:3em;margin:16px 0}#status.done{padding:12px 14px;border-radius:10px;background:#e2e7d6;color:#294d3b;font-weight:650}#status.failed{padding:12px 14px;border-radius:10px;background:#f6e3d6;color:#7a3b12;font-weight:650}#results{list-style:none;margin:0 0 16px;padding:0}#results li{padding:6px 0;border-top:1px solid #dcd9cc;overflow-wrap:anywhere}#results li.ok::before{content:"✓  ";color:#294d3b;font-weight:700}#results li.bad::before{content:"✕  ";color:#9b3b12;font-weight:700}#results li.wait{color:#53645a}#file-help{margin-top:8px}@media(max-width:360px){body{padding:16px}form{padding:16px}h1{font-size:2rem}}@media(max-height:650px){body{align-items:flex-start}}
</style>
<main><div class="mark">COUCH READER</div><h1>Your next chapter, on the big screen.</h1><p>Choose books from this device. They go straight to your TV, over your home network.</p>
<form id="upload" action="/upload" method="post"><label for="file">Choose EPUBs or PDFs</label><input id="file" name="file" type="file" multiple accept=".epub,.pdf,application/epub+zip,application/pdf" aria-describedby="file-help" required><small id="file-help">DRM-free EPUB or unlocked PDF · up to 50 MiB each · pick several at once</small><button id="send" type="submit">Send to TV</button><progress id="progress" hidden aria-label="Upload progress" max="100"></progress></form>
<p id="status" role="status" aria-live="polite"></p><ul id="results" aria-label="Books sent"></ul><small>Keep Add book open on your TV. The TV code refreshes automatically every 10 minutes. If pairing expires, scan the new code. Your book stays on your TV; no cloud or account.</small></main>
<script>
const form=document.getElementById('upload'),file=document.getElementById('file'),button=document.getElementById('send'),status=document.getElementById('status'),progress=document.getElementById('progress');
const results=document.getElementById('results'),offline='Cannot reach your TV. Keep Add book open, check both devices use the same Wi-Fi, and try again. Guest networks may block connections.';
const plural=n=>n===1?'1 book':n+' books';
file.addEventListener('change',()=>{const n=file.files.length;button.textContent=n>1?'Send '+plural(n)+' to TV':'Send to TV';});
function kind(book){const name=book.name.toLowerCase();if(name.endsWith('.pdf')||book.type==='application/pdf')return'application/pdf';if(name.endsWith('.epub')||book.type==='application/epub+zip')return'application/epub+zip';return null;}
function line(text,css){const item=document.createElement('li');item.textContent=text;item.className=css;results.appendChild(item);return item;}
// The TV imports one book at a time, so files go one after another. Resolves {ok, message, stop}.
function send(book,type,onProgress){return new Promise(resolve=>{
 const url=new URL('/upload',location.href);url.searchParams.set('token',new URL(location.href).searchParams.get('token'));url.searchParams.set('name',book.name);
 const request=new XMLHttpRequest();request.open('POST',url);request.timeout=180000;request.setRequestHeader('Content-Type',type);
 request.upload.onprogress=e=>{if(e.lengthComputable)onProgress(e.loaded/e.total);};
 request.onload=()=>resolve({ok:request.status===200,message:request.responseText||'Transfer failed. Please try again.',stop:request.status===403});
 request.onerror=request.ontimeout=()=>resolve({ok:false,message:offline,stop:true});
 request.send(book);});}
form.addEventListener('submit',async event=>{event.preventDefault();const books=Array.from(file.files);if(!books.length)return;
 button.disabled=true;file.disabled=true;results.textContent='';status.className='';progress.hidden=false;
 let sent=0,failed=0;
 for(let i=0;i<books.length;i++){
  const book=books[i],type=kind(book),label=books.length>1?'('+(i+1)+' of '+books.length+') ':'';
  if(!type||book.size>52428800||!book.size){line(book.name+' — not a non-empty EPUB or PDF under 50 MiB','bad');failed++;continue;}
  const item=line(book.name+' — sending…','wait');progress.removeAttribute('value');
  status.textContent='Sending '+label+book.name+'… Keep this page open.';
  const result=await send(book,type,fraction=>{progress.value=fraction*100;if(fraction>=1){status.textContent='Checking '+label+book.name+' on your TV…';item.textContent=book.name+' — checking on your TV…';}});
  item.textContent=result.ok?result.message:book.name+' — '+result.message;item.className=result.ok?'ok':'bad';
  if(result.ok)sent++;else failed++;
  if(result.stop){for(let j=i+1;j<books.length;j++){line(books[j].name+' — not sent','bad');failed++;}break;}
 }
 progress.hidden=true;button.disabled=false;file.disabled=false;form.reset();button.textContent='Send to TV';
 if(!failed){status.className='done';status.textContent=(books.length===1?'Done! Your book is on your TV.':'Done! '+plural(sent)+' sent to your TV.')+' Choose more to keep going.';}
 else{status.className='failed';status.textContent=sent?plural(sent)+' sent, '+failed+' not sent. See the list below.':'Nothing was sent. See the list below.';}
});
</script></html>
""".trimIndent()
    }
}
