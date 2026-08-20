# HoundOS migration — Datadog APM (dd-trace) → OpenTelemetry

Base: 7341564. Metric names preserved verbatim (dashboard continuity).

| metric | instrument | file |
|---|---|---|

## Activating the export

The translated code emits through the global OpenTelemetry meter. **Nothing is exported until the process initializes a MeterProvider with an exporter** — the legacy tool's config path does not do this.

- Attach the OpenTelemetry Java agent (`-javaagent:opentelemetry-javaagent.jar`) with `OTEL_SERVICE_NAME` and `OTEL_EXPORTER_OTLP_*` env set — the global meter binds automatically.

HoundOS manages this instrumentation from here: arrival monitoring by the names above,
per-service signals, and SLI suggestions on the Signals tab.