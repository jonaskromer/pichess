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
// "Good" buckets — LCP ≤ 2.5 s, FCP ≤ 1.8 s, CLS ≤ 0.1.
// k6/browser surfaces these as `browser_web_vital_*` trend metrics.
export const browserThresholds = {
  browser_web_vital_lcp: ['p(95)<2500'],
  browser_web_vital_fcp: ['p(95)<1800'],
  browser_web_vital_cls: ['p(95)<0.1'],
};
