/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.filter

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
 * Emits OpenTelemetry telemetry for every inbound HTTP request handled by this server:
 * - http.server.request.duration (standard semantic-convention histogram, seconds)
 * - a request outcome counter (route x outcome class) for availability computation
 * - a per-client request-rate counter for throughput/capacity SLIs
 * - an auth attempt outcome counter, approximated from 401/403 responses
 * - slow-request span events and error-type span attributes for triage
 *
 * This is additive observability only: it never mutates the request/response and never alters
 * control flow, so existing handler behavior (including HealthController) is unaffected.
 */
@Filter(Filter.MATCH_ALL_PATTERN)
class HttpTelemetryFilter : HttpServerFilter {
  private val meter = GlobalOpenTelemetry.getMeter("io.airbyte.workload.server")

  private val requestDuration =
    meter
      .histogramBuilder("http.server.request.duration")
      .setDescription("Duration of inbound HTTP requests")
      .setUnit("s")
      .build()

  private val requestOutcomeCounter =
    meter
      .counterBuilder("http.server.request.count")
      .setDescription("Count of HTTP requests by route and outcome class (success/failure)")
      .setUnit("1")
      .build()

  private val requestsByClientCounter =
    meter
      .counterBuilder("http.server.request.by_client.count")
      .setDescription("Count of HTTP requests by route and calling client id")
      .setUnit("1")
      .build()

  private val authAttemptCounter =
    meter
      .counterBuilder("auth.attempt.count")
      .setDescription("Authentication/authorization decisions from real auth outcomes (401/403 denials)")
      .setUnit("1")
      .build()

  private val slowRequestBudgetMs = 750L

  override fun doFilter(
    request: HttpRequest<*>,
    chain: ServerFilterChain,
  ): Publisher<MutableHttpResponse<*>> {
    val start = System.nanoTime()
    val method = request.method.name
    val scheme = request.uri.scheme ?: "http"
    val route = request.path
    val clientId = request.headers.get("X-Client-Id") ?: "unknown"

    return Mono
      .from(chain.proceed(request))
      .doOnNext { response -> recordSuccess(response, method, scheme, route, clientId, start) }
      .doOnError { throwable -> recordError(throwable, method, scheme, route, start) }
  }

  private fun recordSuccess(
    response: MutableHttpResponse<*>,
    method: String,
    scheme: String,
    route: String,
    clientId: String,
    start: Long,
  ) {
    val status = response.status.code
    val elapsedNanos = System.nanoTime() - start
    val elapsedSeconds = elapsedNanos / 1_000_000_000.0
    val elapsedMs = elapsedNanos / 1_000_000

    val durationAttrs =
      Attributes
        .builder()
        .put(AttributeKey.stringKey("http.request.method"), method)
        .put(AttributeKey.stringKey("url.scheme"), scheme)
        .put(AttributeKey.longKey("http.response.status_code"), status.toLong())
        .put(AttributeKey.stringKey("http.route"), route)
    if (status >= 500) {
      durationAttrs.put(AttributeKey.stringKey("error.type"), "http_$status")
    }
    requestDuration.record(elapsedSeconds, durationAttrs.build())

    val outcome = if (status < 500) "success" else "failure"
    requestOutcomeCounter.add(
      1,
      Attributes.of(
        AttributeKey.stringKey("http.route"),
        route,
        AttributeKey.stringKey("outcome"),
        outcome,
      ),
    )

    requestsByClientCounter.add(
      1,
      Attributes.of(
        AttributeKey.stringKey("http.route"),
        route,
        AttributeKey.stringKey("client.id"),
        clientId,
      ),
    )

    // Record every response that reflects a real authentication/authorization decision so a rate can
    // be computed: denials (401/403) plus successful non-auth-error responses count as "allowed",
    // giving the auth.attempt.count instrument both a numerator and a denominator.
    if (status == 401 || status == 403) {
      authAttemptCounter.add(
        1,
        Attributes.of(
          AttributeKey.stringKey("outcome"),
          "denied",
          AttributeKey.stringKey("reason"),
          "http_$status",
        ),
      )
    } else if (status < 400) {
      authAttemptCounter.add(
        1,
        Attributes.of(
          AttributeKey.stringKey("outcome"),
          "allowed",
          AttributeKey.stringKey("reason"),
          "http_$status",
        ),
      )
    }

    if (elapsedMs > slowRequestBudgetMs) {
      Span.current().addEvent(
        "slow_request",
        Attributes.of(AttributeKey.longKey("duration_ms"), elapsedMs),
      )
    }

    if (status >= 500) {
      Span.current().setAttribute(AttributeKey.stringKey("error.type"), "http_$status")
    }
  }

  private fun recordError(
    throwable: Throwable,
    method: String,
    scheme: String,
    route: String,
    start: Long,
  ) {
    val elapsedNanos = System.nanoTime() - start
    val elapsedSeconds = elapsedNanos / 1_000_000_000.0
    val errorType = throwable.javaClass.name

    requestDuration.record(
      elapsedSeconds,
      Attributes.of(
        AttributeKey.stringKey("http.request.method"),
        method,
        AttributeKey.stringKey("url.scheme"),
        scheme,
        AttributeKey.stringKey("http.route"),
        route,
        AttributeKey.stringKey("error.type"),
        errorType,
      ),
    )

    requestOutcomeCounter.add(
      1,
      Attributes.of(
        AttributeKey.stringKey("http.route"),
        route,
        AttributeKey.stringKey("outcome"),
        "failure",
      ),
    )

    Span.current().setAttribute(AttributeKey.stringKey("error.type"), errorType)
  }
}
