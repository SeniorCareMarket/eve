// Native JS FORMAT-V1 packer for plain EDN (maps, vecs, lists, sets,
// keywords, symbols, strings, numbers, bools, nil). Byte-identical to
// eve.flat.chunk/pack-rep for that subset, including the root recipe.
// Loaded via js/eval so nbb (SCI) and a browser page both run it in V8.
// IR nodes: {t: 0 nil | 1 bool v | 2 kw ns n s | 3 sym ns n s | 4 str v
//            | 5 int v | 6 float v | 7 map xs:[[k,v],...]
//            | 8 vec xs | 9 set xs | 10 list xs}

(function (root) {
  "use strict";

  var MAGIC0 = 0xEE, MAGIC1 = 0xDB;
  var T_NIL = 0, T_BOOL = 1, T_KW = 2, T_SYM = 3, T_STR = 4, T_INT = 5, T_FLT = 6;
  var T_MAP = 7, T_VEC = 8, T_SET = 9, T_LIST = 10;
  var I32_MIN = -2147483648, I32_MAX = 2147483647;
  var te = (typeof TextEncoder !== "undefined") ? new TextEncoder() : null;

  function utf8(s) {
    if (te) return te.encode(s);
    var out = [];
    for (var i = 0; i < s.length; i++) {
      var c = s.charCodeAt(i);
      if (c < 0x80) out.push(c);
      else if (c < 0x800) out.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F));
      else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
        var lo = s.charCodeAt(++i);
        var cp = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
        out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
      } else out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));
    }
    return new Uint8Array(out);
  }

  function cmpU8(a, b) {
    var n = a.length < b.length ? a.length : b.length;
    for (var i = 0; i < n; i++) {
      if (a[i] !== b[i]) return a[i] - b[i];
    }
    return a.length - b.length;
  }

  function Writer() {
    this.b = new Uint8Array(1024);
    this.dv = new DataView(this.b.buffer);
    this.p = 0;
  }
  Writer.prototype.need = function (n) {
    if (this.p + n <= this.b.length) return;
    var nb = new Uint8Array(Math.max(this.b.length * 2, this.p + n));
    nb.set(this.b);
    this.b = nb;
    this.dv = new DataView(nb.buffer);
  };
  Writer.prototype.u8 = function (v) { this.need(1); this.b[this.p++] = v & 0xFF; };
  Writer.prototype.u16 = function (v) { this.need(2); this.dv.setUint16(this.p, v >>> 0, true); this.p += 2; };
  Writer.prototype.u32 = function (v) { this.need(4); this.dv.setUint32(this.p, v >>> 0, true); this.p += 4; };
  Writer.prototype.i32 = function (v) { this.need(4); this.dv.setInt32(this.p, v, true); this.p += 4; };
  Writer.prototype.i64 = function (v) {
    this.need(8);
    var bi = (typeof v === "bigint") ? v : BigInt(v);
    this.dv.setBigInt64(this.p, bi, true);
    this.p += 8;
  };
  Writer.prototype.f64 = function (v) { this.need(8); this.dv.setFloat64(this.p, v, true); this.p += 8; };
  Writer.prototype.bytes = function (u8, off, n) {
    if (off == null) { off = 0; n = u8.length; }
    this.need(n);
    this.b.set(u8.subarray(off, off + n), this.p);
    this.p += n;
  };
  Writer.prototype.varint = function (v) {
    v = v >>> 0;
    while (v >= 128) { this.u8((v & 127) | 128); v = v >>> 7; }
    this.u8(v);
  };
  Writer.prototype.off = function (v, width) {
    if (width === 4) this.u32(v); else this.u16(v);
  };
  Writer.prototype.align = function (n) {
    while (this.p % n) this.u8(0);
  };
  Writer.prototype.patchU32 = function (at, v) {
    this.dv.setUint32(at, v >>> 0, true);
  };
  Writer.prototype.patchOff = function (at, v, width) {
    if (width === 4) this.dv.setUint32(at, v >>> 0, true);
    else this.dv.setUint16(at, v >>> 0, true);
  };
  Writer.prototype.seek = function (p) { this.p = p; };
  Writer.prototype.slice = function (n) { return this.b.slice(0, n == null ? this.p : n); };

  // one scratch writer for scalar bodies (reset per encode)
  var _scratch = new Writer();
  _scratch.b = new Uint8Array(256);
  _scratch.dv = new DataView(_scratch.b.buffer);
  function scratchBytes(fill) {
    _scratch.p = 0;
    fill(_scratch);
    return _scratch.slice();
  }

  // scalar body (no 0xEE 0xDB magic)
  function encStr(s) {
    var u = utf8(s), n = u.length;
    return scratchBytes(function (w) {
      if (n <= 255) { w.u8(0x05); w.u8(n); w.bytes(u); }
      else { w.u8(0x06); w.u32(n); w.bytes(u); }
    });
  }
  function encKw(ns, name) {
    var nu = ns ? utf8(ns) : null, nuN = nu ? nu.length : 0;
    var na = utf8(name), naN = na.length;
    return scratchBytes(function (w) {
      if (ns) {
        if (nuN <= 255 && naN <= 255) {
          w.u8(0x09); w.u8(nuN); w.bytes(nu); w.u8(naN); w.bytes(na);
        } else {
          w.u8(0x0A); w.u32(nuN); w.bytes(nu); w.u32(naN); w.bytes(na);
        }
      } else if (naN <= 255) { w.u8(0x07); w.u8(naN); w.bytes(na); }
      else { w.u8(0x08); w.u32(naN); w.bytes(na); }
    });
  }
  function encSym(ns, name) {
    var nu = ns ? utf8(ns) : null, nuN = nu ? nu.length : 0;
    var na = utf8(name), naN = na.length;
    if (naN > 255 || nuN > 255) throw new Error("symbol segment longer than 255");
    return scratchBytes(function (w) {
      if (ns) { w.u8(0x0D); w.u8(nuN); w.bytes(nu); w.u8(naN); w.bytes(na); }
      else { w.u8(0x0C); w.u8(naN); w.bytes(na); }
    });
  }
  function encInt(v) {
    var n = (typeof v === "bigint") ? Number(v) : v;
    return scratchBytes(function (w) {
      if (typeof v !== "bigint" && n >= I32_MIN && n <= I32_MAX && (n | 0) === n) {
        w.u8(0x03); w.i32(n);
      } else {
        w.u8(0x0F); w.i64(typeof v === "bigint" ? v : BigInt(Math.trunc(n)));
      }
    });
  }
  function encFlt(v) {
    return scratchBytes(function (w) { w.u8(0x04); w.f64(v); });
  }
  function encBool(v) {
    return scratchBytes(function (w) { w.u8(v ? 0x02 : 0x01); });
  }

  function scalarBody(node) {
    if (node._body) return node._body;
    var b;
    switch (node.t) {
      case T_NIL: b = new Uint8Array([0x00]); break;
      case T_BOOL: b = encBool(node.v); break;
      case T_KW: b = encKw(node.ns, node.n); break;
      case T_SYM: b = encSym(node.ns, node.n); break;
      case T_STR: b = encStr(node.v); break;
      case T_INT: b = encInt(node.v); break;
      case T_FLT: b = encFlt(node.v); break;
      default: return null;
    }
    node._body = b;
    return b;
  }

  function keyBytes(node) {
    var b = scalarBody(node);
    if (b) return b;
    // collections: encode-key is the full flat element (with magic)
    return flatOf(node);
  }

  function ident(node) {
    if (node._id !== undefined) return node._id;
    var id = null;
    if (node.t === T_KW) id = "k:" + (node.s || ((node.ns ? node.ns + "/" : "") + node.n));
    else if (node.t === T_SYM) id = "y:" + (node.s || ((node.ns ? node.ns + "/" : "") + node.n));
    else if (node.t === T_STR) id = "s:" + node.v;
    node._id = id;
    return id;
  }
  function strOf(node) {
    if (node.t === T_KW) return node.s || (":" + (node.ns ? node.ns + "/" : "") + node.n);
    if (node.t === T_SYM) return node.s || ((node.ns ? node.ns + "/" : "") + node.n);
    if (node.t === T_STR) return node.v;
    return "";
  }

  function isShapedKey(n) { return n.t === T_KW || n.t === T_SYM || n.t === T_STR; }

  function scan(node, counts, shapes, keyQ) {
    var id = ident(node);
    if (id) {
      var c = counts[id] || { n: node, tot: 0, keys: 0 };
      c.tot++;
      if (keyQ) c.keys++;
      counts[id] = c;
    }
    if (node.t === T_MAP) {
      var xs = node.xs, i, shaped = true, ks = [];
      for (i = 0; i < xs.length; i++) {
        if (!isShapedKey(xs[i][0])) shaped = false;
        scan(xs[i][0], counts, shapes, true);
        scan(xs[i][1], counts, shapes, false);
        ks.push(xs[i][0]);
      }
      if (shaped) {
        ks.sort(function (a, b) { return cmpU8(keyBytes(a), keyBytes(b)); });
        var sig = ks.map(function (k) { return ident(k); }).join("\x00");
        if (shapes[sig] == null) {
          shapes[sig] = { t: null, ks: ks, i: shapes._n || 0 };
          shapes._n = (shapes._n || 0) + 1;
        }
        node._shapeSig = sig;
      }
    } else if (node.t === T_VEC || node.t === T_LIST || node.t === T_SET) {
      var ys = node.xs;
      for (var j = 0; j < ys.length; j++) scan(ys[j], counts, shapes, false);
    }
  }

  function member(c) {
    var n = c.n;
    return (n.t === T_KW || n.t === T_SYM) || c.tot >= 2 || c.keys > 0;
  }

  // ----- column plan (plain integers / bools / floats / strings / sym-codes) -----
  function isInt(n) { return n && n.t === T_INT; }
  function intVal(n) { return (typeof n.v === "bigint") ? n.v : n.v; }
  function inI32(v) {
    if (typeof v === "bigint") return v >= BigInt(I32_MIN) && v <= BigInt(I32_MAX);
    return v >= I32_MIN && v <= I32_MAX;
  }

  function columnPlan(vals, sym) {
    var present = [], i, n = vals.length;
    for (i = 0; i < n; i++) if (vals[i] && vals[i].t !== T_NIL) present.push(vals[i]);
    if (!present.length) return null;
    var allBool = true, allInt = true, allFlt = true, allStr = true, allSym = true;
    for (i = 0; i < present.length; i++) {
      var t = present[i].t;
      if (t !== T_BOOL) allBool = false;
      if (t !== T_INT) allInt = false;
      if (t !== T_FLT && t !== T_INT) allFlt = false; // integers are not "every? number? that's not integer" — cljs: (every? number? present) after integer branch
      if (t !== T_STR) allStr = false;
      if (!ident(present[i]) || sym[ident(present[i])] == null) allSym = false;
    }
    var base = null;
    if (allBool) base = { kind: 16 };
    else if (allInt) {
      var mn = intVal(present[0]), mx = mn;
      for (i = 1; i < present.length; i++) {
        var v = intVal(present[i]);
        if (typeof mn === "bigint" || typeof v === "bigint") {
          var mnb = typeof mn === "bigint" ? mn : BigInt(mn);
          var vb = typeof v === "bigint" ? v : BigInt(v);
          if (vb < mnb) mnb = vb;
          if (vb > (typeof mx === "bigint" ? mx : BigInt(mx))) mx = vb; else mx = (typeof mx === "bigint" ? mx : BigInt(mx));
          mn = mnb;
        } else {
          if (v < mn) mn = v;
          if (v > mx) mx = v;
        }
      }
      var range;
      if (typeof mn === "bigint" || typeof mx === "bigint") range = (typeof mx === "bigint" ? mx : BigInt(mx)) - (typeof mn === "bigint" ? mn : BigInt(mn));
      else range = mx - mn;
      function ile(a, b) {
        if (typeof a === "bigint" || typeof b === "bigint") return (typeof a === "bigint" ? a : BigInt(a)) <= (typeof b === "bigint" ? b : BigInt(b));
        return a <= b;
      }
      if (n >= 16 && ile(range, 255)) base = { kind: 22, base: mn, width: 1 };
      else if (n >= 16 && ile(range, 65535)) base = { kind: 22, base: mn, width: 2 };
      else if (ile(I32_MIN, mn)) {
        if (ile(mx, I32_MAX)) base = { kind: 6 };
        else if (n >= 16 && ile(range, 4294967295)) base = { kind: 22, base: mn, width: 4 };
        else base = { kind: 10 };
      } else base = { kind: 10 };
    } else if (present.every(function (x) { return x.t === T_FLT; })) {
      // cljs: (every? number? present) only reached if not every integer
      base = { kind: 9 };
    } else if (allSym) {
      var maxc = 0;
      for (i = 0; i < present.length; i++) {
        var c = sym[ident(present[i])];
        if (c > maxc) maxc = c;
      }
      base = { kind: maxc < 256 ? 17 : 18 };
    } else if (allStr) base = { kind: 19 };
    if (!base) return null;
    // kind 23 run-length: only when kind in {6,10,17,18} and n>=32
    return base;
  }

  function writeColumn(w, plan, vals, width, sym) {
    var n = vals.length, kind = plan.kind, nils = false, i;
    for (i = 0; i < n; i++) if (!vals[i] || vals[i].t === T_NIL) { nils = true; break; }
    w.u8(kind | (nils ? 0x80 : 0));
    if (nils) {
      for (i = 0; i < Math.ceil(n / 8); i++) {
        var acc = 0;
        for (var j = 0; j < 8; j++) {
          var idx = i * 8 + j;
          if (idx < n && vals[idx] && vals[idx].t !== T_NIL) acc |= (1 << j);
        }
        w.u8(acc);
      }
    }
    if (kind === 16) {
      for (i = 0; i < Math.ceil(n / 8); i++) {
        var bacc = 0;
        for (var j2 = 0; j2 < 8; j2++) {
          var i2 = i * 8 + j2;
          if (i2 < n && vals[i2] && vals[i2].t === T_BOOL && vals[i2].v) bacc |= (1 << j2);
        }
        w.u8(bacc);
      }
    } else if (kind === 6) {
      w.align(4);
      for (i = 0; i < n; i++) w.u32((vals[i] && vals[i].t === T_INT) ? (intVal(vals[i]) | 0) : 0);
    } else if (kind === 10) {
      w.align(8);
      for (i = 0; i < n; i++) w.i64((vals[i] && vals[i].t === T_INT) ? intVal(vals[i]) : 0);
    } else if (kind === 9) {
      w.align(8);
      for (i = 0; i < n; i++) w.f64((vals[i] && (vals[i].t === T_FLT || vals[i].t === T_INT)) ? Number(vals[i].v) : 0);
    } else if (kind === 17) {
      for (i = 0; i < n; i++) w.u8((vals[i] && vals[i].t !== T_NIL) ? sym[ident(vals[i])] : 0);
    } else if (kind === 18) {
      for (i = 0; i < n; i++) w.u16((vals[i] && vals[i].t !== T_NIL) ? sym[ident(vals[i])] : 0);
    } else if (kind === 19) {
      var bs = [], acc2 = 0;
      for (i = 0; i < n; i++) bs.push((vals[i] && vals[i].t === T_STR) ? utf8(vals[i].v) : new Uint8Array(0));
      w.align(4);
      for (i = 0; i < n; i++) { w.u32(acc2); acc2 += bs[i].length; }
      w.u32(acc2);
      for (i = 0; i < n; i++) w.bytes(bs[i]);
    } else if (kind === 22) {
      w.i64(plan.base); w.u8(plan.width); w.align(plan.width);
      for (i = 0; i < n; i++) {
        var x = ((vals[i] && vals[i].t === T_INT) ? intVal(vals[i]) : plan.base);
        var d = (typeof x === "bigint" || typeof plan.base === "bigint")
          ? Number((typeof x === "bigint" ? x : BigInt(x)) - (typeof plan.base === "bigint" ? plan.base : BigInt(plan.base)))
          : (x - plan.base);
        if (plan.width === 1) w.u8(d);
        else if (plan.width === 2) w.u16(d);
        else w.u32(d);
      }
    }
  }

  function writeNode(w, node, cx, width) {
    if (!node || node.t === T_NIL) {
      var s0 = w.p; w.u8(0); return s0;
    }
    if (node.t === T_MAP) {
      if (node._shapeSig != null && cx.shapes[node._shapeSig] != null) {
        var sh = cx.shapes[node._shapeSig];
        var field = {};
        for (var i = 0; i < node.xs.length; i++) field[ident(node.xs[i][0])] = node.xs[i][1];
        var offs = [];
        for (var k = 0; k < sh.ks.length; k++) offs.push(writeNode(w, field[ident(sh.ks[k])], cx, width));
        var s = w.p;
        w.u8(0x21); w.varint(sh.i);
        for (k = 0; k < offs.length; k++) w.off(offs[k], width);
        return s;
      }
      // kmap
      var es = node.xs.slice();
      es.sort(function (a, b) { return cmpU8(keyBytes(a[0]), keyBytes(b[0])); });
      var keys = es.map(function (e) { return e[0]; });
      var vals = es.map(function (e) { return e[1]; });
      var keysOff = writeKeysNode(w, keys, cx, width);
      var plan = columnPlan(vals, cx.sym);
      var valsOff;
      if (plan) {
        valsOff = w.p; w.u8(0x23); w.varint(vals.length); writeColumn(w, plan, vals, width, cx.sym);
      } else {
        var voffs = [];
        for (i = 0; i < vals.length; i++) voffs.push(writeNode(w, vals[i], cx, width));
        valsOff = w.p; w.u8(0x22); w.varint(vals.length);
        for (i = 0; i < voffs.length; i++) w.off(voffs[i], width);
      }
      var sm = w.p;
      w.u8(0x27); w.varint(es.length); w.off(keysOff, width); w.off(valsOff, width);
      return sm;
    }
    if (node.t === T_SET) {
      var elems = node.xs.slice();
      elems.sort(function (a, b) { return cmpU8(keyBytes(a), keyBytes(b)); });
      var ko = writeKeysNode(w, elems, cx, width);
      var ss = w.p;
      w.u8(0x24); w.varint(elems.length); w.off(ko, width);
      return ss;
    }
    if (node.t === T_VEC) {
      var planV = null;
      var allScalar = true;
      for (var vi = 0; vi < node.xs.length; vi++) {
        var e = node.xs[vi];
        if (e && e.t >= T_MAP) { allScalar = false; break; }
      }
      if (allScalar) planV = columnPlan(node.xs, cx.sym);
      if (planV) {
        var sv = w.p;
        w.u8(0x23); w.varint(node.xs.length); writeColumn(w, planV, node.xs, width, cx.sym);
        return sv;
      }
      var eoffs = [];
      for (vi = 0; vi < node.xs.length; vi++) eoffs.push(writeNode(w, node.xs[vi], cx, width));
      var sv2 = w.p;
      w.u8(0x22); w.varint(node.xs.length);
      for (vi = 0; vi < eoffs.length; vi++) w.off(eoffs[vi], width);
      return sv2;
    }
    if (node.t === T_LIST) {
      var loffs = [];
      for (var li = 0; li < node.xs.length; li++) loffs.push(writeNode(w, node.xs[li], cx, width));
      var sl = w.p;
      w.u8(0x25); w.varint(node.xs.length);
      for (li = 0; li < loffs.length; li++) w.off(loffs[li], width);
      return sl;
    }
    // scalar / symbol-table ref
    var sS = w.p;
    var id = ident(node);
    if (id && cx.sym[id] != null) {
      w.u8(0x20); w.varint(cx.sym[id]);
    } else {
      var body = scalarBody(node);
      w.bytes(body);
    }
    return sS;
  }

  function writeKeysNode(w, elems, cx, width) {
    var allScalar = true, i;
    for (i = 0; i < elems.length; i++) if (elems[i] && elems[i].t >= T_MAP) { allScalar = false; break; }
    var plan = allScalar ? columnPlan(elems, cx.sym) : null;
    if (plan) {
      var s = w.p;
      w.u8(0x23); w.varint(elems.length); writeColumn(w, plan, elems, width, cx.sym);
      return s;
    }
    var offs = [];
    for (i = 0; i < elems.length; i++) offs.push(writeNode(w, elems[i], cx, width));
    var s2 = w.p;
    w.u8(0x22); w.varint(elems.length);
    for (i = 0; i < offs.length; i++) w.off(offs[i], width);
    return s2;
  }

  function writeSymtab(w, syms, width) {
    var n = syms.length, start = w.p, i;
    for (i = 0; i <= n; i++) w.off(0, width);
    var entries = w.p;
    for (i = 0; i < n; i++) {
      var saved = w.p;
      w.seek(start + i * width);
      w.off(saved - entries, width);
      w.seek(saved);
      w.bytes(scalarBody(syms[i]));
    }
    saved = w.p;
    w.seek(start + n * width);
    w.off(saved - entries, width);
    w.seek(saved);
  }

  function writeShapetab(w, shapeList, sym, width) {
    var n = shapeList.length, start = w.p, i, j;
    for (i = 0; i <= n; i++) w.off(0, width);
    var entries = w.p;
    function patch(i) {
      var saved = w.p;
      w.seek(start + i * width);
      w.off(saved - entries, width);
      w.seek(saved);
    }
    for (i = 0; i < n; i++) {
      patch(i);
      var sh = shapeList[i];
      w.varint(sh.ks.length);
      w.varint(0); // t is nil -> 0
      for (j = 0; j < sh.ks.length; j++) w.varint(sym[ident(sh.ks[j])]);
    }
    patch(n);
  }

  // ----- digest preimage: eve.codec flat bytes (with magic) -----
  function flatOf(node) {
    var w = new Writer();
    emitFlat(w, node);
    return w.slice();
  }
  function emitFramed(w, node) {
    var start = w.p;
    w.u32(0);
    emitFlat(w, node);
    w.patchU32(start, w.p - start - 4);
  }
  function emitFlat(w, node) {
    if (!node || node.t === T_NIL) return; // nothing
    if (node.t === T_MAP) {
      var es = node.xs.slice();
      es.sort(function (a, b) { return cmpU8(keyBytes(a[0]), keyBytes(b[0])); });
      w.u8(MAGIC0); w.u8(MAGIC1); w.u8(0xED); w.i32(es.length);
      for (var i = 0; i < es.length; i++) { emitFramed(w, es[i][0]); emitFramed(w, es[i][1]); }
      return;
    }
    if (node.t === T_VEC || node.t === T_LIST) {
      w.u8(MAGIC0); w.u8(MAGIC1); w.u8(0xEF); w.i32(node.xs.length);
      for (var j = 0; j < node.xs.length; j++) emitFramed(w, node.xs[j]);
      return;
    }
    if (node.t === T_SET) {
      var parts = [];
      for (var k = 0; k < node.xs.length; k++) parts.push(flatOf(node.xs[k]));
      parts.sort(cmpU8);
      w.u8(MAGIC0); w.u8(MAGIC1); w.u8(0xEE); w.i32(parts.length);
      for (k = 0; k < parts.length; k++) { w.u32(parts[k].length); w.bytes(parts[k]); }
      return;
    }
    // scalar: magic + body
    var body = scalarBody(node);
    w.u8(MAGIC0); w.u8(MAGIC1); w.bytes(body);
  }

  function sha256(u8) {
    try {
      if (typeof require === "function") {
        var c = require("crypto");
        if (c && c.createHash) {
          var Buf = (typeof Buffer !== "undefined") ? Buffer : require("buffer").Buffer;
          return new Uint8Array(c.createHash("sha256").update(Buf.from(u8)).digest());
        }
      }
    } catch (e) {}
    return sha256Pure(u8);
  }

  function sha256Pure(msg) {
    var K = [
      0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
      0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
      0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
      0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
      0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
      0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
      0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
      0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
    ];
    function rotr(x, n) { return (x >>> n) | (x << (32 - n)); }
    var n = msg.length, z = (55 - n) & 63;
    var m = new Uint8Array(n + 1 + z + 8);
    m.set(msg);
    m[n] = 0x80;
    var bits = n * 8;
    var dv = new DataView(m.buffer);
    dv.setUint32(m.length - 4, bits >>> 0, false);
    dv.setUint32(m.length - 8, Math.floor(bits / 0x100000000) >>> 0, false);
    var H = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
    for (var off = 0; off < m.length; off += 64) {
      var w = new Array(64), i;
      for (i = 0; i < 16; i++) w[i] = dv.getUint32(off + i * 4, false);
      for (i = 16; i < 64; i++) {
        var s0 = rotr(w[i-15],7) ^ rotr(w[i-15],18) ^ (w[i-15]>>>3);
        var s1 = rotr(w[i-2],17) ^ rotr(w[i-2],19) ^ (w[i-2]>>>10);
        w[i] = (w[i-16] + s0 + w[i-7] + s1) >>> 0;
      }
      var a=H[0],b=H[1],c=H[2],d=H[3],e=H[4],f=H[5],g=H[6],h=H[7];
      for (i = 0; i < 64; i++) {
        var S1 = rotr(e,6) ^ rotr(e,11) ^ rotr(e,25);
        var ch = (e & f) ^ (~e & g);
        var t1 = (h + S1 + ch + K[i] + w[i]) >>> 0;
        var S0 = rotr(a,2) ^ rotr(a,13) ^ rotr(a,22);
        var maj = (a & b) ^ (a & c) ^ (b & c);
        var t2 = (S0 + maj) >>> 0;
        h=g; g=f; f=e; e=(d+t1)>>>0; d=c; c=b; b=a; a=(t1+t2)>>>0;
      }
      H[0]=(H[0]+a)>>>0; H[1]=(H[1]+b)>>>0; H[2]=(H[2]+c)>>>0; H[3]=(H[3]+d)>>>0;
      H[4]=(H[4]+e)>>>0; H[5]=(H[5]+f)>>>0; H[6]=(H[6]+g)>>>0; H[7]=(H[7]+h)>>>0;
    }
    var out = new Uint8Array(32);
    var odv = new DataView(out.buffer);
    for (var i2 = 0; i2 < 8; i2++) odv.setUint32(i2 * 4, H[i2], false);
    return out;
  }

  function packPlain(ir, hasheq) {
    if (!ir) return null;
    var counts = {}, shapes = { _n: 0 };
    scan(ir, counts, shapes, false);
    var members = [];
    for (var id in counts) {
      if (id === "_n") continue;
      if (member(counts[id])) members.push(counts[id]);
    }
    members.sort(function (a, b) {
      if (b.tot !== a.tot) return b.tot - a.tot;
      var sa = strOf(a.n), sb = strOf(b.n);
      if (sa < sb) return -1;
      if (sa > sb) return 1;
      return 0;
    });
    var syms = members.map(function (m) { return m.n; });
    var sym = {};
    for (var i = 0; i < syms.length; i++) sym[ident(syms[i])] = i;
    var shapeList = [];
    for (var sig in shapes) {
      if (sig === "_n") continue;
      shapeList[shapes[sig].i] = shapes[sig];
    }
    // first-met order is already shapes[sig].i
    var cx = { sym: sym, syms: syms, shapes: shapes, shapeList: shapeList };

    function build(width) {
      var w = new Writer();
      var h;
      for (h = 0; h < 72; h++) w.u8(0); // 32 header + 40 recipe
      var symtab = w.p;
      writeSymtab(w, syms, width);
      var shapetab = w.p;
      writeShapetab(w, shapeList, sym, width);
      var typetab = 0;
      var root = writeNode(w, ir, cx, width);
      var len = w.p;
      var padded = (len + 7) & ~7;
      if (width === 2 && padded > 65535) return null;
      while (w.p < padded) w.u8(0);
      w.seek(0);
      w.u8(0x45); w.u8(0x56); w.u8(0x53); w.u8(0x31);
      w.u8(1); w.u8(0);
      w.u16((width === 4 ? 1 : 0) | 2);
      w.u32(padded);
      w.u32(root);
      w.u16(syms.length);
      w.u16(shapeList.length);
      w.u32(symtab);
      w.u32(shapetab);
      w.u32(typetab);
      var dg = sha256(flatOf(ir));
      w.patchU32(32, hasheq | 0);
      for (h = 0; h < 32; h++) w.b[36 + h] = dg[h];
      return w.slice(padded);
    }

    var out = build(2);
    if (!out) out = build(4);
    return out;
  }

  root.packPlain = packPlain;
})(typeof globalThis !== "undefined" ? globalThis : this);
