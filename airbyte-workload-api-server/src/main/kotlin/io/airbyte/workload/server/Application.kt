/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.server

import io.micronaut.runtime.Micronaut.build
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk

class Application {
  companion object {
    @JvmStatic fun main(args: Array<String>) {
      // Registers the OpenTelemetry SDK as the GLOBAL instance. Configuration is entirely env-driven
      // (AutoConfiguredOpenTelemetrySdk reads env vars / system properties, NOT application.yml) --
      // the deploy environment for this service MUST set:
      //   OTEL_SERVICE_NAME              (defaults to "unknown_service" otherwise)
      //   OTEL_TRACES_EXPORTER            e.g. "otlp" (or "none" to disable trace export)
      //   OTEL_METRICS_EXPORTER           e.g. "otlp" (or "none" to disable metric export)
      //   OTEL_EXPORTER_OTLP_ENDPOINT     e.g. http://otel-collector:4317 -- never hardcode this
      // This must run unconditionally, as the FIRST statement, before any Micronaut bean can call
      // GlobalOpenTelemetry.get*() and lock the global to a no-op provider.
      AutoConfiguredOpenTelemetrySdk.builder().setResultAsGlobal().build()
      build(*args)
        .deduceCloudEnvironment(false)
        .deduceEnvironment(false)
        .mainClass(Application::class.java)
        .start()
    }
  }
}
