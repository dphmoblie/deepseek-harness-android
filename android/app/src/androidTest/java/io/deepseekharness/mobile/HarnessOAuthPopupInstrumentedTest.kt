package io.deepseekharness.mobile

import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.view.MotionEvent
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.runtime.HarnessAccess
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real WebView popup path with a loopback Harness page.
 * The browser is intercepted by Instrumentation; no account or network request is made.
 */
@RunWith(AndroidJUnit4::class)
class HarnessOAuthPopupInstrumentedTest {
    @Test
    fun userClickTransfersAsyncAuthorizationPopupToExternalBrowser() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val previousAccess = AppAuthenticationState.harnessAccess()
        val browser = Instrumentation.ActivityMonitor(IntentFilter(Intent.ACTION_VIEW), null, true)
        instrumentation.addMonitor(browser)
        try {
            PopupPageServer().use { server ->
                val access = HarnessAccess(
                    "http://127.0.0.1:${server.port}/",
                    HarnessAccess.USERNAME,
                    "P".repeat(43),
                )
                AppAuthenticationState.authorizeHarnessLaunch(access)
                ActivityScenario.launch<HarnessActivity>(Intent(app, HarnessActivity::class.java)).use { scenario ->
                    waitForPage(scenario)
                    scenario.onActivity { activity ->
                        val view = activity.findViewById<WebView>(R.id.harness_web_view)
                        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 32f, 32f, 0)
                        val up = MotionEvent.obtain(0, 20, MotionEvent.ACTION_UP, 32f, 32f, 0)
                        try {
                            assertTrue(view.dispatchTouchEvent(down))
                            assertTrue(view.dispatchTouchEvent(up))
                        } finally {
                            down.recycle()
                            up.recycle()
                        }
                    }
                    assertNotNull(
                        "a user click must hand the async popup navigation to the system browser",
                        instrumentation.waitForMonitorWithTimeout(browser, 5_000),
                    )
                    assertEquals("the popup flow must reach the browser exactly once", 1, browser.getHits())
                }
            }
        } finally {
            instrumentation.removeMonitor(browser)
            if (previousAccess == null) AppAuthenticationState.revokeHarness()
            else AppAuthenticationState.authorizeHarnessLaunch(previousAccess)
        }
    }

    private fun waitForPage(scenario: ActivityScenario<HarnessActivity>) {
        val loaded = AtomicReference(false)
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            scenario.onActivity { activity ->
                val view = activity.findViewById<WebView>(R.id.harness_web_view)
                loaded.set(view.url?.startsWith("http://127.0.0.1:") == true && view.title == "popup-ready")
            }
            if (loaded.get()) return
            Thread.sleep(50)
        }
        assertTrue("loopback Harness page did not load", loaded.get())
    }

    private class PopupPageServer : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val worker = Thread({
            try {
                socket.accept().use { client -> respond(client) }
            } catch (_: Exception) {
                // The test closes the server after the Activity has been destroyed.
            }
        }, "harness-oauth-popup-test").apply { isDaemon = true; start() }

        private fun respond(client: Socket) {
            client.soTimeout = 2_000
            val input = client.getInputStream()
            val request = ByteArray(16_384)
            var size = 0
            while (size < request.size) {
                val read = input.read(request, size, request.size - size)
                if (read < 0) return
                size += read
                if (size >= 4 && request.copyOfRange(size - 4, size).contentEquals("\r\n\r\n".toByteArray())) break
            }
            val html = """
                <!doctype html><title>popup-ready</title><meta name=viewport content="width=device-width">
                <button style="width:200px;height:200px" onclick="const p=window.open('about:blank','_blank'); setTimeout(() => p.location.replace('https://platform.deepseek.com/dsh/authorize?state=instrumented'), 100)">Login</button>
            """.trimIndent().toByteArray()
            val output = client.getOutputStream()
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n" +
                "Cache-Control: no-store\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n").toByteArray())
            output.write(html)
            output.flush()
        }

        override fun close() {
            socket.close()
            worker.join(3_000)
        }
    }
}
