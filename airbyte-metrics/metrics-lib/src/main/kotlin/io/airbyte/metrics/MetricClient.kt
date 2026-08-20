/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.metrics

import jakarta.inject.Singleton
import java.util.function.ToDoubleFunction

/**
 * Converts an array of [MetricAttribute]s to a [List] of [MetricAttribute]s.
 *
 * @param attributes An array of [MetricAttribute]s.
 * @returns A [List] of [MetricAttribute]s.
 */
fun toList(attributes: Array<out MetricAttribute?>): List<MetricAttribute> = attributes.mapNotNull { it }

@Singleton
class MetricClient {
  /**
   * Increment or decrement a counter.
   *
   * @param metric The [MetricsRegistry] defined metric to record
   * @param value The value to record.
   * @param attributes additional attributes
   */
  @JvmOverloads
  fun count(
    metric: MetricsRegistry,
    value: Long = 1L,
    vararg attributes: MetricAttribute?,
  ) {
  }

  /**
   * Creates a counter.
   *
   * @param metric The [MetricsRegistry] defined metric to record
   * @param attributes additional attributes
   */
  fun counter(
    metric: MetricsRegistry,
    vararg attributes: MetricAttribute?,
  ) {
  }

  /**
   * Record the latest value of a state object for a gauge.
   *
   * @param metric The [MetricsRegistry] defined metric to record
   * @param stateObject The object to monitor as part of the gauge.
   * @param function The function used to extract the double value when reporting the gauge's current value.
   * @param attributes additional attributes.
   *
   * @return The gauge-wrapped state object.
   */
  fun <T> gauge(
    metric: MetricsRegistry,
    stateObject: T,
    function: ToDoubleFunction<T>,
    vararg attributes: MetricAttribute?,
  ): T = stateObject

  /**
   * Record the latest value for a gauge.
   *
   * @param metric The [MetricsRegistry] defined metric to record
   * @param value The value to record.
   * @param attributes additional attributes
   */
  fun gauge(
    metric: MetricsRegistry,
    value: Double,
    vararg attributes: MetricAttribute?,
  ) {
  }

  /**
   * Accepts value on the metrics, and report the distribution of these values. Useful to analysis how
   * much time have elapsed, and percentile of a series of records.
   *
   * @param metric The [MetricsRegistry] defined metric to record
   * @param value The value to record.
   * @param attributes additional attributes
   */
  fun distribution(
    metric: MetricsRegistry,
    value: Double,
    vararg attributes: MetricAttribute?,
  ) {
  }

  /**
   * Creates a [Timer]-like handle. Returns null as metrics recording has been removed.
   *
   * @param metric The [MetricsRegistry] defined metric to record as a timer
   * @param attributes additional attributes.
   * @return null, as metrics are not configured.
   */
  fun timer(
    metric: MetricsRegistry,
    vararg attributes: MetricAttribute?,
  ): Nothing? = null

  /**
   * Closes the client and any underlying resources.  This is important to ensure that any remaining metric values are published
   * prior to the shutdown of the containing application
   */
  fun close() {
  }
}

/**
 * Custom tuple that represents a key/value pair to be included with a metric.
 */
data class MetricAttribute(
  val key: String = "",
  val value: String = "",
)
