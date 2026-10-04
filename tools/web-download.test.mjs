import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { webcrypto } from 'node:crypto';

const source = readFileSync(new URL('../app/src/main/assets/web-download.js', import.meta.url), 'utf8');
function harness(blob = new Blob([]), rejectChunk = false) {
  const messages = [];
  let originalClicks = 0, handler;
  class Anchor {
    href = ''; download = '';
    hasAttribute(name) { return name === 'download'; }
    click() { originalClicks++; }
  }
  const bridge = { postMessage(json) {
    const message = JSON.parse(json); messages.push(message);
    queueMicrotask(() => bridge.onmessage({ data: JSON.stringify({ id: message.id,
      ...(rejectChunk && message.type === 'chunk' ? { error: 'cancelled' } : {}) }) }));
  } };
  const window = { __dshDownload: bridge };
  const context = { window, HTMLAnchorElement: Anchor, document: { addEventListener(_, listener) { handler = listener; } },
    location: { href: 'http://127.0.0.1:8080/', origin: 'http://127.0.0.1:8080' },
    URL, crypto: webcrypto, fetch: async () => ({ blob: async () => blob }), Uint8Array, btoa, setTimeout, clearTimeout };
  runInNewContext(source, context);
  const click = (href, name = '文件.bin') => { const a = new Anchor(); a.href = href; a.download = name; a.click(); return a; };
  const finish = async () => { for (let i = 0; i < 100; i++) {
    if (messages.some(m => m.type === 'end' || m.type === 'error' || m.type === 'http')) {
      await new Promise(resolve => setTimeout(resolve, 5)); return;
    }
    await new Promise(resolve => setTimeout(resolve, 5));
  } throw Error('Bridge did not complete'); };
  return { messages, context, click, finish, handler: () => handler, originalClicks: () => originalClicks };
}
test('programmatic HTTP anchor is intercepted and external links retain browser behavior', async () => {
  const h = harness(); h.click('http://127.0.0.1:8080/file'); await h.finish();
  assert.equal(h.messages[0].type, 'http'); assert.equal(h.messages[0].filename, '文件.bin');
  h.click('https://example.com/file'); assert.equal(h.originalClicks(), 1);
});
test('Blob transfers use acknowledged bounded chunks and preserve bytes', async () => {
  const bytes = Uint8Array.from({ length: 524289 }, (_, i) => i % 251);
  const h = harness(new Blob([bytes], { type: 'application/octet-stream' }));
  h.click('blob:http://127.0.0.1:8080/id'); await h.finish();
  assert.equal(h.messages[0].size, bytes.length);
  const chunks = h.messages.filter(m => m.type === 'chunk');
  chunks.forEach((m, i) => { assert.equal(m.sequence, i); assert.ok(Buffer.from(m.data, 'base64').length <= 65536); });
  assert.deepEqual(Buffer.concat(chunks.map(m => Buffer.from(m.data, 'base64'))), Buffer.from(bytes));
  assert.equal(h.messages.at(-1).type, 'end');
});
test('capture click intercepts data URLs and hooks remain idempotent', async () => {
  const h = harness(); const first = h.context.HTMLAnchorElement.prototype.click;
  runInNewContext(source, h.context); assert.equal(h.context.HTMLAnchorElement.prototype.click, first);
  let prevented = false, stopped = false;
  h.handler()({ target: { closest: () => ({ href: 'data:text/plain,hello', download: 'hello.txt' }) },
    preventDefault() { prevented = true; }, stopImmediatePropagation() { stopped = true; } });
  await h.finish(); assert.ok(prevented && stopped); assert.equal(h.messages.at(-1).type, 'end');
});
test('empty Blob completes without chunks', async () => {
  const h = harness(); h.click('blob:http://127.0.0.1:8080/id'); await h.finish();
  assert.deepEqual(h.messages.map(m => m.type), ['begin', 'end']);
});
test('duplicate clicks are rejected and cancellation stops subsequent chunks', async () => {
  const h = harness(new Blob([new Uint8Array(200000)]), true);
  h.click('blob:http://127.0.0.1:8080/id'); h.click('blob:http://127.0.0.1:8080/other'); await h.finish();
  assert.ok(h.messages.some(m => m.type === 'busy'));
  assert.equal(h.messages.filter(m => m.type === 'chunk').length, 1);
  assert.equal(h.messages.at(-1).type, 'error');
});
test('older WebViews without randomUUID can still export', async () => {
  const h = harness();
  h.context.crypto = { getRandomValues: value => webcrypto.getRandomValues(value) };
  h.click('blob:http://127.0.0.1:8080/id'); await h.finish();
  assert.equal(h.messages.at(-1).type, 'end');
});
