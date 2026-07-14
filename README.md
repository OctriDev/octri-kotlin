# Octri Monitoring for Kotlin

Dependency-free (beyond the Kotlin standard library) standalone events, error
capture, W3C trace propagation, and span ingestion for Kotlin/JVM on Java 11+.

```kotlin
import dev.octri.*

Octri.init(OctriConfig(
    url = "https://monitoring.example.com",
    token = System.getenv("OCTRI_TOKEN"),
    environment = "<your project id>",
    release = System.getenv("GIT_SHA"),
))

Octri.captureEvent("checkout.completed", OctriEventOptions(
    tags = mapOf("region" to "eu-west", "plan" to "growth"),
    context = mapOf("orderId" to order.id, "total" to order.total),
))
```

The project-scoped URL, token, and environment are shown in Octri's Monitoring
connection settings. Set `token = null` only for an open self-hosted endpoint.
Delivery is asynchronous, best-effort, and idempotency-keyed.
