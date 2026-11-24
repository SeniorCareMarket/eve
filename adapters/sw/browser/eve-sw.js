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
//
// eve.opfs's follower relay (the OPFS disk tier spec, W9; eve.opfs.relay): a worker that cannot share memory with the
// owner of a root's files calls it in a synchronous XHR this worker holds, and the owner long-polls for the calls:
//   POST /eve-sw/opfs/call?to=<host>&k=<key>&ms=<n>  body = request; held until the host's reply for k (200, body =
//                                                    reply), else 504 after n ms (the caller sends again, same k)
//   GET  /eve-sw/opfs/next?host=<host>&term=<t>&ms=<n>  the next call for host (200, X-Eve-Key: k), else 204 after n ms
//   POST /eve-sw/opfs/reply?k=<key>                  body = reply; 204
//   GET  /eve-sw/opfs/ping                           X-Eve-Sw: 1 (this worker relays the caller's XHRs)
// A call sent again under its key is never queued twice: its reply, kept 30 s, answers it. A call handed to a host
// of an older term (an owner that died holding it) is handed again to the next term's host's poll, and a newer
// term's poll ends an older one's. This worker's
// state goes when the browser stops it: a caller then sends again, and its request id (eve.atom/*request-id*) keeps
// the owner from applying it twice.
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

// ---- eve.opfs: the follower relay

const relayQueue = new Map(); // host -> [{k, body}] calls no poll has taken
const relayPolls = new Map(); // host -> [{term, give(call)}] polls waiting for a call
const relayOut = new Map(); // k -> {host, term, body} handed to a host, not answered
const relayCalls = new Map(); // k -> [resolve(reply)] callers waiting
const relayDone = new Map(); // k -> {body, at} answers no caller waits for now
const RELAY_MAX_MS = 60000;

function relayMs(url, d) {
  return Math.max(0, Math.min(RELAY_MAX_MS, Number(url.searchParams.get('ms')) || d));
}

function relaySweep() {
  const now = Date.now();
  for (const [k, v] of relayDone) if (now - v.at > KEEP_MS) relayDone.delete(k);
}

function relayOffer(host, call) {
  const polls = relayPolls.get(host);
  if (polls && polls.length > 0) {
    let at = 0;
    for (let i = 1; i < polls.length; i++) if (polls[i].term > polls[at].term) at = i;
    const p = polls.splice(at, 1)[0];
    relayOut.set(call.k, { host, term: p.term, body: call.body });
    p.give(call);
  } else {
    if (!relayQueue.has(host)) relayQueue.set(host, []);
    relayQueue.get(host).push(call);
  }
}

function relayQueued(host, k) {
  const q = relayQueue.get(host);
  return !!q && q.some((c) => c.k === k);
}

self.eveSw.route('/eve-sw/opfs/ping', () =>
  new Response('', { status: 200, headers: { 'Cache-Control': 'no-store', 'X-Eve-Sw': '1' } }));

self.eveSw.route('/eve-sw/opfs/call', (e, url) => {
  const host = url.searchParams.get('to');
  const k = url.searchParams.get('k');
  const ms = relayMs(url, 25000);
  return e.request.arrayBuffer().then((body) => {
    const done = relayDone.get(k);
    if (done) {
      relayDone.delete(k);
      return new Response(done.body, { status: 200, headers: bytesHeaders });
    }
    if (!relayOut.has(k) && !relayQueued(host, k)) relayOffer(host, { k, body });
    return new Promise((resolve) => {
      const give = (reply) => resolve(new Response(reply, { status: 200, headers: bytesHeaders }));
      if (!relayCalls.has(k)) relayCalls.set(k, []);
      relayCalls.get(k).push(give);
      setTimeout(() => {
        const ws = relayCalls.get(k);
        const i = ws ? ws.indexOf(give) : -1;
        if (i >= 0) {
          ws.splice(i, 1);
          if (ws.length === 0) relayCalls.delete(k);
          resolve(new Response(null, { status: 504, headers: { 'Cache-Control': 'no-store' } }));
        }
      }, ms);
    });
  });
});

self.eveSw.route('/eve-sw/opfs/next', (e, url) => {
  const host = url.searchParams.get('host');
  const term = Number(url.searchParams.get('term')) || 0;
  const ms = relayMs(url, 25000);
  // a host of an older term is gone (the owner died): its waiting polls end, and the calls it took and never
  // answered go to this one
  const waiting = relayPolls.get(host);
  if (waiting) {
    for (const p of waiting.filter((w) => w.term < term)) {
      waiting.splice(waiting.indexOf(p), 1);
      p.end();
    }
  }
  for (const [k, o] of relayOut) {
    if (o.host === host && o.term < term) {
      relayOut.delete(k);
      if (!relayQueue.has(host)) relayQueue.set(host, []);
      relayQueue.get(host).unshift({ k, body: o.body });
    }
  }
  const answer = (c) => new Response(c.body, { status: 200, headers: Object.assign({ 'X-Eve-Key': c.k }, bytesHeaders) });
  const q = relayQueue.get(host);
  if (q && q.length > 0) {
    const c = q.shift();
    relayOut.set(c.k, { host, term, body: c.body });
    return answer(c);
  }
  return new Promise((resolve) => {
    const p = {
      term,
      give: (c) => resolve(answer(c)),
      end: () => resolve(new Response(null, { status: 204, headers: { 'Cache-Control': 'no-store' } })),
    };
    if (!relayPolls.has(host)) relayPolls.set(host, []);
    relayPolls.get(host).push(p);
    setTimeout(() => {
      const ps = relayPolls.get(host);
      const i = ps ? ps.indexOf(p) : -1;
      if (i >= 0) {
        ps.splice(i, 1);
        p.end();
      }
    }, ms);
  });
});

self.eveSw.route('/eve-sw/opfs/reply', (e, url) => {
  const k = url.searchParams.get('k');
  return e.request.arrayBuffer().then((body) => {
    relayOut.delete(k);
    const ws = relayCalls.get(k);
    if (ws && ws.length > 0) {
      relayCalls.delete(k);
      for (const give of ws) give(body.slice(0));
    } else {
      relaySweep();
      relayDone.set(k, { body, at: Date.now() });
    }
    return new Response(null, { status: 204 });
  });
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
