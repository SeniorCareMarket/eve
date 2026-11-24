"""Native FORMAT-V1 packer for basilisp plain EDN (maps, vecs, lists, sets,
keywords, symbols, strings, numbers, bools, nil). Byte-identical to
eve.flat.chunk/pack-rep for that subset, including the root recipe — the
same contract as eve.flat.pack-plain on ClojureScript.

Called from eve.flat.chunk/pack before to-rep, so a 1,000-entry map does
not walk every entry through basilisp (measured ~200 ms to-rep + ~900 ms
scan/build-chunk even after the portable writer itself was native).
"""
from __future__ import annotations

import array
import hashlib
import struct

T_NIL, T_BOOL, T_KW, T_SYM, T_STR, T_INT, T_FLT = range(7)
T_MAP, T_VEC, T_SET, T_LIST = 7, 8, 9, 10
I32_MIN, I32_MAX = -2147483648, 2147483647
MAGIC0, MAGIC1 = 0xEE, 0xDB


class Writer:
    __slots__ = ("b", "p")

    def __init__(self, cap=1024):
        self.b = bytearray(cap)
        self.p = 0

    def need(self, n):
        p, cap = self.p, len(self.b)
        if p + n <= cap:
            return
        nb = bytearray(max(cap * 2, p + n))
        nb[:cap] = self.b
        self.b = nb

    def u8(self, v):
        self.need(1)
        self.b[self.p] = int(v) & 0xFF
        self.p += 1

    def u16(self, v):
        self.need(2)
        struct.pack_into("<H", self.b, self.p, int(v) & 0xFFFF)
        self.p += 2

    def u32(self, v):
        self.need(4)
        struct.pack_into("<I", self.b, self.p, int(v) & 0xFFFFFFFF)
        self.p += 4

    def i32(self, v):
        self.need(4)
        struct.pack_into("<i", self.b, self.p, int(v))
        self.p += 4

    def i64(self, v):
        self.need(8)
        struct.pack_into("<Q", self.b, self.p, int(v) & 0xFFFFFFFFFFFFFFFF)
        self.p += 8

    def f64(self, v):
        self.need(8)
        struct.pack_into("<d", self.b, self.p, float(v))
        self.p += 8

    def bytes(self, u, off=0, n=None):
        if n is None:
            n = len(u) - off
        self.need(n)
        self.b[self.p:self.p + n] = u[off:off + n]
        self.p += n

    def varint(self, v):
        v = int(v)
        if v < 0:
            v &= 0xFFFFFFFFFFFFFFFF
        while v >= 128:
            self.u8((v & 127) | 128)
            v >>= 7
        self.u8(v)

    def off(self, v, width):
        if width == 4:
            self.u32(v)
        else:
            self.u16(v)

    def align(self, n):
        while self.p % n:
            self.u8(0)

    def patch_u32(self, at, v):
        struct.pack_into("<I", self.b, int(at), int(v) & 0xFFFFFFFF)

    def seek(self, p):
        self.p = int(p)

    def slice(self, n=None):
        if n is None:
            n = self.p
        return bytes(self.b[:n])


_scratch = Writer(256)


def _utf8(s):
    return s.encode("utf-8")


def _cmp_u8(a, b):
    n = min(len(a), len(b))
    for i in range(n):
        if a[i] != b[i]:
            return a[i] - b[i]
    return len(a) - len(b)


def _scratch_bytes(fill):
    _scratch.p = 0
    fill(_scratch)
    return _scratch.slice()


def enc_str(s):
    u = _utf8(s)
    n = len(u)

    def fill(w):
        if n <= 255:
            w.u8(0x05); w.u8(n); w.bytes(u)
        else:
            w.u8(0x06); w.u32(n); w.bytes(u)

    return _scratch_bytes(fill)


def enc_kw(ns, name):
    nu = _utf8(ns) if ns else None
    nu_n = len(nu) if nu is not None else 0
    na = _utf8(name)
    na_n = len(na)

    def fill(w):
        if ns:
            if nu_n <= 255 and na_n <= 255:
                w.u8(0x09); w.u8(nu_n); w.bytes(nu); w.u8(na_n); w.bytes(na)
            else:
                w.u8(0x0A); w.u32(nu_n); w.bytes(nu); w.u32(na_n); w.bytes(na)
        elif na_n <= 255:
            w.u8(0x07); w.u8(na_n); w.bytes(na)
        else:
            w.u8(0x08); w.u32(na_n); w.bytes(na)

    return _scratch_bytes(fill)


def enc_sym(ns, name):
    nu = _utf8(ns) if ns else None
    nu_n = len(nu) if nu is not None else 0
    na = _utf8(name)
    na_n = len(na)
    if na_n > 255 or nu_n > 255:
        raise ValueError("symbol segment longer than 255")

    def fill(w):
        if ns:
            w.u8(0x0D); w.u8(nu_n); w.bytes(nu); w.u8(na_n); w.bytes(na)
        else:
            w.u8(0x0C); w.u8(na_n); w.bytes(na)

    return _scratch_bytes(fill)


def enc_int(v):
    n = int(v)

    def fill(w):
        if I32_MIN <= n <= I32_MAX:
            w.u8(0x03); w.i32(n)
        else:
            w.u8(0x0F); w.i64(n)

    return _scratch_bytes(fill)


def enc_flt(v):
    def fill(w):
        w.u8(0x04); w.f64(v)

    return _scratch_bytes(fill)


def enc_bool(v):
    def fill(w):
        w.u8(0x02 if v else 0x01)

    return _scratch_bytes(fill)


def scalar_body(node):
    b = node.get("_body")
    if b is not None:
        return b
    t = node["t"]
    if t == T_NIL:
        b = bytes([0x00])
    elif t == T_BOOL:
        b = enc_bool(node["v"])
    elif t == T_KW:
        b = enc_kw(node.get("ns"), node["n"])
    elif t == T_SYM:
        b = enc_sym(node.get("ns"), node["n"])
    elif t == T_STR:
        b = enc_str(node["v"])
    elif t == T_INT:
        b = enc_int(node["v"])
    elif t == T_FLT:
        b = enc_flt(node["v"])
    else:
        return None
    node["_body"] = b
    return b


def ident(node):
    if "_id" in node:
        return node["_id"]
    t = node["t"]
    i = None
    if t == T_KW:
        i = "k:" + (node.get("s") or ((str(node["ns"]) + "/") if node.get("ns") else "") + node["n"])
    elif t == T_SYM:
        i = "y:" + (node.get("s") or ((str(node["ns"]) + "/") if node.get("ns") else "") + node["n"])
    elif t == T_STR:
        i = "s:" + node["v"]
    node["_id"] = i
    return i


def str_of(node):
    t = node["t"]
    if t == T_KW:
        return node.get("s") or (":" + ((str(node["ns"]) + "/") if node.get("ns") else "") + node["n"])
    if t == T_SYM:
        return node.get("s") or (((str(node["ns"]) + "/") if node.get("ns") else "") + node["n"])
    if t == T_STR:
        return node["v"]
    return ""


def is_shaped_key(n):
    return n["t"] in (T_KW, T_SYM, T_STR)


def key_bytes(node):
    b = scalar_body(node)
    if b is not None:
        return b
    return flat_of(node)


def scan(node, counts, shapes, key_q):
    i = ident(node)
    if i:
        c = counts.get(i) or {"n": node, "tot": 0, "keys": 0}
        c["tot"] += 1
        if key_q:
            c["keys"] += 1
        counts[i] = c
    t = node["t"]
    if t == T_MAP:
        xs = node["xs"]
        shaped = True
        ks = []
        for k, v in xs:
            if not is_shaped_key(k):
                shaped = False
            scan(k, counts, shapes, True)
            scan(v, counts, shapes, False)
            ks.append(k)
        if shaped:
            # list(bytes) orders as _cmp_u8: common prefix, then shorter first
            ks = sorted(ks, key=lambda k: list(key_bytes(k)))
            sig = "\x00".join(ident(k) for k in ks)
            if sig not in shapes:
                shapes[sig] = {"t": None, "ks": ks, "i": shapes.get("_n", 0)}
                shapes["_n"] = shapes.get("_n", 0) + 1
            node["_shapeSig"] = sig
    elif t in (T_VEC, T_LIST, T_SET):
        for y in node["xs"]:
            scan(y, counts, shapes, False)


def member(c):
    n = c["n"]
    return n["t"] in (T_KW, T_SYM) or c["tot"] >= 2 or c["keys"] > 0


def int_val(n):
    return int(n["v"])


def column_plan(vals, sym):
    present = [x for x in vals if x and x["t"] != T_NIL]
    if not present:
        return None
    all_bool = all(x["t"] == T_BOOL for x in present)
    all_int = all(x["t"] == T_INT for x in present)
    all_flt = all(x["t"] == T_FLT for x in present)
    all_str = all(x["t"] == T_STR for x in present)
    all_sym = True
    for x in present:
        i = ident(x)
        if not i or i not in sym:
            all_sym = False
            break
    n = len(vals)
    if all_bool:
        return {"kind": 16}
    if all_int:
        mn = int_val(present[0])
        mx = mn
        for x in present[1:]:
            v = int_val(x)
            if v < mn:
                mn = v
            if v > mx:
                mx = v
        rng = mx - mn
        if n >= 16 and rng <= 255:
            return {"kind": 22, "base": mn, "width": 1}
        if n >= 16 and rng <= 65535:
            return {"kind": 22, "base": mn, "width": 2}
        if I32_MIN <= mn:
            if mx <= I32_MAX:
                return {"kind": 6}
            if n >= 16 and rng <= 4294967295:
                return {"kind": 22, "base": mn, "width": 4}
            return {"kind": 10}
        return {"kind": 10}
    if all_flt:
        return {"kind": 9}
    if all_sym:
        maxc = 0
        for x in present:
            c = sym[ident(x)]
            if c > maxc:
                maxc = c
        return {"kind": 17 if maxc < 256 else 18}
    if all_str:
        return {"kind": 19}
    return None


def write_column(w, plan, vals, width, sym):
    n = len(vals)
    kind = plan["kind"]
    nils = any((v is None or v["t"] == T_NIL) for v in vals)
    w.u8(kind | (0x80 if nils else 0))
    if nils:
        for i in range((n + 7) // 8):
            acc = 0
            for j in range(8):
                idx = i * 8 + j
                if idx < n and vals[idx] and vals[idx]["t"] != T_NIL:
                    acc |= (1 << j)
            w.u8(acc)
    if kind == 16:
        for i in range((n + 7) // 8):
            acc = 0
            for j in range(8):
                i2 = i * 8 + j
                if i2 < n and vals[i2] and vals[i2]["t"] == T_BOOL and vals[i2]["v"]:
                    acc |= (1 << j)
            w.u8(acc)
    elif kind == 6:
        w.align(4)
        for i in range(n):
            w.u32((int_val(vals[i]) if vals[i] and vals[i]["t"] == T_INT else 0) & 0xFFFFFFFF)
    elif kind == 10:
        w.align(8)
        for i in range(n):
            w.i64(int_val(vals[i]) if vals[i] and vals[i]["t"] == T_INT else 0)
    elif kind == 9:
        w.align(8)
        for i in range(n):
            v = vals[i]
            w.f64(float(v["v"]) if v and v["t"] in (T_FLT, T_INT) else 0.0)
    elif kind == 17:
        for i in range(n):
            w.u8(sym[ident(vals[i])] if vals[i] and vals[i]["t"] != T_NIL else 0)
    elif kind == 18:
        for i in range(n):
            w.u16(sym[ident(vals[i])] if vals[i] and vals[i]["t"] != T_NIL else 0)
    elif kind == 19:
        bs = [(_utf8(vals[i]["v"]) if vals[i] and vals[i]["t"] == T_STR else b"") for i in range(n)]
        w.align(4)
        acc = 0
        for b in bs:
            w.u32(acc)
            acc += len(b)
        w.u32(acc)
        for b in bs:
            w.bytes(b)
    elif kind == 22:
        w.i64(plan["base"])
        w.u8(plan["width"])
        w.align(plan["width"])
        base = plan["base"]
        for i in range(n):
            x = int_val(vals[i]) if vals[i] and vals[i]["t"] == T_INT else base
            d = x - base
            if plan["width"] == 1:
                w.u8(d)
            elif plan["width"] == 2:
                w.u16(d)
            else:
                w.u32(d)


def write_keys_node(w, elems, cx, width):
    all_scalar = all((e is None or e["t"] < T_MAP) for e in elems)
    plan = column_plan(elems, cx["sym"]) if all_scalar else None
    if plan:
        s = w.p
        w.u8(0x23); w.varint(len(elems)); write_column(w, plan, elems, width, cx["sym"])
        return s
    offs = [write_node(w, e, cx, width) for e in elems]
    s2 = w.p
    w.u8(0x22); w.varint(len(elems))
    for o in offs:
        w.off(o, width)
    return s2


def write_node(w, node, cx, width):
    if not node or node["t"] == T_NIL:
        s0 = w.p
        w.u8(0)
        return s0
    t = node["t"]
    if t == T_MAP:
        sig = node.get("_shapeSig")
        if sig is not None and sig in cx["shapes"]:
            sh = cx["shapes"][sig]
            field = {ident(k): v for k, v in node["xs"]}
            offs = [write_node(w, field[ident(k)], cx, width) for k in sh["ks"]]
            s = w.p
            w.u8(0x21); w.varint(sh["i"])
            for o in offs:
                w.off(o, width)
            return s
        es = sorted(node["xs"], key=lambda e: list(key_bytes(e[0])))
        keys = [e[0] for e in es]
        vals = [e[1] for e in es]
        keys_off = write_keys_node(w, keys, cx, width)
        plan = column_plan(vals, cx["sym"])
        if plan:
            vals_off = w.p
            w.u8(0x23); w.varint(len(vals)); write_column(w, plan, vals, width, cx["sym"])
        else:
            voffs = [write_node(w, v, cx, width) for v in vals]
            vals_off = w.p
            w.u8(0x22); w.varint(len(vals))
            for o in voffs:
                w.off(o, width)
        sm = w.p
        w.u8(0x27); w.varint(len(es)); w.off(keys_off, width); w.off(vals_off, width)
        return sm
    if t == T_SET:
        elems = sorted(node["xs"], key=lambda e: list(key_bytes(e)))
        ko = write_keys_node(w, elems, cx, width)
        ss = w.p
        w.u8(0x24); w.varint(len(elems)); w.off(ko, width)
        return ss
    if t == T_VEC:
        plan_v = None
        all_scalar = all((e is None or e["t"] < T_MAP) for e in node["xs"])
        if all_scalar:
            plan_v = column_plan(node["xs"], cx["sym"])
        if plan_v:
            sv = w.p
            w.u8(0x23); w.varint(len(node["xs"])); write_column(w, plan_v, node["xs"], width, cx["sym"])
            return sv
        eoffs = [write_node(w, e, cx, width) for e in node["xs"]]
        sv2 = w.p
        w.u8(0x22); w.varint(len(node["xs"]))
        for o in eoffs:
            w.off(o, width)
        return sv2
    if t == T_LIST:
        loffs = [write_node(w, e, cx, width) for e in node["xs"]]
        sl = w.p
        w.u8(0x25); w.varint(len(node["xs"]))
        for o in loffs:
            w.off(o, width)
        return sl
    sS = w.p
    i = ident(node)
    if i and i in cx["sym"]:
        w.u8(0x20); w.varint(cx["sym"][i])
    else:
        w.bytes(scalar_body(node))
    return sS


def write_symtab(w, syms, width):
    n = len(syms)
    start = w.p
    for _ in range(n + 1):
        w.off(0, width)
    entries = w.p
    for i in range(n):
        saved = w.p
        w.seek(start + i * width)
        w.off(saved - entries, width)
        w.seek(saved)
        w.bytes(scalar_body(syms[i]))
    saved = w.p
    w.seek(start + n * width)
    w.off(saved - entries, width)
    w.seek(saved)


def write_shapetab(w, shape_list, sym, width):
    n = len(shape_list)
    start = w.p
    for _ in range(n + 1):
        w.off(0, width)
    entries = w.p

    def patch(i):
        saved = w.p
        w.seek(start + i * width)
        w.off(saved - entries, width)
        w.seek(saved)

    for i in range(n):
        patch(i)
        sh = shape_list[i]
        w.varint(len(sh["ks"]))
        w.varint(0)
        for k in sh["ks"]:
            w.varint(sym[ident(k)])
    patch(n)


def emit_framed(w, node):
    start = w.p
    w.u32(0)
    emit_flat(w, node)
    w.patch_u32(start, w.p - start - 4)


def emit_flat(w, node):
    if not node or node["t"] == T_NIL:
        return
    t = node["t"]
    if t == T_MAP:
        es = sorted(node["xs"], key=lambda e: list(key_bytes(e[0])))
        w.u8(MAGIC0); w.u8(MAGIC1); w.u8(0xED); w.i32(len(es))
        for k, v in es:
            emit_framed(w, k)
            emit_framed(w, v)
        return
    if t in (T_VEC, T_LIST):
        w.u8(MAGIC0); w.u8(MAGIC1); w.u8(0xEF); w.i32(len(node["xs"]))
        for e in node["xs"]:
            emit_framed(w, e)
        return
    if t == T_SET:
        parts = sorted((flat_of(e) for e in node["xs"]), key=lambda b: list(b))
        w.u8(MAGIC0); w.u8(MAGIC1); w.u8(0xEE); w.i32(len(parts))
        for p in parts:
            w.u32(len(p)); w.bytes(p)
        return
    body = scalar_body(node)
    w.u8(MAGIC0); w.u8(MAGIC1); w.bytes(body)


def flat_of(node):
    w = Writer()
    emit_flat(w, node)
    return w.slice()


def pack_plain(ir, hasheq):
    if not ir:
        return None
    counts = {}
    shapes = {"_n": 0}
    scan(ir, counts, shapes, False)
    members = [c for i, c in counts.items() if i != "_n" and member(c)]
    members.sort(key=lambda c: (-c["tot"], str_of(c["n"])))
    syms = [m["n"] for m in members]
    sym = {ident(s): i for i, s in enumerate(syms)}
    shape_list = [None] * (shapes.get("_n", 0))
    for sig, sh in shapes.items():
        if sig == "_n":
            continue
        shape_list[sh["i"]] = sh
    cx = {"sym": sym, "syms": syms, "shapes": shapes, "shapeList": shape_list}

    def build(width):
        w = Writer()
        for _ in range(72):
            w.u8(0)
        symtab = w.p
        write_symtab(w, syms, width)
        shapetab = w.p
        write_shapetab(w, shape_list, sym, width)
        typetab = 0
        root = write_node(w, ir, cx, width)
        length = w.p
        padded = (length + 7) & ~7
        if width == 2 and padded > 65535:
            return None
        while w.p < padded:
            w.u8(0)
        w.seek(0)
        w.u8(0x45); w.u8(0x56); w.u8(0x53); w.u8(0x31)
        w.u8(1); w.u8(0)
        w.u16((1 if width == 4 else 0) | 2)
        w.u32(padded)
        w.u32(root)
        w.u16(len(syms))
        w.u16(len(shape_list))
        w.u32(symtab)
        w.u32(shapetab)
        w.u32(typetab)
        dg = hashlib.sha256(flat_of(ir)).digest()
        w.patch_u32(32, hasheq)
        w.seek(36)
        w.bytes(dg)
        return w.slice(padded)

    out = build(2)
    if out is None:
        out = build(4)
    return out


# ----- basilisp / Eve value -> IR -----

def _mod_name(v):
    t = type(v)
    return (t.__module__ or ""), t.__name__


def _is_keyword(v):
    mod, name = _mod_name(v)
    return name == "Keyword" or mod.endswith(".keyword")


def _is_symbol(v):
    mod, name = _mod_name(v)
    return name == "Symbol" or mod.endswith(".symbol")


def _is_map(v):
    if v is None or isinstance(v, (str, bytes, bytearray)):
        return False
    mod, name = _mod_name(v)
    if name in ("PersistentMap", "TransientMap", "EveHashMap") or "map" in mod:
        return True
    return hasattr(v, "items") and hasattr(v, "keys") and not hasattr(v, "nth")


def _is_set(v):
    mod, name = _mod_name(v)
    if name in ("PersistentSet", "TransientSet", "EveHashSet") or mod.endswith(".set"):
        return True
    if isinstance(v, set) and not _is_map(v):
        return True
    return False


def _is_vector(v):
    mod, name = _mod_name(v)
    return name in ("PersistentVector", "TransientVector", "EveVector") or mod.endswith(".vector")


def _is_list(v):
    mod, name = _mod_name(v)
    return name in ("PersistentList", "Cons", "EveList") or mod.endswith(".list") or name == "LazySeq"


def _entry_kv(entry):
    if entry is None:
        return None
    if isinstance(entry, (tuple, list)) and len(entry) >= 2:
        return entry[0], entry[1]
    if hasattr(entry, "key") and hasattr(entry, "value"):
        return entry.key, entry.value
    try:
        return entry[0], entry[1]
    except Exception:
        return None


def to_ir(v):
    if v is None:
        return {"t": T_NIL}
    if isinstance(v, bool):
        return {"t": T_BOOL, "v": v}
    if _is_keyword(v):
        ns = getattr(v, "ns", None)
        return {"t": T_KW, "ns": ns, "n": v.name, "s": str(v)}
    if _is_symbol(v):
        ns = getattr(v, "ns", None)
        return {"t": T_SYM, "ns": ns, "n": v.name, "s": str(v)}
    if isinstance(v, str):
        return {"t": T_STR, "v": v}
    if isinstance(v, int):
        return {"t": T_INT, "v": int(v)}
    if isinstance(v, float):
        return {"t": T_FLT, "v": float(v)}
    if _is_map(v):
        xs = []
        orig = []
        # PersistentMap.items() yields [k v] entries. EveHashMap has no
        # items and is Iterable of map-entries via hamt-seq — iterating
        # a PersistentMap itself yields keys only, which broke to_ir.
        name = _mod_name(v)[1]
        if name == "EveHashMap":
            items = v
        elif hasattr(v, "items"):
            items = v.items()
        else:
            items = v
        for entry in items:
            kv = _entry_kv(entry)
            if kv is None:
                return None
            ki, xi = to_ir(kv[0]), to_ir(kv[1])
            if ki is None or xi is None:
                return None
            xs.append((ki, xi))
            orig.append(kv)
        return {"t": T_MAP, "xs": xs, "_orig_pairs": orig}
    if _is_set(v):
        xs = []
        for e in v:
            ei = to_ir(e)
            if ei is None:
                return None
            xs.append(ei)
        return {"t": T_SET, "xs": xs}
    if _is_vector(v):
        xs = []
        for e in v:
            ei = to_ir(e)
            if ei is None:
                return None
            xs.append(ei)
        return {"t": T_VEC, "xs": xs}
    if _is_list(v) or (hasattr(v, "__iter__") and not isinstance(v, (str, bytes, dict))):
        # last-chance sequential: only basilisp lists / seqs, not arbitrary
        if not (_is_list(v) or _mod_name(v)[1] in ("LazySeq", "ArraySeq", "ChunkedCons")):
            return None
        xs = []
        for e in v:
            ei = to_ir(e)
            if ei is None:
                return None
            xs.append(ei)
        return {"t": T_LIST, "xs": xs}
    return None


def _recipe_hasheq_map(pairs):
    """lpy node-hasheq* of a packed plain map is
    (hash (into #{} (map (fn [[k v]] [k v]) entries))) — a PersistentSet
    of PersistentVectors of the entry objects. Low 32 bits are what
    pack-rep patches into the recipe word."""
    import basilisp.lang.vector as vec
    import basilisp.lang.set as st
    vs = [vec.v(k, val) for k, val in pairs]
    return hash(st.PersistentSet.from_iterable(vs))


def _recipe_hasheq_set(elems):
    import basilisp.lang.set as st
    return hash(st.PersistentSet.from_iterable(elems))


_last_obj = None
_last_bs = None


def pack_value(v, hasheq=None):
    """Pack a basilisp/Eve plain value as FORMAT-V1 bytes (array('b')), or None
    when the fast path does not apply. If hasheq is omitted, it is the
    lpy node-hasheq* of the packed form (a basilisp set-of-vectors hash
    for maps). Repeating pack of the same object (official deref) hits a
    one-slot identity cache so (hash evemap) / into is not paid again."""
    global _last_obj, _last_bs
    if v is _last_obj:
        return _last_bs
    ir = to_ir(v)
    if ir is None:
        return None
    # a lone scalar is not a chunk (chunk/pack returns the value itself)
    if ir["t"] < T_MAP:
        return None
    if hasheq is None:
        if ir["t"] == T_MAP:
            hasheq = _recipe_hasheq_map(ir.get("_orig_pairs") or [])
        elif ir["t"] == T_SET:
            hasheq = _recipe_hasheq_set(ir.get("_orig_elems") or [])
        else:
            # vec/list: node-hasheq* is hash-ordered, which on lpy is
            # hash of a basilisp vector of the same items (or a tuple
            # for lists). Leave 0 and let callers pass hasheq; official
            # deref/swap-map are maps.
            hasheq = 0
    out = pack_plain(ir, hasheq)
    if out is None:
        return None
    a = array.array("b")
    a.frombytes(out)
    _last_obj = v
    _last_bs = a
    return a
