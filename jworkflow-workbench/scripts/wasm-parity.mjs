import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {chromium} from '@playwright/test';
import assert from 'node:assert/strict';
const jvm = spawnSync(process.env.PROBE_JAVA || 'java', ['-cp', 'target/probes', 'RuleProbe'], {encoding: 'utf8'});
if (jvm.status !== 0) throw new Error(`JVM fixture failed: ${jvm.stderr}`);
const expected = jvm.stdout.trim().split(/\r?\n/);
const assets = new Map([
  ['/', {type: 'text/html', body: '<!doctype html><title>Bounded Wasm rule probe</title><script src="/ruleprobe.js"></script>'}],
  ['/ruleprobe.js', {type: 'text/javascript', body: await readFile('target/probes/ruleprobe.js')}],
  ['/ruleprobe.js.wasm', {type: 'application/wasm', body: await readFile('target/probes/ruleprobe.js.wasm')}]
]);
const server = createServer((request, response) => {
  const asset = assets.get(request.url);
  response.writeHead(asset ? 200 : 404, {'Content-Type': asset?.type || 'text/plain'}); response.end(asset?.body || 'Missing');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
try {
  for (const channel of ['chrome', 'msedge']) {
    const browser = await chromium.launch({channel, headless: true});
    try {
      const page = await browser.newPage(); const actual = []; const errors = [];
      page.on('console', message => {if (/^-?\d+:\d+$/.test(message.text())) actual.push(message.text());});
      page.on('pageerror', error => errors.push(error.message));
      await page.goto(`http://127.0.0.1:${server.address().port}`);
      const deadline = Date.now() + 15000;
      while (actual.length < expected.length && errors.length === 0 && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 50));
      assert.deepEqual(errors, []); assert.deepEqual(actual, expected);
      console.log(`PASS Wasm/JVM parity: ${channel} ${browser.version()}, ${expected.length} boundary fixtures`);
    } finally {await browser.close();}
  }
} finally {server.close();}
