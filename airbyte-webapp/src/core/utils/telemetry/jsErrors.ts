import { getWebappMeter } from "./otel";

let started = false;
let hasJsError = false;
let pageLoadRecorded = false;

/**
 * Tracks the JS error rate SLI (airbyte-webapp-web-js-error-rate). A "session" here is one page
 * load (this in-memory module holds the flag for its lifetime): every uncaught error / unhandled
 * promise rejection increments web.js_errors and flags this page load, and web.page_loads is
 * recorded exactly once per page load - at the first visibility-hidden transition - carrying
 * has_js_error, so error-rate = web.js_errors-affected loads / web.page_loads is an honest
 * denominator (not attempts, not raw error volume).
 */
export function initJsErrorTracking(): void {
  if (started) {
    return;
  }
  started = true;

  const meter = getWebappMeter();
  const errorCounter = meter.createCounter("web.js_errors", {
    description: "Count of uncaught JS errors and unhandled promise rejections",
  });
  const pageLoadCounter = meter.createCounter("web.page_loads", {
    description: "Count of page loads, tagged with whether a JS error occurred during the load",
  });

  const onError = (event: ErrorEvent | PromiseRejectionEvent) => {
    hasJsError = true;
    // Low-cardinality error class (e.g. "TypeError"), never the message - per the general
    // error.type semantic-convention guidance.
    const reason = "error" in event ? event.error : event.reason;
    const errorType = reason instanceof Error ? reason.name : "UnknownError";
    errorCounter.add(1, { "error.type": errorType });
  };

  window.addEventListener("error", onError);
  window.addEventListener("unhandledrejection", onError);

  const recordPageLoad = () => {
    if (pageLoadRecorded) {
      return;
    }
    if (document.visibilityState !== "hidden") {
      return;
    }
    pageLoadRecorded = true;
    pageLoadCounter.add(1, { has_js_error: String(hasJsError) });
  };

  document.addEventListener("visibilitychange", recordPageLoad);
  window.addEventListener("pagehide", recordPageLoad);
}
