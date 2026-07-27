// The read-path load test. This is where the README's numbers come from.
//
//   node loadtest/seed.mjs
//   k6 run -e BASE_URL=http://localhost loadtest/redirect.js
//   # or, without installing k6:
//   docker compose --profile loadtest run --rm k6 run /scripts/redirect.js

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

const errorRate = new Trend('redirect_errors_pct');
const failures = new Rate('errors');
const redirectLatency = new Trend('redirect_latency', true);
const notFound = new Counter('not_found');
const rateLimited = new Counter('rate_limited');

const BASE_URL = __ENV.BASE_URL || 'http://localhost';

// Stages: ramp, hold, spike, sustain, ramp down. The spike is the interesting part -
// the screenshot worth putting in a README is latency staying flat while VUs go from
// 200 to 1000.
export const options = {
  stages: [
    { duration: '30s', target: 200 },
    { duration: '1m', target: 200 },
    { duration: '30s', target: 1000 },
    { duration: '2m', target: 1000 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    // Thresholds are assertions, not decoration: a run that breaches them exits
    // non-zero, which is what makes this runnable in CI.
    http_req_duration: ['p(95)<100', 'p(99)<250'],
    errors: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  discardResponseBodies: true,
};

const codes = JSON.parse(open('./codes.json'));

// Zipf-ish selection: 80% of traffic to the top 20% of links.
//
// This matters more than it looks. Testing with uniformly random codes gives an
// artificially LOW cache hit ratio, because almost every request is a different key and
// the cache never gets to do its job - you would measure a pessimistic system and then
// report it as the real one. Real link traffic is heavily skewed: a few links go viral,
// most get a handful of clicks. Modelling that is what makes the hit-ratio number
// honest, and being able to say why you modelled it is worth more than the number.
function pickCode() {
  const hot = Math.max(1, Math.floor(codes.length * 0.2));
  const idx = Math.random() < 0.8
    ? Math.floor(Math.random() * hot)
    : Math.floor(Math.random() * codes.length);
  return codes[idx];
}

export default function () {
  const code = pickCode();

  // redirects: 0 - we are measuring our own service, not example.com.
  const res = http.get(`${BASE_URL}/${code}`, {
    redirects: 0,
    tags: { name: 'redirect' },
  });

  const ok = check(res, {
    'status is 302': (r) => r.status === 302,
    'has Location header': (r) => !!(r.headers['Location'] || r.headers['location']),
  });

  if (res.status === 404) notFound.add(1);
  if (res.status === 429) rateLimited.add(1);

  failures.add(!ok);
  errorRate.add(ok ? 0 : 100);
  redirectLatency.add(res.timings.duration);
}

export function handleSummary(data) {
  const m = data.metrics;
  const q = (name, stat) => (m[name] && m[name].values[stat] !== undefined
    ? m[name].values[stat].toFixed(2)
    : 'n/a');

  const lines = [
    '',
    '─────────────────────────────────────────────',
    ' Snip redirect load test',
    '─────────────────────────────────────────────',
    ` throughput    ${q('http_reqs', 'rate')} req/s`,
    ` p50           ${q('http_req_duration', 'med')} ms`,
    ` p95           ${q('http_req_duration', 'p(95)')} ms`,
    ` p99           ${q('http_req_duration', 'p(99)')} ms`,
    ` max           ${q('http_req_duration', 'max')} ms`,
    ` error rate    ${m.errors ? (m.errors.values.rate * 100).toFixed(2) : '0.00'} %`,
    ` 404s          ${m.not_found ? m.not_found.values.count : 0}`,
    ` 429s          ${m.rate_limited ? m.rate_limited.values.count : 0}`,
    '─────────────────────────────────────────────',
    ' Cache hit ratio is not measured here - read it from',
    ' GET /api/status or the Grafana "Cache hit ratio" panel,',
    ' since it is a server-side property.',
    '',
  ];

  return {
    stdout: lines.join('\n'),
    [`/results/redirect-${__ENV.RUN_LABEL || 'run'}.json`]: JSON.stringify(data, null, 2),
  };
}
