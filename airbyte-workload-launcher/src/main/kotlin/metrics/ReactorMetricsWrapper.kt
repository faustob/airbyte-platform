/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.launcher.metrics

import io.airbyte.metrics.MetricAttribute
import jakarta.inject.Singleton
import reactor.core.scheduler.Scheduler

// This could be moved into airbyte-metrics however, it would require moving the dependency on reactor as well.
@Singleton
class ReactorMetricsWrapper {
  fun asTimedScheduler(
    scheduler: Scheduler,
    metricPrefix: String,
    vararg attributes: MetricAttribute?,
  ): Scheduler = scheduler
}
