# The ants demo — run it

Rich Hickey's ants, re-hosted on Eve's STM (the port is
`docs/stm-ants/ants.cljc`, written as close to the original as
possible): every cell is a ref in its own persistent Eve atom
domain, an ant's move is one STM transaction spanning two cell
refs, and the board is shared on disk by every runtime that
joins. This directory is the packaged front door: one script
that spawns the member runtimes, deals the board across whoever
can actually join, and audits the result.

## The one command

From the root of a fresh clone:

```sh
bb docs/stm-ants/demo/run.cljc
```

That runs the small board (8×8, the M2 shape): the launcher
probes the twelve hosts of the contention ORDER, starts the
members that can join on this machine, deals the world across
them, streams the driver's board renders and audits, and ends
with a verdict block. Exit code 0 means the world hydrated
fully and every audit held.

## The board

The board is the demo: a live ASCII picture of the shared world,
redrawn in place on a terminal (ANSI cursor-up + overwrite, one
frame per driver audit cycle — a stable picture that updates,
not a scrolling stream). In a pipe or a log file the same frames
scroll as plain text. The legend prints once, with the first
frame:

```
LEGEND ant by heading ↑↗→↘↓↙←↖ · ant carrying food ⇑⇗⇒⇘⇓⇙⇐⇖ (double arrows)
LEGEND home zone ⌂ · food · 1, : 2, + 3, # 4+ · pheromone ░ faint, ▒ fresh, ▓ busy trail, █ highway
```

Ants are arrows with a heading (double arrows are carrying food
home); the home zone is the ⌂ block; food piles read by quantity;
pheromone trails are gradient blocks that brighten with traffic.
The latest AUDIT line sits in a fixed status line under the
board. At 144×144 the in-place region is 145 lines — taller than
a normal terminal; the dim-144 presentation direction is a
scaled (aggregated) render, and until that lands the 144 run is
best watched through its audit stream and run-dir logs.

The launcher inherits your environment, and so does every
member it spawns: if your `clojure` needs a config (for example
`CLJ_CONFIG` pointing at a seeded local repo), export it before
running — the JVM probe and the driver both run your real
`clojure`, exactly as configured.

The 144×144 board (the ORDER's full contention shape — 20,736
cells, 12 column groups):

```sh
bb docs/stm-ants/demo/run.cljc --board 144
```

A full 144 run takes hours (the driver alone spends ~1–2 h
creating the cell domains). It prints its deal table up front —
12 group processes across the members that joined — and can be
stopped with Ctrl-C / SIGTERM; the launcher takes its child
processes down with it and marks the output as stopped, not a
verdict.

## Options (few, on purpose)

```
--board small|144   board size (default small = 8x8)
--dim N             explicit dimension (overrides --board)
--seconds N         audit window (default 90 small, 120 at 144)
--ants N            ants per member process (default 2 small, 4 at 144)
--world DIR         world root (default: a fresh dir inside the run dir)
--run DIR           run dir for logs + generated configs
                    (default ./ants-demo-<yyyymmdd-hhmmss>)
--members a,b,c     only these seats may join
--exclude a,b,c     treat these seats as unavailable
--gate SECONDS      144 only: the driver's expect-gate (default 7200)
--dry-run           probe + deal + print the spawn plan; spawn nothing
--root DIR          checkout root (default: detected from the script)
```

Seats: `jvm bb jolt nbb cljs browser cljr lpy glj jank clje vs`.

Try the plan before a run:

```sh
bb docs/stm-ants/demo/run.cljc --dry-run
```

Prove the fallback path — make a member that would join
unavailable, and watch its slice fall back:

```sh
bb docs/stm-ants/demo/run.cljc --exclude glj
```

## How membership works (elastic by design)

The member table names all twelve hosts from the ORDER. At
startup the launcher probes each one, Eve-media style: the
runtime must be found, and it must run `(+ 1 2)` → `3`. Three
more gates exist where the tree demands them:

- **nbb** must also pass the lane's own F-ANTS-2 discriminator
  (`hosts/nbb/probe_nbb_domains.cljc`: three persistent domains
  written 0/100/200 must read back distinct — the CLJS seat's
  domain-aliasing defect is still open, and a seat whose domains
  alias never joins). nbb is also spawned only *after* the
  driver reports the world built, because its main does not
  wait for the build the way the other hosts do.
- Seats with **no ant-host driver in the tree** (ClojureScript/
  Node, browser, jank, clojerl, the ValueScript seat — each
  blocked for its own named reason, per the ORDER) are named in
  every verdict and always fall back.
- The **JVM seat is the one hard requirement**: its driver is
  the only program that creates the world and runs the audit.
  If it cannot join, the launcher says so and exits 2 — every
  other seat falls back; the driver cannot.

Dealing: on the small board each joined member runs one process
and the audit's expect-total is recomputed from who joined —
never hardcoded. On the 144 board exactly 12 group processes
(one per 12-column group) are dealt across the joined members
as evenly as possible; a group is seeded by whichever process
draws its tag, so the board is fully seeded with any non-empty
set of joined members — down to the JVM alone. A member that
dies *mid-join* (after spawning, before its READY line) fails
the run loudly with the member named: its process tag may
already be claimed, so its slice cannot be honestly re-dealt
after the fact. Pre-join unavailability is what falls back;
mid-join death is a failure, never a silently partial world.

## Exit codes

| code | meaning |
|---|---|
| 0 | run completed; the driver's audits all held, final audit registry-exact |
| 1 | the run completed but an audit failed (see FINAL-AUDIT in the driver log) |
| 2 | setup abort: no checkout found, world dir already has content, or the JVM seat cannot join |
| 3 | a member died mid-join, missed the join deadline, or the run hit its absolute deadline |
| 130 | stopped by the operator (SIGINT/SIGTERM) — partial state, not a verdict |

## What it runs

The launcher is a Babashka script — that is Eve's own shell
layer: Eve's `eve.process` carries no spawn call on any seat,
and Eve's own multi-runtime programs (`eve/examples/
disk_domain_processes.clj`, `eve/capstone/eve-media/
launcher.clj`) are bb scripts over `babashka.process` /
`babashka.fs`, which is the pattern followed here. Every
member's classpath is composed from `eve/hosts.edn`, the tree's
own per-host adapter-roots table, plus `docs/stm-eve` (eve.stm)
and `docs/stm-ants` (the port); each member's `deps.edn` /
`ants-config.edn` is generated into the run dir at launch time
from the detected checkout root. **No absolute machine paths
ship in this demo** — the committed `hosts/*/deps.edn` files
with their `/home/hatch/...` paths are legacy launch configs
the launcher does not use.

One addition ships here: `jvm_host_main.clj`, the JVM seat's
ant-host program. The tree ships host mains for the other six
driver seats but never needed one for the JVM (it was always
the driver); elastic dealing at 144 can assign the JVM seat
extra group processes, so the demo carries a JVM host main
modeled line-for-line on `hosts/bb/ants_bb_main.clj`. The port
(`ants.cljc`) and all existing drivers are used as-is,
unmodified.

Per-run artifacts land in the run dir: `logs/` (the driver's
full render/audit log plus one stdout/stderr pair per member),
`members/` (each process's generated config/deps and working
dir), and `world/` (the shared world itself — one clock domain
plus one domain per cell).

**Disk use:** the demo writes a world to disk (about 10 GB at dim 60: one Eve domain per board cell). It refuses to start below `--min-free-gb` free space, aborts and cleans up below `--disk-floor-gb` (default 3 GB), and deletes the world when the run ends; pass `--keep-run` to keep it.
