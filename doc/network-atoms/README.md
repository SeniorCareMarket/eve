# Network atoms

A **network atom** is an eve value kept at a broker or a gateway and read and written over HTTP. It has the same
`deref` / `swap!` / `reset!` semantics on every host that has an http adapter. This page is the user guide. The other
files in this directory are the design record (index at the end).

Both samples below were run as written on 2026-10-20 ET on babashka 1.13.226 and on the JVM (JDK 21.0.12, Clojure
1.12.0), against this tree; the first again on the final tree, 2026-10-21 ET, on both, with the same output.

## What it is

- **The value is a map.** A `swap!` whose function returns anything else throws `:not-a-map`
  (`src/eve/net/atom.cljc`).
- **Every operation is synchronous, on every host.** `deref` and `swap!` answer the value, never a promise. The
  host's http adapter blocks the caller; on Node that is a worker plus `Atomics.wait` (`adapters/http/node`).
- **The handle is a map, not an atom.** `@` does not apply. Use `eve.net.atom/deref`, `swap!`, `reset!`, `cached`.
- **`swap!` is a CAS.** f's value is encoded against the handle's last record of the value, and only the changed
  paths are imaged and uploaded. A conflict (HTTP 409) backs off at 10 ms × 2^attempt, capped at 2 s, with jitter, and
  retries, up to 50 attempts (`MAX-RETRIES`). A policy denial (403) throws `:policy-denied`.
- **A deref holds a lease** at the broker. `release!` gives it up, and `renew!` renews it once it is 40 s old
  (`LEASE-RENEW-AFTER-MS`).
- **Blobs go direct.** Every block GET, HEAD and PUT goes to a URL the broker presigns, into an object store (any
  S3-compatible store in production). The broker and gateway exchange small JSON only, and there is no `/blob/<sha>` route. A fetched
  blob is checked against its key (its SHA-256) before it is trusted.

### Config

```clojure
{:endpoint  "https://broker.example"   ; the broker's or gateway's base URL
 :domain-id "d1"
 :atom-id   "app%2Fcounter"            ; an atom's registered name, each / written %2F (eve.net.gateway/atom-id)
 :api-key   "..."                      ; sent as Authorization: Bearer <api-key>
 :gak       group-key}                 ; optional: 32 bytes; with it every blob is stored encrypted
```

`(eve.net.atom/remote-atom cfg)` runs over the host's platform, `(eve.net.platform/platform)`: its http adapter plus
`eve.process`'s clock and sleep. `(remote-atom cfg platform)` takes a platform map `{:request :sleep :now-ms :seed}`
of your own (tests, or a router as in the sample below).

## A network atom in one process

A disk domain served by a gateway, an object store in memory, and a client, all in one process with no sockets.
The gateway (`eve.net.gateway`) lets any member of a disk domain serve that domain's atoms on the broker's routes. A
network write is **one eve swap** of the atom, comparing content keys, so network writers are atomic against disk
writers.

```clojure
(require '[clojure.string :as str]
         '[eve.atom :as ea]
         '[eve.net.atom :as na]
         '[eve.net.gateway :as gw]
         '[eve.process :as proc])

(def base (first *command-line-args*))   ; a fresh directory

;; The object store: blobs behind presigned URLs, each request's signature checked. In production this is
;; an S3-compatible store; here it is a map in memory.
(defn mem-store [secret]
  (let [blobs (atom {})]
    (fn [{:keys [method url body]}]
      (let [[_ k q] (re-find #"/blobs/([^?]+)\?(.*)" url)
            p (into {} (map #(vec (str/split % #"=" 2))) (str/split q #"&"))
            mode ({"PUT" "write" "GET" "read" "HEAD" "head"} method)
            ok? (and mode (= mode (p "mode"))
                     (gw/hmac-ok? secret k mode (parse-long (p "exp")) (p "sig") (quot (proc/now-ms) 1000)))]
        (cond (not ok?)        {:status 403 :headers {} :body ""}
              (= method "PUT") (do (swap! blobs assoc k body) {:status 200 :headers {} :body ""})
              (= method "HEAD") {:status (if (contains? @blobs k) 200 404) :headers {} :body ""}
              :else            (if-let [b (get @blobs k)]
                                 {:status 200 :headers {} :body b}
                                 {:status 404 :headers {} :body ""}))))))

(def store (mem-store "store-secret"))

;; A gateway: this process serves its disk domain's atoms on eve.net's broker routes.
(def d (ea/persistent-atom-domain (str base "/db")))
(def counter (ea/lookup-or-create-mmap-atom! d "app/counter" {:n 0}))
(def g (gw/gateway d {:endpoint "http://gw.test" :domain-id "d1" :api-key "k"
                      :presign (gw/hmac-presigner {:url "http://store.test/blobs/" :secret "store-secret"
                                                   :now-ms proc/now-ms})
                      :platform {:request store}}))

;; A client's platform: requests to the gateway reach its handler, the rest reach the store. Over a real network
;; this is the host's http adapter (eve.net.platform/platform) and the gateway is served by eve.http-server/serve!.
(def platform {:request (fn [req] (if (str/starts-with? (:url req) "http://gw.test") ((:handle g) req) (store req)))
               :sleep proc/sleep-ms! :now-ms proc/now-ms :seed 1})

(def ra (na/remote-atom {:endpoint "http://gw.test" :domain-id "d1" :atom-id (gw/atom-id "app/counter") :api-key "k"}
                        platform))

(println (na/deref ra))                    ; {:n 0}
(println (na/swap! ra update :n inc))      ; {:n 1}
(swap! counter update :n inc)              ; a disk writer of the same atom
(println (na/deref ra))                    ; {:n 2}
(println @counter)                         ; {:n 2}
(println (gw/stats g))
(ea/close-atom-domain! d)
```

Output, the same on babashka and the JVM [ran]:

```
{:n 0}
{:n 1}
{:n 2}
{:n 2}
{:requests 12, :commits 1, :conflicts 0, :by-atom {app/counter 4}}
```

`gw/hmac-presigner` stands in for an S3 store's SigV4 presigning. The store holds the secret and checks it with
`gw/hmac-ok?`; the client only ever sees URLs.

## Over sockets

The same parts, the store and the gateway each served by `eve.http-server/serve!` (the JVM's `http-server/jdk`,
babashka's `http-server/httpkit`), and a client on the host's own http adapter:

```clojure
(require '[clojure.string :as str]
         '[eve.atom :as ea]
         '[eve.net.atom :as na]
         '[eve.net.gateway :as gw]
         '[eve.net.platform]
         '[eve.process :as proc]
         '[eve.http-server :as http])

(def base (first *command-line-args*))   ; a fresh directory

;; The object store: blobs behind presigned URLs, each request's signature checked. In production this is
;; an S3-compatible store; here it is a map in memory.
(defn mem-store [secret]
  (let [blobs (atom {})]
    (fn [{:keys [method url body]}]
      (let [[_ k q] (re-find #"/blobs/([^?]+)\?(.*)" url)
            p (into {} (map #(vec (str/split % #"=" 2))) (str/split q #"&"))
            mode ({"PUT" "write" "GET" "read" "HEAD" "head"} method)
            ok? (and mode (= mode (p "mode"))
                     (gw/hmac-ok? secret k mode (parse-long (p "exp")) (p "sig") (quot (proc/now-ms) 1000)))]
        (cond (not ok?)        {:status 403 :headers {} :body ""}
              (= method "PUT") (do (swap! blobs assoc k body) {:status 200 :headers {} :body ""})
              (= method "HEAD") {:status (if (contains? @blobs k) 200 404) :headers {} :body ""}
              :else            (if-let [b (get @blobs k)]
                                 {:status 200 :headers {} :body b}
                                 {:status 404 :headers {} :body ""}))))))

(def store (mem-store "store-secret"))

;; The store and the gateway, each served over a socket on a free port
(def store-srv (http/serve! {:port 0} store))
(def store-url (str "http://127.0.0.1:" (:port store-srv) "/blobs/"))

(def d (ea/persistent-atom-domain (str base "/db")))
(def counter (ea/lookup-or-create-mmap-atom! d "app/counter" {:n 0}))
(def g (gw/gateway d {:endpoint "unused" :domain-id "d1" :api-key "k"
                      :presign (gw/hmac-presigner {:url store-url :secret "store-secret" :now-ms proc/now-ms})
                      :platform (eve.net.platform/platform)}))
(def gw-srv (http/serve! {:port 0} (:handle g)))

;; a client on the host's own http adapter (the default platform)
(def ra (na/remote-atom {:endpoint (str "http://127.0.0.1:" (:port gw-srv)) :domain-id "d1"
                         :atom-id (gw/atom-id "app/counter") :api-key "k"}))
(println (na/swap! ra update :n inc))      ; {:n 1}
(println @counter)                         ; {:n 1}
((:stop gw-srv)) ((:stop store-srv))
(ea/close-atom-domain! d)
```

It printed `{:n 1}` twice on both babashka and the JVM [ran]. (`http-server/jdk`'s `:stop` also shuts its request
thread pool down since `b20fe09a9`, so the JVM exits when the script ends.)

## What is encrypted, and what is not

- **Without `:gak`, nothing is encrypted.** The object store holds every block in plaintext.
- **With `:gak`**, every blob in the store is AES-256-CTR under a per-blob key, HKDF(gak, the first 16 bytes of the
  blob's key) (`src/eve/net/blobs.cljc:8-27`). The scheme is **deterministic**: equal plaintexts encrypt equally. A
  blob's address is the SHA-256 of its *plaintext*, so the store sees content hashes.
- **The root snapshot the broker receives is never encrypted.** Each swap sends it in the CAS body, and each deref
  answers it. It carries every top-level key and every top-level scalar value of the map in plaintext, and the content
  keys of the collections (`src/eve/net/snapshot.cljc`).
- **A gateway sees everything.** It is a member that holds the domain, not a passive broker.

[SECURITY.md](../../SECURITY.md) has the full statement.

## Which hosts can open one

A host needs an `http` root in its `hosts.edn` row:

| Has http | jvm (`http/java`), cljs and nbb (`http/node`), browser (`http/browser`), bb (`http/java`), cljr (`http/clr`), lpy (`http/python`), clje (`http/beam`), glj (`http/go`) |
|---|---|
| **No http** | jolt, ValueScript (vs) |
| **Can serve a gateway** (`http-server`) | jvm (`http-server/jdk`), cljs (`http-server/node`), bb (`http-server/httpkit`) |

Each host's crypto is its own crypto adapter, or the portable one (the browser, jolt, vs, glj). The portable code is the reference
every native adapter is held to; it is not constant-time (see SECURITY.md).

## The design record in this directory

These are history: the work's plans and reports, kept for their rationale. This page and the code are current.

| File | What it is |
|---|---|
| [WIRE.md](WIRE.md) | the wire protocol (routes, bodies, the snapshot and graft formats): the **current spec** |
| [ADAPTERS.md](ADAPTERS.md) | the network atom's host seams (http, crypto, process, encoding) and their measured costs |
