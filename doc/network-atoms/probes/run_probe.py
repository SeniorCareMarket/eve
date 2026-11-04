#!/usr/bin/env python3
"""THROWAWAY PROBE driver (eve/doc/network-atoms/SCOPE.md §2): compiles coord_wire.vs once (vs compile, cached by
vs/test-hosts/hostlib.py) and runs it under vs-term --bridge against coord_standin.py in each dialect. One line per
dialect: `<dialect>: <the program's line>`. Expected with M=5: `client: final:10 …`; `do:` shows what the
atom-broker/services/atom-broker coordinator's field names do to the same client."""
import os
import sys

HERE = os.path.dirname(os.path.realpath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(REPO, "vs", "test-hosts"))
sys.path.insert(0, HERE)
import hostlib  # noqa: E402
import coord_standin  # noqa: E402

wasm = hostlib.compile_vs(os.path.join(HERE, "coord_wire.vs"))
for dialect in ("client", "do"):
    srv, port = coord_standin.start(dialect)
    h = hostlib.Proc([hostlib.VS_TERM, "--bridge", "--env", "BROKER_PORT=%d" % port, wasm])
    line = h.wait_line(300, 0)
    print("%s: %s" % (dialect, line if line is not None else "NO OUTPUT -- " + h.stderr_text()[-800:]), flush=True)
    h.stop()
    srv.shutdown()
print("DONE")
