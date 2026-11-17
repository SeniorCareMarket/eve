package staticprobe;

import java.util.Arrays;

/**
 * The relocating block copier of the static-Eve research, written tight (research code, not product code): every
 * block a value reaches in a heap slab domain, copied once into one byte array, each pointer rewritten to a position
 * in that array; with the block table {offset, size} and the relocation table {field position, owner, target}.
 * Plain collections only (map, set, vector, list, eve array, value blocks) — the kinds eve.retire reads; block sizes
 * are derived from each block's own bytes. The Clojure copier in lib.clj does the same for every kind, slowly.
 */
public final class ImageCopier {
  private final byte[][] reg;      // per class 0..6: the heap bytes of its region
  private final int[] dataOff;     // per class: where its blocks start
  private final int[] blk;         // per class: block size (1 for class 6: the index is a byte offset)

  public byte[] img = new byte[1 << 20];
  public int len = 8;
  public int[] boff = new int[4096], bsz = new int[4096];
  public int nb = 0;
  public int[] rpos = new int[4096], rown = new int[4096], rtgt = new int[4096];
  public int nr = 0;

  private long[] keys = new long[1 << 12];
  private int[] vals = new int[1 << 12];
  private int nkeys = 0;
  private long[] st = new long[7 * 256];
  private int sp = 0;
  private long[] tp = new long[5 * 64];   // the pointers of the block being read: [srcPos kind p1 p2 p3]
  private int ntp = 0;

  public ImageCopier(byte[][] reg, int[] dataOff, int[] blk) {
    this.reg = reg; this.dataOff = dataOff; this.blk = blk;
  }

  static int i32(byte[] a, int i) {
    return (a[i] & 0xFF) | ((a[i + 1] & 0xFF) << 8) | ((a[i + 2] & 0xFF) << 16) | ((a[i + 3] & 0xFF) << 24);
  }
  static void put(byte[] a, int i, int v) {
    a[i] = (byte) v; a[i + 1] = (byte) (v >> 8); a[i + 2] = (byte) (v >> 16); a[i + 3] = (byte) (v >> 24);
  }
  static boolean ptrTag(int tag) {
    return (tag >= 0x10 && tag <= 0x13) || (tag >= 0x1C && tag <= 0x1F) || (tag >= 0x40 && tag <= 0xEC);
  }
  static boolean inlinePtr(byte[] a, int p) {
    int w = i32(a, p);
    return (w & 0xFFFF) == 0xDBEE && ptrTag((w >>> 16) & 0xFF);
  }
  private static int slot(long k, int m) {
    long h = k * -7046029254386353131L;
    return (int) ((h ^ (h >>> 32)) & m);
  }
  private int lookup(long k) {
    int m = keys.length - 1;
    for (int i = slot(k, m); ; i = (i + 1) & m) {
      long x = keys[i];
      if (x == 0) return -1;
      if (x == k) return vals[i];
    }
  }
  private void insert(long k, int v) {
    if (2 * (nkeys + 1) > keys.length) {
      long[] ok = keys; int[] ov = vals;
      keys = new long[ok.length * 2]; vals = new int[ok.length * 2];
      int m = keys.length - 1;
      for (int j = 0; j < ok.length; j++) {
        long x = ok[j];
        if (x != 0) { int i = slot(x, m); while (keys[i] != 0) i = (i + 1) & m; keys[i] = x; vals[i] = ov[j]; }
      }
    }
    int m = keys.length - 1;
    int i = slot(k, m);
    while (keys[i] != 0) i = (i + 1) & m;
    keys[i] = k; vals[i] = v; nkeys++;
  }
  private void push(long old, long kind, long p1, long p2, long p3, long patch, long owner) {
    if (sp + 7 > st.length) st = Arrays.copyOf(st, st.length * 2);
    st[sp] = old; st[sp + 1] = kind; st[sp + 2] = p1; st[sp + 3] = p2; st[sp + 4] = p3; st[sp + 5] = patch; st[sp + 6] = owner;
    sp += 7;
  }
  private void ptr(int srcPos, int kind, long p1, long p2, long p3) {
    if (ntp + 5 > tp.length) tp = Arrays.copyOf(tp, tp.length * 2);
    tp[ntp] = srcPos; tp[ntp + 1] = kind; tp[ntp + 2] = p1; tp[ntp + 3] = p2; tp[ntp + 4] = p3;
    ntp += 5;
  }
  private void rel(int pos, int own, int tgt) {
    if (nr == rpos.length) {
      rpos = Arrays.copyOf(rpos, nr * 2); rown = Arrays.copyOf(rown, nr * 2); rtgt = Arrays.copyOf(rtgt, nr * 2);
    }
    rpos[nr] = pos; rown[nr] = own; rtgt[nr] = tgt; nr++;
  }
  private static final int[] ESIZE = {0, 1, 1, 1, 2, 2, 4, 4, 4, 8};

  /** The pointers of the block at a[base..] of the given kind into tp; its byte size. */
  private int read(byte[] a, int base, int kind, long p1, long p2, long p3, int ci) {
    ntp = 0;
    switch (kind) {
      case 0: {
        int t = a[base] & 0xFF;
        switch (t) {
          case 0xED: { if (i32(a, base + 8) != -1) ptr(base + 8, 1, 0, 0, 0); return 12; }
          case 0xEE: { if (i32(a, base + 8) != -1) ptr(base + 8, 2, 0, 0, 0); return 12; }
          case 0x12: {
            int cnt = i32(a, base + 4), sh = i32(a, base + 8), root = i32(a, base + 12), tail = i32(a, base + 16), tl = i32(a, base + 20);
            int toff = cnt < 32 ? 0 : ((cnt - 1) >>> 5) << 5;
            if (toff > 0 && root != -1) ptr(base + 12, 5, sh, 0, toff);
            if (tail != -1) ptr(base + 16, 6, tl, 0, 0);
            return 24;
          }
          case 0x13: { if (i32(a, base + 8) != -1) ptr(base + 8, 4, 0, 0, 0); return 12; }
          case 0x1D: return 8 + i32(a, base + 4) * ESIZE[a[base + 1] & 0xFF];
          case 0x01: { if (ci < 6) return blk[ci]; throw new UnsupportedOperationException("a scalar block in the overflow class"); }
          default: throw new UnsupportedOperationException("ImageCopier: block type " + t + " (plain collections only)");
        }
      }
      case 1: {
        int t = a[base] & 0xFF;
        int pos, n;
        if (t == 1) {
          int dbm = i32(a, base + 4), nbm = i32(a, base + 8);
          int nc = Integer.bitCount(nbm);
          n = Integer.bitCount(dbm);
          for (int i = 0; i < nc; i++) { int p = base + 12 + 4 * i; if (i32(a, p) != -1) ptr(p, 1, 0, 0, 0); }
          pos = base + 12 + 4 * nc + 4 * n;
        } else { n = a[base + 1] & 0xFF; pos = base + 8; }
        for (int j = 0; j < n; j++) {
          int klen = i32(a, pos), vpos = pos + 4 + klen, vlen = i32(a, vpos);
          if (vlen == 7 && inlinePtr(a, vpos + 4)) ptr(vpos + 7, 0, 0, 0, 0);
          pos = vpos + 4 + vlen;
        }
        return pos - base;
      }
      case 2: {
        int t = a[base] & 0xFF;
        int pos, n;
        if (t == 1) {
          int dbm = i32(a, base + 4), nbm = i32(a, base + 8);
          int nc = Integer.bitCount(nbm);
          n = Integer.bitCount(dbm);
          for (int i = 0; i < nc; i++) { int p = base + 12 + 4 * i; if (i32(a, p) != -1) ptr(p, 2, 0, 0, 0); }
          pos = base + 12 + 4 * nc;
        } else { n = a[base + 1] & 0xFF; pos = base + 8; }
        for (int j = 0; j < n; j++) pos += 4 + i32(a, pos);
        return pos - base;
      }
      case 3: {
        int n = i32(a, base);
        if (n == 7 && inlinePtr(a, base + 4)) ptr(base + 7, 0, 0, 0, 0);
        return 4 + n;
      }
      case 4: {
        int vlen = i32(a, base + 4);
        if (i32(a, base) != -1) ptr(base, 4, 0, 0, 0);
        if (vlen == 7 && inlinePtr(a, base + 8)) ptr(base + 11, 0, 0, 0, 0);
        return 8 + vlen;
      }
      case 5: {
        int sh = (int) p1; long b0 = p2, toff = p3;
        long span = toff - b0;
        long w = 1L << sh;
        int n = span <= 0 ? 0 : (int) Math.min(32, (span + w - 1) / w);
        for (int i = 0; i < n; i++) {
          int p = base + 4 * i;
          if (i32(a, p) != -1) { if (sh == 0) ptr(p, 3, 0, 0, 0); else ptr(p, 5, sh - 5, b0 + i * w, toff); }
        }
        return 128;
      }
      case 6: {
        int n = (int) p1;
        for (int i = 0; i < n; i++) { int p = base + 4 * i; if (i32(a, p) != -1) ptr(p, 3, 0, 0, 0); }
        return 128;
      }
      default: throw new IllegalStateException();
    }
  }

  /** Copy everything the value at root reaches; the root's position in the image. */
  public int run(int root) {
    push(root & 0xFFFFFFFFL, 0, 0, 0, 0, -1, -1);
    while (sp > 0) {
      sp -= 7;
      long old = st[sp];
      int kind = (int) st[sp + 1];
      long p1 = st[sp + 2], p2 = st[sp + 3], p3 = st[sp + 4];
      int patch = (int) st[sp + 5], owner = (int) st[sp + 6];
      long key = old + 1;
      int ord0 = lookup(key);
      if (ord0 >= 0) { put(img, patch, boff[ord0]); rel(patch, owner, ord0); continue; }
      int ci = (int) (old >>> 29);
      int bi = (int) (old & 0x1FFFFFFFL);
      byte[] a = reg[ci];
      int base = dataOff[ci] + bi * blk[ci];
      int size = read(a, base, kind, p1, p2, p3, ci);
      int nbase = len;
      int end = nbase + ((size + 3) & ~3);
      if (end > img.length) img = Arrays.copyOf(img, Math.max(end, img.length * 2));
      System.arraycopy(a, base, img, nbase, size);
      len = end;
      if (nb == boff.length) { boff = Arrays.copyOf(boff, nb * 2); bsz = Arrays.copyOf(bsz, nb * 2); }
      int ord = nb++;
      boff[ord] = nbase; bsz[ord] = size;
      insert(key, ord);
      if (patch >= 0) { put(img, patch, nbase); rel(patch, owner, ord); }
      for (int j = 0; j < ntp; j += 5) {
        int srcPos = (int) tp[j];
        long child = i32(a, srcPos) & 0xFFFFFFFFL;
        push(child, tp[j + 1], tp[j + 2], tp[j + 3], tp[j + 4], nbase + (srcPos - base), ord);
      }
    }
    return boff[0];
  }
}
