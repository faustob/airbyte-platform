/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.telemetry

import io.micronaut.http.HttpAttributes
import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Filter
import io.micronaut.http.filter.HttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono

/**
 * Records OpenTelemetry telemetry for every inbound HTTP request handled by this service.
 *
 * Emits:
 *  - the semantic-convention `http.server.request.duration` latency histogram (seconds)
 *  - a `http.server.request.count` outcome/tenant counter, so availability, error-rate and
 *    per-tenant throughput SLIs can be computed without scanning traces
 *  - a `http.server.auth.decision.count` outcome counter, derived from 401/403 responses
 *  - a `slow_request` span event when a request exceeds the P99 latency budget (750ms)
 */
@Filter("/**")
class OpenTelemetryHttpMetricsFilter : HttpServerFilter {
  private val meter by lazy { GlobalOpenTelemetry.getMeter("io.airbyte.workload.server") }

  private val requestDuration by lazy {
    meter
      .histogramBuilder("http.server.request.duration")
      .setDescription("Duration of inbound HTTP requests")
      .setUnit("s")
      .build()
  }

  private val requestCounter by lazy {
    meter
      .counterBuilder("http.server.request.count")
      .setDescription("Count of inbound HTTP requests by route, outcome and caller")
      .build()
  }

  private val authDecisionsCounter by lazy {
    meter
      .counterBuilder("http.server.auth.decision.count")
      .setDescription("Authentication/authorization decisions for inbound HTTP requests")
      .build()
  }

  override fun doFilter(
    request: HttpRequest<*>,
    chain: ServerFilterChain,
  ): Publisher<MutableHttpResponse<*>> {
    val startNanos = System.nanoTime()
    val method = request.methodName
    val route =
      request.attributes
        .get(HttpAttributes.URI_TEMPLATE, String::class.java)
        .orElse(request.path)
    val scheme = request.uri.scheme ?: "http"
    val tenant = request.headers.get("X-Airbyte-Client-Id") ?: "unknown"

    return Mono.from(chain.proceed(request))
      .doOnNext { response -> recordOutcome(method, route, scheme, tenant, response.status.code, null, startNanos) }
      .doOnError { throwable -> recordOutcome(method, route, scheme, tenant, 500, throwable, startNanos) }
  }

  private fun recordOutcome(
    method: String,
    route: String,
    scheme: String,
    tenant: String,
    statusCode: Int,
    error: Throwable?,
    startNanos: Long,
  ) {
    val elapsedNanos = System.nanoTime() - startNanos
    val elapsedSeconds = elapsedNanos / 1_000_000_000.0
    val elapsedMs = elapsedNanos / 1_000_000
    val outcome = if (statusCode >= 500) "failure" else "success"

    val durationAttributesBuilder =
      Attributes
        .builder()
        .put(AttributeKey.stringKey("http.request.method"), method)
        .put(AttributeKey.stringKey("url.scheme"), scheme)
        .put(AttributeKey.longKey("http.response.status_code"), statusCode.toLong())
        .put(AttributeKey.stringKey("http.route"), route)

    if (error != null) {
      val errorType = error::class.java.name
      durationAttributesBuilder.put(AttributeKey.stringKey("error.type"), errorType)
      Span.current().setAttribute("error.type", errorType)
    }

    requestDuration.record(elapsedSeconds, durationAttributesBuilder.build())

    requestCounter.add(
      1,
      Attributes.of(
        AttributeKey.stringKey("http.route"), route,
        AttributeKey.stringKey("outcome"), outcome,
        AttributeKey.stringKey("tenant"), tenant,
      ),
    )

    if (statusCode == 401 || statusCode == 403) {
      authDecisionsCounter.add(
        1,
        Attributes.of(
          AttributeKey.stringKey("outcome"), "denied",
          AttributeKey.stringKey("reason"), if (statusCode == 401) "unauthenticated" else "forbidden",
        ),
      )
    } else {
      authDecisionsCounter.add(
        1,
        Attributes.of(AttributeKey.stringKey("outcome"), "allowed"),
      )
    }

    if (elapsedMs > 750) {
      Span.current().addEvent(
        "slow_request",
        Attributes.of(AttributeKey.longKey("duration_ms"), elapsedMs),
      )
    }
  }
}
