/*
 * Copyright (c) 2020-2026 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workload.errors

import io.airbyte.commons.json.Jsons
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Produces
import io.micronaut.http.server.exceptions.ExceptionHandler
import io.opentelemetry.api.trace.Span
import jakarta.inject.Singleton

@Produces
@Singleton
class KnownExceptionHandler : ExceptionHandler<KnownException, HttpResponse<String>> {
  override fun handle(
    request: HttpRequest<Any>,
    exception: KnownException,
  ): HttpResponse<String> {
    // Record the originating exception type on the current server span so error responses can be
    // attributed to a root cause class without altering the response that is returned.
    Span.current().setAttribute("error.type", exception.javaClass.simpleName)
    return HttpResponse
      .status<Any>(exception.getHttpCode())
      .body(Jsons.serialize(exception.getInfo()))
      .contentType(MediaType.APPLICATION_JSON_TYPE)
  }
}
