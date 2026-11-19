# Eve fix plan — what eve.db.ds needs from Eve

Written Nov 19 2026 for John. Companion: `DS-IN-EVE-PLAN.md` (same folder).
The DS rewrite does not start a unit that depends on a row below until that
row is green in a tagged Eve pin.

## 0. Measured state (JVM, `release/eve-0.9.3.3` tip `f4a1c3a`)

Probe: an in-process `eve/atom` holding `{:db (->Box …)}` where `Box` is an
`eve/deftype` built inside `swap!`.

| # | Call | Result today |
|---|---|---|
| E1 | `@a` | throws `flat chunk: a value held by reference cannot be packed` |
| E2 | `add-watch` then `swap!` | same throw |
| E3 | `compare-and-set!` | same throw |
| E4 | `reset!` with a deftype built outside | same throw |
| E5 | `($ (:db @a))` | returns a live `Box` pointing into the atom, unpinned |
| E6 | two reads of one nested deftype | `identical?` false, `=` false |
| E7 | old root kept in the atom beside the new one | both readable (works) |
| E8 | `swap!` return value | slab-backed, unsafe outside |

Not measured: ClojureScript in a browser; a disk (`:persistent`) atom; what
happens to an Eve value built outside every `swap!` and never stored.

## 1. Step 1 — find the existing fix before writing one

John believes an agent already implemented flat packing for deftype values.
It is not on `release/eve-0.9.3.3`. `SeniorCareMarket/eve` has no branch
named for it (only `collate/eve-db-ds-gaps-07644be`, `rc/eve-0.9.3.2-ds-gaps`
matched a search of branch names). Unverified lead: the eve.db.ds `deps.edn`
comment names `atom.broker` branch `lane-m/eve-bb-delivery-fix`.

1. In `johnmn3/atom.broker` and `SeniorCareMarket/eve`, list every branch and
   run `git log --all --oneline -S"held by reference cannot be packed"`.
2. For each hit, run the probe in §4 on that branch. Record branch, commit,
   and the E1–E8 results in this file.
3. If a branch turns E1 green: merge it into the release line (John's word
   for the push), then go to §3 for whatever is still red.
4. If none does: build §2.

## 2. The fixes, in order

F1. **Flat packing for `eve/deftype` values (fixes E1–E4).** In
`src/eve/flat/` the packer refuses content held by reference. A deftype
instance is not a reference: it is a type id plus a value array of fields.
Pack it as: type tag, the type's registered name, field count, each field
packed recursively. Unpack through the type registry (`eve.type-readers`)
into a flat object of the same type that answers the type's protocols.
Fns and host objects stay refused. Done when E1–E4 return values, the
delivered value answers the deftype's protocol methods after later swaps,
and an unknown type name at unpack throws a named error.

F2. **`$` must not hand out live deftype objects (fixes E5).** On region
exit the result is walked; any deftype in it is delivered by F1's packer, the
same as deref. Done when E5 returns a flat object that still reads correctly
after 1,000 further swaps on the atom.

F3. **Block identity in alpha (answers E6).** Add `eve/same?` to
`eve.alpha`: true when two handles name the same block of the same domain
(the logic already in narcis `eve.text.store/same-node?`). Document that `=`
on a deftype is whatever the type defines. Done when
`($ (eve/same? (:db @a) (:db @a)))` is true and false for distinct blocks.

F4. **Document E8 where users see it**, and state in `doc/api-guide.md`
which calls work on an atom holding deftypes after F1.

F5. **Lifetime of values built outside every atom.** Measure it: build
1,000,000 small deftype values outside any `swap!`, watch used bytes. If the
process slab never gives them back, say so in `doc/memory-model.md` and add
the alpha call that releases them, or state that such values must be built
in an atom. DS unit U3 depends on this answer.

## 3. Hosts

Every fix lands green on the JVM **and** on ClojureScript in a browser
(`clojure -M:browser-test` per `deps.edn`). A fix green on one is not done.

## 4. The probe (commit it as `test/eve/deftype_atom_laws_test.cljc`)

One `deftest` per row E1–E8, written against `eve.alpha` only, asserting the
fixed behavior. It is red today on E1–E6. It runs on the JVM and in the
browser. The DS repo copies this file as its first commit.

## 5. Rules

One branch per fix off the release line. Each commit message carries the
probe's before/after rows. No fix is called done from one host. Report the
row counts, not prose.
