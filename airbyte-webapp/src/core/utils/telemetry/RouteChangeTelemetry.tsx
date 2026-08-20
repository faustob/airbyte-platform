import React, { useEffect, useMemo, useRef } from "react";
import { useLocation } from "react-router-dom";

import { getWebappMeter } from "core/utils/telemetry/otel";

/**
 * Collapses ids/uuids/numbers in a pathname into a stable placeholder so the recorded attribute
 * is a low-cardinality route TEMPLATE, never the raw path.
 */
function toRouteTemplate(pathname: string): string {
  return pathname
    .split("/")
    .map((segment) => (/^[0-9a-fA-F-]{8,}$/.test(segment) || /^\d+$/.test(segment) ? "{id}" : segment))
    .join("/");
}

/**
 * Times SPA (soft) navigations - from route-change start to the next painted frame - for the
 * airbyte-webapp-web-spa-nav-p75 SLI. Core Web Vitals only cover the initial page load, not in-app
 * router transitions. Renders nothing; must be mounted inside the router context (see App.tsx).
 */
export const RouteChangeTelemetry: React.FC = () => {
  const location = useLocation();
  const previousPathRef = useRef<string | null>(null);
  const navHistogram = useMemo(
    () =>
      getWebappMeter().createHistogram("browser.route_change.duration", {
        description: "Duration of SPA route transitions from navigation start to the next painted frame",
        unit: "ms",
      }),
    []
  );

  useEffect(() => {
    const previousPath = previousPathRef.current;
    previousPathRef.current = location.pathname;

    if (previousPath === null || previousPath === location.pathname) {
      return;
    }

    const start = performance.now();
    const destination = toRouteTemplate(location.pathname);
    // Wait two animation frames so the destination route has had a chance to paint before we
    // measure the transition duration.
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        navHistogram.record(performance.now() - start, { "http.route": destination });
      });
    });
  }, [location.pathname, navHistogram]);

  return null;
};
