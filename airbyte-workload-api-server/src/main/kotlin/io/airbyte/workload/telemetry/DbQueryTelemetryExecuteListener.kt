/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.telemetry

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import org.jooq.ExecuteContext
import org.jooq.impl.DefaultExecuteListener

/**
 * jOOQ ExecuteListener that records database query outcome and latency for the workload-state
 * store, backing the db-query-success and db-query-latency SLIs.
 *
 * Registered via jOOQ's Configuration (see wiring). This is additive observability only: it
 * never mutates the query, its parameters, or its result, and never alters control flow or
 * exception propagation.
 */
class DbQueryTelemetryExecuteListener : DefaultExecuteListener() {
  companion object {
    private const val START_NANOS_DATA_KEY = 