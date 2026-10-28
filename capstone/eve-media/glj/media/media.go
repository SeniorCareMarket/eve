// Package media is eve-media's Go side, compiled into the glj eve-media's Go member runs on (glj/build.sh: glj-eve's
// build with this package added to glojure's package map): the media server — the outputs with range requests, the
// page, and the gateway's routes and blob store proxied — and what the folder watcher and the publish job need of Go.
// It decides nothing about the application: the member's glojure code (members/glj/src/eve_media/tasks.glj) calls it.
package media

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Serve starts the media server on addr (host:port; port 0 picks one) and answers the port it listens on, or -1.
// GET /media/<path> serves the file under root by http.ServeFile, which answers Range requests (206) as browsers and
// players ask; /gw/ and /blobs/ are proxied to upstream (the gateway: eve.net's routes under /gw, and its blob
// store), so the page reaches the network atom on its own origin; everything else is the page's directory.
func Serve(addr, root, page, upstream string) int64 {
	ln, err := net.Listen("tcp", addr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "media.Serve:", err)
		return -1
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/media/", func(w http.ResponseWriter, r *http.Request) {
		rel := filepath.Clean(strings.TrimPrefix(r.URL.Path, "/media/"))
		if strings.HasPrefix(rel, "..") {
			http.Error(w, "outside the media root", http.StatusForbidden)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		http.ServeFile(w, r, filepath.Join(root, rel))
	})
	if up, err := url.Parse(upstream); err == nil && upstream != "" {
		proxy := httputil.NewSingleHostReverseProxy(up)
		mux.Handle("/gw/", proxy)
		mux.Handle("/blobs/", proxy)
	}
	mux.Handle("/", http.FileServer(http.Dir(page)))
	// cross-origin isolated (COOP + COEP): eve on ClojureScript needs SharedArrayBuffer, which a page has only so
	isolated := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Cross-Origin-Opener-Policy", "same-origin")
		h.Set("Cross-Origin-Embedder-Policy", "require-corp")
		h.Set("Cross-Origin-Resource-Policy", "same-origin")
		mux.ServeHTTP(w, r)
	})
	go http.Serve(ln, isolated)
	return int64(ln.Addr().(*net.TCPAddr).Port)
}

// ListDir answers the regular files of dir, sorted (absolute paths), or nothing when it cannot be read.
func ListDir(dir string) []any {
	es, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	names := []string{}
	for _, e := range es {
		if e.Type().IsRegular() {
			names = append(names, filepath.Join(dir, e.Name()))
		}
	}
	sort.Strings(names)
	out := make([]any, len(names))
	for i, n := range names {
		out[i] = n
	}
	return out
}

// RangeCheck asks url for its first n bytes with a Range header: the status (206 when ranges are served) and the
// bytes received, or -1 and 0 when the request fails.
func RangeCheck(u string, n int64) (int64, int64) {
	req, err := http.NewRequest("GET", u, nil)
	if err != nil {
		return -1, 0
	}
	req.Header.Set("Range", fmt.Sprintf("bytes=0-%d", n-1))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return -1, 0
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return int64(resp.StatusCode), int64(len(b))
}
