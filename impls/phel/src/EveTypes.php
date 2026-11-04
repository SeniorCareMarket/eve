<?php

declare(strict_types=1);

// EVE's value types for phel, a start: the route a port would ship. Each class
// implements the PHP interface phel 0.34.1's core fns test with `instanceof`
// (PersistentMapInterface, PersistentVectorInterface, PersistentHashSetInterface,
// PersistentListInterface); the atom is duck-typed, because phel's deref /
// reset! call ->deref() / ->set() on anything and Phel\Lang\Variable is final.
//
// Values live in an FFI region of int32 cells (cell 0 = count, then the payload)
// and are read from it on every call; nothing is cached in PHP arrays. Writes
// copy the cells into a new region (a toy path-copy). Every interface method
// appends "<Class>.<method>" to Log, which shows which method each core fn
// reached. items() and dump() are unlogged readers.

namespace Eve;

use ArrayIterator;
use FFI;
use FFI\CData;
use LogicException;
use Phel\Lang\AbstractType;
use Phel\Lang\Collections\HashSet\PersistentHashSetInterface;
use Phel\Lang\Collections\LinkedList\PersistentListInterface;
use Phel\Lang\Collections\Map\PersistentMapInterface;
use Phel\Lang\Collections\Vector\PersistentVectorInterface;
use Phel\Lang\MetaTrait;
use Traversable;

final class Log
{
    /** @var list<string> */
    public static array $calls = [];

    public static function hit(string $m): void
    {
        self::$calls[] = $m;
    }

    /** @return list<string> */
    public static function take(): array
    {
        $c = self::$calls;
        self::$calls = [];
        return $c;
    }
}

/** A region of int32 cells in FFI memory (FFI::new zero-fills). */
final class Slab
{
    private static ?FFI $la = null;

    public CData $c;

    private function __construct(int $cells)
    {
        $this->c = FFI::new("int32_t[$cells]");
    }

    /** @param list<int> $payload */
    public static function of(array $payload): self
    {
        $s = new self(count($payload) + 1);
        $s->c[0] = count($payload);
        foreach ($payload as $i => $v) {
            $s->c[$i + 1] = $v;
        }
        return $s;
    }

    public static function cell(int $v): self
    {
        return self::of([$v]);
    }

    public function n(): int
    {
        return $this->c[0];
    }

    /** @return list<int> payload cells, read from the region */
    public function cells(int $width = 1): array
    {
        $out = [];
        $len = $this->n() * $width;
        for ($i = 1; $i <= $len; $i++) {
            $out[] = $this->c[$i];
        }
        return $out;
    }

    public function at(int $i): int
    {
        return $this->c[1 + $i];
    }

    /** libatomic.so.1 (lock xadd / lock cmpxchg on x86-64), as in p4_ffi.phel */
    public static function atomics(): FFI
    {
        return self::$la ??= FFI::cdef(
            'int __atomic_load_4(void *p, int mo);
             void __atomic_store_4(void *p, int v, int mo);
             _Bool __atomic_compare_exchange_4(void *p, void *expected, int desired, _Bool weak, int smo, int fmo);',
            'libatomic.so.1'
        );
    }

    /** int32_t* to one cell (indexing a scalar CData array yields a PHP int, not a CData) */
    public function addr(int $cell): CData
    {
        return FFI::cast('int32_t*', FFI::addr($this->c)) + $cell;
    }
}

abstract class EveColl extends AbstractType
{
    use MetaTrait;

    final public function __construct(public readonly Slab $s) {}

    /** @param list<int> $xs */
    public static function of(array $xs): static
    {
        return new static(Slab::of(array_values($xs)));
    }

    protected function log(string $m): void
    {
        $short = substr(static::class, strrpos(static::class, '\\') + 1);
        Log::hit("$short.$m");
    }

    /** @return list<int> the payload, unlogged */
    public function items(): array
    {
        return $this->s->cells();
    }

    public function dump(): string
    {
        $short = substr(static::class, strrpos(static::class, '\\') + 1);
        return $short . '[' . implode(' ', $this->items()) . ']';
    }

    public function count(): int
    {
        $this->log('count');
        return $this->s->n();
    }

    public function equals(mixed $other): bool
    {
        $this->log('equals');
        return $other instanceof static && $other->items() === $this->items();
    }

    public function hash(): int
    {
        $this->log('hash');
        return crc32(static::class . ':' . implode(',', $this->items()));
    }

    public function asTransient()
    {
        $this->log('asTransient');
        throw new LogicException('eve: no transient');
    }

    public function concat($xs)
    {
        $this->log('concat');
        $out = $this->items();
        foreach ($xs as $x) {
            $out[] = $x;
        }
        return static::of($out);
    }

    public function getIterator(): Traversable
    {
        $this->log('getIterator');
        return new ArrayIterator($this->items());
    }

    public function __invoke(mixed ...$args): mixed
    {
        $this->log('__invoke');
        return null;
    }
}

/** Sequential cells [n, e0, e1, ...]: shared by the vector and the list. */
abstract class EveSeqColl extends EveColl
{
    public function first()
    {
        $this->log('first');
        return $this->s->n() > 0 ? $this->s->at(0) : null;
    }

    public function cdr()
    {
        $this->log('cdr');
        return $this->s->n() > 1 ? static::of(array_slice($this->items(), 1)) : null;
    }

    public function rest()
    {
        $this->log('rest');
        return static::of(array_slice($this->items(), 1));
    }

    public function toArray(): array
    {
        $this->log('toArray');
        return $this->items();
    }

    public function get(int $i)
    {
        $this->log('get');
        return ($i >= 0 && $i < $this->s->n()) ? $this->s->at($i) : null;
    }

    public function offsetExists(mixed $offset): bool
    {
        $this->log('offsetExists');
        return is_int($offset) && $offset >= 0 && $offset < $this->s->n();
    }

    public function offsetGet(mixed $offset): mixed
    {
        $this->log('offsetGet');
        return (is_int($offset) && $offset >= 0 && $offset < $this->s->n()) ? $this->s->at($offset) : null;
    }

    public function offsetSet(mixed $offset, mixed $value): void
    {
        $this->log('offsetSet');
        throw new LogicException('eve: immutable');
    }

    public function offsetUnset(mixed $offset): void
    {
        $this->log('offsetUnset');
        throw new LogicException('eve: immutable');
    }

    public function contains(mixed $key): bool
    {
        $this->log('contains');
        return is_int($key) && $key >= 0 && $key < $this->s->n();
    }

}

final class EveVec extends EveSeqColl implements PersistentVectorInterface
{
    public function append(mixed $value): self
    {
        $this->log('append');
        return self::of([...$this->items(), $value]);
    }

    public function update(int $i, mixed $value): self
    {
        $this->log('update');
        $xs = $this->items();
        $xs[$i] = $value;
        return self::of($xs);
    }

    public function cons(mixed $x)
    {
        $this->log('cons');
        return self::of([$x, ...$this->items()]);
    }

    public function push(mixed $x)
    {
        $this->log('push');
        return self::of([...$this->items(), $x]);
    }

    public function slice(int $offset = 0, ?int $length = null)
    {
        $this->log('slice');
        return self::of(array_slice($this->items(), $offset, $length));
    }

    public function pop(): self
    {
        $this->log('pop');
        return self::of(array_slice($this->items(), 0, -1));
    }
}

final class EveList extends EveSeqColl implements PersistentListInterface
{
    public function prepend(mixed $value): self
    {
        $this->log('prepend');
        return self::of([$value, ...$this->items()]);
    }

    public function cons(mixed $x)
    {
        $this->log('cons');
        return self::of([$x, ...$this->items()]);
    }

    public function pop(): self
    {
        $this->log('pop');
        return self::of(array_slice($this->items(), 0, -1));
    }
}

final class EveSet extends EveColl implements PersistentHashSetInterface
{
    public function add(mixed $value): self
    {
        $this->log('add');
        $xs = $this->items();
        return in_array($value, $xs, true) ? $this : self::of([...$xs, $value]);
    }

    public function remove(mixed $value): self
    {
        $this->log('remove');
        return self::of(array_values(array_filter($this->items(), fn ($x) => $x !== $value)));
    }

    public function contains(mixed $key): bool
    {
        $this->log('contains');
        return in_array($key, $this->items(), true);
    }

    public function toPhpArray(): array
    {
        $this->log('toPhpArray');
        return $this->items();
    }
}

/** Cells [n, k0, v0, k1, v1, ...]; int keys and values. */
final class EveMap extends EveColl implements PersistentMapInterface
{
    /** @param list<int> $kvs flat k v k v ... */
    public static function of(array $kvs): static
    {
        $s = Slab::of(array_values($kvs));
        $s->c[0] = intdiv(count($kvs), 2);
        return new self($s);
    }

    /** @return array<int,int> k => v, read from the region, unlogged */
    public function pairs(): array
    {
        $out = [];
        $cells = $this->s->cells(2);
        for ($i = 0; $i < count($cells); $i += 2) {
            $out[$cells[$i]] = $cells[$i + 1];
        }
        return $out;
    }

    public function items(): array
    {
        return $this->s->cells(2);
    }

    public function lookup(mixed $key): ?int
    {
        return is_int($key) ? ($this->pairs()[$key] ?? null) : null;
    }

    /** @param array<int,int> $kv */
    private static function fromPairs(array $kv): self
    {
        $flat = [];
        foreach ($kv as $k => $v) {
            $flat[] = $k;
            $flat[] = $v;
        }
        return self::of($flat);
    }

    public function put(mixed $key, mixed $value): self
    {
        $this->log('put');
        $kv = $this->pairs();
        $kv[$key] = $value;
        return self::fromPairs($kv);
    }

    public function remove(mixed $key): self
    {
        $this->log('remove');
        $kv = $this->pairs();
        unset($kv[$key]);
        return self::fromPairs($kv);
    }

    public function find(mixed $key)
    {
        $this->log('find');
        return $this->lookup($key);
    }

    public function merge(PersistentMapInterface $other): self
    {
        $this->log('merge');
        $kv = $this->pairs();
        foreach ($other as $k => $v) {
            $kv[$k] = $v;
        }
        return self::fromPairs($kv);
    }

    public function contains(mixed $key): bool
    {
        $this->log('contains');
        return $this->lookup($key) !== null;
    }

    public function offsetExists(mixed $offset): bool
    {
        $this->log('offsetExists');
        return $this->lookup($offset) !== null;
    }

    public function offsetGet(mixed $offset): mixed
    {
        $this->log('offsetGet');
        return $this->lookup($offset);
    }

    public function offsetSet(mixed $offset, mixed $value): void
    {
        $this->log('offsetSet');
        throw new LogicException('eve: immutable');
    }

    public function offsetUnset(mixed $offset): void
    {
        $this->log('offsetUnset');
        throw new LogicException('eve: immutable');
    }

    public function getIterator(): Traversable
    {
        $this->log('getIterator');
        return new ArrayIterator($this->pairs());
    }

    public function dump(): string
    {
        $parts = [];
        foreach ($this->pairs() as $k => $v) {
            $parts[] = "$k $v";
        }
        return 'EveMap{' . implode(', ', $parts) . '}';
    }
}

/**
 * The atom: one int32 cell; deref / set / compareAndSet go through libatomic
 * (__atomic_load_4 / __atomic_store_4 / __atomic_compare_exchange_4).
 */
final class EveAtom
{
    public readonly Slab $s;

    public function __construct(int $v)
    {
        $this->s = Slab::cell($v);
    }

    public function deref()
    {
        Log::hit('EveAtom.deref');
        return Slab::atomics()->__atomic_load_4($this->s->addr(1), 5);
    }

    public function set(mixed $value): mixed
    {
        Log::hit('EveAtom.set');
        Slab::atomics()->__atomic_store_4($this->s->addr(1), $value, 5);
        return $value;
    }

    /** CAS; returns the witness (the value seen), as HOST-CONTRACT §2.1 asks. */
    public function compareAndSet(int $old, int $new): int
    {
        Log::hit('EveAtom.compareAndSet');
        $e = FFI::new('int32_t');
        $e->cdata = $old;
        Slab::atomics()->__atomic_compare_exchange_4($this->s->addr(1), FFI::addr($e), $new, false, 5, 5);
        return $e->cdata;
    }

    public function dump(): string
    {
        return 'EveAtom<' . $this->s->c[1] . '>';
    }
}
