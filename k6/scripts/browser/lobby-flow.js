// Browser surface: load the SPA, walk the lobby-creation flow, capture
// Core Web Vitals along the way. This is the signal the Gatling layer
// cannot produce — Gatling sees HTTP latency, not rendered-page latency.
//
// Flow:
//   1. Open `/` — measure LCP/FCP on the landing page.
//   2. Navigate to `#new` — the new-game menu (hash route from
//      web-ui/Main.scala:217).
//   3. Navigate to `#join` — the join-by-invite screen (Main.scala:218).
//
// The SPA is a Laminar/Scala.js app served by gateway:8090 (see
// gateway/.../HtmlPage.scala and WebController.scala:342). Hash routes
// avoid relying on button selectors that aren't tagged with stable
// test ids — when those land, swap the goto() calls for click().

import { browser } from 'k6/browser';
import { check } from 'k6';
import { cfg } from '/k6lib/config.js';
import { browserThresholds } from '/k6lib/thresholds.js';

export const options = {
  scenarios: {
    lobby_flow: {
      executor: 'shared-iterations',
      vus: cfg.vus,
      iterations: cfg.vus * 4,
      maxDuration: cfg.duration,
      options: { browser: { type: 'chromium' } },
    },
  },
  thresholds: browserThresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export default async function () {
  const page = await browser.newPage();

  try {
    // Landing — primary Web Vitals capture point. The k6/browser
    // Locator API does not match Playwright 1:1 (no .count() etc.),
    // so we sanity-check rendering with page.content() — `#app` is
    // injected by HtmlPage.scala and present iff the SPA mounted.
    await page.goto(cfg.gatewayUrl + '/', { waitUntil: 'networkidle' });
    const landing = await page.content();
    check(landing, { 'landing renders #app': (h) => h.includes('id="app"') });

    await page.goto(cfg.gatewayUrl + '/#new', { waitUntil: 'networkidle' });
    const newHtml = await page.content();
    check(newHtml, { 'new-game screen mounts': (h) => h.length > 0 });

    await page.goto(cfg.gatewayUrl + '/#join', { waitUntil: 'networkidle' });
    const joinHtml = await page.content();
    check(joinHtml, { 'join screen mounts': (h) => h.length > 0 });
  } finally {
    await page.close();
  }
}

export function handleSummary(data) {
  // k6 writes /out/browser/summary.json — picked up by the perf-bake
  // pipeline alongside Gatling's stats.json.
  return {
    '/out/browser/summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

// Inline a minimal text summary so we don't depend on jslib.k6.io at
// container runtime (the k6 image has no outbound network in CI by
// default). For richer summaries, swap to k6-summary from jslib.
function textSummary(data) {
  const m = data.metrics;
  const fmt = (v) => (v === undefined ? 'n/a' : v.toFixed(1));
  const lcp = m.browser_web_vital_lcp?.values;
  const fcp = m.browser_web_vital_fcp?.values;
  const cls = m.browser_web_vital_cls?.values;
  return [
    '',
    '── k6/browser — Web Vitals ───────────────────────────────',
    `  LCP  p95: ${fmt(lcp?.['p(95)'])} ms   (dev-rig target ≤ 5500)`,
    `  FCP  p95: ${fmt(fcp?.['p(95)'])} ms   (dev-rig target ≤ 5500)`,
    `  CLS  p95: ${fmt(cls?.['p(95)'])}      (target ≤ 0.1)`,
    `  iterations: ${m.iterations?.values?.count ?? 0}`,
    '──────────────────────────────────────────────────────────',
    '',
  ].join('\n');
}
