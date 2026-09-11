package dev.octri

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OctriTest {
    private var server: HttpServer? = null

    @AfterTest
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `parses valid traceparent and rejects zero identifiers`() {
        val valid = Octri.traceFromHeader(
            "00-4BF92F3577B34DA6A3CE929D0E0E4736-00F067AA0BA902B7-01",
        )
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", valid.traceId)
        assertEquals("00f067aa0ba902b7", valid.parentSpanId)

        val invalid = Octri.traceFromHeader(
            "00-00000000000000000000000000000000-0000000000000000-01",
        )
        assertTrue(invalid.traceId.matches(Regex("[0-9a-f]{32}")))
        assertFalse(invalid.traceId.matches(Regex("0{32}")))
        assertNull(invalid.parentSpanId)
    }

    @Test
    fun `replaces an oversized event id`() {
        val received = CountDownLatch(1)
        val idempotencyKey = AtomicReference<String>()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { http ->
            http.createContext("/ingest") { exchange ->
                idempotencyKey.set(exchange.requestHeaders.getFirst("idempotency-key"))
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
                received.countDown()
            }
            http.start()
        }

        Octri.init(OctriConfig(
            url = "http://127.0.0.1:${server!!.address.port}/",
            token = "project-token",
            environment = "project-1",
        ))
        Octri.captureEvent("checkout.completed", OctriEventOptions(eventId = "e".repeat(257)))

        assertTrue(received.await(3, TimeUnit.SECONDS))
        assertTrue(Regex("^[0-9a-f]{32}$").matches(idempotencyKey.get()))
    }

    @Test
    fun `sends scoped idempotent json without header injection`() {
        val received = CountDownLatch(1)
        val body = AtomicReference<String>()
        val idempotencyKey = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { http ->
            http.createContext("/ingest") { exchange ->
                body.set(exchange.requestBody.bufferedReader().readText())
                idempotencyKey.set(exchange.requestHeaders.getFirst("idempotency-key"))
                authorization.set(exchange.requestHeaders.getFirst("authorization"))
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
                received.countDown()
            }
            http.start()
        }

        Octri.init(OctriConfig(
            url = "http://127.0.0.1:${server!!.address.port}/",
            token = "project-token",
            environment = "project-1",
        ))
        Octri.captureEvent("checkout.completed", OctriEventOptions(
            eventId = "unsafe\r\nX-Injected: true",
            tags = mapOf("numbers" to intArrayOf(1, 2, 3), "notFinite" to Double.NaN),
        ))

        assertTrue(received.await(3, TimeUnit.SECONDS))
        val eventId = Regex("\\\"eventId\\\":\\\"([0-9a-f]{32})\\\"")
            .find(body.get())!!.groupValues[1]
        assertEquals(eventId, idempotencyKey.get())
        assertEquals("Bearer project-token", authorization.get())
        assertTrue(body.get().contains("\"environment\":\"project-1\""))
        assertTrue(body.get().contains("\"numbers\":[1,2,3]"))
        assertTrue(body.get().contains("\"notFinite\":null"))
    }
}
