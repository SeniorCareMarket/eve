;; pages_v3_test — R-B5.1, R-B5.2: eve's page writer and reader
;; (eve.net.pages) and the eve sealer (eve.net.codec: §2.6 once in .cljc), pinned against the kernel. The pass line is
;; `CROSS PASS` (the `cross` deftest): a value eve writes is adopted by the kernel and every block eve_seal_verify'd —
;; at the writer's addresses (kernel_attestation.py ATTEST-X's warm start) and adopted by PAGE_CID through
;; eve_page_adopt_b64 (rebased, the broker's path) — and a value the kernel writes (seal_block_attested records,
;; root_stamp) is read by eve, every block verified, its records' attestations and the root's stamp read back from the
;; headers; an attested record's digest agrees across the two sealers.
;;
;; The kernel side is the loader of VS's test/core-census/kernel_attestation.py (v3_layout.py's Kern over kernel.wasm, the
;; published `wasmtime` package; V3_KERNEL picks another kernel) and kernel_merkle_canon.py's value builders, driven
;; by the program below through python3 — on the hosts that run a process (bb, the JVM). Run from eve/:
;;   bb test:bb eve.aegis.pages-v3-test        clojure -M:jvm-test -n eve.aegis.pages-v3-test
(ns eve.aegis.pages-v3-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [eve.codec :as wire]
            [eve.encoding :as enc]
            [eve.net.bytes :as b]
            [eve.net.codec :as c]
            [eve.net.pages :as p]
            #?@(:clj [[clojure.java.shell :as sh]] :default [])))

(def ^:private spec (vec (range 0x40 0x60)))
(def ^:private at3 {:epoch 3 :spec-id spec :group-root (vec (repeat 32 0x11)) :principal-commit (vec (repeat 32 0x22))
                    :evaluator (vec (repeat 16 0xE7))})
(def ^:private stamp {:epoch 5 :spec-id (vec (repeat 32 1)) :evaluator (vec (repeat 16 2)) :prev (vec (repeat 32 3))})
(def ^:private message {"text" "hello, room" "author" "alice"})

(defn- state []
  {"log" [(c/attest message at3) {"text" "hi"}] "s" #{1 2 :k} "l" (list 1 "two" [3]) "n" 7 "d" -0.0
   "big" 4294967296 :kw "v" "m" (into {} (map (fn [i] [(str "k" i) i]) (range 40)))})

(defn- page-map [w] (into {} (map (fn [pg] [(:cid pg) (:bytes pg)]) (:pages w))))

(defn- thrown-type [f] (try (f) nil (catch #?(:cljs :default :lpy python/Exception :jank jank.runtime.object_ref :default Exception) e (:type (ex-data e)))))

(deftest eve-writes-and-reads-its-pages
  (let [v (state)
        w (p/write-pages v {:salt (vec (repeat 32 9)) :stamp stamp})
        r (p/read-pages (:root w) (page-map w))]
    (is (= v (:value r)) "the value read back")
    (is (= (:key w) (:key r)) "the root's band")
    (is (= at3 (c/attestation (first (get (:value r) "log")))) "the record's attestation, from its header")
    (is (nil? (c/attestation (second (get (:value r) "log")))) "an unattested map carries none")
    (is (= stamp (:stamp r)) "the root's stamp")
    (is (= (:key w) (b/hex (first (c/band-of (with-meta v nil))))) "the root's band is the codec's: a stamp is outside the digest")
    (testing "the next state: only what changed is written, the rest referenced by its core"
      (let [v2 (update v "log" conj (c/attest {"text" "second"} (assoc at3 :epoch 6)))
            w2 (p/write-pages v2 {:salt (vec (repeat 32 8)) :known (:known r) :region 40})
            r2 (p/read-pages (:root w2) (merge (page-map w) (page-map w2)))]
        (is (= v2 (:value r2)))
        (is (< (reduce + (map (comp count :bytes) (:pages w2))) (reduce + (map (comp count :bytes) (:pages w)))))
        (testing "read from the earlier read: what it verified at the same place is taken as it was, the same answer"
          (let [r2' (p/read-pages (:root w2) (merge (page-map w) (page-map w2)) {:prev r})]
            (is (= v2 (:value r2')))
            (is (= (:key r2) (:key r2')))
            (is (= (:known r2) (:known r2')) "every block's place")
            (is (= (:blocks r2) (:blocks r2')) "every block's image")
            (is (= (c/rec-key (:record r2)) (c/rec-key (:record r2'))))
            (is (< (:checked r2') (:checked r2)) "only the blocks the next value added are read and verified")
            (is (= at3 (c/attestation (first (get (:value r2') "log")))) "a record taken from the earlier read keeps its attestation")))))
    (testing "a block whose bytes changed is refused"
      (let [pm (page-map w)
            rec-band (b/hex (first (c/band-of (c/attest message at3))))
            {:keys [cid off]} (get (:known r) rec-band)
            k (b/hex cid) local (- off (p/region-base off))
            flip (fn [i] (assoc pm k (update (get pm k) (+ local i) (fn [x] (bit-xor x 1)))))]
        (is (= :bad-page (thrown-type (fn [] (p/read-pages (:root w) (flip 70))))) "an attested byte (EPOCH)")
        (is (= :bad-page (thrown-type (fn [] (p/read-pages (:root w) (flip 300))))) "a payload byte")
        (is (= :bad-page (thrown-type (fn [] (p/read-pages (:root w) (assoc pm k (assoc (get pm k) (+ local 22) 2))))))
            "a v2 header (§2.3)")))))

;; a value another writer imaged, in a form this codec reads but does not write, keeps its keys through eve's next write:
;; the kernel's runtime FLOAT64 (the web page's timestamps — the page's clock is a float64) decodes to the long
;; 1793006789451, which this codec images as its own LONG ("UPA2lng ") under another key. A writer that imaged the read
;; value again named the old state by a key no writer gave it and rewrote every such entry; the swarm's seats
;; (eve/capstone/eve-swarm) were refused claim/state-roots on every write after the browser's first. read-pages' :record
;; and write-pages' :record (eve.net.codec's decode-rec / encode-rec) keep the read tree's keys wherever the value is
;; unchanged.
(def ^:private K-FLOAT64-TS (b/unhex "f6f6f6f6f6f6f6f6f4f4f4f4f4f4f4f400b0348678177a420000000000000000"))

(deftest a-value-another-writer-imaged-keeps-its-keys
  (let [{:keys [root blocks]} (c/map-of-bands [["ts" K-FLOAT64-TS] ["n" (first (c/band-of 1))]])
        [v rec] (c/decode-rec (b/unhex root) blocks)
        w (p/write-pages (assoc v "n" 2) {:record rec :salt (vec (repeat 32 9)) :stamp stamp})
        r (p/read-pages (:root w) (page-map w))]
    (is (= {"ts" 1793006789451 "n" 2} (:value r)) "the kernel's FLOAT64 reads as the integer it holds")
    (is (= (vec K-FLOAT64-TS) (vec (c/rec-band-of (:record r) "ts"))) "the entry the write left keeps the writer's band")
    (is (= (:key w) (:key r)) "the root's band")
    (is (not= (:key r) (:key (p/write-pages (:value r) {:salt (vec (repeat 32 9))})))
        "imaged again, the value is another key: the codec writes the long in its own form")
    (testing "the next write, from the read's record: only the changed path imaged, the rest keeps its keys"
      (let [w2 (p/write-pages (assoc (:value r) "n" 3) {:record (:record r) :known (:known r) :salt (vec (repeat 32 8))
                                                         :region 40})
            r2 (p/read-pages (:root w2) (merge (page-map w) (page-map w2)))]
        (is (= {"ts" 1793006789451 "n" 3} (:value r2)))
        (is (= (vec K-FLOAT64-TS) (vec (c/rec-band-of (:record r2) "ts"))))
        (is (= (:key w2) (:key r2)))))))

;; a /deref or /cas-root answer as every host's client holds it (eve.aegis.chat.client/body-value): eve-wire bytes made
;; by eve.encoding/bytes-of, decoded by eve.codec — which copies each nested key, value and element out through
;; eve.encoding/copy-of-range. On basilisp bytes-of answers a bytearray, which copy-of-range must copy as it came
;; (encoding.lpy; it refused one into an array('b'), and so no basilisp client could decode a body).
(deftest a-body-held-as-bytes-of-decodes
  (let [v {"root" [1 2 255] "pages" {"a" "b" "c" [3 4]} :n 7 "big" 4294967296 "log" [{"text" "hi" "at" [5 6]}]}
        bs (mapv (fn [x] (if (neg? x) (+ x 256) x)) (seq (wire/encode-key v)))]
    (is (= [0xEE 0xDB] (subvec bs 0 2)) "eve-wire")
    ;; (copy-of-range is the byte-array codec's: ClojureScript's eve.encoding has none, its codec slices)
    #?(:cljs nil
       :default (is (= [2 255] (mapv #(bit-and % 0xFF) (seq (enc/copy-of-range (enc/bytes-of [1 2 255]) 1 3))))
                    "copy-of-range over a value bytes-of made"))
    (is (= v (wire/decode nil (enc/bytes-of bs))) "the body decodes, every nested value copied out")))

;; a /deref's pages are base64 inside a base64 body: eve.net.bytes/from-b64 reads the text's UTF-16 units by index (a
;; lazy seq a character cost basilisp ~160 µs a byte — e2e9's lpy read ran past 1,800 s), its tails as a 4-digit group
;; with the missing digits zero, characters outside the alphabet skipped
(deftest a-page-decodes-from-its-base64
  (let [page (mapv (fn [i] (mod (* 37 (+ i 11)) 256)) (range 3000))]
    (doseq [n [0 1 2 3 4 5 6 7 2999 3000]]
      (let [bs (subvec page 0 n) t (b/to-b64 bs)
            broken (str (subs t 0 (quot (count t) 2)) "\r\n" (subs t (quot (count t) 2)))]
        (is (= bs (vec (b/from-b64 t))) (str n " bytes"))
        (is (= bs (vec (b/from-b64 broken))) (str n " bytes, a line break inside")))))
  (is (= [[65] [65 66] [65 66 67] [65 66 67 68] [65 66 67]]
         (mapv (comp vec b/from-b64) ["QQ" "QUI" "QUJD" "QUJDRA==" "QU\nJD="])) "known texts")
  (is (= [4] (vec (b/from-b64 "B"))) "a lone digit, as the 4-digit group's first byte")
  (is (= (vec (range 256)) (vec (b/from-b64 (b/to-b64 (range 256))))) "every byte value"))

(def ^:private kernel-py "import sys, os, base64, hashlib, importlib.util, json
VS = sys.argv[1]; MODE = sys.argv[2]
def load(n, p):
    s = importlib.util.spec_from_file_location(n, p); m = importlib.util.module_from_spec(s); s.loader.exec_module(m); return m
L = load('v3_layout', os.path.join(VS, 'test', 'core-census', 'v3_layout.py'))
k = L.fresh(os.environ.get('V3_KERNEL', L.KERNEL_WASM))
REGION = L.G('REGION_SIZE'); OWN = L.EXTCID_OWN; CORE = L.EXTCID_CORE; SHA = L.EXTCID_SHA_OFF
HEADED = (L.G('T_MAP'), L.G('T_HAMT_NODE_BITMAP'), L.G('T_VEC'), L.G('T_LIST'))
def image_of(c):
    s = k.f('alloc_block', k.f('size_to_class', CORE)); k.f('slot_store', s, c)
    r = k.read(s + SHA, 32); k.f('free_block_off', s); return r
def is_headed(c): return L.tag_of(k, c) in HEADED and L.asize(k, c) >= 288 and k.read(c + L.EXTCID_VERSION_OFF, 1)[0] == L.EXTCID_VERSION
def slots_of(c):
    t = L.tag_of(k, c)
    if t == L.G('T_LIST'):
        if k.read(c, 8) == (L.G('LIST_ROOT_CID_LO') & (2**64 - 1)).to_bytes(8, 'little'): return [c + L.G('LIST_HEAD_OFF')]
        return [c + L.G('LNODE_NEXT_OFF'), c + L.G('LNODE_HEADER_SIZE')]
    return L.slots(k, c)
ROWS = {'n': 0, 'fail': 0}
def row(label, ok, detail=''):
    ROWS['n'] += 1
    if not ok: ROWS['fail'] += 1
    print(('PASS ' if ok else 'FAIL ') + label + ((' -- ' + detail) if detail and not ok else ''))
def walk(c, band, resolve, out):
    if c in out['seen']: return
    out['seen'].add(c)
    if is_headed(c):
        out['headed'] += 1
        v = k.f('eve_seal_verify', c)
        if v != 1: out['bad'].append('eve_seal_verify(@%d) -> %d' % (c, v))
        own = L.own_sha(k, c)
        if band != own and band != L.xor_ee(own): out['bad'].append('@%d: own SHA %s, the parent band %s' % (c, own.hex()[:12], band.hex()[:12]))
        f = k.f('eve_hdr_flags', c) & 0xFFFFFFFF
        if f & L.EXTCID_F_ATTESTED: out['attested'].append(own.hex())
        for s in slots_of(c):
            if k.read(s, CORE) == bytes(CORE): continue
            cc = resolve(s)
            if cc <= 0: out['bad'].append('slot @%d resolves to %d' % (s, cc)); continue
            walk(cc, k.read(s + SHA, 32), resolve, out)
    else:
        out['flat'] += 1
        img = image_of(c)
        if img != band: out['bad'].append('flat @%d: the kernel images it %s, the band says %s' % (c, img.hex()[:12], band.hex()[:12]))
def check_eve(lines):
    pages, root = [], None
    for l in lines:
        p = l.split()
        if p and p[0] == 'page': pages.append((p[1], int(p[2]), p[3].encode()))
        if p and p[0] == 'root': root = bytes.fromhex(p[1])
    # world A: each page at the address its writer gave it (the warm start's page map: kernel_attestation.py ATTEST-X)
    for cid, base, text in pages: L.put(k, base, base64.b64decode(text))
    rb = int.from_bytes(root[16:22], 'little')
    out = {'seen': set(), 'headed': 0, 'flat': 0, 'bad': [], 'attested': []}
    walk(rb, root[32:64], lambda s: int.from_bytes(k.read(s + 16, 6), 'little'), out)
    row('EVE->KERNEL same addresses: %d headed blocks eve_seal_verify 1, %d value blocks imaged alike' % (out['headed'], out['flat']),
        not out['bad'] and out['headed'] > 0, '; '.join(out['bad'][:3]))
    print('ATTESTED ' + ' '.join(sorted(out['attested'])))
    # world B: a second kernel adopts the pages by CID (eve_page_adopt_b64: the broker's path), the root resolved by its slot
    k2 = L.fresh(os.environ.get('V3_KERNEL', L.KERNEL_WASM))
    STAGE = 2048 * 65536 - 2 * REGION
    got = []
    for cid, base, text in pages:
        L.put(k2, STAGE - 64, bytes.fromhex(cid)); L.put(k2, STAGE, text)
        got.append(k2.f('eve_page_adopt_b64', STAGE, len(text), STAGE - 64))
    row('EVE->KERNEL adopt: %d/%d pages adopted by eve_page_adopt_b64' % (sum(1 for r in got if r > 0), len(got)), all(r > 0 for r in got), str(got))
    return out, k2
def mode_eve():
    lines = sys.stdin.read().splitlines()
    out, k2 = check_eve(lines)
    global k
    k = k2
    root = [bytes.fromhex(l.split()[1]) for l in lines if l.startswith('root ')][0]
    slot = k.f('alloc_block', k.f('size_to_class', CORE)); L.put(k, slot, root)
    local = k.f('eve_slot_resolve', slot)
    out2 = {'seen': set(), 'headed': 0, 'flat': 0, 'bad': [], 'attested': []}
    if local > 0: walk(local, root[32:64], lambda s: k.f('eve_slot_resolve', s), out2)
    row('EVE->KERNEL adopted, rebased: the root resolves (%d), %d headed blocks eve_seal_verify 1, %d value blocks imaged alike'
        % (local, out2['headed'], out2['flat']), local > 0 and not out2['bad'] and out2['headed'] == out['headed'], '; '.join(out2['bad'][:3]))
    if local > 0 and L.tag_of(k, local) == L.G('T_MAP'):
        print('COUNT %d' % k.f('hm_count', local))
    # get [path] value: the kernel's own lookup (hm_get_off, the gate's get-in) through the adopted pages, the value it
    # finds imaged as the kernel's own build of the value images
    M = None
    for l in lines:
        if not l.startswith('get '): continue
        if M is None: M = load('mcanon', os.path.join(VS, 'test', 'core-census', 'kernel_merkle_canon.py'))
        ptext, wtext = l[4:].split('] ', 1); path, want = json.loads(ptext + ']'), json.loads(wtext)
        c = local
        for seg in path:
            c = k.f('hm_get_off', c, M.kkey(k, M.Kw(seg[1:]) if seg.startswith(':') else seg)) if c > 0 else -1
        w = M.kval(k, want)
        got = (L.own_sha(k, c) if is_headed(c) else image_of(c)) if c > 0 else b''
        row('EVE->KERNEL get %s: the value eve wrote' % ' '.join(path), c > 0 and got == image_of(w),
            'found @%d imaged %s, the kernel images the value %s' % (c, got.hex()[:16], image_of(w).hex()[:16]))
def scratch(data):
    b = k.f('alloc_block', k.f('size_to_class', max(len(data), 1))); k.write(b, data); return b
def mode_kernel():
    M = load('mcanon', os.path.join(VS, 'test', 'core-census', 'kernel_merkle_canon.py'))
    spec, group, princ, ev = bytes(range(0x40, 0x60)), bytes([0x11]) * 32, bytes([0x22]) * 32, bytes([0xE7]) * 16
    mask = L.EXTCID_F_HAS_GROUP | L.EXTCID_F_HAS_PRINCIPAL
    recs = []
    for epoch in (3, 4):
        r = M.kval(k, {'text': 'hello, room', 'author': 'alice'})
        c = scratch(epoch.to_bytes(16, 'little') + spec + group + princ + ev)
        row('KERNEL seal_block_attested epoch %d -> 0' % epoch, k.f('seal_block_attested', r, c, mask) == 0)
        recs.append(r)
    log = M.vconj_blocks(k, recs)
    m = k.f('hm_create')
    for key, val in (('log', log), ('room', M.kval(k, 'public')), ('n', k.box(7)), (M.Kw('k'), M.kval(k, M.Kw('v')))):
        m = M.assoc(k, m, M.kkey(k, key), val)
    prev = scratch(bytes([3]) * 32)
    row('KERNEL root_stamp -> 0', k.f('root_stamp', m, prev, 5, scratch(bytes([1]) * 32), scratch(bytes([2]) * 16)) == 0)
    seen, ends = set(), {}
    def reach(c):
        if c in seen or c <= 0: return
        seen.add(c); b = L.region_base(c); ends[b] = max(ends.get(b, 0), c - b + L.asize(k, c))
        if is_headed(c):
            for s in slots_of(c):
                if k.read(s, CORE) != bytes(CORE): reach(int.from_bytes(k.read(s + 16, 6), 'little'))
    reach(m)
    dst = k.f('alloc_block', 14)
    for b in sorted(ends):
        n = ends[b]; n += (3 - n % 3) % 3; parts = []
        for fr in range(0, n, 6144):
            parts.append(k.read(dst, k.f('eve_page_b64_chunk', b, fr, min(6144, n - fr), dst)))
        print('page %s %d %s' % (L.mem(k, b + L.G('PHV2_PAGE_CID'), 16).hex(), b, b''.join(parts).decode()))
    root = (L.mem(k, L.region_base(m) + L.G('PHV2_PAGE_CID'), 16) + m.to_bytes(6, 'little') + bytes([L.EXTCID_VERSION, 0])
            + L.EXTCID_F_ACTIVE.to_bytes(4, 'little') + bytes(4) + L.key_of(k, m))
    print('root ' + root.hex())
    for r in recs: print('record ' + L.own_sha(k, r).hex())
mode_eve() if MODE == 'eve' else mode_kernel()
print('%d/%d rows PASS' % (ROWS['n'] - ROWS['fail'], ROWS['n']))
")

#?(:clj
   (defn- kernel [mode input]
     (let [{:keys [exit out err]} (sh/sh "python3" "-c" kernel-py "../vs" mode :in input)]
       (when-not (zero? exit) (println "pages_v3_test: the kernel loader exited" exit (subs err (max 0 (- (count err) 2000)))))
       (str/split-lines out))))

;; the regions row's second write (e2e10a: claim/old-root-mismatch, eve reading {"m-alice" <pk>} back): the gate looks
;; the members map up with the kernel's own get through eve's adopted pages, then roots it. A map shaped as the read-
;; regions spec's state after its first add-member, written by eve: the kernel's lookup finds the value eve wrote
(def ^:private PK "a696ce5f21e72aa8f082684badf606d5f3676cdaa74d8c72012da599be0a6623")
(defn- members-state []
  {:rooms {:private {:members {"m-alice" PK} :messages []}} :keys {:private {}}
   :aegis {:spent {"6f1d2a59e0c4b8a7d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f3a2b1c0" true} :ballot-spends {}}})

#?(:clj
   (deftest the-kernel-gets-what-eve-wrote
     (let [w (p/write-pages (members-state) {:salt (vec (repeat 32 9)) :stamp stamp :region 100})
           lines (kernel "eve" (str (str/join "\n" (map (fn [pg] (str "page " (:cid pg) " " (:base pg) " " (p/page-b64 (:bytes pg))))
                                                        (:pages w)))
                                    "\nroot " (b/hex (:root w))
                                    "\nget [\":rooms\",\":private\",\":members\",\"m-alice\"] \"" PK "\"\n"))
           rows (filter (fn [l] (or (str/starts-with? l "PASS ") (str/starts-with? l "FAIL "))) lines)]
       (doseq [l rows] (println " " l))
       (is (= 1 (count (filter (fn [l] (str/includes? l " get ")) rows))) "the lookup ran")
       (is (and (= 4 (count rows)) (every? (fn [l] (str/starts-with? l "PASS ")) rows))
           "the kernel adopts eve's pages and its own get finds the members map's entry eve wrote"))))

#?(:clj
   (deftest cross
     (let [v (state)
           w (p/write-pages v {:salt (vec (repeat 32 7)) :stamp stamp :region 100})
           rec-band (b/hex (first (c/band-of (c/attest message at3))))
           ;; eve → the kernel
           lines (kernel "eve" (str (str/join "\n" (map (fn [pg] (str "page " (:cid pg) " " (:base pg) " " (p/page-b64 (:bytes pg))))
                                                        (:pages w)))
                                    "\nroot " (b/hex (:root w)) "\n"))
           rows (filter (fn [l] (or (str/starts-with? l "PASS ") (str/starts-with? l "FAIL "))) lines)
           eve->kernel (and (= 3 (count rows)) (every? (fn [l] (str/starts-with? l "PASS ")) rows)
                            (some #{(str "COUNT " (count v))} lines)
                            (some #{(str "ATTESTED " rec-band)} lines))
           _ (doseq [l rows] (println " " l))
           ;; the kernel → eve
           klines (kernel "kernel" "")
           kpages (into {} (for [l klines :when (str/starts-with? l "page ")]
                             (let [[_ cid _ t] (str/split l #" ")] [cid (p/b64-page t)])))
           kroot (b/unhex (subs (first (filter (fn [l] (str/starts-with? l "root ")) klines)) 5))
           records (map (fn [l] (subs l 7)) (filter (fn [l] (str/starts-with? l "record ")) klines))
           r (p/read-pages kroot kpages)
           log (get (:value r) "log")
           want {"room" "public" "log" [message message] "n" 7 :k :v}
           kernel->eve (and (= want (:value r))
                            (= [3 4] (map (comp :epoch c/attestation) log))
                            (= (assoc at3 :epoch 4) (c/attestation (second log)))
                            (= stamp (:stamp r)))
           ;; the two sealers: eve's attested digest of the same record is the kernel's seal_block_attested SHA
           digests (map (fn [e] (b/hex (first (c/band-of (c/attest message (assoc at3 :epoch e)))))) [3 4])
           sealers (and (= 2 (count records)) (= digests records) (not= (first records) (second records)))
           ok (boolean (and eve->kernel kernel->eve sealers))]
       (is eve->kernel "the kernel adopts eve's pages and verifies every block (the loader's rows above)")
       (is kernel->eve "eve reads the kernel's pages: the value, the records' attestations, the root's stamp")
       (is sealers "an attested record's digest agrees across the two sealers (two epochs, two digests)")
       (println (str "  KERNEL->EVE: " (:checked r) " blocks verified by eve; records " (str/join " " (map (fn [x] (subs x 0 16)) records))))
       (println (if ok "CROSS PASS" "CROSS FAIL")))))
