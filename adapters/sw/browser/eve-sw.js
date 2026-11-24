// eve-sw.js — eve's one service worker for a site (served from the site root, so its scope covers the page and the
// workers it starts). Features add routes to it; the interface is /workspace/eve-perf/specs/eve-sw-and-boot-contract.md
// (the OPFS tier and eve.dom share this file).
//
//   self.eveSw.route(prefix, handler)  handler (event, url) -> Response | Promise<Response> | undefined (fall through)
//
// Registered with ?coi=1 (eve.dom.app), it makes the pages and workers it serves cross-origin isolated: navigations and
// same-origin responses get COOP same-origin, COEP credentialless (Chromium, Firefox; require-corp elsewhere) and
// CORP cross-origin. Opaque responses pass through unchanged.
//
// eve.dom's no-isolation transport (after cljs-thread's and tau.alpha's synchronous-XHR service-worker relay, as
// inspiration): a worker posts a frame to the page and blocks in a synchronous XHR
//   POST /eve-sw/px/wait?k=<worker>.<seq>   held here until the page's reply for k arrives (or answers at once)
// the page runs the frame and posts the reply
//   POST /eve-sw/px/reply?k=<worker>.<seq>  body = reply bytes; 204
// either may come first; a reply nobody waited for is dropped after 30 s. And a blocking sleep for a worker:
//   GET  /eve-sw/px/sleep?ms=<n>   (answers with X-Eve-Sw: 1, so a worker can tell its XHRs reach this worker:
//                                    Firefox does not route a synchronous XHR through a service worker)
'use strict';

const routes = [];
self.eveSw = {
  route(prefix, handler) {
    routes.push([prefix, handler]);
    routes.sort((a, b) => b[0].length - a[0].length);
  },
};

const coi = new URL(self.location.href).searchParams.get('coi') === '1';
const ua = (self.navigator && self.navigator.userAgent) || '';
const credentialless = /Chrome\/|Firefox\//.test(ua);

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

// ---- eve.dom: the synchronous relay

const waiting = new Map(); // key -> resolve(bytes)
const stored = new Map(); // key -> {body, at}
const KEEP_MS = 30000;
const bytesHeaders = { 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-store' };

function sweep() {
  const now = Date.now();
  for (const [k, v] of stored) if (now - v.at > KEEP_MS) stored.delete(k);
}

self.eveSw.route('/eve-sw/px/wait', (e, url) => {
  const k = url.searchParams.get('k');
  const s = stored.get(k);
  if (s) {
    stored.delete(k);
    return new Response(s.body, { status: 200, headers: bytesHeaders });
  }
  return new Promise((resolve) => waiting.set(k, resolve))
    .then((body) => new Response(body, { status: 200, headers: bytesHeaders }));
});

self.eveSw.route('/eve-sw/px/reply', (e, url) => {
  const k = url.searchParams.get('k');
  return e.request.arrayBuffer().then((body) => {
    const w = waiting.get(k);
    if (w) {
      waiting.delete(k);
      w(body);
    } else {
      sweep();
      stored.set(k, { body, at: Date.now() });
    }
    return new Response(null, { status: 204 });
  });
});

self.eveSw.route('/eve-sw/px/sleep', (e, url) => {
  const ms = Math.max(0, Math.min(60000, Number(url.searchParams.get('ms')) || 0));
  return new Promise((r) => setTimeout(r, ms))
    .then(() => new Response('', { status: 200, headers: { 'Cache-Control': 'no-store', 'X-Eve-Sw': '1' } }));
});

// ---- isolation headers

function isolate(response) {
  if (response.status === 0 || response.type === 'opaque' || response.type === 'opaqueredirect') return response;
  const h = new Headers(response.headers);
  h.set('Cross-Origin-Opener-Policy', 'same-origin');
  h.set('Cross-Origin-Embedder-Policy', credentialless ? 'credentialless' : 'require-corp');
  h.set('Cross-Origin-Resource-Policy', 'cross-origin');
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers: h });
}

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (url.origin === self.location.origin) {
    for (const [prefix, handler] of routes) {
      if (url.pathname.startsWith(prefix)) {
        const r = handler(e, url);
        if (r) {
          e.respondWith(r);
          return;
        }
      }
    }
  }
  if (coi && (e.request.mode === 'navigate' || url.origin === self.location.origin)) {
    if (e.request.cache === 'only-if-cached' && e.request.mode !== 'same-origin') return;
    e.respondWith(fetch(e.request).then(isolate));
  }
});
