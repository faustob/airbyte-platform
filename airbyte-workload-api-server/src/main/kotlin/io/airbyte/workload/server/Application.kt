/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.server

import io.micronaut.runtime.Micronaut.build
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk

class Application {
  companion object {
    @JvmStatic fun main(args: Array<String>) {
      // Register the OpenTelemetry SDK as the global instance before any Micronaut bean
      // (controllers, filters, etc.) is constructed and potentially creates a meter/tracer.
      // This is the first statement in main(), so registration should never legitimately
      // fail here; let any real failure surface instead of silently falling back to a
      // permanently no-op provider.
      val openTelemetrySdk = AutoConfiguredOpenTelemetrySdk.builder().setResultAsGlobal().build().openTelemetrySdk
      Runtime.getRuntime().addShutdownHook(
        Thread {
          openTelemetrySdk.close()
        },
      )
      build(*args)
        .deduceCloudEnvironment(false)
        .deduceEnvironment(false)
        .mainClass(Application::class.java)
        .start()
    }
  }
}
