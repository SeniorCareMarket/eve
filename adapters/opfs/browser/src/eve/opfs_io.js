// eve's OPFS io worker (the OPFS disk tier spec, W3). Inlined by eve.opfs (shadow.resource/inline) and started from
// a Blob by eve.opfs/mount! on the page, before any eve thread. It owns every FileSystemSyncAccessHandle of the page
// (a handle is exclusive and lives in one worker), and serves the page's workers through one request block in a
// SharedArrayBuffer (ctl): a caller takes LOCK, writes OP, its int args and its strings, bumps DOORBELL and
// Atomics.waits on DONE; this worker answers STATUS and its results and sets DONE. Between requests it blocks in
// Atomics.wait on DOORBELL; a request that needs OPFS's promise API (opening a file, a directory walk) awaits.
//
// A file's bytes live in a pool slot: a growable SharedArrayBuffer every worker received at its start (eve.opfs's
// pool), so a slot number names the same bytes in every worker. OPEN loads a file into a fresh slot (or answers the
// slot it is in), PERSIST writes a slot's byte runs and flushes. The handle's read/write refuse a view over a
// growable buffer (measured in Chromium and Firefox, W0), so bytes go through a fixed bounce buffer.
'use strict';
const LOCK = 0, DOORBELL = 1, DONE = 2, OP = 3, STATUS = 4, R0 = 5, R1 = 6, NARGS = 8, NSTR = 9, ARGS = 16;
const OPEN = 1, PERSIST = 2, EXISTS = 3, SIZE = 4, CREATE_EXCL = 5, RENAME = 6, DELETE = 7, CLOSE_ALL = 8,
      MKDIRS = 9;
const PAGE = 4096, BOUNCE = 1 << 20;
let ctl, ctlBytes, argsCap, strBase, slots, dirty, root, nextSlot = 0;
const byPath = new Map();       // path -> {slot, handle}
const bySlot = [];              // slot -> {path, handle}
const bounce = new Uint8Array(BOUNCE);
const decoder = new TextDecoder(), encoder = new TextEncoder();

function dirtyBytes(len) { const pages = Math.ceil(len / PAGE); return 4 * Math.max(1, Math.ceil(pages / 32)); }

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
  const b = slots[slot], d = dirty[slot];
  if (b.byteLength < len) b.grow(len);
  const db = dirtyBytes(len);
  if (d.byteLength < db) d.grow(db);
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
  let e = byPath.get(path);
  if (!e) {
    if (nextSlot >= slots.length) throw new Error('eve.opfs: the pool has no free slot (' + slots.length + ' files)');
    const fh = await fileHandle(path, true);
    const handle = await fh.createSyncAccessHandle();
    const slot = nextSlot++;
    e = {slot, handle, path};
    byPath.set(path, e); bySlot[slot] = e;
    const len = handle.getSize();
    growTo(slot, Math.max(len, size));
    readInto(handle, slot, 0, len);
  }
  const len = e.handle.getSize();
  if (len < size) { e.handle.truncate(size); e.handle.flush(); }
  growTo(e.slot, Math.max(len, size));
  ctl[R0] = e.slot; ctl[R1] = Math.max(len, size);
}

function persist(nargs) {
  const slot = ctl[ARGS], e = bySlot[slot];
  if (!e) throw new Error('eve.opfs: slot ' + slot + ' is not open');
  const src = new Uint8Array(slots[slot]);
  for (let i = 1; i + 1 < nargs; i += 2) {
    let off = ctl[ARGS + i], len = ctl[ARGS + i + 1];
    while (len > 0) {
      const n = Math.min(BOUNCE, len);
      const v = n === BOUNCE ? bounce : bounce.subarray(0, n);
      v.set(src.subarray(off, off + n));
      e.handle.write(v, {at: off});
      off += n; len -= n;
    }
  }
  e.handle.flush();
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

function closeAll() {
  for (const e of byPath.values()) { e.handle.flush(); e.handle.close(); }
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
    ctl[STATUS] = 0;
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
    slots = d.slots; dirty = d.dirty;
    root = await navigator.storage.getDirectory();
    for (const p of String(d.root).split('/').filter(Boolean)) root = await root.getDirectoryHandle(p, {create: true});
    self.postMessage({eveOpfsReady: true, unsafe: false});
  } catch (err) {
    self.postMessage({eveOpfsReady: false, error: String(err && err.stack || err)});
    return;
  }
  loop();
};
