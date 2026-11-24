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
'use strict';
const LOCK = 0, DOORBELL = 1, DONE = 2, OP = 3, STATUS = 4, R0 = 5, R1 = 6, R2 = 7, NARGS = 8, NSTR = 9, ARGS = 16;
const OPEN = 1, PERSIST = 2, EXISTS = 3, SIZE = 4, CREATE_EXCL = 5, RENAME = 6, DELETE = 7, CLOSE_ALL = 8,
      MKDIRS = 9, PERSIST_CHANGED = 10;
const PAGE = 4096, BOUNCE = 1 << 20;
let ctl, ctlBytes, argsCap, strBase, slots, root, nextSlot = 0, handleOpts;
const freeSlots = [];           // slots CLOSE_ALL let go, reused before fresh ones
const byPath = new Map();       // path -> {slot, handle}
const bySlot = [];              // slot -> {path, handle, hashes: Int32Array, 2 per page}
const bounce = new Uint8Array(BOUNCE);
const bounce32 = new Int32Array(bounce.buffer);
const decoder = new TextDecoder(), encoder = new TextEncoder();

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

async function open(path, size) {
  let e = byPath.get(path), fresh = 0;
  if (!e) {
    fresh = 1;
    if (freeSlots.length === 0 && nextSlot >= slots.length)
      throw new Error('eve.opfs: the pool has no free slot (' + slots.length + ' files)');
    const fh = await fileHandle(path, true);
    const handle = await fh.createSyncAccessHandle(handleOpts);
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

function strings() {
  const n = ctl[NSTR];
  if (n === 0) return [];
  return decoder.decode(ctlBytes.slice(strBase, strBase + n)).split('\0');
}

async function serve() {
  const op = ctl[OP], nargs = ctl[NARGS];
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
  ctl[NSTR] = n; ctl[STATUS] = 1;
}

async function loop() {
  let seen = Atomics.load(ctl, DOORBELL);
  for (;;) {
    const db = Atomics.load(ctl, DOORBELL);
    if (db === seen) { Atomics.wait(ctl, DOORBELL, seen, 10000); continue; }
    seen = db;
    ctl[STATUS] = 0; ctl[R2] = 0;
    try { await serve(); } catch (err) { fail(err); }
    Atomics.store(ctl, DONE, 1);
    Atomics.notify(ctl, DONE);
  }
}

self.onmessage = async (ev) => {
  const d = ev.data;
  if (!d || !d.eveOpfsBoot) return;
  self.onmessage = null;
  try {
    ctl = new Int32Array(d.ctl); ctlBytes = new Uint8Array(d.ctl);
    argsCap = d.argsWords; strBase = 4 * (ARGS + argsCap);
    slots = d.slots;
    root = await navigator.storage.getDirectory();
    for (const p of String(d.root).split('/').filter(Boolean)) root = await root.getDirectoryHandle(p, {create: true});
    const unsafe = await probeUnsafe();
    handleOpts = unsafe ? {mode: 'readwrite-unsafe'} : undefined;
    self.postMessage({eveOpfsReady: true, mode: unsafe ? 'readwrite-unsafe' : 'readwrite'});
  } catch (err) {
    self.postMessage({eveOpfsReady: false, error: String(err && err.stack || err)});
    return;
  }
  loop();
};
