// eve-atomics-pod — a babashka pod (bb's own extension mechanism) that maps a file
// MAP_SHARED and performs hardware atomic RMW on it for the bb process that loaded it.
// HOST-CONTRACT §1.2/§2.1 for bb: the cross-process 32-bit atomics bb's own runtime cannot reach on a mapped
// file. Stdlib only (bencode by hand).
//
//   go build -o eve-atomics-pod .
//
// Vars (namespace pod.eve.atomics, format json): (open path size) -> handle,
// (load32 h off), (store32 h off v), (add32 h off delta) -> old, (cas32 h off e d) -> witness.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sort"
	"strconv"
	"sync/atomic"
	"syscall"
	"unsafe"
)

// ---------------------------------------------------------------- bencode
func decode(r *bufio.Reader) (interface{}, error) {
	b, err := r.ReadByte()
	if err != nil {
		return nil, err
	}
	switch {
	case b == 'd':
		m := map[string]interface{}{}
		for {
			p, err := r.Peek(1)
			if err != nil {
				return nil, err
			}
			if p[0] == 'e' {
				r.ReadByte()
				return m, nil
			}
			k, err := decode(r)
			if err != nil {
				return nil, err
			}
			v, err := decode(r)
			if err != nil {
				return nil, err
			}
			m[k.(string)] = v
		}
	case b == 'l':
		var l []interface{}
		for {
			p, err := r.Peek(1)
			if err != nil {
				return nil, err
			}
			if p[0] == 'e' {
				r.ReadByte()
				return l, nil
			}
			v, err := decode(r)
			if err != nil {
				return nil, err
			}
			l = append(l, v)
		}
	case b == 'i':
		s, err := r.ReadString('e')
		if err != nil {
			return nil, err
		}
		return strconv.ParseInt(s[:len(s)-1], 10, 64)
	case b >= '0' && b <= '9':
		s, err := r.ReadString(':')
		if err != nil {
			return nil, err
		}
		n, err := strconv.Atoi(string(b) + s[:len(s)-1])
		if err != nil {
			return nil, err
		}
		buf := make([]byte, n)
		if _, err := io.ReadFull(r, buf); err != nil {
			return nil, err
		}
		return string(buf), nil
	}
	return nil, fmt.Errorf("bencode: unexpected byte %q", b)
}

func encode(w *bufio.Writer, v interface{}) {
	switch x := v.(type) {
	case map[string]interface{}:
		w.WriteByte('d')
		keys := make([]string, 0, len(x))
		for k := range x {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			encode(w, k)
			encode(w, x[k])
		}
		w.WriteByte('e')
	case []interface{}:
		w.WriteByte('l')
		for _, e := range x {
			encode(w, e)
		}
		w.WriteByte('e')
	case string:
		w.WriteString(strconv.Itoa(len(x)) + ":" + x)
	case int64:
		w.WriteString("i" + strconv.FormatInt(x, 10) + "e")
	}
}

// ---------------------------------------------------------------- regions
var maps [][]byte

func word(h, off int64) (*int32, error) {
	if h < 0 || int(h) >= len(maps) {
		return nil, fmt.Errorf("bad handle %d", h)
	}
	m := maps[h]
	if off < 0 || off+4 > int64(len(m)) || off%4 != 0 {
		return nil, fmt.Errorf("bad offset %d (len %d, 4-aligned)", off, len(m))
	}
	return (*int32)(unsafe.Pointer(&m[off])), nil
}

func call(name string, a []int64, path string) (int64, error) {
	switch name {
	case "open": // grow, never truncate; MAP_SHARED
		f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE, 0o644)
		if err != nil {
			return 0, err
		}
		defer f.Close()
		st, err := f.Stat()
		if err != nil {
			return 0, err
		}
		if st.Size() < a[0] {
			if err := f.Truncate(a[0]); err != nil {
				return 0, err
			}
		}
		m, err := syscall.Mmap(int(f.Fd()), 0, int(a[0]), syscall.PROT_READ|syscall.PROT_WRITE, syscall.MAP_SHARED)
		if err != nil {
			return 0, err
		}
		maps = append(maps, m)
		return int64(len(maps) - 1), nil
	case "pid":
		return int64(os.Getpid()), nil
	}
	p, err := word(a[0], a[1])
	if err != nil {
		return 0, err
	}
	switch name {
	case "load32":
		return int64(atomic.LoadInt32(p)), nil
	case "store32":
		atomic.StoreInt32(p, int32(a[2]))
		return a[2], nil
	case "add32":
		return int64(atomic.AddInt32(p, int32(a[2])) - int32(a[2])), nil
	case "cas32": // returns the witness, like EVE's -cas-i32!
		for {
			w := atomic.LoadInt32(p)
			if w != int32(a[2]) {
				return int64(w), nil
			}
			if atomic.CompareAndSwapInt32(p, w, int32(a[3])) {
				return int64(w), nil
			}
		}
	}
	return 0, fmt.Errorf("unknown var %s", name)
}

func main() {
	r := bufio.NewReader(os.Stdin)
	w := bufio.NewWriter(os.Stdout)
	const ns = "pod.eve.atomics"
	for {
		msg, err := decode(r)
		if err != nil {
			return
		}
		m := msg.(map[string]interface{})
		switch m["op"] {
		case "describe":
			vars := []interface{}{}
			for _, n := range []string{"open", "pid", "load32", "store32", "add32", "cas32"} {
				vars = append(vars, map[string]interface{}{"name": n})
			}
			encode(w, map[string]interface{}{
				"format":     "json",
				"namespaces": []interface{}{map[string]interface{}{"name": ns, "vars": vars}},
				"ops":        map[string]interface{}{"shutdown": map[string]interface{}{}},
			})
		case "invoke":
			id, _ := m["id"].(string)
			v, _ := m["var"].(string)
			name := v[len(ns)+1:]
			var raw []interface{}
			json.Unmarshal([]byte(m["args"].(string)), &raw)
			var a []int64
			path := ""
			for _, x := range raw {
				switch y := x.(type) {
				case float64:
					a = append(a, int64(y))
				case string:
					path = y
				}
			}
			res, err := call(name, a, path)
			if err != nil {
				encode(w, map[string]interface{}{"id": id, "ex-message": err.Error(), "ex-data": "{}",
					"status": []interface{}{"done", "error"}})
			} else {
				encode(w, map[string]interface{}{"id": id, "value": strconv.FormatInt(res, 10),
					"status": []interface{}{"done"}})
			}
		case "shutdown":
			w.Flush()
			return
		}
		w.Flush()
	}
}
