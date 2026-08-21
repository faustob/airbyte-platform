/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.handler

import io.airbyte.config.WorkloadPriority
import io.airbyte.config.WorkloadType
import io.airbyte.featureflag.Empty
import io.airbyte.featureflag.FeatureFlagClient
import io.airbyte.featureflag.UseDeadlineInWorkloadMonitorQueries
import io.airbyte.micronaut.runtime.AirbyteWorkloadApiClientConfig
import io.airbyte.workload.api.domain.Workload
import io.airbyte.workload.api.domain.WorkloadLabel
import io.airbyte.workload.api.domain.WorkloadQueueStats
import io.airbyte.workload.errors.ConflictException
import io.airbyte.workload.errors.InvalidStatusTransitionException
import io.airbyte.workload.errors.NotFoundException
import io.airbyte.workload.repository.WorkloadQueueRepository
import io.airbyte.workload.repository.WorkloadRepository
import io.airbyte.workload.repository.domain.WorkloadStatus
import io.airbyte.workload.services.WorkloadService
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import jakarta.inject.Singleton
import java.time.OffsetDateTime
import java.util.UUID
import io.airbyte.workload.repository.domain.Workload as DomainWorkload

/**
 * Interface layer between the API and Persistence layers.
 */
@Singleton
class WorkloadHandlerImpl(
  private val workloadService: WorkloadService,
  private val workloadRepository: WorkloadRepository,
  private val workloadQueueRepository: WorkloadQueueRepository,
  private val airbyteWorkloadApiClientConfig: AirbyteWorkloadApiClientConfig,
  private val featureFlagClient: FeatureFlagClient,
) : WorkloadHandler {
  override fun getWorkload(workloadId: String): ApiWorkload = getDomainWorkload(workloadId).toApi()

  private fun getDomainWorkload(workloadId: String): DomainWorkload =
    withWorkloadServiceExceptionConverter {
      withDbTelemetry("SELECT", "workload") { workloadService.getWorkload(workloadId) }
    }

  override fun getWorkloads(
    dataplaneId: List<String>?,
    workloadStatus: List<ApiWorkloadStatus>?,
    updatedBefore: OffsetDateTime?,
  ): List<Workload> {
    val domainWorkloads =
      withDbTelemetry("SELECT", "workload") {
        workloadRepository.search(
          dataplaneId,
          workloadStatus?.map { it.toDomain() },
          updatedBefore,
        )
      }

    return domainWorkloads.map { it.toApi() }
  }

  override fun workloadAlreadyExists(workloadId: String): Boolean =
    withDbTelemetry("SELECT", "workload") { workloadRepository.existsById(workloadId) }

  override fun createWorkload(
    workloadId: String,
    labels: List<WorkloadLabel>?,
    input: String,
    workspaceId: UUID?,
    organizationId: UUID?,
    logPath: String,
    mutexKey: String?,
    type: WorkloadType,
    autoId: UUID,
    deadline: OffsetDateTime,
    signalInput: String?,
    dataplaneGroup: String?,
    priority: WorkloadPriority?,
  ) {
    withWorkloadServiceExceptionConverter {
      withDbTelemetry("INSERT", "workload") {
        workloadService.createWorkload(
          workloadId = workloadId,
          labels =
            labels?.map {
              io.airbyte.workload.repository.domain
                .WorkloadLabel(key = it.key, value = it.value)
            },
          logPath = logPath,
          input = input,
          workspaceId = workspaceId,
          organizationId = organizationId,
          mutexKey = mutexKey,
          type = type,
          autoId = autoId,
          deadline = deadline,
          signalInput = signalInput,
          dataplaneGroup = dataplaneGroup,
          priority = priority,
        )
      }
    }
  }

  override fun claimWorkload(
    workloadId: String,
    dataplaneId: String,
    deadline: OffsetDateTime,
    dataplaneVersion: String?,
  ): Boolean = workloadService.claimWorkload(workloadId, dataplaneId, deadline, dataplaneVersion) != null

  override fun cancelWorkload(
    workloadId: String,
    source: String?,
    reason: String?,
  ) {
    withWorkloadServiceExceptionConverter {
      workloadService.cancelWorkload(workloadId, source, reason)
    }
  }

  override fun failWorkload(
    workloadId: String,
    source: String?,
    reason: String?,
    dataplaneVersion: String?,
  ) {
    withWorkloadServiceExceptionConverter {
      workloadService.failWorkload(workloadId, source, reason, dataplaneVersion)
    }
  }

  override fun succeedWorkload(
    workloadId: String,
    dataplaneVersion: String?,
  ) {
    withWorkloadServiceExceptionConverter {
      workloadService.succeedWorkload(workloadId, dataplaneVersion)
    }
  }

  override fun setWorkloadStatusToRunning(
    workloadId: String,
    deadline: OffsetDateTime,
    dataplaneVersion: String?,
  ) {
    withWorkloadServiceExceptionConverter {
      workloadService.runningWorkload(workloadId, deadline, dataplaneVersion)
    }
  }

  override fun setWorkloadStatusToLaunched(
    workloadId: String,
    deadline: OffsetDateTime,
    dataplaneVersion: String?,
  ) {
    withWorkloadServiceExceptionConverter {
      workloadService.launchWorkload(workloadId, deadline, dataplaneVersion)
    }
  }

  override fun heartbeat(
    workloadId: String,
    deadline: OffsetDateTime,
    dataplaneVersion: String?,
  ) {
    withWorkloadServiceExceptionConverter {
      workloadService.heartbeatWorkload(workloadId, deadline, dataplaneVersion)
    }
  }

  fun offsetDateTime(): OffsetDateTime = OffsetDateTime.now()

  override fun getWorkloadsRunningCreatedBefore(
    dataplaneId: List<String>?,
    workloadType: List<ApiWorkloadType>?,
    createdBefore: OffsetDateTime?,
  ): List<Workload> {
    val useDeadline = featureFlagClient.boolVariation(UseDeadlineInWorkloadMonitorQueries, Empty)
    val domainWorkloads =
      if (useDeadline) {
        workloadRepository.searchByTypeStatusAndCreationDateWithDeadline(
          dataplaneId,
          listOf(WorkloadStatus.RUNNING),
          workloadType?.map { it.toDomain() },
          createdBefore,
        )
      } else {
        workloadRepository.searchByTypeStatusAndCreationDate(
          dataplaneId,
          listOf(WorkloadStatus.RUNNING),
          workloadType?.map { it.toDomain() },
          createdBefore,
        )
      }

    return domainWorkloads.map { it.toApi() }
  }

  override fun pollWorkloadQueue(
    dataplaneGroup: String?,
    priority: WorkloadPriority?,
    quantity: Int,
  ): List<Workload> {
    val domainWorkloads =
      withDbTelemetry("SELECT", "workload_queue") {
        workloadQueueRepository.pollWorkloadQueue(
          dataplaneGroup,
          priority?.toInt(),
          quantity,
          redeliveryWindowSecs = airbyteWorkloadApiClientConfig.workloadRedeliveryWindowSeconds,
        )
      }

    return domainWorkloads.map { it.toApi() }
  }

  override fun countWorkloadQueueDepth(
    dataplaneGroup: String?,
    priority: WorkloadPriority?,
  ): Long = withDbTelemetry("SELECT", "workload_queue") { workloadQueueRepository.countEnqueuedWorkloads(dataplaneGroup, priority?.toInt()) }

  override fun getWorkloadQueueStats(): List<WorkloadQueueStats> {
    val domainStats =
      withDbTelemetry("SELECT", "workload_queue") { workloadQueueRepository.getEnqueuedWorkloadStats() }

    return domainStats.map { it.toApi() }
  }

  override fun cleanWorkloadQueue(limit: Int) {
    withDbTelemetry("DELETE", "workload_queue") { workloadQueueRepository.cleanUpAckedEntries(limit) }
  }

  override fun getActiveWorkloads(
    dataplaneIds: List<String>?,
    statuses: List<ApiWorkloadStatus>?,
  ): List<ApiWorkloadSummary> {
    val domainWorkloadsDTO =
      withDbTelemetry("SELECT", "workload") {
        workloadRepository.searchActive(
          dataplaneIds = dataplaneIds,
          statuses = statuses?.map { it.toDomain() },
        )
      }
    return domainWorkloadsDTO.map { it.toApi() }
  }

  override fun getWorkloadsWithExpiredDeadline(
    dataplaneId: List<String>?,
    workloadStatus: List<ApiWorkloadStatus>?,
    deadline: OffsetDateTime,
  ): List<Workload> {
    val domainWorkloads =
      withDbTelemetry("SELECT", "workload") {
        workloadRepository.searchForExpiredWorkloads(
          dataplaneId,
          workloadStatus?.map { it.toDomain() },
          deadline,
        )
      }

    return domainWorkloads.map { it.toApi() }
  }

  private fun <T> withWorkloadServiceExceptionConverter(f: () -> T): T {
    try {
      return f()
    } catch (e: io.airbyte.workload.services.ConflictException) {
      throw ConflictException(e.message)
    } catch (e: io.airbyte.workload.services.InvalidStatusTransitionException) {
      throw InvalidStatusTransitionException(e.message)
    } catch (e: io.airbyte.workload.services.NotFoundException) {
      throw NotFoundException(e.message)
    }
  }

  /**
   * Wraps a database operation with OpenTelemetry db.client.operation.duration telemetry,
   * classifying the outcome (success/failure) and flagging slow queries with a span event, without
   * altering control flow. The success outcome is recorded ONLY after the operation returns; on
   * failure the SAME exception is always rethrown after recording (never swallowed).
   */
  private fun <T> withDbTelemetry(
    operationName: String,
    collectionName: String,
    block: () -> T,
  ): T {
    val start = System.nanoTime()
    try {
      val result = block()
      recordDbOperation(operationName, collectionName, start, null)
      return result
    } catch (t: Throwable) {
      recordDbOperation(operationName, collectionName, start, t)
      throw t
    }
  }

  private fun recordDbOperation(
    operationName: String,
    collectionName: String,
    start: Long,
    error: Throwable?,
  ) {
    val elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0
    val errorType = error?.javaClass?.simpleName
    val attributesBuilder =
      Attributes
        .builder()
        .put(AttributeKey.stringKey("db.system.name"), "postgresql")
        .put(AttributeKey.stringKey("db.operation.name"), operationName)
        .put(AttributeKey.stringKey("db.collection.name"), collectionName)
    if (errorType != null) {
      attributesBuilder.put(AttributeKey.stringKey("error.type"), errorType)
    }
    dbOperationDuration.record(elapsedSeconds, attributesBuilder.build())
    // Plain, unsuffixed count -- outcome is carried purely as an attribute dimension, never baked
    // into the metric name.
    dbOperationCounter.add(
      1,
      Attributes.of(
        AttributeKey.stringKey("db.operation.name"), operationName,
        AttributeKey.stringKey("outcome"), if (errorType == null) "success" else "failure",
      ),
    )
    if (elapsedSeconds * 1000 > SLOW_QUERY_THRESHOLD_MS) {
      Span.current().addEvent(
        "slow_query",
        Attributes.of(
          AttributeKey.stringKey("db.operation.name"), operationName,
          AttributeKey.stringKey("db.collection.name"), collectionName,
        ),
      )
    }
  }

  companion object {
    private const val SLOW_QUERY_THRESHOLD_MS = 200L

    // Resolved lazily (not at class-load time) so these bind to the GLOBAL OpenTelemetry SDK that
    // Application.main() registers, rather than to a pre-registration no-op snapshot.
    private val meter by lazy { GlobalOpenTelemetry.getMeter("io.airbyte.workload.api.server") }
    private val dbOperationDuration by lazy {
      meter
        .histogramBuilder("db.client.operation.duration")
        .setUnit("s")
        .setDescription("Duration of database operations issued by the workload API server")
        .build()
    }
    private val dbOperationCounter by lazy {
      meter
        .counterBuilder("db.client.operation.count")
        .setDescription("Count of database operations by operation name and outcome")
        .build()
    }
  }
}
