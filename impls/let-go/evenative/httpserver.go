package evenative

// The http-server natives eve's let-go http-server adapter
// (eve/adapters/http-server/letgo) calls: Go's net/http serving an
// eve handler, the server half of what host.go's http-request does
// as a client. Stock let-go's own http/start was evaluated and
// cannot carry eve's response contract (its body coercion sends a
// sequence element's text, and the only bytes->string route in the
// adapter set sanitizes UTF-8 — a :body-as :b64 binary answer, the
// eve-media store's block route, would not survive byte-exact), so
// the request/response maps are built here, in Go, from raw bytes,
// exactly as the JDK adapter builds them from an HttpExchange.
//
// (eve.host-native/http-serve host port threads handler)
//   -> {:port <bound port> :stop <fn>}
// Binds host:port now (a bind failure is the call's error; port 0
// takes any free port and the answer's :port is the bound one),
// serves in the background. Each request is handled on its own
// goroutine (net/http's shape), with at most `threads` handlers in
// flight at once (a semaphore; threads <= 0 means no bound) — the
// JDK adapter's fixed pool, mapped onto goroutines. The handler is
// invoked on a Child of the root execution context, the per-goroutine
// isolation let-go's own spawn uses, and takes eve's request map
// {:method :url :headers :body :body-b64} (:url the request URI as
// sent, header names lower-case, first value) and answers
// {:status :headers :body :body-as} (:body-as :b64 sends the bytes
// its base64 :body names). A handler that errors answers 500 with
// the error's text, as the JDK adapter's catch does. :stop closes
// the server and its connections at once; the port is released
// when :stop returns.

import (
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"

	"github.com/nooga/let-go/pkg/vm"
)

// httpServeHandle runs handler for one net/http request.
func httpServeHandle(handler vm.Fn, sem chan struct{}, w http.ResponseWriter, r *http.Request) {
	if sem != nil {
		sem <- struct{}{}
		defer func() { <-sem }()
	}
	defer r.Body.Close()
	raw, err := io.ReadAll(r.Body)
	if err != nil {
		w.WriteHeader(500)
		_, _ = w.Write([]byte(err.Error()))
		return
	}
	hs := vm.Associative(vm.EmptyPersistentMap)
	for k, vs := range r.Header {
		if len(vs) > 0 {
			hs = hs.Assoc(vm.String(strings.ToLower(k)), vm.String(vs[0]))
		}
	}
	req := vm.EmptyPersistentMap.
		Assoc(kw("method"), vm.String(r.Method)).
		Assoc(kw("url"), vm.String(r.RequestURI)).
		Assoc(kw("headers"), hs).
		Assoc(kw("body"), vm.String(string(raw))).
		Assoc(kw("body-b64"), vm.String(base64.StdEncoding.EncodeToString(raw)))
	resp, err := vm.RootExecContext.Child().Invoke(handler, []vm.Value{req})
	if err != nil {
		w.WriteHeader(500)
		_, _ = w.Write([]byte(err.Error()))
		return
	}
	lk, ok := resp.(vm.Lookup)
	if !ok {
		w.WriteHeader(500)
		_, _ = w.Write([]byte("eve.http-server: handler did not answer a map"))
		return
	}
	status := 200
	if s := lk.ValueAt(kw("status")); s != vm.NIL {
		if n, ok := s.(vm.Int); ok {
			status = int(n)
		}
	}
	if hv := lk.ValueAt(kw("headers")); hv != vm.NIL {
		if sq, ok := hv.(vm.Sequable); ok {
			for s := sq.Seq(); s != nil && s != vm.EmptyList; s = s.Next() {
				e, ok := s.First().(vm.Sequable)
				if !ok {
					continue
				}
				es := e.Seq()
				if es == nil || es.Next() == nil {
					continue
				}
				k, v := es.First(), es.Next().First()
				ks := k.String()
				if kk, ok := k.(vm.Keyword); ok {
					ks = string(kk)
				} else if sk, ok := k.(vm.String); ok {
					ks = string(sk)
				}
				if strings.EqualFold(ks, "content-length") {
					continue // net/http computes it from the body written
				}
				vs := v.String()
				if sv, ok := v.(vm.String); ok {
					vs = string(sv)
				}
				w.Header().Set(ks, vs)
			}
		}
	}
	var out []byte
	bv := lk.ValueAt(kw("body"))
	switch {
	case bv == vm.NIL:
		out = nil
	case lk.ValueAt(kw("body-as")) == kw("b64"):
		s, _ := bv.(vm.String)
		out, err = base64.StdEncoding.DecodeString(string(s))
		if err != nil {
			w.WriteHeader(500)
			_, _ = w.Write([]byte("eve.http-server: :body-as :b64 with a non-base64 :body"))
			return
		}
	default:
		if s, ok := bv.(vm.String); ok {
			out = []byte(string(s))
		} else if b, err := anyBytes(bv); err == nil {
			out = b
		} else {
			out = []byte(bv.String())
		}
	}
	w.WriteHeader(status)
	if len(out) > 0 {
		_, _ = w.Write(out)
	}
}

// registerHTTPServer defines the http-server natives on host.go's
// eve.host-native namespace. It is called from host.go's init (one
// additive line): a second NewNamespace("eve.host-native") here
// would construct a distinct namespace object and split the
// registry, so the defs must land on host.go's own object.
func registerHTTPServer(ns *vm.Namespace) {
	def(ns, "http-serve", func(vs []vm.Value) (vm.Value, error) {
		if err := argc("http-serve", vs, 4); err != nil {
			return vm.NIL, err
		}
		host, err := strOf(vs[0], "host")
		if err != nil {
			return vm.NIL, err
		}
		port, err := intOf(vs[1], "port")
		if err != nil {
			return vm.NIL, err
		}
		threads, err := intOf(vs[2], "threads")
		if err != nil {
			return vm.NIL, err
		}
		handler, ok := vs[3].(vm.Fn)
		if !ok {
			return vm.NIL, fmt.Errorf("eve.host-native/http-serve: handler must be a function, got %s", vs[3].Type().Name())
		}
		ln, err := net.Listen("tcp", fmt.Sprintf("%s:%d", host, port))
		if err != nil {
			return vm.NIL, err
		}
		var sem chan struct{}
		if threads > 0 {
			sem = make(chan struct{}, threads)
		}
		srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			httpServeHandle(handler, sem, w, r)
		})}
		go func() { _ = srv.Serve(ln) }()
		stop, err := vm.NativeFnType.Wrap(func([]vm.Value) (vm.Value, error) {
			_ = srv.Close()
			return vm.NIL, nil
		})
		if err != nil {
			_ = srv.Close()
			return vm.NIL, err
		}
		bound := 0
		if ta, ok := ln.Addr().(*net.TCPAddr); ok {
			bound = ta.Port
		}
		return vm.EmptyPersistentMap.
			Assoc(kw("port"), vm.Int(bound)).
			Assoc(kw("stop"), stop), nil
	})
}
