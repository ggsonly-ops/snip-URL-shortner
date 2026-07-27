// The write-path load test, kept separate from redirect.js because it is a completely
// different shape of work: URL validation (including a DNS lookup for the SSRF guard),
// a dedupe query, an insert, and a cache write. Expect roughly an order of magnitude
// less throughput than the read path, and that contrast is the point.
//
//   k6 run -e BASE_URL=http://localhost loadtest/create.js

import http from 'k6/http';
import { check } from 'k6';
import { Rate, Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost';
const API_KEY = __ENV.API_KEY || 'snip_loadtest_write_key';

const failures = new Rate('errors');
const rateLimited = new Counter('rate_limited');
const dedupeHits = new Counter('dedupe_hits');
const createLatency = new Trend('create_latency', true);

export const options = {
  stages: [
    { duration: '20s', target: 20 },
    { duration: '1m', target: 50 },
    { duration: '20s', target: 0 },
  ],
  thresholds: {
    // Much looser than the read path, deliberately. A create does real work.
    http_req_duration: ['p(95)<500'],
    // 429s are a correct response here, not a failure, so they are excluded from
    // the error rate below and counted separately.
    errors: ['rate<0.05'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  // Unique per iteration and per VU: two VUs generating the same URL would exercise the
  // dedupe path instead of the insert path and quietly flatter the numbers.
  const unique = `${exec.vu.idInTest}-${exec.vu.iterationInInstance}-${Date.now()}`;
  const body = JSON.stringify({
    url: `https://example.com/load/${unique}`,
  });

  const res = http.post(`${BASE_URL}/api/links`, body, {
    headers: { 'Content-Type': 'application/json', 'X-API-Key': `${API_KEY}-${exec.vu.idInTest}` },
    tags: { name: 'create' },
  });

  if (res.status === 429) {
    rateLimited.add(1);
    failures.add(false);
    return;
  }

  const ok = check(res, {
    'status is 201': (r) => r.status === 201,
    'returned a short code': (r) => {
      try {
        return !!r.json('shortCode');
      } catch {
        return false;
      }
    },
  });

  if (ok && res.json('deduplicated') === true) dedupeHits.add(1);

  failures.add(!ok);
  createLatency.add(res.timings.duration);
}

export function handleSummary(data) {
  const m = data.metrics;
  const q = (name, stat) => (m[name] && m[name].values[stat] !== undefined
    ? m[name].values[stat].toFixed(2)
    : 'n/a');

  return {
    stdout: [
      '',
      '─────────────────────────────────────────────',
      ' Snip create (write path) load test',
      '─────────────────────────────────────────────',
      ` throughput   ${q('http_reqs', 'rate')} req/s`,
      ` p50          ${q('http_req_duration', 'med')} ms`,
      ` p95          ${q('http_req_duration', 'p(95)')} ms`,
      ` p99          ${q('http_req_duration', 'p(99)')} ms`,
      ` error rate   ${m.errors ? (m.errors.values.rate * 100).toFixed(2) : '0.00'} %`,
      ` 429s         ${m.rate_limited ? m.rate_limited.values.count : 0}  (expected: the write bucket is small)`,
      '─────────────────────────────────────────────',
      '',
    ].join('\n'),
    [`/results/create-${__ENV.RUN_LABEL || 'run'}.json`]: JSON.stringify(data, null, 2),
  };
}
