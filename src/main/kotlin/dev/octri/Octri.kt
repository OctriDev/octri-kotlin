package dev.octri

import java.lang.reflect.Array as ReflectArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Connection settings for one Octri monitoring project.
 *
 * Hosted users can copy the URL, token and environment from the Monitoring
 * connection settings in the dashboard.
 *
 * @property url Base URL of the monitoring backend. [Octri.init] stores it with
 *   any trailing slashes removed.
 * @property token Project ingest token, sent as a bearer token on every request.
 *   Optional only for open self-hosted ingestion. Hosted Octri requires it.
 * @property environment Dashboard project id that received events and spans are
 *   filed under.
 * @property release Release this process is running, such as a commit SHA.
 */
data class OctriConfig(
    val url: String,
    val token: String?,
    val environment: String,
    val release: String? = null,
)

/**
 * Identifies one W3C distributed trace, and the span that called into this
 * process.
 *
 * @property traceId Hexadecimal id, 32 characters long, shared by every span in
 *   the trace.
 * @property parentSpanId Hexadecimal id, 16 characters long, of the span that
 *   called this process. Null when this process started the trace.
 */
data class OctriTraceContext(val traceId: String, val parentSpanId: String? = null)

/**
 * Optional detail attached to a call to [Octri.captureEvent].
 *
 * A null field is dropped from the payload rather than sent as a null.
 *
 * @property timestamp When the event happened, as an ISO-8601 string. Defaults
 *   to the time of the call.
 * @property level Severity, such as `info`, `warning` or `error`.
 * @property operationId OpenAPI operation id the event belongs to.
 * @property method HTTP method of the request the event describes.
 * @property path Request path the event describes.
 * @property statusCode HTTP status code the request ended with.
 * @property latencyMs How long the described work took, in milliseconds.
 * @property attempt Retry number, counting from 1 for the first attempt.
 * @property requestId Your own correlation id for the request.
 * @property user Who the event happened to, such as a map holding an `id`.
 * @property tags Searchable keys and values, such as region or plan. Octri sets
 *   `octri.origin` itself; these are merged over it.
 * @property context Free-form detail shown alongside the event in the dashboard.
 * @property breadcrumbs Steps leading up to the event, oldest first.
 * @property fingerprint Overrides how the dashboard groups this event with
 *   similar ones.
 * @property trace Trace the event belongs to, usually from
 *   [Octri.traceFromHeader].
 * @property spanId Span within that trace the event was raised in.
 * @property eventId Idempotency key for the delivery. Pass the same value when
 *   retrying so the backend stores the event once. A value that is empty or
 *   carries a carriage return or newline is replaced with a random id.
 */
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

/**
 * Optional detail attached to a call to [Octri.captureError].
 *
 * @property level Severity, such as `error` or `fatal`.
 * @property operationId OpenAPI operation id the failure happened under.
 * @property method HTTP method of the request that failed.
 * @property path Request path that failed.
 * @property statusCode HTTP status code the failed request ended with.
 * @property trace Trace to file the error under, usually from
 *   [Octri.traceFromHeader]. When null, the error starts a new trace of its own.
 */
data class OctriErrorOptions(
    val level: String = "error",
    val operationId: String? = null,
    val method: String? = null,
    val path: String? = null,
    val statusCode: Int? = null,
    val trace: OctriTraceContext? = null,
)

/**
 * One timed unit of work, drawn as a bar in the dashboard waterfall.
 *
 * [Octri.captureSpan] drops any span whose [traceId], [spanId], [name] or
 * [startTime] is empty.
 *
 * @property traceId Id of the trace this span belongs to.
 * @property spanId Id of this span, unique within the trace.
 * @property parentSpanId Id of the enclosing span, or null when this is the root
 *   of the trace.
 * @property name Readable name for the work, such as `orders.list`.
 * @property service Side of the call the span was recorded on.
 * @property operationId OpenAPI operation id the span belongs to.
 * @property startTime When the work started, as an ISO-8601 string.
 * @property endTime When the work finished, as an ISO-8601 string, or null while
 *   it is still running.
 * @property status How the work ended, such as `ok` or `error`.
 */
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
    private const val MAX_IDEMPOTENCY_KEY_LENGTH = 256
    private val random = SecureRandom()
    private val traceparent = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$", RegexOption.IGNORE_CASE)

    @Volatile
    private var config: OctriConfig? = null

    /**
     * Points every later call at the project described by [value].
     *
     * Call this once at startup. Until it runs, [captureEvent], [captureError]
     * and [captureSpan] return without sending anything. Any trailing slashes
     * on the configured URL are trimmed here. Calling it again replaces the
     * settings used by subsequent calls.
     *
     * @param value the settings to report under
     */
    @JvmStatic
    fun init(value: OctriConfig) {
        config = value.copy(url = value.url.trimEnd('/'))
    }

    /**
     * Reads a W3C `traceparent` header into a trace context.
     *
     * Returns the trace and parent span carried by [value] when it is a
     * well-formed version `00` header. Returns a context holding a fresh random
     * trace id and no parent when the header is null, malformed, or carries an
     * all-zero trace or parent id, so the caller always gets a usable trace.
     *
     * @param value the inbound `traceparent` header, or null
     * @return the trace to report under
     */
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

    /**
     * Logs an event, without depending on a generated Octri API SDK.
     *
     * Returns immediately; the send happens in the background. Does nothing
     * when [init] has not run.
     *
     * @param message what happened
     * @param options extra detail to attach
     */
    @JvmStatic
    fun captureEvent(message: String, options: OctriEventOptions = OctriEventOptions()) {
        val config = config ?: return
        val eventId = options.eventId?.takeIf(::safeIdempotencyKey) ?: randomHex(16)
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

    /**
     * Reports [error], with one symbolic frame per line of its stack.
     *
     * Frames outside `java.`, `kotlin.` and `sun.` are marked as in-app, so the
     * dashboard shows your own code first. Files the error under the trace in
     * [options], or under a new trace of its own when none is given. Returns
     * immediately; the send happens in the background. Does nothing when [init]
     * has not run.
     *
     * @param error the exception to report
     * @param options request and trace detail to attach
     */
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

    /**
     * Records [span] as one bar in the dashboard request waterfall.
     *
     * Ignores a span whose trace id, span id, name or start time is empty.
     * Returns immediately; the send happens in the background. Does nothing
     * when [init] has not run.
     *
     * @param span the finished span to report
     */
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

    // ── Scrubbing ──────────────────────────────────────────────────────────

    /**
     * Keys whose value never leaves the process. Compared against the key with
     * case and separators removed, so `api_key`, `apiKey` and `API-KEY` all
     * match `apikey`, and the test is a substring one, so `stripeSecretKey`
     * matches too.
     */
    private val scrubKeys = listOf(
        "password", "passwd", "passphrase", "secret", "token", "apikey",
        "authorization", "credential", "cookie", "session", "privatekey",
        "accesskey", "cardnumber", "creditcard", "cvv", "ssn",
    )

    private const val REDACTED = "[redacted]"
    private const val TRUNCATED = "[truncated]"

    /** Deep enough for real context maps, shallow enough to stay cheap. */
    private const val MAX_SCRUB_DEPTH = 8

    private val bearer = Regex("""\bbearer\s+[\w.~+/-]+=*""", RegexOption.IGNORE_CASE)
    private val jwt = Regex("""\beyJ[\w-]+\.[\w-]+\.[\w-]+""")
    private val digitRun = Regex("""\b(?:\d[ -]?){12,18}\d\b""")
    private val email = Regex("""[\w.%+-]+@[\w-]+(?:\.[\w-]+)+""")
    private val nonAlphanumeric = Regex("[^a-z0-9]")

    private val extraScrubKeys = CopyOnWriteArrayList<String>()

    @Volatile
    private var beforeSend: ((Map<String, Any?>) -> Map<String, Any?>?)? = null

    /**
     * Redacts more key names, on top of the built-in list. Matching ignores case
     * and separators and is a substring test, so `account` also covers
     * `accountNumber`.
     *
     *     Octri.addScrubFields("accountNumber", "otp")
     */
    fun addScrubFields(vararg fields: String) {
        for (field in fields) {
            val key = normalizeKey(field)
            if (key.isNotEmpty() && key !in extraScrubKeys) extraScrubKeys.add(key)
        }
    }

    /**
     * Runs [hook] on every payload just before it is sent. Return the payload to
     * send it, or null to drop the event:
     *
     *     Octri.setBeforeSend { payload -> if (payload["path"] == "/health") null else payload }
     *
     * Redaction still runs afterwards, so a hook cannot leak a credential by
     * accident. Pass null to remove the hook.
     */
    fun setBeforeSend(hook: ((Map<String, Any?>) -> Map<String, Any?>?)?) {
        beforeSend = hook
    }

    private fun normalizeKey(key: String): String =
        nonAlphanumeric.replace(key.lowercase(Locale.ROOT), "")

    private fun isSecretKey(key: String): Boolean {
        val normalized = normalizeKey(key)
        if (normalized.isEmpty()) return false
        return scrubKeys.any { normalized.contains(it) } ||
            extraScrubKeys.any { normalized.contains(it) }
    }

    /** Tells a card number from the order ids and timestamps that look like one. */
    private fun passesLuhn(digits: String): Boolean {
        var sum = 0
        var doubling = false
        for (index in digits.indices.reversed()) {
            var digit = digits[index] - '0'
            if (doubling) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            doubling = !doubling
        }
        return sum % 10 == 0
    }

    /** Removes credentials and personal data that leaked into free text. */
    private fun scrubText(value: String): String {
        if (value.isEmpty()) return value
        var scrubbed = bearer.replace(value, REDACTED)
        scrubbed = jwt.replace(scrubbed, REDACTED)
        scrubbed = digitRun.replace(scrubbed) { match ->
            if (passesLuhn(match.value.filter(Char::isDigit))) REDACTED else match.value
        }
        return email.replace(scrubbed, REDACTED)
    }

    /**
     * Redacts credential-shaped keys anywhere in the payload, and strips secrets
     * out of the free text around them. `user` is the field you deliberately
     * fill with an identity, so its strings are left alone; its keys are still
     * checked.
     */
    private fun scrubValue(value: Any?, depth: Int, text: Boolean): Any? = when {
        value is String -> if (text) scrubText(value) else value
        value is Map<*, *> -> if (depth >= MAX_SCRUB_DEPTH) {
            TRUNCATED
        } else {
            value.entries.associateTo(LinkedHashMap<String, Any?>()) { (key, nested) ->
                val name = key.toString()
                name to if (isSecretKey(name)) {
                    REDACTED
                } else {
                    scrubValue(nested, depth + 1, text && name != "user")
                }
            }
        }
        value is Iterable<*> -> if (depth >= MAX_SCRUB_DEPTH) {
            TRUNCATED
        } else {
            value.map { scrubValue(it, depth + 1, text) }
        }
        value != null && value.javaClass.isArray -> if (depth >= MAX_SCRUB_DEPTH) {
            TRUNCATED
        } else {
            (0 until ReflectArray.getLength(value)).map {
                scrubValue(ReflectArray.get(value, it), depth + 1, text)
            }
        }
        else -> value
    }

    /**
     * The last thing every payload passes through. Both the hook and the
     * redaction live here rather than in the capture functions, so nothing can
     * be reported around them.
     */
    private fun scrubPayload(payload: Any): Any? {
        @Suppress("UNCHECKED_CAST")
        val map = payload as? Map<String, Any?> ?: return null
        val hook = beforeSend
        val hooked = if (hook == null) map else hook(map) ?: return null
        return scrubValue(hooked, 0, true)
    }

    private fun post(config: OctriConfig, path: String, payload: Any, idempotencyKey: String) {
        try {
            if (!safeIdempotencyKey(idempotencyKey) ||
                (config.token?.isNotEmpty() == true && !safeHeaderValue(config.token))) return
            val scrubbed = scrubPayload(payload) ?: return
            val builder = HttpRequest.newBuilder(URI.create(config.url + path))
                .timeout(Duration.ofSeconds(5))
                .header("content-type", "application/json")
                .header("idempotency-key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(toJson(scrubbed)))
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

    /**
     * A caller-supplied event id becomes the idempotency-key header, so it is
     * bounded as well as newline-free.
     */
    private fun safeIdempotencyKey(value: String): Boolean =
        safeHeaderValue(value) && value.toByteArray().size <= MAX_IDEMPOTENCY_KEY_LENGTH

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
