/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.launcher.metrics

import io.airbyte.workload.launcher.model.DataplaneConfig
import io.micronaut.context.event.ApplicationEventListener
import jakarta.inject.Singleton

@Singleton
class DataplaneMeterTagsUpdater : ApplicationEventListener<DataplaneConfig> {
  override fun onApplicationEvent(event: DataplaneConfig) {
    // Micrometer-based meter tag replacement has been removed. This listener is preserved
    // so that other producers of DataplaneConfig events continue to have a registered listener.
  }
}
