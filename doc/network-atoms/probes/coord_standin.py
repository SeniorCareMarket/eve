#!/usr/bin/env python3
"""THROWAWAY PROBE (eve/doc/network-atoms/SCOPE.md §2). A stand-in atom.broker on 127.0.0.1, in one of two dialects,
driving probes/coord_wire.vs under the wasmtime bridge host (vs-term --bridge).

  --dialect do      the atom-broker/services/atom-broker coordinator, transcribed from the source at BASE 739d79cb1:
                      create  coordinator/atoms.vs:94-104   INSERT OR IGNORE (atom_id, epoch 0), root_key NULL
                      deref   coordinator/atoms.vs:8-39     {root_key root_blob … epoch lease_id}; 404 when no row
                      cas     coordinator/atoms.vs:41-92    reads body.root_key / body.new_root_key; equal -> UPDATE
                                                            root_key := new_root_key; else 409 {current_root_key …}
                    the worker forwards the client's body verbatim (handlers/data_plane.vs:35-45, atom_id set from
                    the path), so this server reads the body the client sent, as the DO would.
  --dialect client  the dialect vs.net.coordinator speaks (coordinator.vs:243-278): old_key / new_key, 409
                    {current_key …} — the dialect of test/net/local_server.js and vs/test/vs/tl_fixtures.vs.

Usage: python3 coord_standin.py --dialect do|client  (prints nothing; run by run_probe.py)"""
import json
import sys
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def make_handler(dialect):
    state = {"row": None, "epoch": 0}
    lock = threading.Lock()

    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        def reply(self, status, obj):
            body = json.dumps(obj).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            n = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(n) or b"{}")
            op = self.path.rsplit("/", 1)[-1]
            with lock:
                row = state["row"]
                if op == "create":
                    if row is None:
                        state["row"] = {"root_key": None, "root_blob": None, "epoch": 0}
                    return self.reply(200, {"status": "ok"})
                if op == "deref":
                    if row is None:
                        return self.reply(404, {"error": "atom not found"})
                    return self.reply(200, {"root_key": row["root_key"], "root_blob": row["root_blob"],
                                            "epoch": row["epoch"], "lease_id": str(uuid.uuid4())})
                if op == "cas-root":
                    if dialect == "do":
                        rk, nrk = body.get("root_key"), body.get("new_root_key")
                    else:
                        rk, nrk = body.get("old_key"), body.get("new_key")
                    rb = body.get("root_blob")
                    if row is None:
                        state["row"] = {"root_key": nrk, "root_blob": rb, "epoch": 1}
                        return self.reply(200, {"status": "created", "epoch": 1})
                    if rk == row["root_key"]:
                        row.update(root_key=nrk, root_blob=rb, epoch=row["epoch"] + 1)
                        return self.reply(200, {"status": "ok", "epoch": row["epoch"]})
                    if dialect == "do":
                        return self.reply(409, {"status": "conflict", "current_root_key": row["root_key"],
                                                "current_epoch": row["epoch"]})
                    return self.reply(409, {"status": "conflict", "current_key": row["root_key"],
                                            "current_epoch": row["epoch"]})
            self.reply(404, {"error": "no route"})

    return H


def start(dialect):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(dialect))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, srv.server_address[1]


if __name__ == "__main__":
    srv, port = start(sys.argv[sys.argv.index("--dialect") + 1])
    print(port, flush=True)
    threading.Event().wait()
