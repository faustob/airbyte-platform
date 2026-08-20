/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.server

import io.micronaut.runtime.Micronaut.build
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk

class Application {
  companion object {
    @JvmStatic fun main(args: Array<String>) {
      try {
        // Register the OpenTelemetry SDK as the global instance BEFORE Micronaut builds its context,
        // so every bean/filter that later calls GlobalOpenTelemetry.get* obtains a working provider.
        val autoConfiguredSdk = AutoConfiguredOpenTelemetrySdk.builder().setResultAsGlobal().build()
        Runtime.getRuntime().addShutdownHook(
          Thread {
            // Flush and close exporters (PeriodicMetricReader/BatchSpanProcessor) so buffered
            // metrics/spans are not lost when the JVM terminates.
            autoConfiguredSdk.openTelemetrySdk.sdkMeterProvider.close()
            autoConfiguredSdk.openTelemetrySdk.sdkTracerProvider.close()
          },
        )
      } catch (e: IllegalStateException) {
        // An OpenTelemetry SDK is already registered globally (e.g. attached agent). Keep using it.
      }
      build(*args)
        .deduceCloudEnvironment(false)
        .deduceEnvironment(false)
        .mainClass(Application::class.java)
        .start()
    }
  }
}
