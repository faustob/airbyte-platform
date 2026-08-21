/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.filter

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
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux

private const val SLOW_REQUEST_THRESHOLD_MS = 750L
private const val CLIENT_CLOSED_REQUEST_STATUS = 499

/**
 * Emits OpenTelemetry HTTP server telemetry (request duration, outcome, auth decisions, slow
 * request span events, and exception-to-status attribution) for every inbound request handled by
 * this service, per the OTel HTTP semantic conventions. This is the ONLY OpenTelemetry HTTP-server
 * instrument in this codebase (the pre-existing Micrometer/StatsD metrics configured via
 * application.yml `micronaut.metrics` are a separate, untouched telemetry pipeline), so these
 * metric names do not collide with or duplicate another OTel instrument. Registered automatically
 * as a Micronaut bean via the @Filter stereotype -- no additional wiring is required.
 */
@Filter("/**")
class OpenTelemetryHttpServerFilter : HttpServerFilter {
  // Resolved lazily (not at bean-construction time) so these bind to the GLOBAL OpenTelemetry SDK
  // that Application.main() registers, rather than to a pre-registration no-op snapshot.
  private val meter by lazy { GlobalOpenTelemetry.getMeter("io.airbyte.workload.api.server") }
  private val tracer by lazy { GlobalOpenTelemetry.getTracer("io.airbyte.workload.api.server") }

  private val requestDuration by lazy {
    meter
      .histogramBuilder("http.server.request.duration")
      .setUnit("s")
      .setDescription("Duration of inbound HTTP requests")
      .build()
  }

  // Plain, unsuffixed count -- outcome is carried purely as an attribute dimension, never baked
  // into the metric name.
  private val requestCounter by lazy {
    meter
      .counterBuilder("http.server.request.count")
      .setDescription("Count of inbound HTTP requests by route and outcome class (availability SLI)")
      .build()
  }

  private val authAttemptsCounter by lazy {
    meter
      .counterBuilder("http.server.auth.attempts")
      .setDescription("Authentication/authorization decisions for inbound requests")
      .build()
  }

  override fun doFilter(
    request: HttpRequest<*>,
    chain: ServerFilterChain,
  ): Publisher<MutableHttpResponse<*>> {
    val startNanos = System.nanoTime()
    val method = request.method.name
    val route = request.getAttribute(HttpAttributes.URI_TEMPLATE, String::class.java).orElse(request.path)
    val scheme = if (request.isSecure) "https" else "http"
    val span =
      tracer
        .spanBuilder("$method $route")
        .setSpanKind(SpanKind.SERVER)
        .setAttribute("http.request.method", method)
        .setAttribute("http.route", route)
        .setAttribute("url.scheme", scheme)
        .startSpan()

    var responseStatus = 0
    var capturedError: Throwable? = null

    // doOnNext/doOnError only capture outcome; the actual metric recording and span end happen
    // exactly once in doFinally, which fires on completion, error, AND cancellation -- so a
    // cancelled or empty stream can never leave the span unended or drop telemetry.
    return Flux
      .from(chain.proceed(request))
      .doOnNext { response -> responseStatus = response.status.code }
      .doOnError { throwable -> capturedError = throwable }
      .doFinally {
        val status =
          when {
            capturedError != null -> 500
            responseStatus != 0 -> responseStatus
            else -> CLIENT_CLOSED_REQUEST_STATUS
          }
        finish(span, startNanos, method, route, scheme, status, capturedError)
      }
  }

  private fun finish(
    span: Span,
    startNanos: Long,
    method: String,
    route: String,
    scheme: String,
    status: Int,
    throwable: Throwable?,
  ) {
    val elapsedSeconds = (System.nanoTime() - startNanos) / 1_000_000_000.0
    val errorType = throwable?.javaClass?.simpleName

    val attributesBuilder =
      Attributes
        .builder()
        .put(AttributeKey.stringKey("http.request.method"), method)
        .put(AttributeKey.stringKey("url.scheme"), scheme)
        .put(AttributeKey.longKey("http.response.status_code"), status.toLong())
        .put(AttributeKey.stringKey("http.route"), route)
    if (errorType != null) {
      attributesBuilder.put(AttributeKey.stringKey("error.type"), errorType)
    }
    requestDuration.record(elapsedSeconds, attributesBuilder.build())

    val outcome = if (status < 500) "success" else "failure"
    requestCounter.add(
      1,
      Attributes.of(
        AttributeKey.stringKey("http.route"), route,
        AttributeKey.stringKey("outcome"), outcome,
      ),
    )

    if (route != "/health") {
      val authOutcome = if (status == 401 || status == 403) "denied" else "allowed"
      authAttemptsCounter.add(
        1,
        Attributes.of(
          AttributeKey.stringKey("outcome"), authOutcome,
          AttributeKey.stringKey("reason"), if (authOutcome == "denied") status.toString() else "n/a",
        ),
      )
    }

    span.setAttribute("http.response.status_code", status.toLong())
    if (errorType != null) {
      span.setAttribute("error.type", errorType)
      span.recordException(throwable!!)
      span.setStatus(StatusCode.ERROR)
    } else if (status >= 500) {
      span.setStatus(StatusCode.ERROR)
    }

    val elapsedMs = (elapsedSeconds * 1000).toLong()
    if (elapsedMs > SLOW_REQUEST_THRESHOLD_MS) {
      span.addEvent(
        "slow_request",
        Attributes.of(AttributeKey.longKey("duration_ms"), elapsedMs),
      )
    }

    span.end()
  }
}
