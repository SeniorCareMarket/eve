// eve's OPFS io worker (the OPFS disk tier spec, W3). Inlined by eve.opfs (shadow.resource/inline) and started from
// a Blob by eve.opfs/mount! on the page, before any eve thread. It owns every FileSystemSyncAccessHandle of the page
// (a handle is exclusive and lives in one worker), and serves the page's workers through one request block in a
// SharedArrayBuffer (ctl): a caller takes LOCK, writes OP, its int args and its strings, bumps DOORBELL and
// Atomics.waits on DONE; this worker answers STATUS and its results and sets DONE. Between requests it blocks in
// Atomics.wait on DOORBELL; a request that needs OPFS's promise API (opening a file, a directory walk) awaits.
//
// A file's bytes live in a pool slot: a growable SharedArrayBuffer every worker received at its start (eve.opfs's
// pool), so a slot number names the same bytes in every worker. OPEN loads a file into a fresh slot (or answers the
// slot it is in), PERSIST writes a slot's byte runs, PERSIST_CHANGED every page whose bytes differ from what this
// worker last wrote (or read) there — it keeps a 64-bit hash per page of the file as written: a region's bytes are
// written through views no region op sees (a slab's), so only the bytes can tell — and both flush. The handle's
// read/write refuse a view over a growable buffer (measured in Chromium and Firefox, W0), so bytes go through a
// fixed bounce buffer, and a page's hash is the bounce copy's: what the file holds.
//
// Ownership (W7): one tab of the origin owns a root's files, the one whose io worker holds the Web Lock
// 'eve:<root>'. A tab that finds it held is a follower: its requests are refused (STATUS 2, EveNotOwner) and its
// lock request stays queued; when the owner's tab closes or dies the browser lets its lock (and its handles) go and
// the next follower in the queue takes over: it bumps the root's term (.eve-term, a u32), sets ROLE and TERM in the
// request block (waiters wake), tells its page, and announces {owner: tab, term} on the BroadcastChannel
// 'eve:<root>'. Its workers then open the files, and a domain's root read in fresh is recovered (eve.atom), as after
// a crash. Handles a dead owner held may outlive it a moment (exclusive mode): opens retry while the browser says
// NoModificationAllowedError.
//
// Followers (W8): a follower tab's worker calls the owner (FOLLOW_CALL: request bytes in the string area, the reply
// there after); this worker posts {eveCall} on the channel and answers when the owner's {eveReply} comes (or EveNoOwner
// after CALL_MS: no owner served it). The owner's io worker queues the calls and hands them one at a time to its
// tab's follower server (eve.opfs.follow, an eve thread) through the pool's svc block (and so, posted by the page, a
// call from the service worker's relay, W9: eve.opfs.relay): S_REQ goes up with a call in
// it, S_RESP is set to it with the reply. An owner's file write (a commit's) is announced {eveCommit}, at most once
// a turn: a follower counts them in COMMITS, and a cached value read before the count moved is stale.
'use strict';
const LOCK = 0, DOORBELL = 1, DONE = 2, OP = 3, STATUS = 4, R0 = 5, R1 = 6, R2 = 7, NARGS = 8, NSTR = 9,
      ROLE = 10, TERM = 11, SEEN_TERM = 12, COMMITS = 13, ARGS = 16;
const S_REQ = 0, S_RESP = 1, S_LEN = 2, S_BYTES = 16, CALL_MS = 10000;
const FOLLOWER = 0, OWNER = 1;
const OPEN = 1, PERSIST = 2, EXISTS = 3, SIZE = 4, CREATE_EXCL = 5, RENAME = 6, DELETE = 7, CLOSE_ALL = 8,
      MKDIRS = 9, PERSIST_CHANGED = 10, FOLLOW_CALL = 11, NOP = 12;
const PAGE = 4096, BOUNCE = 1 << 20;
let ctl, ctlBytes, argsCap, strBase, slots, root, nextSlot = 0, handleOpts, tab, channel, svc, svcBytes;
let callSeq = 0, announcing = false;
const calls = new Map();        // a follower's call id -> resolve(bytes)
const queue = [];               // the owner's calls not yet served: {from, id, bytes}
let serving = null;             // the owner's call in the svc block
const freeSlots = [];           // slots CLOSE_ALL let go, reused before fresh ones

// waitWord: Atomics.waitAsync's promise, or null when it settled at once (the word had moved). WebKit's dedicated
// workers can lose a waitAsync wake-up (WebKit PR 77821, bugs.webkit.org 325822, open as of 2026-11):
// WorkerDedicatedRunLoop::runInMode reads its pending timers before it registers for timer notifications, so a notify
// from another thread in between is seen by neither, and the promise (its timeout too) stays pending until some
// unrelated event wakes the worker. In WebKit, waitWord also ticks a timer on this worker's own run loop every
// WK_TICK_MS: it settles once the word has moved or the timeout has passed. A delivered notify settles at once, as on
// every other engine.
const WK_TICK_MS = 20;
const webkitWorker = (() => {
  const ua = (self.navigator && self.navigator.userAgent) || '';
  return /AppleWebKit\//.test(ua) && !/Chrome\/|Chromium\//.test(ua);
})();
function waitWord(ia, i, seen, ms) {
  const r = Atomics.waitAsync(ia, i, seen, ms);
  if (!r.async) return null;
  if (!webkitWorker) return r.value;
  return new Promise((ok) => {
    const t0 = Date.now();
    let t;
    const tick = () => {
      if (Atomics.load(ia, i) !== seen) ok('ok');
      else if (ms !== undefined && Date.now() - t0 >= ms) ok('timed-out');
      else t = setTimeout(tick, WK_TICK_MS);
    };
    r.value.then((v) => { clearTimeout(t); ok(v); });
    t = setTimeout(tick, WK_TICK_MS);
  });
}
const byPath = new Map();       // path -> {slot, handle}
const bySlot = [];              // slot -> {path, handle, hashes: Int32Array, 2 per page}
const bounce = new Uint8Array(BOUNCE);
const bounce32 = new Int32Array(bounce.buffer);
const decoder = new TextDecoder(), encoder = new TextEncoder();
let relayHost = null, ownerHost = null, maxFileBytes = 0;   // the relay mode's (W10)
// SharedArrayBuffer's constructor: the global's, or (a page not cross-origin isolated has none) a shared
// WebAssembly.Memory's buffer's, which every engine still makes — usable in this worker alone
const SAB = typeof SharedArrayBuffer === 'function' ? SharedArrayBuffer
  : new WebAssembly.Memory({initial: 0, maximum: 1, shared: true}).buffer.constructor;

// A page's 64-bit hash (two 32-bit lanes) over its bytes [off, end): i32 words, then a tail of bytes.
let H1 = 0, H2 = 0;
function hashPage(u8, i32, off, end) {
  let h1 = 0x811c9dc5 | 0, h2 = 0x9e3779b9 | 0;
  const wend = off + ((end - off) & ~3);
  for (let i = off >> 2, n = wend >> 2; i < n; i++) {
    const w = i32[i];
    h1 = Math.imul(h1 ^ w, 16777619);
    h2 = Math.imul((h2 + w) | 0, 0x85ebca77); h2 ^= h2 >>> 13;
  }
  for (let i = wend; i < end; i++) {
    h1 = Math.imul(h1 ^ u8[i], 16777619);
    h2 = Math.imul((h2 + u8[i]) | 0, 0x85ebca77); h2 ^= h2 >>> 13;
  }
  H1 = h1; H2 = h2;
}
function ensureHashes(e, len) {
  const need = 2 * Math.ceil(len / PAGE);
  if (!e.hashes || e.hashes.length < need) {
    const h = new Int32Array(Math.max(need, e.hashes ? 2 * e.hashes.length : 0));
    if (e.hashes) h.set(e.hashes);
    e.hashes = h;
  }
}
// Hash pages [from, to) of the slot's live bytes as the file's (bytes just read, or zeros just grown)
function hashLive(e, from, to) {
  ensureHashes(e, to);
  const u8 = new Uint8Array(slots[e.slot]), i32 = new Int32Array(slots[e.slot]);
  for (let off = from; off < to; off += PAGE) {
    hashPage(u8, i32, off, Math.min(to, off + PAGE));
    const p = off / PAGE; e.hashes[2 * p] = H1; e.hashes[2 * p + 1] = H2;
  }
  e.hashedLen = to;
}

function parts(path) {
  const ps = String(path).split('/').filter(p => p !== '' && p !== '.');
  if (ps.length === 0 || ps.some(p => p === '..')) throw new Error('eve.opfs: not a file path: ' + path);
  return ps;
}
async function dirOf(ps, create) {
  let d = root;
  for (let i = 0; i < ps.length - 1; i++) d = await d.getDirectoryHandle(ps[i], {create});
  return d;
}
async function fileHandle(path, create) {
  const ps = parts(path);
  return (await dirOf(ps, create)).getFileHandle(ps[ps.length - 1], {create});
}
function notFound(e) { return e && (e.name === 'NotFoundError' || e.name === 'TypeMismatchError'); }

function growTo(slot, len) {
  if (!slots[slot]) slots[slot] = new SAB(0, {maxByteLength: maxFileBytes});   // relay mode: made when first used
  const b = slots[slot];
  if (b.byteLength < len) b.grow(len);
}
function readInto(handle, slot, from, to) {
  const u8 = new Uint8Array(slots[slot]);
  for (let at = from; at < to; at += BOUNCE) {
    const n = Math.min(BOUNCE, to - at);
    const v = n === BOUNCE ? bounce : bounce.subarray(0, n);
    const got = handle.read(v, {at});
    u8.set(got === n ? v : v.subarray(0, got), at);
  }
}

// A sync handle, retried while a dead owner's handle on the file is still being let go (exclusive mode)
async function syncHandle(fh) {
  const until = Date.now() + 15000;
  for (let wait = 5; ; wait = Math.min(250, wait * 2)) {
    try { return await fh.createSyncAccessHandle(handleOpts); }
    catch (err) {
      if (!(err && err.name === 'NoModificationAllowedError') || Date.now() > until) throw err;
      await new Promise(r => setTimeout(r, wait));
    }
  }
}

async function open(path, size) {
  let e = byPath.get(path), fresh = 0;
  if (!e) {
    fresh = 1;
    if (freeSlots.length === 0 && nextSlot >= slots.length)
      throw new Error('eve.opfs: the pool has no free slot (' + slots.length + ' files)');
    const fh = await fileHandle(path, true);
    const handle = await syncHandle(fh);
    const slot = freeSlots.length > 0 ? freeSlots.pop() : nextSlot++;
    e = {slot, handle, path};
    byPath.set(path, e); bySlot[slot] = e;
    const len = handle.getSize();
    growTo(slot, Math.max(len, size));
    readInto(handle, slot, 0, len);
    e.hashedLen = len;
    hashLive(e, 0, len);
  }
  const len = e.handle.getSize();
  if (len < size) { e.handle.truncate(size); e.handle.flush(); }
  const flen = Math.max(len, size);
  growTo(e.slot, flen);
  // A reused slot's buffer may hold an earlier file's bytes past this file's end (a buffer never shrinks): the
  // file's bytes from its old end are zeros, as the file's are
  if (e.hashedLen < flen) new Uint8Array(slots[e.slot]).fill(0, e.hashedLen, flen);
  // Pages past the hashed length hold zeros, on disk (truncate extends with them) and live (a grown buffer is zero
  // filled, and no region reaches past the old length yet). A page the old length ended inside is one the disk holds
  // only part of what the hash covered: its hash is forced to differ, so the next sync writes it.
  if (e.hashedLen < flen) {
    const old = e.hashedLen, part = old % PAGE;
    hashLive(e, old - part, flen);
    if (part !== 0) { const p = (old - part) / PAGE; e.hashes[2 * p] ^= 1; e.hashes[2 * p + 1] ^= 1; }
  }
  ctl[R0] = e.slot; ctl[R1] = flen; ctl[R2] = fresh;
}

function openSlot(slot) {
  const e = bySlot[slot];
  if (!e || byPath.get(e.path) !== e) throw new Error('eve.opfs: slot ' + slot + ' is not open');
  return e;
}
// Write [off, off+len) of the slot (page-aligned off) through the bounce buffer, each page's hash the copy's
function writeRun(e, src, off, len) {
  while (len > 0) {
    const n = Math.min(BOUNCE, len);
    const v = n === BOUNCE ? bounce : bounce.subarray(0, n);
    v.set(src.subarray(off, off + n));
    for (let q = 0; q < n; q += PAGE) {
      hashPage(bounce, bounce32, q, Math.min(n, q + PAGE));
      const p = (off + q) / PAGE; e.hashes[2 * p] = H1; e.hashes[2 * p + 1] = H2;
    }
    e.handle.write(v, {at: off});
    off += n; len -= n;
  }
}
function persist(nargs) {
  const e = openSlot(ctl[ARGS]);
  const src = new Uint8Array(slots[e.slot]);
  for (let i = 1; i + 1 < nargs; i += 2) {
    const off = ctl[ARGS + i], len = ctl[ARGS + i + 1];
    if (off % PAGE !== 0) throw new Error('eve.opfs: a run must start on a page: ' + off);
    writeRun(e, src, off, len);
  }
  e.handle.flush();
  announce();
}
function persistChanged() {
  const e = openSlot(ctl[ARGS]);
  const len = e.hashedLen, u8 = new Uint8Array(slots[e.slot]), i32 = new Int32Array(slots[e.slot]);
  let start = -1, pages = 0;
  for (let off = 0; off < len; off += PAGE) {
    const end = Math.min(len, off + PAGE), p = off / PAGE;
    hashPage(u8, i32, off, end);
    const changed = H1 !== e.hashes[2 * p] || H2 !== e.hashes[2 * p + 1];
    if (changed) { if (start < 0) start = off; pages++; }
    if ((!changed || end - start >= BOUNCE) && start >= 0) {
      const runEnd = changed ? end : off;
      writeRun(e, u8, start, runEnd - start);
      start = -1;
    }
  }
  if (start >= 0) writeRun(e, u8, start, len - start);
  e.handle.flush();
  ctl[R0] = pages;
  if (pages > 0) announce();
}

async function size(path) {
  const e = byPath.get(path);
  if (e) return e.handle.getSize();
  try { return (await (await fileHandle(path, false)).getFile()).size; }
  catch (err) { if (notFound(err)) return -1; throw err; }
}

async function createExclusive(path) {
  if (byPath.has(path) || (await size(path)) >= 0) return 0;
  await fileHandle(path, true);
  return 1;
}

async function copyFile(from, to) {
  const src = await (await fileHandle(from, false)).getFile();
  const dst = await (await fileHandle(to, true)).createSyncAccessHandle();
  try {
    dst.truncate(0);
    const buf = new Uint8Array(await src.arrayBuffer());
    dst.write(buf, {at: 0}); dst.flush();
  } finally { dst.close(); }
}

async function remove(path) {
  if (byPath.has(path)) throw new Error('eve.opfs: ' + path + ' is open');
  const ps = parts(path);
  try { await (await dirOf(ps, false)).removeEntry(ps[ps.length - 1]); return 1; }
  catch (err) { if (notFound(err)) return 0; throw err; }
}

async function rename(from, to) {
  if (byPath.has(from) || byPath.has(to)) throw new Error('eve.opfs: rename of an open file: ' + from + ' -> ' + to);
  const fh = await fileHandle(from, false);
  if (typeof fh.move === 'function') {
    const tp = parts(to);
    await remove(to);
    await fh.move(await dirOf(tp, true), tp[tp.length - 1]);
  } else {
    await remove(to); await copyFile(from, to); await remove(from);
  }
}

// readwrite-unsafe (Chromium 121+): handles that do not lock the file, so a tab taking a domain over (W7) opens its
// files while a hung owner still holds its handles -- the Web Lock, not the handle, keeps one writer. Probed: two
// handles on one file in that mode both open. Elsewhere the mode is unknown (Firefox ignores it and the second open
// fails) and every handle is the exclusive default.
async function probeUnsafe() {
  const name = '.eve-mode-probe';
  let h1 = null, h2 = null, ok = false;
  try {
    const fh = await root.getFileHandle(name, {create: true});
    h1 = await fh.createSyncAccessHandle({mode: 'readwrite-unsafe'});
    h2 = await fh.createSyncAccessHandle({mode: 'readwrite-unsafe'});
    ok = true;
  } catch (_) { ok = false; }
  for (const h of [h1, h2]) { try { if (h) h.close(); } catch (_) {} }
  try { await root.removeEntry(name); } catch (_) {}
  return ok;
}

// Close every file and let its slot go (a slot is reused for the next file opened: a region over it from before is
// over that file's bytes then — what eve.opfs/close-all! warns of)
function closeAll() {
  for (const e of byPath.values()) { e.handle.flush(); e.handle.close(); freeSlots.push(e.slot); }
  byPath.clear();
}

// This tab owns the root now: the term goes up (in the root's .eve-term, read and written under the lock), the
// request block says so, and so do the page and the channel
async function takeOver() {
  const fh = await root.getFileHandle('.eve-term', {create: true});
  const h = await syncHandle(fh);
  const t = new Uint32Array(1);
  try {
    if (h.getSize() >= 4) h.read(t, {at: 0});
    t[0] += 1;
    h.write(t, {at: 0}); h.flush();
  } finally { h.close(); }
  const term = t[0];
  Atomics.store(ctl, TERM, term); Atomics.store(ctl, SEEN_TERM, term);
  Atomics.store(ctl, ROLE, OWNER); Atomics.notify(ctl, ROLE);
  channel.postMessage({eveOwner: tab, term});
  self.postMessage({eveOpfsRole: 'owner', term});
}

function strings() {
  const n = ctl[NSTR];
  if (n === 0) return [];
  return decoder.decode(ctlBytes.slice(strBase, strBase + n)).split('\0');
}

class EveNotOwner extends Error {}
EveNotOwner.prototype.name = 'EveNotOwner';
class EveNoOwner extends Error {}
EveNoOwner.prototype.name = 'EveNoOwner';

function announce() {
  Atomics.add(ctl, COMMITS, 1);
  if (announcing) return;
  announcing = true;
  setTimeout(() => { announcing = false; channel.postMessage({eveCommit: tab}); }, 0);
}

// A follower's call to the owner: the string area's bytes out, the reply's back in
async function followCall() {
  if (Atomics.load(ctl, ROLE) === OWNER) throw new EveNotOwner('this tab owns ' + root.name + ': it calls no owner');
  const n = ctl[NSTR];
  const bytes = ctlBytes.slice(strBase, strBase + n);
  const id = ++callSeq;
  const reply = await new Promise((resolve) => {
    calls.set(id, resolve);
    channel.postMessage({eveCall: {from: tab, id, bytes}});
    setTimeout(() => { if (calls.delete(id)) resolve(null); }, CALL_MS);
  });
  if (reply === null) throw new EveNoOwner('no owner of ' + root.name + ' answered within ' + CALL_MS + ' ms');
  if (reply.length > ctlBytes.length - strBase) throw new Error('eve.opfs: a reply exceeds the request block');
  ctlBytes.set(reply, strBase);
  ctl[NSTR] = reply.length; ctl[R0] = reply.length;
}

// A call's reply: on the channel to the tab that called, or (a call the page took from the service worker's relay,
// eve.opfs.relay) to the page
function replyTo(c, bytes) {
  if (c.relay !== undefined) self.postMessage({eveRelayReply: {id: c.relay, bytes}});
  else channel.postMessage({eveReply: {to: c.from, id: c.id, bytes}});
}

// The relay mode's owner (W10): its follower server is the domain host's eve.opfs.follow/serve-relay!, on the
// service worker's relay under ownerHost; the queued calls go there, one at a time
function pumpRelay() {
  if (serving || queue.length === 0) return;
  const c = serving = queue.shift();
  const k = tab + '.io.' + (++callSeq);
  const ask = () => fetch(location.origin + '/eve-sw/opfs/call?to=' + encodeURIComponent(ownerHost) + '&k=' + k + '&ms=25000',
                          {method: 'POST', body: c.bytes})
    .then((r) => r.status === 504 ? ask() : r.status === 200 ? r.arrayBuffer().then((b) => new Uint8Array(b)) : null);
  ask().catch(() => null).then((bytes) => { replyTo(c, bytes); serving = null; pump(); });
}

// The relay mode (W10: a page not cross-origin isolated): the domain host (one worker, eve.opfs's relay pool)
// keeps its own request block and slots; each request comes over the service worker's relay under relayHost as
// [prefix length, extra length] (u32s), the request block's prefix (header, args, strings) and extra bytes (a
// PERSIST's runs, in order), is served by serve() over this worker's copy of the block and its mirror slots, and
// is answered the same way: the block's prefix back, and an OPEN's file bytes as extra
function unframe(b) {
  const h = new DataView(b.buffer, b.byteOffset, 8), n = h.getUint32(0, true), m = h.getUint32(4, true);
  return [b.subarray(8, 8 + n), b.subarray(8 + n, 8 + n + m)];
}
function frame(prefix, extra) {
  const out = new Uint8Array(8 + prefix.length + extra.length), h = new DataView(out.buffer);
  h.setUint32(0, prefix.length, true); h.setUint32(4, extra.length, true);
  out.set(prefix, 8); out.set(extra, 8 + prefix.length);
  return out;
}
async function relayServe(req) {
  const [prefix, extra] = unframe(req);
  ctlBytes.set(prefix, 0);
  if (ctl[OP] === PERSIST && extra.length > 0 && Atomics.load(ctl, ROLE) === OWNER) {
    const e = openSlot(ctl[ARGS]), dst = new Uint8Array(slots[e.slot]);
    let at = 0;
    for (let i = 1; i + 1 < ctl[NARGS]; i += 2) {
      const off = ctl[ARGS + i], len = ctl[ARGS + i + 1];
      dst.set(extra.subarray(at, at + len), off); at += len;
    }
  }
  ctl[STATUS] = 0; ctl[R2] = 0;
  try { await serve(); } catch (err) { fail(err); }
  const out = ctlBytes.slice(0, strBase + ctl[NSTR]);
  const ext = ctl[STATUS] === 0 && ctl[OP] === OPEN ? new Uint8Array(slots[ctl[R0]]).slice(0, ctl[R1]) : new Uint8Array(0);
  return frame(out, ext);
}
async function relayLoop() {
  const q = location.origin + '/eve-sw/opfs/next?host=' + encodeURIComponent(relayHost) + '&term=1&ms=25000';
  for (;;) {
    let r;
    try { r = await fetch(q, {cache: 'no-store'}); } catch (err) { await new Promise((ok) => setTimeout(ok, 500)); continue; }
    if (r.status !== 200) continue;
    const k = r.headers.get('X-Eve-Key');
    const reply = await relayServe(new Uint8Array(await r.arrayBuffer()));
    await fetch(location.origin + '/eve-sw/opfs/reply?k=' + encodeURIComponent(k), {method: 'POST', body: reply}).catch(() => null);
  }
}

// The owner: the next queued call into the svc block, its reply posted back when the server sets S_RESP
function pump() {
  if (ownerHost) return pumpRelay();
  if (serving || queue.length === 0 || !svc) return;
  const c = queue.shift();
  if (c.bytes.length > svcBytes.length - 4 * S_BYTES) { replyTo(c, null); return pump(); }
  serving = c;
  svcBytes.set(c.bytes, 4 * S_BYTES);
  Atomics.store(svc, S_LEN, c.bytes.length);
  const req = Atomics.add(svc, S_REQ, 1) + 1;
  Atomics.notify(svc, S_REQ);
  awaitReply(req);
}
function awaitReply(req) {
  const done = () => {
    const n = Atomics.load(svc, S_LEN);
    const bytes = svcBytes.slice(4 * S_BYTES, 4 * S_BYTES + n);
    replyTo(serving, bytes);
    serving = null;
    pump();
  };
  const cur = Atomics.load(svc, S_RESP);
  if (cur === req) return done();
  if (typeof Atomics.waitAsync === 'function') {
    const w = waitWord(svc, S_RESP, cur);
    if (w) w.then(() => awaitReply(req)); else awaitReply(req);
  } else setTimeout(() => awaitReply(req), 1);
}

async function serve() {
  const op = ctl[OP], nargs = ctl[NARGS];
  if (op === FOLLOW_CALL) return followCall();
  if (op === NOP) return;   // the relay mode's: the block's words (role, term, commits) come back
  if (Atomics.load(ctl, ROLE) !== OWNER)
    throw new EveNotOwner('this tab follows the owner of ' + root.name + ' (term ' + Atomics.load(ctl, SEEN_TERM) + ')');
  const s = strings();
  switch (op) {
    case OPEN: return open(s[0], ctl[ARGS]);
    case PERSIST: return persist(nargs);
    case PERSIST_CHANGED: return persistChanged();
    case EXISTS: ctl[R0] = (await size(s[0])) >= 0 ? 1 : 0; return;
    case SIZE: ctl[R0] = await size(s[0]); return;
    case CREATE_EXCL: ctl[R0] = await createExclusive(s[0]); return;
    case RENAME: return rename(s[0], s[1]);
    case DELETE: ctl[R0] = await remove(s[0]); return;
    case CLOSE_ALL: return closeAll();
    case MKDIRS: await dirOf(parts(s[0] + '/x'), true); return;
    default: throw new Error('eve.opfs: unknown op ' + op);
  }
}

function fail(err) {
  const msg = encoder.encode(String((err && err.name ? err.name + ': ' : '') + (err && err.message || err)));
  const n = Math.min(msg.length, ctlBytes.length - strBase);
  ctlBytes.set(msg.subarray(0, n), strBase);
  ctl[NSTR] = n; ctl[STATUS] = err instanceof EveNotOwner ? 2 : err instanceof EveNoOwner ? 3 : 1;
}

// The loop gives the worker's event loop a turn (the Web Lock's grant, the channel's messages) while idle: with
// Atomics.waitAsync where there is one, else between short waits, and at least every 50 ms while busy.
async function loop() {
  let seen = Atomics.load(ctl, DOORBELL), turn = Date.now();
  const waitAsync = typeof Atomics.waitAsync === 'function';
  for (;;) {
    const db = Atomics.load(ctl, DOORBELL);
    if (db === seen) {
      if (waitAsync) {
        const w = waitWord(ctl, DOORBELL, seen, 10000);
        if (w) await w;
      } else if (Atomics.wait(ctl, DOORBELL, seen, 20) === 'timed-out') {
        await new Promise(r => setTimeout(r, 0));
      }
      turn = Date.now();
      continue;
    }
    seen = db;
    if (Date.now() - turn > 50) { await new Promise(r => setTimeout(r, 0)); turn = Date.now(); }
    ctl[STATUS] = 0; ctl[R2] = 0;
    try { await serve(); } catch (err) { fail(err); }
    Atomics.store(ctl, DONE, 1);
    Atomics.notify(ctl, DONE);
  }
}

self.onmessage = async (ev) => {
  const d = ev.data;
  if (!d || !d.eveOpfsBoot) return;
  // after the boot: calls the page took from the service worker's relay (W9), served as a channel's are
  self.onmessage = (m) => {
    const c = m.data && m.data.eveRelayCall;
    if (!c) return;
    if (Atomics.load(ctl, ROLE) === OWNER) { queue.push({relay: c.id, bytes: c.bytes}); pump(); }
    else self.postMessage({eveRelayReply: {id: c.id, bytes: null}});
  };
  try {
    if (d.relay) {   // the relay mode (W10): this worker's own block and mirror slots, made here
      relayHost = String(d.relay); ownerHost = String(d.ownerHost); maxFileBytes = d.maxFileBytes;
      d.ctl = new SAB(4 * (ARGS + d.argsWords) + d.stringBytes);
      d.slots = new Array(d.slotCount).fill(null);
    }
    ctl = new Int32Array(d.ctl); ctlBytes = new Uint8Array(d.ctl);
    argsCap = d.argsWords; strBase = 4 * (ARGS + argsCap);
    slots = d.slots;
    if (d.svc) { svc = new Int32Array(d.svc); svcBytes = new Uint8Array(d.svc); }
    root = await navigator.storage.getDirectory();
    for (const p of String(d.root).split('/').filter(Boolean)) root = await root.getDirectoryHandle(p, {create: true});
    tab = String(d.tab);
    const unsafe = await probeUnsafe();
    handleOpts = unsafe ? {mode: 'readwrite-unsafe'} : undefined;
    const lockName = 'eve:' + String(d.root);
    channel = new BroadcastChannel(lockName);
    channel.onmessage = (m) => {
      const v = m.data;
      if (!v) return;
      if (v.eveOwner && v.term > Atomics.load(ctl, SEEN_TERM)) {
        Atomics.store(ctl, SEEN_TERM, v.term);
        self.postMessage({eveOpfsOwner: v.eveOwner, term: v.term});
      } else if (v.eveCommit) {
        if (Atomics.load(ctl, ROLE) !== OWNER) { Atomics.add(ctl, COMMITS, 1); Atomics.notify(ctl, COMMITS); }
      } else if (v.eveCall) {
        if (Atomics.load(ctl, ROLE) === OWNER) { queue.push(v.eveCall); pump(); }
      } else if (v.eveReply && v.eveReply.to === tab) {
        const r = calls.get(v.eveReply.id);
        if (r) { calls.delete(v.eveReply.id); r(v.eveReply.bytes || new Uint8Array(0)); }
      }
    };
    const owner = await new Promise((resolve) => {
      navigator.locks.request(lockName, {ifAvailable: true}, (lock) => {
        resolve(lock !== null);
        return lock === null ? undefined : new Promise(() => {});   // held while this worker lives
      }).catch(() => resolve(false));
    });
    if (owner) await takeOver();
    else {
      // the term the owner took (read, not locked: the owner's handle on it is closed)
      try {
        const t = new Uint32Array(await (await (await root.getFileHandle('.eve-term')).getFile()).arrayBuffer());
        if (t.length > 0 && t[0] > Atomics.load(ctl, SEEN_TERM)) Atomics.store(ctl, SEEN_TERM, t[0]);
      } catch (err) { if (!notFound(err)) throw err; }
      navigator.locks.request(lockName, () => takeOver().then(() => new Promise(() => {})));
    }
    self.postMessage({eveOpfsReady: true, mode: unsafe ? 'readwrite-unsafe' : 'readwrite',
                      role: owner ? 'owner' : 'follower', term: Atomics.load(ctl, owner ? TERM : SEEN_TERM)});
  } catch (err) {
    self.postMessage({eveOpfsReady: false, error: String(err && err.stack || err)});
    return;
  }
  if (relayHost) relayLoop(); else loop();
};
