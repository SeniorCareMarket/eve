;; adapters/flat-io/jvm/src/eve/flat_io.clj — eve.flat-io, the JVM fast root (lane U2b of the flat lanes, 2026-11-17).
;; The contract: doc/flat/FLAT-IO.md. The reference semantics: chunk.cljc's byte layer as it stood at 3b656fea20
;; (ru8 … rf64, rvarint, varint-len, the IBuf / Buf writer), which flat-io/portable carries; every answer here is that
;; answer, byte for byte (adapters/flat-io/test). The fast parts: little-endian loads and stores through
;; MethodHandles/byteArrayViewVarHandle, Arrays/mismatch-family compares, the JDK's UTF-8 decoder, and a tuple that is
;; a view over the chunk's bytes.
(ns eve.flat-io
  "The byte-level seam under eve's flat layer — JVM fast root (doc/flat/FLAT-IO.md)."
  (:refer-clojure :exclude [chunk])
  (:import [clojure.asm ClassWriter MethodVisitor Opcodes Type]
           [clojure.lang APersistentVector$RSeq Compiler DynamicClassLoader IFn IPersistentVector
            MapEntry Murmur3 RT Util]
           [java.nio.charset StandardCharsets]
           ; the Arrays import is used below: the bare class name sits in the config's host-class unresolved-symbol exclusion (for the seats that reach it without an import), which blinds kondo to the import's use
           #_{:clj-kondo/ignore [:unused-import]} [java.util Arrays]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; The little-endian loads and stores: a class of static methods over three
;; byteArrayViewVarHandle views (short[], int[], long[] — LITTLE_ENDIAN).
;;
;; A VarHandle's get / set are signature-polymorphic: the call site's own
;; descriptor is the access's type, and Clojure's compiler can only call them
;; through reflection or a spread MethodHandle over an Object[] (boxing every
;; argument and the answer — adapters/region/jvm-varhandle measured 18.6 ns a
;; get that way). So the call sites are written once here as bytecode, with
;; the exact descriptors javac would emit, by clojure.asm — the assembler
;; Clojure's own compiler (and so every deftype) uses — and defined into the
;; namespace's class loader before the functions below compile against it.
;; The handles are static finals, so the JIT folds them and each method is a
;; bounds check and one load or store. When the namespace is AOT-compiled the
;; class file is written beside its classes (Compiler/writeClassFile), as
;; gen-class's are.
;; ---------------------------------------------------------------------------

(def ^:private le-class "eve/flat_io/LE")

(defn- le-class-bytes
  "The bytecode of eve.flat_io.LE."
  ^bytes []
  (let [cw (ClassWriter. ClassWriter/COMPUTE_MAXS)
        vh "Ljava/lang/invoke/VarHandle;"
        fields [["S16" "[S"] ["S32" "[I"] ["S64" "[J"]]]
    (.visit cw Opcodes/V11 (+ Opcodes/ACC_PUBLIC Opcodes/ACC_FINAL Opcodes/ACC_SUPER)
            ^String le-class nil "java/lang/Object" nil)
    (doseq [[f _] fields]
      (.visitEnd (.visitField cw (+ Opcodes/ACC_PRIVATE Opcodes/ACC_STATIC Opcodes/ACC_FINAL)
                              ^String f vh nil nil)))
    (let [^MethodVisitor mv (.visitMethod cw Opcodes/ACC_STATIC "<clinit>" "()V" nil nil)]
      (.visitCode mv)
      (doseq [[f arr] fields]
        (.visitLdcInsn mv (Type/getType ^String arr))
        (.visitFieldInsn mv Opcodes/GETSTATIC "java/nio/ByteOrder" "LITTLE_ENDIAN" "Ljava/nio/ByteOrder;")
        (.visitMethodInsn mv Opcodes/INVOKESTATIC "java/lang/invoke/MethodHandles" "byteArrayViewVarHandle"
                          "(Ljava/lang/Class;Ljava/nio/ByteOrder;)Ljava/lang/invoke/VarHandle;" false)
        (.visitFieldInsn mv Opcodes/PUTSTATIC ^String le-class ^String f vh))
      (.visitInsn mv Opcodes/RETURN)
      (.visitMaxs mv 0 0)
      (.visitEnd mv))
    ;; Every method is straight-line code (no branch, so no stack map frame):
    ;; (byte[] b, long i[, long v]) — Clojure's primitive long, narrowed here.
    (letfn [(method [^String nm ^String desc body]
              (let [^MethodVisitor mv (.visitMethod cw (+ Opcodes/ACC_PUBLIC Opcodes/ACC_STATIC) nm desc nil nil)]
                (.visitCode mv) (body mv) (.visitMaxs mv 0 0) (.visitEnd mv)))
            (load-vh [^MethodVisitor mv ^String f]
              (.visitFieldInsn mv Opcodes/GETSTATIC ^String le-class f vh)
              (.visitVarInsn mv Opcodes/ALOAD 0)
              (.visitVarInsn mv Opcodes/LLOAD 1)
              (.visitInsn mv Opcodes/L2I))
            (vh-get [^MethodVisitor mv ^String desc]
              (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/invoke/VarHandle" "get" desc false))
            (vh-set [^MethodVisitor mv ^String desc]
              (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL "java/lang/invoke/VarHandle" "set" desc false))]
      (method "u16" "([BJ)J"
              (fn [^MethodVisitor mv] (load-vh mv "S16") (vh-get mv "([BI)S")
                (.visitLdcInsn mv (int 0xFFFF)) (.visitInsn mv Opcodes/IAND)
                (.visitInsn mv Opcodes/I2L) (.visitInsn mv Opcodes/LRETURN)))
      (method "i16" "([BJ)J"
              (fn [^MethodVisitor mv] (load-vh mv "S16") (vh-get mv "([BI)S")
                (.visitInsn mv Opcodes/I2L) (.visitInsn mv Opcodes/LRETURN)))
      (method "u32" "([BJ)J"
              (fn [^MethodVisitor mv] (load-vh mv "S32") (vh-get mv "([BI)I")
                (.visitInsn mv Opcodes/I2L) (.visitLdcInsn mv (long 0xFFFFFFFF))
                (.visitInsn mv Opcodes/LAND) (.visitInsn mv Opcodes/LRETURN)))
      (method "i32" "([BJ)J"
              (fn [^MethodVisitor mv] (load-vh mv "S32") (vh-get mv "([BI)I")
                (.visitInsn mv Opcodes/I2L) (.visitInsn mv Opcodes/LRETURN)))
      (method "i64" "([BJ)J"
              (fn [^MethodVisitor mv] (load-vh mv "S64") (vh-get mv "([BI)J")
                (.visitInsn mv Opcodes/LRETURN)))
      ;; the floats go through the integer views and the bit conversions, as
      ;; the reference does (Double/longBitsToDouble, Float/intBitsToFloat)
      (method "f64" "([BJ)D"
              (fn [^MethodVisitor mv] (load-vh mv "S64") (vh-get mv "([BI)J")
                (.visitMethodInsn mv Opcodes/INVOKESTATIC "java/lang/Double" "longBitsToDouble" "(J)D" false)
                (.visitInsn mv Opcodes/DRETURN)))
      (method "f32" "([BJ)D"
              (fn [^MethodVisitor mv] (load-vh mv "S32") (vh-get mv "([BI)I")
                (.visitMethodInsn mv Opcodes/INVOKESTATIC "java/lang/Float" "intBitsToFloat" "(I)F" false)
                (.visitInsn mv Opcodes/F2D) (.visitInsn mv Opcodes/DRETURN)))
      (method "p16" "([BJJ)V"
              (fn [^MethodVisitor mv] (load-vh mv "S16")
                (.visitVarInsn mv Opcodes/LLOAD 3) (.visitInsn mv Opcodes/L2I) (.visitInsn mv Opcodes/I2S)
                (vh-set mv "([BIS)V") (.visitInsn mv Opcodes/RETURN)))
      (method "p32" "([BJJ)V"
              (fn [^MethodVisitor mv] (load-vh mv "S32")
                (.visitVarInsn mv Opcodes/LLOAD 3) (.visitInsn mv Opcodes/L2I)
                (vh-set mv "([BII)V") (.visitInsn mv Opcodes/RETURN)))
      (method "p64" "([BJJ)V"
              (fn [^MethodVisitor mv] (load-vh mv "S64")
                (.visitVarInsn mv Opcodes/LLOAD 3)
                (vh-set mv "([BIJ)V") (.visitInsn mv Opcodes/RETURN))))
    (.visitEnd cw)
    (.toByteArray cw)))

(let [bs (le-class-bytes)]
  (when *compile-files* (Compiler/writeClassFile le-class bs))
  (.defineClass ^DynamicClassLoader (RT/makeClassLoader) "eve.flat_io.LE" bs nil))

;; ---------------------------------------------------------------------------
;; chunks — on the JVM a chunk is its byte array
;; ---------------------------------------------------------------------------

(defn chunk
  "The chunk over host byte array bs: on the JVM, bs itself."
  ^bytes [bs] bs)

(defn chunk-bytes
  "The host byte array of chunk c."
  ^bytes [c] c)

(defn chunk-len
  "Chunk c's length in bytes."
  ^long [c] (alength ^bytes c))

;; ---------------------------------------------------------------------------
;; reads — little-endian, absolute offsets
;; ---------------------------------------------------------------------------

(defn u8 ^long [c ^long i] (bit-and (long (aget ^bytes c (unchecked-int i))) 0xFF))
(defn u16 ^long [c ^long i] (eve.flat_io.LE/u16 c i))
(defn u32 ^long [c ^long i] (eve.flat_io.LE/u32 c i))
(defn i32 ^long [c ^long i] (eve.flat_io.LE/i32 c i))
(defn i64 ^long [c ^long i] (eve.flat_io.LE/i64 c i))
(defn f32 ^double [c ^long i] (eve.flat_io.LE/f32 c i))
(defn f64 ^double [c ^long i] (eve.flat_io.LE/f64 c i))

(defn varint-v
  "The value of the unsigned LEB128 varint at i."
  ^long [c ^long i]
  (let [^bytes b c
        x (bit-and (long (aget b (unchecked-int i))) 0xFF)]
    (if (< x 128)
      x
      (loop [i (unchecked-inc i) sh 7 v (bit-and x 127)]
        (let [x (bit-and (long (aget b (unchecked-int i))) 0xFF)]
          (if (< x 128)
            (bit-or v (bit-shift-left x sh))
            (recur (unchecked-inc i) (unchecked-add sh 7) (bit-or v (bit-shift-left (bit-and x 127) sh)))))))))

(defn varint-next
  "The offset after the varint at i."
  ^long [c ^long i]
  (let [^bytes b c]
    (loop [i i]
      (if (< (bit-and (long (aget b (unchecked-int i))) 0xFF) 128)
        (unchecked-inc i)
        (recur (unchecked-inc i))))))

(defn varint
  "[value next] — the unsigned LEB128 varint at i."
  [c ^long i]
  (let [^bytes b c]
    (loop [i i sh 0 v 0]
      (let [x (bit-and (long (aget b (unchecked-int i))) 0xFF)]
        (if (< x 128)
          [(bit-or v (bit-shift-left x sh)) (unchecked-inc i)]
          (recur (unchecked-inc i) (unchecked-add sh 7) (bit-or v (bit-shift-left (bit-and x 127) sh))))))))

(defn varint-len
  "The byte count of v as a varint (a signed compare, as the reference's: a
   negative v answers 1)."
  ^long [^long v]
  (loop [v v n 1] (if (< v 128) n (recur (unsigned-bit-shift-right v 7) (unchecked-inc n)))))

(defn utf8
  "The string of the n UTF-8 bytes at off (the JDK's decoder: malformed input
   becomes U+FFFD, a leading U+FEFF is kept — String(byte[], off, n, UTF_8),
   the reference's eve.encoding/utf8-string)."
  [c ^long off ^long n]
  (String. ^bytes c (unchecked-int off) (unchecked-int n) StandardCharsets/UTF_8))

;; ---------------------------------------------------------------------------
;; in place — compares with no copy of the stored bytes
;; ---------------------------------------------------------------------------

(defn bytes=
  "Are the n bytes at off of chunk c equal to host bytes bs from boff? False
   when bs holds fewer than n bytes from boff (the portable root's answer)."
  [c off n bs boff]
  (let [off (long off) n (long n) boff (long boff)]
    (and (<= (+ boff n) (alength ^bytes bs))
         (Arrays/equals ^bytes c (unchecked-int off) (unchecked-int (+ off n))
                        ^bytes bs (unchecked-int boff) (unchecked-int (+ boff n))))))

(defn bytes-cmp
  "-1 / 0 / 1: the n bytes at off of c against the bn bytes of bs from boff,
   unsigned lexicographic, a shorter one first on a common prefix."
  [c off n bs boff bn]
  (let [off (long off) n (long n) boff (long boff) bn (long bn)]
    (Integer/signum (Arrays/compareUnsigned ^bytes c (unchecked-int off) (unchecked-int (+ off n))
                                            ^bytes bs (unchecked-int boff) (unchecked-int (+ boff bn))))))

(defn- utf8-len
  "The byte count of String.getBytes(UTF_8) of k: a lone surrogate encodes as
   '?' (one byte) — the JDK encoder's replacement, which eve.codec/encode-key's
   (eve.encoding/utf8-bytes) answers."
  ^long [^String k]
  (let [m (.length k)]
    (loop [i 0 n 0]
      (if (< i m)
        (let [ch (int (.charAt k i))]
          (cond
            (< ch 0x80) (recur (unchecked-inc i) (unchecked-inc n))
            (< ch 0x800) (recur (unchecked-inc i) (unchecked-add n 2))
            (and (>= ch 0xD800) (<= ch 0xDBFF))
            (if (and (< (unchecked-inc i) m)
                     (let [lo (int (.charAt k (unchecked-inc i)))] (and (>= lo 0xDC00) (<= lo 0xDFFF))))
              (recur (unchecked-add i 2) (unchecked-add n 4))
              (recur (unchecked-inc i) (unchecked-inc n)))
            (and (>= ch 0xDC00) (<= ch 0xDFFF)) (recur (unchecked-inc i) (unchecked-inc n))
            :else (recur (unchecked-inc i) (unchecked-add n 3))))
        n))))

(defmacro ^:private cmp-byte
  "Compare the stored byte at j with the encoded byte e: answer -1 / 1 from the
   enclosing fn on a difference, else continue with body."
  [b j e & body]
  `(let [s# (bit-and (long (aget ~b (unchecked-int ~j))) 0xFF) e# (long ~e)]
     (if (== s# e#) (do ~@body) (if (< s# e#) -1 1))))

(defn- key-cmp-general
  "key-cmp's whole rule: k's UTF-8 length first, then the class and length
   bytes, then the bytes encoded on the fly."
  ^long [c ^long off ^long n k]
  (let [^bytes b c
        ^String k k
        m (utf8-len k)
        cs (if (< n 256) 5 6)
        ck (if (< m 256) 5 6)]
    (cond
      (not (== cs ck)) (if (< cs ck) -1 1)
      (and (== cs 5) (not (== n m))) (if (< n m) -1 1)
      (and (== cs 6) (not (== n m)))
      (loop [sh 0]
        (let [x (bit-and (unsigned-bit-shift-right n sh) 0xFF)
              y (bit-and (unsigned-bit-shift-right m sh) 0xFF)]
          (if (== x y) (recur (unchecked-add sh 8)) (if (< x y) -1 1))))
      :else
      ;; equal lengths: walk k's chars, encoding each, against the stored bytes
      (let [km (.length k)]
        (loop [i 0 j off]
          (if (< i km)
            (let [ch (int (.charAt k i))]
              (cond
                (< ch 0x80) (cmp-byte b j ch (recur (unchecked-inc i) (unchecked-inc j)))
                (< ch 0x800)
                (cmp-byte b j (bit-or 0xC0 (bit-shift-right ch 6))
                          (cmp-byte b (unchecked-inc j) (bit-or 0x80 (bit-and ch 0x3F))
                                    (recur (unchecked-inc i) (unchecked-add j 2))))
                (and (>= ch 0xD800) (<= ch 0xDFFF))
                (let [lo (if (< (unchecked-inc i) km) (int (.charAt k (unchecked-inc i))) 0)]
                  (if (and (<= ch 0xDBFF) (>= lo 0xDC00) (<= lo 0xDFFF))
                    (let [cp (+ 0x10000 (bit-shift-left (- ch 0xD800) 10) (- lo 0xDC00))]
                      (cmp-byte b j (bit-or 0xF0 (bit-shift-right cp 18))
                                (cmp-byte b (unchecked-inc j) (bit-or 0x80 (bit-and (bit-shift-right cp 12) 0x3F))
                                          (cmp-byte b (unchecked-add j 2) (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F))
                                                    (cmp-byte b (unchecked-add j 3) (bit-or 0x80 (bit-and cp 0x3F))
                                                              (recur (unchecked-add i 2) (unchecked-add j 4)))))))
                    (cmp-byte b j 0x3F (recur (unchecked-inc i) (unchecked-inc j)))))
                :else
                (cmp-byte b j (bit-or 0xE0 (bit-shift-right ch 12))
                          (cmp-byte b (unchecked-inc j) (bit-or 0x80 (bit-and (bit-shift-right ch 6) 0x3F))
                                    (cmp-byte b (unchecked-add j 2) (bit-or 0x80 (bit-and ch 0x3F))
                                              (recur (unchecked-inc i) (unchecked-add j 3)))))))
            0))))))

(defn- ascii-cmp
  "k (of exactly n chars) against the n stored bytes at off, when every char of
   k is ASCII (its UTF-8 bytes are its chars): -1 / 0 / 1 by the first differing
   byte; 2 when k has a char past ASCII (its UTF-8 length is then not n, and the
   caller takes the whole rule)."
  ^long [^bytes b ^long off ^long n ^String k]
  (loop [i 0 r 0]
    (if (< i n)
      (let [ch (int (.charAt k i))]
        (if (>= ch 0x80)
          2
          (if (zero? r)
            (let [s (bit-and (long (aget b (unchecked-int (unchecked-add off i)))) 0xFF)]
              (recur (unchecked-inc i) (if (== s ch) 0 (if (< s ch) -1 1))))
            (recur (unchecked-inc i) r))))
      r)))

(defn key-cmp
  "-1 / 0 / 1: the string key stored as the n UTF-8 bytes at [off, off+n) of c
   against host string k, in eve's key-byte order — the order of
   eve.codec/encode-key's bytes under unsigned lexicographic compare: a string
   below 256 UTF-8 bytes is [0x05][len u8][bytes], from 256 [0x06][len u32 LE]
   [bytes]. So: length class, then the length's encoded bytes (one byte; or
   four little-endian bytes compared lexicographically — low byte first, which
   is not numeric order past 256), then the bytes. k is encoded on the fly,
   never copied. A binary search's common cases answer early: k's char count km
   bounds its UTF-8 length m (km <= m <= 3 km), so a short stored key (n < 256)
   is below a k of more than n chars and above one of fewer than n/3; and a k of
   n ASCII chars is compared byte for byte in one pass."
  ^long [c ^long off ^long n k]
  (let [^String k k
        km (long (.length k))]
    (cond
      (and (< n 256) (> km n)) -1
      (and (< n 256) (< (* 3 km) n)) 1
      (== km n) (let [r (ascii-cmp c off n k)] (if (== r 2) (key-cmp-general c off n k) r))
      :else (key-cmp-general c off n k))))

;; ---------------------------------------------------------------------------
;; the writer — FORMAT-V1's children-first writer over a growable byte[]
;; Growth is the reference's exactly (start at 256 bytes; a write past the end
;; doubles, or grows to what a block needs if more), so buf-raw answers the
;; same array length the reference's does.
;; ---------------------------------------------------------------------------

(definterface IFlatBuf
  (^bytes raw [])
  (^long pos [])
  (seek [^long p])
  (u8 [^long v])
  (u16 [^long v])
  (u32 [^long v])
  (i64 [^long v])
  (f32 [^double v])
  (f64 [^double v])
  (varint [^long v])
  (putBytes [^bytes src ^long off ^long n])
  (off [^long v])
  (patchU32 [^long at ^long v])
  (align [^long a])
  (^bytes chunkOf [^long n]))

(deftype FlatBuf [^:unsynchronized-mutable ^bytes b
                  ^:unsynchronized-mutable ^long p
                  ^long width]
  IFlatBuf
  (raw [_] b)
  (pos [_] p)
  (seek [_ np] (set! p np) nil)
  (u8 [_ v]
    (when (>= p (alength b)) (set! b (Arrays/copyOf b (unchecked-multiply-int 2 (alength b)))))
    (aset b (unchecked-int p) (unchecked-byte v))
    (set! p (unchecked-inc p))
    nil)
  (u16 [_ v]
    (let [e (unchecked-add p 2)]
      (when (> e (alength b)) (set! b (Arrays/copyOf b (int (max e (* 2 (alength b)))))))
      (eve.flat_io.LE/p16 b p v)
      (set! p e)
      nil))
  (u32 [_ v]
    (let [e (unchecked-add p 4)]
      (when (> e (alength b)) (set! b (Arrays/copyOf b (int (max e (* 2 (alength b)))))))
      (eve.flat_io.LE/p32 b p v)
      (set! p e)
      nil))
  (i64 [_ v]
    (let [e (unchecked-add p 8)]
      (when (> e (alength b)) (set! b (Arrays/copyOf b (int (max e (* 2 (alength b)))))))
      (eve.flat_io.LE/p64 b p v)
      (set! p e)
      nil))
  (f32 [this v] (.u32 this (long (Float/floatToRawIntBits (float v)))))
  (f64 [this v] (.i64 this (Double/doubleToRawLongBits v)))
  (varint [this v]
    (loop [v v]
      (if (< v 128)
        (.u8 this v)
        (do (.u8 this (bit-or (bit-and v 127) 128)) (recur (unsigned-bit-shift-right v 7))))))
  (putBytes [_ src off n]
    (let [e (unchecked-add p n)]
      (when (> e (alength b)) (set! b (Arrays/copyOf b (int (max e (* 2 (alength b)))))))
      (System/arraycopy src (unchecked-int off) b (unchecked-int p) (unchecked-int n))
      (set! p e)
      nil))
  (off [this v] (if (== width 2) (.u16 this v) (.u32 this v)))
  (patchU32 [_ at v] (eve.flat_io.LE/p32 b at v) nil)
  (align [_ a]
    (let [r (rem p a)]
      (when-not (zero? r)
        (let [e (unchecked-add p (- a r))]
          (when (> e (alength b)) (set! b (Arrays/copyOf b (int (max e (* 2 (alength b)))))))
          ;; explicit zeros: a seek back can leave old bytes past p
          (Arrays/fill b (unchecked-int p) (unchecked-int e) (byte 0))
          (set! p e))))
    nil)
  (chunkOf [_ n] (Arrays/copyOf b (unchecked-int n))))

(defn buf
  "A growable writer; width 2 or 4, the chunk's offset width."
  [width]
  (FlatBuf. (byte-array 256) 0 (long width)))

(defn buf-pos ^long [w] (.pos ^IFlatBuf w))
(defn buf-seek [w ^long p] (.seek ^IFlatBuf w p))
(defn buf-u8 [w ^long v] (.u8 ^IFlatBuf w v))
(defn buf-u16 [w ^long v] (.u16 ^IFlatBuf w v))
(defn buf-u32 [w ^long v] (.u32 ^IFlatBuf w v))
(defn buf-i64 [w ^long v] (.i64 ^IFlatBuf w v))
(defn buf-f32
  "Not in the contract's list (FLAT-IO.md): the float bits of v as a u32 —
   what chunk.cljc's JVM column writer spells (buf-u32 (Float/floatToRawIntBits
   v)) and its cljs Buf's .f32 writes. Here so a writer can say it once."
  [w ^double v] (.f32 ^IFlatBuf w v))
(defn buf-f64 [w ^double v] (.f64 ^IFlatBuf w v))
(defn buf-varint [w ^long v] (.varint ^IFlatBuf w v))
(defn buf-bytes [w src ^long off ^long n] (.putBytes ^IFlatBuf w src off n))
(defn buf-off [w ^long v] (.off ^IFlatBuf w v))
(defn buf-patch-u32 [w ^long at ^long v] (.patchU32 ^IFlatBuf w at v))
(defn buf-align [w ^long a] (.align ^IFlatBuf w a))
(defn buf-raw ^bytes [w] (.raw ^IFlatBuf w))
(defn buf-chunk
  "The chunk of the writer's first n bytes: a copy of exactly n (zero-padded
   past the bytes written, as Arrays/copyOf)."
  ^bytes [w ^long n] (.chunkOf ^IFlatBuf w n))

;; ---------------------------------------------------------------------------
;; tuples and columns — a view over the chunk that IS the host's vector of its
;; elements. The elements are what chunk.cljc's COL reader (col-at) answers for
;; the kind, as flat-io/portable's tuple answers them: integers of every width
;; a long (a u64 past 2^63 its two's complement, as eve.flat.column reads kind
;; 11), f32 a java.lang.Float (so a vector of Floats packs and reads back = and
;; hash-equal — chunk.cljc's col-at answers the same), f64 a Double.
;; ---------------------------------------------------------------------------

(defn- kind-size ^long [^long kind]
  (case (int kind)
    (1 2 3) 1
    (4 5) 2
    (6 7 8) 4
    (9 10 11) 8
    (throw (ex-info "eve.flat-io: no tuple of this column kind" {:kind kind}))))

(defn- elem
  "Element i (no bounds check) of the kind-kind column at off of b."
  [^bytes b ^long off ^long kind ^long i]
  (case (int kind)
    1 (bit-and (long (aget b (unchecked-int (+ off i)))) 0xFF)
    2 (long (aget b (unchecked-int (+ off i))))
    3 (bit-and (long (aget b (unchecked-int (+ off i)))) 0xFF)
    4 (eve.flat_io.LE/i16 b (+ off (* 2 i)))
    5 (eve.flat_io.LE/u16 b (+ off (* 2 i)))
    6 (eve.flat_io.LE/i32 b (+ off (* 4 i)))
    7 (eve.flat_io.LE/u32 b (+ off (* 4 i)))
    8 (Float/valueOf (Float/intBitsToFloat (unchecked-int (eve.flat_io.LE/i32 b (+ off (* 4 i))))))
    9 (eve.flat_io.LE/f64 b (+ off (* 8 i)))
    10 (eve.flat_io.LE/i64 b (+ off (* 8 i)))
    11 (eve.flat_io.LE/i64 b (+ off (* 8 i)))))

(defn- tuple-vec
  "The tuple's elements as a persistent vector — what a write answers."
  ^IPersistentVector [^bytes b ^long off ^long n ^long kind]
  (let [a (object-array n)]
    (dotimes [i n] (aset a i (elem b off kind i)))
    (RT/vector a)))

(deftype FlatTuple [^bytes b ^long off ^long n ^long kind
                    ^:unsynchronized-mutable ^int _hasheq
                    ^:unsynchronized-mutable ^int _hash]
  Object
  (equals [this o]
    (cond
      (identical? this o) true
      (or (instance? IPersistentVector o) (instance? java.util.RandomAccess o))
      (and (== n (count o))
           (loop [i 0]
             (cond (== i n) true
                   (Util/equals (elem b off kind i) (nth o i)) (recur (unchecked-inc i))
                   :else false)))
      (or (instance? clojure.lang.Sequential o) (instance? java.util.List o))
      (Util/equals (seq this) (seq o))
      :else false))
  (hashCode [_]
    (if (zero? _hash)
      (let [h (loop [h (int 1) i 0]
                (if (== i n) h
                    (recur (unchecked-add-int (unchecked-multiply-int 31 h) (Util/hash (elem b off kind i)))
                           (unchecked-inc i))))]
        (set! _hash (int h)) h)
      _hash))
  (toString [this] (RT/printString this))

  clojure.lang.IHashEq
  (hasheq [_]
    (if (zero? _hasheq)
      (let [h (loop [h (int 1) i 0]
                (if (== i n) (Murmur3/mixCollHash h n)
                    (recur (unchecked-add-int (unchecked-multiply-int 31 h) (Util/hasheq (elem b off kind i)))
                           (unchecked-inc i))))]
        (set! _hasheq (int h)) h)
      _hasheq))

  clojure.lang.Counted
  (count [_] n)

  clojure.lang.IMeta
  (meta [_] nil)
  clojure.lang.IObj
  (withMeta [_ m] (.withMeta ^clojure.lang.IObj (tuple-vec b off n kind) m))

  clojure.lang.Indexed
  (nth [_ i]
    (if (and (>= i 0) (< i n)) (elem b off kind i) (throw (IndexOutOfBoundsException.))))
  (nth [_ i not-found]
    (if (and (>= i 0) (< i n)) (elem b off kind i) not-found))

  clojure.lang.IPersistentCollection
  (cons [_ v] (.cons ^IPersistentVector (tuple-vec b off n kind) v))
  (empty [_] [])
  (equiv [this o]
    (cond
      (identical? this o) true
      (or (instance? IPersistentVector o) (instance? java.util.RandomAccess o))
      (and (== n (count o))
           (loop [i 0]
             (cond (== i n) true
                   (Util/equiv (elem b off kind i) (nth o i)) (recur (unchecked-inc i))
                   :else false)))
      (or (instance? clojure.lang.Sequential o) (instance? java.util.List o))
      (Util/equiv (seq this) (seq o))
      :else false))

  clojure.lang.IPersistentStack
  (peek [_] (when (pos? n) (elem b off kind (dec n))))
  (pop [_] (.pop ^IPersistentVector (tuple-vec b off n kind)))

  clojure.lang.IPersistentVector
  (assocN [_ i v] (.assocN ^IPersistentVector (tuple-vec b off n kind) i v))
  (length [_] n)

  clojure.lang.Reversible
  (rseq [this] (when (pos? n) (APersistentVector$RSeq. this (dec n))))

  clojure.lang.Associative
  (assoc [_ k v] (.assoc ^IPersistentVector (tuple-vec b off n kind) k v))
  (containsKey [_ k]
    (and (Util/isInteger k) (let [i (.intValue ^Number k)] (and (>= i 0) (< i n)))))
  (entryAt [this k]
    (when (.containsKey this k)
      (MapEntry/create k (elem b off kind (.intValue ^Number k)))))

  clojure.lang.ILookup
  (valAt [this k] (.valAt this k nil))
  (valAt [_ k not-found]
    (if (Util/isInteger k)
      (let [i (.intValue ^Number k)] (if (and (>= i 0) (< i n)) (elem b off kind i) not-found))
      not-found))

  clojure.lang.IFn
  (invoke [this k]
    (if (Util/isInteger k)
      (.nth this (.intValue ^Number k))
      (throw (IllegalArgumentException. "Key must be integer"))))
  (applyTo [this args]
    (if (== 1 (RT/boundedLength args 1))
      (.invoke this (first args))
      (throw (clojure.lang.ArityException. (RT/boundedLength args 20) "FlatTuple"))))

  clojure.lang.Seqable
  (seq [_]
    (when (pos? n)
      (let [a (object-array n)]
        (dotimes [i n] (aset a i (elem b off kind i)))
        (RT/seq a))))

  clojure.lang.Sequential

  clojure.lang.IReduceInit
  (reduce [_ f init]
    (let [^IFn f f]
      (loop [i 0 acc init]
        (if (< i n)
          (let [acc (.invoke f acc (elem b off kind i))]
            (if (reduced? acc) @acc (recur (unchecked-inc i) acc)))
          acc))))
  clojure.lang.IReduce
  (reduce [_ f]
    (let [^IFn f f]
      (case n
        0 (.invoke f)
        (loop [i 1 acc (elem b off kind 0)]
          (if (< i n)
            (let [acc (.invoke f acc (elem b off kind i))]
              (if (reduced? acc) @acc (recur (unchecked-inc i) acc)))
            acc)))))

  clojure.lang.IKVReduce
  (kvreduce [_ f init]
    (let [^IFn f f]
      (loop [i 0 acc init]
        (if (< i n)
          (let [acc (.invoke f acc i (elem b off kind i))]
            (if (reduced? acc) @acc (recur (unchecked-inc i) acc)))
          acc))))

  java.lang.Comparable
  (compareTo [_this o]
    (let [^IPersistentVector v (cast IPersistentVector o) vn (.count v)]
      (cond (< n vn) -1
            (> n vn) 1
            :else (loop [i 0]
                    (if (== i n) 0
                        (let [c (Util/compare (elem b off kind i) (.nth v (int i)))]
                          (if (zero? c) (recur (unchecked-inc i)) c)))))))

  java.lang.Iterable
  (iterator [_this] (.iterator ^java.util.List (tuple-vec b off n kind)))

  java.util.RandomAccess
  java.util.Collection
  (contains [this o] (boolean (some #(= % o) this)))
  (containsAll [this c] (every? #(.contains this %) c))
  (isEmpty [_] (zero? n))
  (toArray [_this] (let [a (object-array n)] (dotimes [i n] (aset a i (elem b off kind i))) a))
  (^objects toArray [_this ^objects arr] (.toArray ^java.util.List (tuple-vec b off n kind) arr))
  (size [_] n)
  (add [_ _o] (throw (UnsupportedOperationException.)))
  (addAll [_ _c] (throw (UnsupportedOperationException.)))
  (clear [_] (throw (UnsupportedOperationException.)))
  (^boolean remove [_ _o] (throw (UnsupportedOperationException.)))
  (removeAll [_ _c] (throw (UnsupportedOperationException.)))
  (retainAll [_ _c] (throw (UnsupportedOperationException.)))

  java.util.List
  (get [this i] (.nth this i))
  (indexOf [_this o]
    (loop [i 0] (cond (== i n) -1 (= o (elem b off kind i)) i :else (recur (unchecked-inc i)))))
  (lastIndexOf [_this o]
    (loop [i (dec n)] (cond (neg? i) -1 (= o (elem b off kind i)) i :else (recur (dec i)))))
  (listIterator [_this] (.listIterator ^java.util.List (tuple-vec b off n kind)))
  (listIterator [_this i] (.listIterator ^java.util.List (tuple-vec b off n kind) (int i)))
  (subList [this a z] (subvec this a z))
  (add [_ _i _o] (throw (UnsupportedOperationException.)))
  (addAll [_ _i _c] (throw (UnsupportedOperationException.)))
  (^Object remove [_ ^int _i] (throw (UnsupportedOperationException.)))
  (set [_ _i _e] (throw (UnsupportedOperationException.))))

(defmethod print-method FlatTuple [t w]
  ((get-method print-method IPersistentVector) t w))

(defn tuple
  "The n elements of column kind kind (FORMAT-V1 §5: 1 u8 · 2 i8 · 3 u8c ·
   4 i16 · 5 u16 · 6 i32 · 7 u32 · 8 f32 · 9 f64 · 10 i64 · 11 u64) stored at
   data-off of chunk c, as a vector over the bytes: count, nth, get, seq,
   reduce, =, hash and print as the vector of its elements, with no copy. A
   write (assoc, conj, pop past the end, with-meta) answers a persistent
   vector."
  [c ^long data-off ^long n ^long kind]
  (kind-size kind)
  (FlatTuple. c data-off n kind 0 0))

(defn col
  "The column of n kind-kind elements at data-off of chunk c — the same view
   as tuple."
  [c ^long data-off ^long n ^long kind]
  (tuple c data-off n kind))
