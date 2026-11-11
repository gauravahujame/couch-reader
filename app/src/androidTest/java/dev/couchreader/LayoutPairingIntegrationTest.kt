package dev.couchreader

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.couchreader.importer.UploadServer
import dev.couchreader.ui.BookUnderline
import dev.couchreader.ui.SquareQrView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class LayoutPairingIntegrationTest {
    @Test fun landingCopyFitsAndExpiredQrRenewsInPlace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun waitUntil(predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 10000
            while (SystemClock.elapsedRealtime() < end) {
                var done = false
                instrumentation.runOnMainSync { done = predicate() }
                if (done) return
                Thread.sleep(50)
            }
            fail("Timed out waiting for UI state")
        }
        fun allViews() = views(activity.window.decorView)
        // An empty library shows a labelled button; a populated one shows an icon-only header button.
        fun isAddBook(view: View) = (view is Button && view.text.contains("Add book")) || view.contentDescription == "Add book"
        var server: UploadServer? = null
        try {
            waitUntil { allViews().any(::isAddBook) }
            instrumentation.runOnMainSync {
                allViews().filterIsInstance<TextView>().firstOrNull { it.text.startsWith("Make yourself") }?.let { heading ->
                    val layout = heading.layout
                    assertEquals(heading.text.length, layout.getLineEnd(layout.lineCount - 1))
                    assertTrue("Heading clipped vertically", layout.getLineBottom(layout.lineCount - 1) <= heading.height)
                }
                allViews().filterIsInstance<BookUnderline>().firstOrNull()?.let { mark ->
                    val parent = mark.parent as View
                    assertEquals(parent.width / 2f, (mark.left + mark.right) / 2f, 1f)
                }
                allViews().first(::isAddBook).performClick()
                server = MainActivity::class.java.getDeclaredField("server").apply { isAccessible = true }.get(activity) as UploadServer
            }
            val upload = server!!
            waitUntil { allViews().filterIsInstance<SquareQrView>().any { it.width > 0 } }
            val oldToken = upload.token
            val port = upload.listeningPort
            // Advance only the private expiry in the test; production always uses ten minutes.
            val field = UploadServer::class.java.getDeclaredField("pairing").apply { isAccessible = true }
            val oldPairing = field.get(upload)!!
            val constructor = oldPairing.javaClass.getDeclaredConstructor(String::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
            field.set(upload, constructor.newInstance(oldToken, SystemClock.elapsedRealtime() - 1))
            waitUntil { upload.token != oldToken }
            instrumentation.runOnMainSync {
                val qr = allViews().filterIsInstance<SquareQrView>().single()
                assertEquals(qr.width, qr.height)
                val bitmap = (qr.drawable as BitmapDrawable).bitmap
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val result = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
                assertTrue("QR did not show renewed token", result.text.endsWith("token=${upload.token}"))
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            }
            fun status(token: String) = Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 2000); socket.soTimeout = 2000
                socket.getOutputStream().write("GET /?token=$token HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
            }
            assertEquals(403, status(oldToken)); assertEquals(200, status(upload.token))
        } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
    }
}
