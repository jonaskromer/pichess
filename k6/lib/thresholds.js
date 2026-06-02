// Shared SLA thresholds — the k6 analogue of Gatling's
// `setUp(...).assertions(global.responseTime.percentile3.lt(...))`.
//
// A breached threshold turns the run RED in k6's summary and exits with
// non-zero status, mirroring how Gatling fails CI on a violated SLA.
//
// Values intentionally match the Gatling assertions where applicable
// (see gatling/src/test/scala/chess/gatling/*Simulation.scala) so the
// two tools agree on what "fast enough" means.

export const httpThresholds = {
  http_req_failed:   ['rate<0.01'],   // <1 % error rate
  http_req_duration: ['p(95)<500', 'p(99)<1500'],
};

// Browser-specific thresholds. Web Vitals values follow Google's
// "Good" buckets in production, but the perf rig is a dev-mode build
// served from `make stack-*` with an unwarmed JVM and a containerised
// Chromium under Docker Desktop on macOS — first-load LCP runs ~3.5 s
// even on hardware that ships ~1.5 s under prod conditions. Thresholds
// here are sized for that environment so the smoke test gates real
// regressions instead of failing every cold-start.
//
// Production targets (Google "Good"):  LCP ≤ 2500, FCP ≤ 1800, CLS ≤ 0.1
// Dev-rig allowances (used here):       LCP ≤ 5500, FCP ≤ 5500, CLS ≤ 0.1
// k6/browser surfaces these as `browser_web_vital_*` trend metrics.
//
// LCP and FCP are sized close together because the SPA shell renders
// at FCP and the actual content (the chess board / lobby UI) renders
// in the same microtask — there's no meaningful gap between them on
// this app, unlike a typical content-heavy site.
export const browserThresholds = {
  browser_web_vital_lcp: ['p(95)<5500'],
  browser_web_vital_fcp: ['p(95)<5500'],
  browser_web_vital_cls: ['p(95)<0.1'],
};
