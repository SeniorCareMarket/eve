"""Native FORMAT-V1 writer for basilisp (eve.flat-io :lpy).

The portable Buf deftype wrote each byte through a basilisp protocol
method plus two volatiles. Packing a 1,000-entry map on that path is
~1.8 s (to-rep ~200 ms; scan+build-chunk+recipe digest the rest). This
class keeps the same little-endian layout and returns signed array('b')
chunks so the rest of the seam is unchanged.
"""
from __future__ import annotations

import array
import struct


class Buf:
    __slots__ = ("b", "p", "width")

    def __init__(self, width):
        self.b = bytearray(256)
        self.p = 0
        self.width = int(width)

    def _need(self, n):
        p = self.p
        cap = len(self.b)
        if p + n <= cap:
            return
        new = max(cap * 2, p + n)
        nb = bytearray(new)
        nb[:cap] = self.b
        self.b = nb

    def raw(self):
        a = array.array("b")
        a.frombytes(self.b)
        return a

    def pos(self):
        return self.p

    def seek(self, np):
        self.p = int(np)

    def u8(self, v):
        self._need(1)
        self.b[self.p] = int(v) & 0xFF
        self.p += 1

    def u16(self, v):
        self._need(2)
        v = int(v) & 0xFFFF
        p = self.p
        b = self.b
        b[p] = v & 0xFF
        b[p + 1] = (v >> 8) & 0xFF
        self.p = p + 2

    def u32(self, v):
        self._need(4)
        v = int(v) & 0xFFFFFFFF
        p = self.p
        b = self.b
        b[p] = v & 0xFF
        b[p + 1] = (v >> 8) & 0xFF
        b[p + 2] = (v >> 16) & 0xFF
        b[p + 3] = (v >> 24) & 0xFF
        self.p = p + 4

    def i64(self, v):
        self._need(8)
        struct.pack_into("<Q", self.b, self.p, int(v) & 0xFFFFFFFFFFFFFFFF)
        self.p += 8

    def f64(self, v):
        self._need(8)
        struct.pack_into("<d", self.b, self.p, float(v))
        self.p += 8

    def varint(self, v):
        v = int(v)
        if v < 0:
            v &= 0xFFFFFFFFFFFFFFFF
        while v >= 128:
            self.u8((v & 127) | 128)
            v >>= 7
        self.u8(v)

    def bytes(self, src, off, n):
        off = int(off)
        n = int(n)
        self._need(n)
        if isinstance(src, (bytes, bytearray, memoryview)):
            sl = src[off:off + n]
        elif isinstance(src, array.array):
            sl = src.tobytes()[off:off + n]
        else:
            sl = bytes(src[off:off + n])
        self.b[self.p:self.p + n] = sl
        self.p += n

    def off(self, v):
        if self.width == 2:
            self.u16(v)
        else:
            self.u32(v)

    def patch_u32(self, at, v):
        at = int(at)
        v = int(v) & 0xFFFFFFFF
        b = self.b
        b[at] = v & 0xFF
        b[at + 1] = (v >> 8) & 0xFF
        b[at + 2] = (v >> 16) & 0xFF
        b[at + 3] = (v >> 24) & 0xFF

    def chunk(self, n):
        n = int(n)
        a = array.array("b")
        a.frombytes(self.b[:n])
        return a
