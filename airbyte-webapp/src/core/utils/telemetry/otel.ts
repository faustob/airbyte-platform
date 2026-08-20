import { metrics } from "@opentelemetry/api";
import { OTLPMetricExporter } from "@opentelemetry/exporter-metrics-otlp-http";
import { Resource } from "@opentelemetry/resources";
import { MeterProvider, PeriodicExportingMetricReader } from "@opentelemetry/sdk-metrics";

let initialized = false;

/**
 * Registers a browser-side OpenTelemetry MeterProvider as the GLOBAL meter provider. This is a
 * pure static SPA (served by nginx, no app-owned backend route to relay through), so the browser
 * itself is the OTel-instrumented process: it builds and registers its own MeterProvider, exactly
 * once, before any RUM signal (Core Web Vitals, JS errors, SPA navigation timing) is recorded.
 *
 * The OTLP endpoint is env-driven (REACT_APP_OTEL_EXPORTER_OTLP_ENDPOINT, wired the same way as
 * the other REACT_APP_* variables in this app - see packages/vite-plugins/environment-variables.ts)
 * and defaults to a same-origin path so deployments can reverse-proxy it to a collector without
 * ever hardcoding a host.
 *
 * Safe to call multiple times (e.g. HMR) - only registers once.
 */
export function initializeOtel(): void {
  if (initialized) {
    return;
  }
  initialized = true;

  const otlpEndpoint = process.env.REACT_APP_OTEL_EXPORTER_OTLP_ENDPOINT || "/v1/metrics";

  const exporter = new OTLPMetricExporter({
    url: otlpEndpoint,
  });

  const meterProvider = new MeterProvider({
    resource: new Resource({
      "service.name": "airbyte-webapp",
    }),
    readers: [
      new PeriodicExportingMetricReader({
        exporter,
        exportIntervalMillis: 10000,
      }),
    ],
  });

  metrics.setGlobalMeterProvider(meterProvider);
}

/**
 * Returns the shared meter for this app. Always call `initializeOtel()` first (done once at
 * bootstrap in src/index.tsx) - otherwise this resolves to the OTel API's no-op meter.
 */
export function getWebappMeter() {
  return metrics.getMeter("airbyte-webapp");
}
