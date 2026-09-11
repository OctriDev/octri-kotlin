package dev.octri

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bodies are asserted as text: the reporter writes its own JSON, and a
 * parser would only be another thing to keep in step with it.
 */
class ScrubTest {
    private var server: HttpServer? = null
    private val bodies = LinkedBlockingQueue<String>()

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { http ->
            http.createContext("/ingest") { exchange ->
                bodies.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
            http.start()
        }
        Octri.init(
            OctriConfig(
                url = "http://127.0.0.1:${server!!.address.port}/",
                token = null,
                environment = "project-1",
            ),
        )
    }

    @AfterTest
    fun stopServer() {
        Octri.setBeforeSend(null)
        server?.stop(0)
    }

    private fun next(): String {
        val body = bodies.poll(3, TimeUnit.SECONDS)
        assertNotNull(body, "timed out waiting for an event")
        return body
    }

    // ── Keys ────────────────────────────────────────────────────────────────

    @Test
    fun `redacts credential-shaped keys however they are spelled`() {
        Octri.captureEvent(
            "checkout failed",
            OctriEventOptions(
                context = mapOf(
                    "api_key" to "sk_live_1",
                    "apiKey" to "sk_live_2",
                    "X-API-KEY" to "sk_live_3",
                    "stripeSecretKey" to "sk_live_4",
                    "Authorization" to "Bearer abc",
                    "refresh_token" to "rt_1",
                    "cookie" to "sid=1",
                    "orderId" to "A-1024",
                    "author" to "ada",
                ),
            ),
        )

        val body = next()
        for (key in listOf(
            "api_key", "apiKey", "X-API-KEY", "stripeSecretKey",
            "Authorization", "refresh_token", "cookie",
        )) {
            assertTrue(body.contains("\"$key\":\"[redacted]\""), "$key in $body")
        }
        assertFalse(body.contains("sk_live"), body)
        assertTrue(body.contains("\"orderId\":\"A-1024\""), body)
        assertTrue(body.contains("\"author\":\"ada\""), body)
    }

    @Test
    fun `redacts nested and list values`() {
        Octri.captureEvent(
            "upstream rejected the call",
            OctriEventOptions(
                context = mapOf(
                    "upstream" to mapOf(
                        "headers" to listOf(mapOf("authorization" to "Bearer abc")),
                    ),
                ),
            ),
        )

        val body = next()
        assertTrue(body.contains("\"authorization\":\"[redacted]\""), body)
        assertFalse(body.contains("Bearer abc"), body)
    }

    @Test
    fun `addScrubFields is additive`() {
        Octri.addScrubFields("accountNumber")
        Octri.captureEvent(
            "payout failed",
            OctriEventOptions(
                context = mapOf("accountNumber" to "12345678", "orderId" to "A-1024"),
            ),
        )

        val body = next()
        assertTrue(body.contains("\"accountNumber\":\"[redacted]\""), body)
        assertTrue(body.contains("\"orderId\":\"A-1024\""), body)
    }

    // ── Free text ───────────────────────────────────────────────────────────

    @Test
    fun `strips secrets that leaked into a message`() {
        Octri.captureEvent("401 from billing: Authorization: Bearer sk_live_abc123 rejected")
        val body = next()
        assertFalse(body.contains("sk_live_abc123"), body)
        assertTrue(body.contains("[redacted]"), body)

        Octri.captureEvent("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.7Hk2 expired")
        assertTrue(next().contains("\"message\":\"token [redacted] expired\""))

        Octri.captureEvent("no account for ada@example.com")
        assertTrue(next().contains("\"message\":\"no account for [redacted]\""))
    }

    @Test
    fun `strips card numbers but not order numbers`() {
        Octri.captureEvent("charge 4242 4242 4242 4242 failed for order 1234567890123")

        val body = next()
        assertFalse(body.contains("4242"), body)
        assertTrue(body.contains("1234567890123"), body)
    }

    @Test
    fun `scrubs an error message`() {
        Octri.captureError(IllegalStateException("mail to ada@example.com bounced"))

        val body = next()
        assertFalse(body.contains("ada@example.com"), body)
        assertTrue(body.contains("mail to [redacted] bounced"), body)
    }

    // ── The user field ──────────────────────────────────────────────────────

    @Test
    fun `keeps user identity but not user credentials`() {
        Octri.captureEvent(
            "profile update failed",
            OctriEventOptions(
                user = mapOf(
                    "id" to "u_1",
                    "email" to "ada@example.com",
                    "sessionToken" to "st_1",
                ),
            ),
        )

        val body = next()
        assertTrue(body.contains("\"email\":\"ada@example.com\""), body)
        assertTrue(body.contains("\"sessionToken\":\"[redacted]\""), body)
    }

    // ── setBeforeSend ───────────────────────────────────────────────────────

    @Test
    fun `before send edits the payload and redaction still runs after it`() {
        Octri.setBeforeSend { payload ->
            payload + mapOf("context" to mapOf("note" to "call ada@example.com"))
        }
        Octri.captureEvent("build failed")

        assertTrue(next().contains("\"note\":\"call [redacted]\""))
    }

    @Test
    fun `before send returning null drops the event`() {
        Octri.setBeforeSend { payload -> if (payload["message"] == "noise") null else payload }
        Octri.captureEvent("noise")
        Octri.captureEvent("signal")

        assertTrue(next().contains("\"message\":\"signal\""))
    }
}
