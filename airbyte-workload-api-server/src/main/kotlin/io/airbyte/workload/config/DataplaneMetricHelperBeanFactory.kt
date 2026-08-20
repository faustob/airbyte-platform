/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.config

import io.airbyte.commons.server.metrics.MetricTagsPrettifierCache
import io.airbyte.data.services.DataplaneGroupService
import io.airbyte.data.services.DataplaneService
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class DataplaneMetricHelperBeanFactory {
  @Singleton
  fun metricPrettifierCache(
    dataplaneService: DataplaneService,
    dataplaneGroupService: DataplaneGroupService,
  ): MetricTagsPrettifierCache = MetricTagsPrettifierCache(dataplaneService, dataplaneGroupService)
}
