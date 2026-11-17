# cohost-media — eve 0.9's capstone

A local media workbench built from six host runtimes and a browser that share one eve disk domain. Point it at a folder and you get, in a browser: every media file with its metadata, thumbnails and a contact
sheet per video, web-ready H.264/AAC MP4s served with range requests, EBU R128 loudness per audio track, and
duplicate groups from perceptual hashes. New files dropped into the folder are picked up while it runs; a process
killed mid-job is started again and its job redone, visibly.

Each runtime does the part it is best at. Every job is a thread descriptor in the job atom's metadata (eve.thread's
pools and leases over an atom domain), and a member takes the jobs its caps can run: no runtime sends another work.
The coordinator is named, not elected — the JVM member (babashka's in a run without a JVM). One boot program
(stock Clojure on the JVM, `boot.cljc` — the program is `start.cljc`) starts every runtime as a pool and watches them itself: the JVM member
runs in the boot process on a cohost.thread host executor, the other runtimes are process-backed pools on their
native seats, and every member is handed its pool config — caps, pool sizes, options — as data; all of them load
the same namespaces (`src/cohost_media`), the one `cohost-media.tasks` included.

```
bb cohost-media setup                   # install what the run needs and this machine lacks (--hosts ..., --only ...)
bb cohost-media demo                    # ten generated files (ffmpeg lavfi), every runtime; exits when the index is complete
bb cohost-media scan ~/Videos           # a folder of your own
bb cohost-media verify                  # the done bar: four runs, compared (below)
bb cohost-media build                   # (re)build the Node member, the page and the Go binary; demo builds what is missing
```

Run from `eve/`. A run's directory is `~/.cohost-media/runs/<stamp>/` (`out/` the outputs, `d*` the disk domain, `logs/`
every process's log — `<rid>.log`, `<rid>.<n>.log` from its n-th start — `report.edn`,
`page.png` the page as the headless browser saw it); its window is `/dev/shm/cohost-media/<stamp>` (decoded PCM; the
system's temporary directory where there is no `/dev/shm`, as on macOS). Options: `--hosts bb,jvm,...` (any subset),
`--kill transcode` (kill -9 the member running a transcode, mid-run) or `--kill coordinator`, `--no-browser`, `--headless`, `--timeout ms`.

The page opens in a window of its own (Chromium or Chrome, a fresh profile) where the machine shows windows — macOS,
or Linux with `DISPLAY` or `WAYLAND_DISPLAY` set — and runs headless elsewhere or with `--headless`. `demo` and `scan`
keep a shown page up once the index is complete, its URL logged, until Enter ends the run; `verify` closes it with each
run. The page says what it is watching: what the application does, step by step; a card per process — its runtime and
language, how it reaches the atoms, its part, what it is running now and what it has run; a feed of every step as it
happens (a file found, planned, each job started and finished on which runtime's thread, a process started again);
and the files, each job coloured by its state and edged with the runtime that ran it.

### What it needs

The boot program checks first and names what is missing before anything starts (`verify` names the runs this machine can
make and the `--only` that runs them). `bb cohost-media setup` (with `--only` / `--hosts` as a run takes them), or
`--install` on `verify` / `demo` / `scan`, installs what it can: Homebrew's formulae and casks where there is a
Homebrew (macOS). The boot program itself runs on cohost (its `cohost.thread` executors): a cohost.cljc checkout
beside the eve clone, or `COHOST_HOME` naming one.

- **every run**: babashka, `ffmpeg` and `ffprobe` on `PATH`, the ffmpeg built with `libx264` (every transcode is H.264;
  Homebrew's and the distributions' packages are). Other encoders the demo prefers — `libvorbis`, `libmp3lame` — are
  used when the build has them and replaced by ffmpeg's own (Vorbis) when it does not.
- **the JVM member** (and the boot program, and the gateway in it): `java` and the `clojure` CLI. **Node**: `node` and `npm` (the first run builds
  eve's native addon: a C++ toolchain — Xcode's command-line tools on macOS). **Go member**: `go` (glj-cohost-media is
  built from source for this machine). **The page**: a Chromium or Chrome — Playwright's,
  `/Applications/Google Chrome.app`, `chromium` on `PATH`, or `EVE_CHROME`.
- **jolt**: a jolt that runs — Homebrew's (`brew install jolt-lang/jolt/jolt`, macOS arm64 14+), jolt's install script,
  or a source tree with its Chez Scheme (`JOLT_CHEZ`) — and FFmpeg's shared libraries of release 6.1, 7.x or 8.x
  (libavutil 58, 59 or 60: Homebrew's `ffmpeg`, a distribution's libavcodec/libavformat packages, or
  `EVE_FFMPEG_LIB=<dir>`); on Linux `libatomic.so.1`. Where jolt cannot run, a run without `--hosts` drops it and says
  so: the JVM transcodes with the ffmpeg CLI.

Every runtime runs on Linux and macOS. On macOS, where there is no futex, eve's waits poll (100 µs), as its Node addon
always has there; jolt's region takes libSystem's atomics there and libatomic's on Linux
(`adapters/region/jolt-ffi`).

## The processes

| process | runtime | role | caps → pools | why this runtime |
|---|---|---|---|---|
| the boot program | JVM (stock Clojure) | the run's only main (`boot.cljc`; the program is `start.cljc`): creates the domain and the window, writes the run's configuration and its children into the control atom, starts every runtime as a pool and watches them (the restart policy of `cohost-media.supervise`, recorded in the control atom as the supervisors recorded it), starts the gateway in its own process and the browser seat (a cohost `:browser` runtime executor) that serves the page, writes the library, checks the outputs | — (runs the JVM member's pool) | one mostly-vanilla-Clojure program boots the whole example |
| `jvm` | JVM | a member in the boot process, on a cohost.thread host executor: the coordinator (below); metadata (Apache Tika, metadata-extractor), perceptual hashes, EBU R128 loudness read in place from the window (eve.region), the duplicate grouping; the CLI transcoder and PCM decoder (`ffmpeg` by `ProcessBuilder`) when jolt is not in the run | `:meta :phash :transcode-cli :pcm-cli :loudness` → `:meta :phash :transcode :pcm :loudness` | maven's metadata libraries |
| `jolt` | Chez Scheme (jolt) | the native transcoder and PCM decoder: libavformat/libavcodec/libswscale/libswresample called through jolt's FFI (`src/cohost_media/av_jolt.cljc`; no binding library, FFmpeg 6.1, 7.x or 8.x) | `:transcode-native :pcm-native` → `:transcode :pcm` | native-library interop without JNI |
| `node` | Node (ClojureScript) | thumbnails and contact sheets (`sharp`), audio tags (`music-metadata`) | `:npm` → `:npm` | those libraries exist only on npm |
| `glj` | Go (glojure, `glj-cohost-media`) | the media server (Go's `net/http`: outputs with range requests, the page, the gateway proxied), the folder watcher, `:publish` (a range request against the server) | `:serve` → `:publish` | a static binary with `net/http` and goroutines |
| `bb` | babashka | the index | `:index` → `:index` | starts in milliseconds and shells out natively |
| `browser` | the browser (ClojureScript, Chromium) | a runtime of the data plane: joins the registry over the network rung and serves the `:browser` pool on two Web Workers of its own (eve.thread's browser adapter) — the waveform job, drawn from the loudness job's peaks; the page (reagami) renders the run live and re-queues jobs through that runtime | `:browser` → `:browser` | the frontend is where a waveform is drawn |
| `gateway` | JVM | eve.net.gateway over the disk domain, in the boot process, for the runtime that cannot map it (the browser) | — (runs no jobs) | the JDK's http server |

The boot program creates the domain and the window, writes the run's configuration and its children into the
control atom, starts the pools, and checks the outputs. With jolt in the set the
coordinator routes transcodes and PCM to the native caps (`cohost-media.core/needs`), and waits (bounded, 120 s) for every
launched member to join before planning, so a slow starter is not routed around.

ValueScript is not part of 0.9 (2026-10-20): the loudness pool the plan gave VS runs on the JVM with the same
descriptor and data shape — PCM in a window region, read in place.

## Data flow, through eve atoms

The disk domain (`d*` in the run directory) holds four atoms:

- `cohost-media/jobs` — the rendezvous atom. Its meta-atom (`eve.meta/cohost-media/jobs`) is eve.thread's metadata: the
  runtime registry (each member's caps), the pools, every job as a thread descriptor.
- `cohost-media/library` — `{:files {path entry}}`: each file's status, its jobs' ids, states, attempts and `:ran-on`,
  and its results.
- `cohost-media/index` — totals, loudness per file, the duplicate groups, `:complete?`.
- `cohost-media/control` — the run's notes: its configuration (the coordinator named), the children the boot
  program's watch starts and what became of each (`cohost-media.supervise`: its pid, its starts, its exits), the watch
  itself (as `:supervisor`, host `boot`), the
  restarts the coordinator asked for, the page's check, the stop.

1. The boot program writes `control :config` (ports, the watched folder, the run's api key, the coordinator) and
   `control :children`, and starts the pools: the gateway and the JVM member in its own process, the other members
   as processes on their native seats (babashka once the gateway is up); the browser is a cohost :browser runtime executor in the boot process.
2. The Go member's watcher writes every media file of the folder into the library as `:new` (one swap; later files as
   they appear). Without a Go member the boot program scans.
3. The coordinator plans each `:new` file's DAG — video: meta, phash, transcode, thumb, sheet after thumb, pcm,
   loudness after pcm, publish after transcode, and with a browser the waveform after loudness; audio and images
   their subsets — in one swap of the metadata per file (`eve.thread.meta/submit`: `:needs` the caps, `:after` the
   dependencies, `:retry :restart`).
4. Members pull from the pools they serve (`eve.thread.domain/claim!`), run the task, write the result into the
   library, complete the descriptor. A transcode writes a temporary file renamed when complete, so a restart is safe.
5. Each pass the coordinator mirrors descriptor states into the library, resubmits a failed, cancelled or lost job
   with its dependents (escalating it after three tries), and once every file is settled submits the index and, on its
   own queue, the duplicate grouping (`eve.thread.fold/fold` over the hash pairs). A job's descriptor can be gone
   before the coordinator mirrors it — eve.thread forgets a departed runtime's settled descriptors past a horizon, so
   a killed coordinator's jobs go — and is then read from the result the job recorded in the library. Everything the
   coordinator knows is in the domain, so when it is killed and started again it carries on where it stopped.
6. A process that dies is started again by the boot program's watch (its next start's log is `<rid>.<n>.log`); the JVM
   member's serve is submitted again on its executor. A member whose
   lease lapses while its process lives is asked for by the coordinator (control `:restarts`); the watch kills its
   process and starts it again. Its running job is adopted when its claim's lease expires and is claimed again
   (`:attempts 2`).
7. The browser is a runtime of its own: a worker the page starts joins the registry over the network rung, serves
   the `:browser` pool on two more workers, and the coordinator plans a `:waveform` job per file (after its loudness)
   into it. The page (reagami, the UI of cljs-thread's reagami-counter example) reads the library, the index and the
   control atom as network atoms once a second and renders the run; a re-queue button asks the browser's runtime to
   submit the job again (`eve.thread.meta/submit`, by `eve.thread.domain/swap-meta!`, so a starved swap takes a turn).
8. At the end the boot program sets `control :stop`: the members detach and exit, the watch kills what is left (the
   browser), the gateway stops and the executor shuts down.

## The network rung — who uses it, and its threat model

One runtime reaches the domain through a gateway (`src/cohost_media/serve.cljc`: eve.net.gateway over the disk domain,
served by the boot program in its own process, `src/cohost_media/gateway.cljc`, on eve.http-server's JDK adapter): the browser —
its runtime, its pool's workers and the page — through the browser seat's server on the page's origin, whose `:handler`
is the gateway in-process (`/gw/`, `/blobs/`).

Every other member joins the disk domain directly, babashka included (2026-11-15): its region swaps under a
FileChannel lock (the jvm-nio-lock adapter), which is atomic read-modify-write at file-lock prices. The gateway is
part of the boot process but no member's death takes the rung down (the kill tests kill members), and its request
threads serve the network runtime in parallel (on one event loop their requests queued behind each other, and their leases
lapsed). The network runtime holds a longer lease — the browser 60 s, the others 4 s — because a
network join or heartbeat is a write of seconds (a cold read of the job metadata is ~1,100 block GETs; the browser's
first heartbeat comes after its ticker worker has loaded the program and made that read), and eve.thread stretches
every member's lease to the longest while they are joined. The writer is whoever holds the run's api key, which the boot program mints per run and gives only to the page it serves; both
servers listen on 127.0.0.1. A network write lands by a compare of content keys, atomic against every disk writer; the
gateway refuses an upgrade's hello over the network (`eve.thread.net/hello-over-disk-only`). Blobs never pass through
the gateway: the page PUTs and GETs them at the presigned store (`/blobs/`, HMAC-checked; held in the serving
process's memory, nothing deleted).

## The done bar

`bb cohost-media verify` runs:

| run | processes | checks |
|---|---|---|
| full | every runtime, the browser, the boot program supervising | every output (below), every member in the registry and in some `:ran-on` (the browser's waveforms), the page's library and re-queue, a file dropped into the watched folder indexed; the boot program the supervisor |
| full-kill | the same without the browser, `--kill transcode` | jolt killed mid-transcode and started again by the watch; the killed transcode at `:attempts 2`, its MP4's SHA-256 equal to full's |
| coordinator-kill | no JVM member (babashka coordinates), `--kill coordinator` | the coordinator killed once a file is done and another planned, started again by the watch; the outputs equal full's |
| cli-transcode | the same without jolt | every output, transcoded by the ffmpeg CLI |

The outputs, in every run: every MP4 probes as H.264/AAC (AAC alone for audio files); every thumbnail is 320 px wide
and every contact sheet 640×270 (4×3 tiles); every file's integrated loudness is within 0.5 LU of ffmpeg's `ebur128`
on the same file; the duplicate groups are exactly the two seeded pairs (`f01 f02`, `f09 f10`); the index is complete.

## What the plan names that is not here

- The coordinator by election (the `:leader` lease): the coordinator is named (2026-10-22: "We don't want
  coordinator by election. That's not part of this demo."); eve.thread.domain's election stays in eve, unused here.
- The BEAM's per-job waiters on doorbells, and the JVM's in-order `pmap`, as driving strategies (plan §8.3): the
  coordinator runs the portable pass loop; the hashing runs as one descriptor per file in the `:phash` pool, not as a
  `pmap` over it. The duplicate grouping is a `fold` over the pool (eve.thread.fold).
- VS's loudness pool (re-homed to the JVM, above).
