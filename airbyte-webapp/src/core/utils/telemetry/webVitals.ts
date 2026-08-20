import { onCLS, onINP, onLCP } from "web-vitals";

import { getWebappMeter } from "./otel";

let started = false;

/**
 * Reports Core Web Vitals (LCP, INP, CLS) from real user sessions as OpenTelemetry histograms so
 * the airbyte-webapp-web-lcp-p75 / -web-inp-p75 / -web-cls-p75 SLIs can be computed.
 *
 * web-vitals finalizes each metric (LCP once it stops changing, INP once the page is hidden/
 * unloaded, CLS for the whole session) and flushes on the page's first visibility-hidden
 * transition internally - callers just need to register the callbacks once, which is what this
 * does.
 */
export function initWebVitalsReporting(): void {
  if (started) {
    return;
  }
  started = true;

  const meter = getWebappMeter();

  const lcpHistogram = meter.createHistogram("web.vital.lcp", {
    description: "Largest Contentful Paint as reported by the browser",
    unit: "ms",
  });
  const inpHistogram = meter.createHistogram("web.vital.inp", {
    description: "Interaction to Next Paint as reported by the browser",
    unit: "ms",
  });
  const clsHistogram = meter.createHistogram("web.vital.cls", {
    description: "Cumulative Layout Shift score as reported by the browser",
    unit: "1",
  });

  onLCP((metric) => {
    lcpHistogram.record(metric.value, { "web.vital.rating": metric.rating });
  });
  onINP((metric) => {
    inpHistogram.record(metric.value, { "web.vital.rating": metric.rating });
  });
  onCLS((metric) => {
    clsHistogram.record(metric.value, { "web.vital.rating": metric.rating });
  });
}
