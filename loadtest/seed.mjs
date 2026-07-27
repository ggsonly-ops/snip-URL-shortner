#!/usr/bin/env node
//
// Seeds the service with N links and writes loadtest/codes.json for redirect.js.
//
//   node loadtest/seed.mjs                       # 1000 links against http://localhost
//   BASE_URL=http://localhost:8080 COUNT=5000 node loadtest/seed.mjs
//
// This is a Node script rather than a k6 script on purpose: each k6 VU runs in its own
// isolated JS runtime, so codes collected across VUs cannot be gathered into one array
// and written out at the end. Seeding is setup, not measurement, so it does not need
// k6's machinery anyway.

import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const BASE_URL = (process.env.BASE_URL || 'http://localhost').replace(/\/$/, '');
const COUNT = Number(process.env.COUNT || 1000);
const CONCURRENCY = Number(process.env.CONCURRENCY || 16);
const API_KEY = process.env.API_KEY || 'snip_loadtest_seed_key';

const here = dirname(fileURLToPath(import.meta.url));
const OUT = join(here, 'codes.json');

async function createLink(i, attempt = 0) {
  // A distinct target per link, so dedupe does not collapse them into one row and the
  // cache ends up with a realistic number of distinct keys.
  const url = `https://example.com/article/${i}?ref=seed&n=${i}`;

  const res = await fetch(`${BASE_URL}/api/links`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-API-Key': API_KEY },
    body: JSON.stringify({ url }),
  });

  if (res.status === 429) {
    // The write bucket is small by design. Honour Retry-After rather than hammering.
    const wait = Number(res.headers.get('retry-after') || 1) * 1000;
    if (attempt > 30) throw new Error('rate limited repeatedly while seeding');
    await new Promise((r) => setTimeout(r, wait + 50));
    return createLink(i, attempt + 1);
  }
  if (!res.ok) {
    throw new Error(`create failed (${res.status}): ${await res.text()}`);
  }
  return (await res.json()).shortCode;
}

async function main() {
  console.log(`Seeding ${COUNT} links against ${BASE_URL} (concurrency ${CONCURRENCY})...`);
  const started = Date.now();
  const codes = new Array(COUNT);
  let next = 0;
  let done = 0;

  async function worker() {
    for (;;) {
      const i = next++;
      if (i >= COUNT) return;
      codes[i] = await createLink(i);
      if (++done % 200 === 0) process.stdout.write(`  ${done}/${COUNT}\r`);
    }
  }

  await Promise.all(Array.from({ length: CONCURRENCY }, worker));

  await writeFile(OUT, JSON.stringify(codes));
  const secs = ((Date.now() - started) / 1000).toFixed(1);
  console.log(`\nSeeded ${codes.length} links in ${secs}s -> ${OUT}`);
  console.log(`API key used: ${API_KEY}`);
}

main().catch((e) => {
  console.error(`\nSeeding failed: ${e.message}`);
  process.exit(1);
});
