package io.deepseekharness.mobile

import android.content.Intent
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.runtime.HarnessAccess
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 只使用临时 loopback 服务，不启动/停止真实 Harness，不修改会话或运行时文件。 */
@RunWith(AndroidJUnit4::class)
class HarnessRestartTest {
    @Test
    fun reusedWindowAuthenticatesHttpAndWebSocketAfterRuntimeRestartAndRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val previousAccess = AppAuthenticationState.harnessAccess()
        try {
            AuthServer().use { server ->
                AppAuthenticationState.authorizeHarnessLaunch(server.access())
                val intent = Intent(app, HarnessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ActivityScenario.launch<HarnessActivity>(intent).use { scenario ->
                    waitForTitle(scenario, "run-one")
                    var original: HarnessActivity? = null
                    scenario.onActivity { original = it }

                    server.restart()
                    AppAuthenticationState.authorizeHarnessLaunch(server.access())
                    // REORDER_TO_FRONT 的 onNewIntent 路径；不能用 recreate 掩盖旧 client 的问题。
                    scenario.onActivity { activity ->
                        activity.startActivity(Intent(app, HarnessActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(HarnessActivity.EXTRA_RESET_TO_HOME, true))
                    }
                    waitForTitle(scenario, "run-two")
                    scenario.onActivity { activity ->
                        assertTrue(original === activity)
                        assertTrue(!activity.findViewById<WebView>(R.id.harness_web_view).canGoBack())
                    }

                    // 旋转和 renderer 恢复都走 recreate；旧窗口销毁不得清除新授权/Cookie。
                    scenario.recreate()
                    waitForTitle(scenario, "run-two")
                    assertEquals(server.access(), AppAuthenticationState.harnessAccess())
                    assertTrue(server.firstHttpAccepted)
                    assertTrue(server.secondHttpAccepted)
                    assertTrue(server.firstWebSocketAccepted)
                    assertTrue(server.secondWebSocketAccepted)
                }
            }
        } finally {
            if (previousAccess == null) AppAuthenticationState.revokeHarness()
            else AppAuthenticationState.authorizeHarnessLaunch(previousAccess)
        }
    }

    private fun waitForTitle(scenario: ActivityScenario<HarnessActivity>, expected: String) {
        val title = AtomicReference<String?>()
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            scenario.onActivity { title.set(it.findViewById<WebView>(R.id.harness_web_view).title) }
            if (title.get() == expected) return
            Thread.sleep(50)
        }
        assertEquals("HTTP credentials and WebSocket cookie must belong to the current run", expected, title.get())
    }

    private class AuthServer : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        @Volatile private var secondRun = false
        @Volatile var firstHttpAccepted = false
        @Volatile var secondHttpAccepted = false
        @Volatile var firstWebSocketAccepted = false
        @Volatile var secondWebSocketAccepted = false
        private val worker = Thread({
            while (!socket.isClosed) {
                try { socket.accept().use(::respond) } catch (_: Exception) {
                    if (socket.isClosed) break
                }
            }
        }, "harness-restart-test").apply { isDaemon = true; start() }

        fun access() = HarnessAccess(
            "http://127.0.0.1:${socket.localPort}/", HarnessAccess.USERNAME,
            (if (secondRun) "B" else "A").repeat(43),
        )

        fun restart() { secondRun = true }

        private fun respond(client: Socket) {
            client.soTimeout = 2_000
            val input = client.getInputStream()
            val header = StringBuilder()
            while (header.length < 16_384 && !header.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) return
                header.append(next.toChar())
            }
            if (!header.endsWith("\r\n\r\n")) return
            val lines = header.toString().split("\r\n")
            val headers = lines.drop(1).mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator < 1) null else line.substring(0, separator).lowercase() to line.substring(separator + 1).trim()
            }.toMap()
            val current = access()
            val name = if (secondRun) "run-two" else "run-one"
            val basic = "Basic " + Base64.getEncoder().encodeToString("${current.username}:${current.password}".toByteArray())
            val cookie = headers["cookie"]?.split(';')?.any { it.trim() == "dsh_mobile_token=${current.password}" } == true
            val output = client.getOutputStream()
            if (lines.first().startsWith("GET /socket ")) {
                val key = headers["sec-websocket-key"]
                if (!cookie || key == null) {
                    output.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(
                    (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(),
                ))
                if (secondRun) secondWebSocketAccepted = true else firstWebSocketAccepted = true
                output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                output.flush()
                return
            }
            if (headers["authorization"] != basic) {
                output.write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"${HarnessAccess.REALM}\", charset=\"UTF-8\"\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                return
            }
            if (secondRun) secondHttpAccepted = true else firstHttpAccepted = true
            val body = ("<!doctype html><title>connecting</title><script>" +
                "const ws=new WebSocket('ws://127.0.0.1:${socket.localPort}/socket');" +
                "ws.onopen=()=>{document.title='$name';ws.close();};</script>").toByteArray()
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nCache-Control: no-store\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
            output.write(body)
        }

        override fun close() { socket.close(); worker.join(3_000) }
    }
}
