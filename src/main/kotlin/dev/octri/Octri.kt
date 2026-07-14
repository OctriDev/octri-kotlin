package dev.octri

import java.lang.reflect.Array as ReflectArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

data class OctriConfig(
    val url: String,
    /** Optional only for open self-hosted ingestion. Hosted Octri requires it. */
    val token: String?,
    val environment: String,
    val release: String? = null,
)

data class OctriTraceContext(val traceId: String, val parentSpanId: String? = null)

data class OctriEventOptions(
    val timestamp: String? = null,
    val level: String = "info",
    val operationId: String? = null,
    val method: String? = null,
    val path: String? = null,
    val statusCode: Int? = null,
    val latencyMs: Double? = null,
    val attempt: Int? = null,
    val requestId: String? = null,
    val user: Map<String, Any?>? = null,
    val tags: Map<String, Any?>? = null,
    val context: Map<String, Any?>? = null,
    val breadcrumbs: List<Map<String, Any?>>? = null,
    val fingerprint: String? = null,
    val trace: OctriTraceContext? = null,
    val spanId: String? = null,
    val eventId: String? = null,
)

data class OctriErrorOptions(
    val level: String = "error",
    val operationId: String? = null,
    val method: String? = null,
    val path: String? = null,
    val statusCode: Int? = null,
    val trace: OctriTraceContext? = null,
)

data class OctriSpan(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    val name: String,
    val service: String = "server",
    val operationId: String? = null,
    val startTime: String,
    val endTime: String? = null,
    val status: String = "ok",
)

/**
 * Standalone Octri monitoring for Kotlin/JVM. All reporting is asynchronous and
 * best-effort so telemetry failures cannot affect the host application.
 */
object Octri {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build()
    private val random = SecureRandom()
    private val traceparent = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$", RegexOption.IGNORE_CASE)

    @Volatile
    private var config: OctriConfig? = null

    @JvmStatic
    fun init(value: OctriConfig) {
        config = value.copy(url = value.url.trimEnd('/'))
    }

    @JvmStatic
    fun traceFromHeader(value: String?): OctriTraceContext {
        val match = value?.trim()?.let(traceparent::matchEntire)
        return if (match != null && !allZeros(match.groupValues[1]) && !allZeros(match.groupValues[2])) {
            OctriTraceContext(
                match.groupValues[1].lowercase(),
                match.groupValues[2].lowercase(),
            )
        } else {
            OctriTraceContext(randomHex(16))
        }
    }

    /** Log an event without depending on a generated Octri API SDK. */
    @JvmStatic
    fun captureEvent(message: String, options: OctriEventOptions = OctriEventOptions()) {
        val config = config ?: return
        val eventId = options.eventId?.takeIf(::safeHeaderValue) ?: randomHex(16)
        val tags = linkedMapOf<String, Any?>("octri.origin" to "standalone").apply {
            options.tags?.let(::putAll)
        }
        val payload = linkedMapOf<String, Any?>(
            "eventId" to eventId,
            "timestamp" to (options.timestamp ?: Instant.now().toString()),
            "level" to options.level,
            "message" to message,
            "operationId" to options.operationId,
            "method" to options.method,
            "path" to options.path,
            "statusCode" to options.statusCode,
            "latencyMs" to options.latencyMs,
            "attempt" to options.attempt,
            "requestId" to options.requestId,
            "environment" to config.environment,
            "release" to config.release,
            "user" to options.user,
            "tags" to tags,
            "context" to options.context,
            "breadcrumbs" to options.breadcrumbs,
            "fingerprint" to options.fingerprint,
            "traceId" to options.trace?.traceId,
            "spanId" to options.spanId,
        ).compact()
        post(config, "/ingest", payload, eventId)
    }

    @JvmStatic
    fun captureError(error: Throwable, options: OctriErrorOptions = OctriErrorOptions()) {
        val config = config ?: return
        val trace = options.trace ?: traceFromHeader(null)
        val eventId = randomHex(16)
        val frames = error.stackTrace.map { frame ->
            linkedMapOf<String, Any?>(
                "function" to "${frame.className}.${frame.methodName}",
                "filename" to frame.fileName,
                "lineno" to frame.lineNumber,
                "colno" to 0,
                "inApp" to (!frame.className.startsWith("java.") &&
                    !frame.className.startsWith("kotlin.") &&
                    !frame.className.startsWith("sun.")),
            ).compact()
        }
        val payload = linkedMapOf<String, Any?>(
            "eventId" to eventId,
            "timestamp" to Instant.now().toString(),
            "level" to options.level,
            "operationId" to options.operationId,
            "method" to options.method,
            "path" to options.path,
            "statusCode" to options.statusCode,
            "environment" to config.environment,
            "release" to config.release,
            "traceId" to trace.traceId,
            "spanId" to randomHex(8),
            "tags" to mapOf("octri.origin" to "server"),
            "error" to mapOf(
                "name" to error::class.qualifiedName,
                "message" to error.message,
                "stack" to error.stackTraceToString(),
                "frames" to frames,
            ),
        ).compact()
        post(config, "/ingest", payload, eventId)
    }

    @JvmStatic
    fun captureSpan(span: OctriSpan) {
        val config = config ?: return
        if (span.traceId.isEmpty() || span.spanId.isEmpty() || span.name.isEmpty() || span.startTime.isEmpty()) return
        val payload = linkedMapOf<String, Any?>(
            "traceId" to span.traceId,
            "spanId" to span.spanId,
            "parentSpanId" to span.parentSpanId,
            "environment" to config.environment,
            "name" to span.name,
            "service" to span.service,
            "operationId" to span.operationId,
            "startTime" to span.startTime,
            "endTime" to span.endTime,
            "status" to span.status,
        ).compact()
        post(config, "/traces", payload, "${span.traceId}:${span.spanId}")
    }

    private fun post(config: OctriConfig, path: String, payload: Any, idempotencyKey: String) {
        try {
            if (!safeHeaderValue(idempotencyKey) ||
                (config.token?.isNotEmpty() == true && !safeHeaderValue(config.token))) return
            val builder = HttpRequest.newBuilder(URI.create(config.url + path))
                .timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json")
                .header("idempotency-key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)))
            config.token?.takeIf(String::isNotEmpty)?.let {
                builder.header("authorization", "Bearer $it")
            }
            http.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding())
                .exceptionally { null }
        } catch (_: Throwable) {
            // Monitoring must never affect the application.
        }
    }

    private fun Map<String, Any?>.compact(): LinkedHashMap<String, Any> =
        entries.filter { it.value != null }.associateTo(linkedMapOf()) { it.key to it.value!! }

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun safeHeaderValue(value: String): Boolean =
        value.isNotEmpty() && '\r' !in value && '\n' !in value

    private fun allZeros(value: String): Boolean = value.all { it == '0' }

    private fun toJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${escape(value)}\""
        is Double -> if (value.isFinite()) value.toString() else "null"
        is Float -> if (value.isFinite()) value.toString() else "null"
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") {
            "${toJson(it.key.toString())}:${toJson(it.value)}"
        }
        is Iterable<*> -> value.joinToString(",", "[", "]") { toJson(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { toJson(it) }
        else -> if (value.javaClass.isArray) {
            (0 until ReflectArray.getLength(value)).joinToString(",", "[", "]") {
                toJson(ReflectArray.get(value, it))
            }
        } else {
            toJson(value.toString())
        }
    }

    private fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }
}
