#!/usr/bin/env python3
"""scripts/net-local-check.py — the end-to-end check of eve's local network (bb net:local), from eve/.

    python3 -I scripts/net-local-check.py [--hosts bb,jvm,nbb] [--port 8700] [--no-page] [--no-persist]

Starts `bb net:local --no-open` in a process group of its own and checks, one row a line:
  ready      the launcher printed {:step :ready ...}
  nodes      every node's /health answers 200 (broker, store, each thread server; the app host's / answers the page)
  page       (unless --no-page) the page in headless Chromium: every node green, a swap moves the epoch, the store
             logs the PUTs, a pool job runs on a thread server, no page errors
  stop       SIGINT to the launcher leaves no process of the run and frees every port
  persist    (unless --no-persist) with --dir D: a network atom written (bb net:probe write), the launcher stopped and
             started again on the same D, the atom read back by a fresh handle equal (bb net:probe read) — the broker's
             rows and the store's blobs (D/store) both survive the restart
Exit 0 only when every row passes. The run's logs are under target/net-local/logs.
"""
import json
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.request

EVE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHROME = os.environ.get("EVE_CHROME", "/opt/pw-browsers/chromium")


def arg(name, default):
    a = sys.argv[1:]
    return a[a.index(name) + 1] if name in a else default


def row(name, ok, detail=""):
    print(f"{name:6} {'PASS' if ok else 'FAIL'} {detail}".rstrip(), flush=True)
    return ok


def get(url, timeout=3):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001 — a row reports it
        return None, str(e)


def port_free(p):
    s = socket.socket()
    try:
        return s.connect_ex(("127.0.0.1", p)) != 0
    finally:
        s.close()


def edn_ready(line):
    """the fields of the launcher's {:step :ready ...} line this check reads"""
    import re
    out = {}
    for k in ("broker", "store", "app", "domain", "api-key"):
        m = re.search(r":%s \"([^\"]+)\"" % k, line)
        out[k] = m.group(1) if m else None
    m = re.search(r":threads \[([^\]]*)\]", line)
    out["threads"] = re.findall(r"\"([^\"]+)\"", m.group(1)) if m else []
    return out


def page_rows(app):
    """the page in headless Chromium (playwright): its own report, read from window.__evecheck"""
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        return row("page", False, "playwright is not installed for python3")
    with sync_playwright() as pw:
        exe = CHROME if os.path.isfile(CHROME) else None
        if exe is None and os.path.isdir(CHROME):
            for root, _, files in os.walk(CHROME):
                if "chrome" in files:
                    exe = os.path.join(root, "chrome")
                    break
        b = pw.chromium.launch(executable_path=exe) if exe else pw.chromium.launch()
        errors = []
        console = []
        pg = b.new_page()
        pg.on("pageerror", lambda e: errors.append(str(e)))
        pg.on("console", lambda m: console.append(m.text))
        seen = {}
        def note(q):
            k = q.method + " " + q.url.split("/")[2] + "/" + (q.url.split("/")[3] if len(q.url.split("/")) > 3 else "")
            seen[k] = seen.get(k, 0) + 1
        pg.on("request", note)
        pg.on("requestfailed", lambda q: console.append(f"request failed: {q.method} {q.url[:120]} {q.failure}"))
        pg.on("response", lambda r: console.append(f"HTTP {r.status} {r.request.method} {r.url[:120]}") if r.status >= 400 else None)
        pg.goto(app + "/?check=1")
        rep = None
        deadline = time.time() + int(os.environ.get("NET_LOCAL_PAGE_S", "300"))
        while time.time() < deadline:
            rep = pg.evaluate("window.__evecheck || null")
            if rep and rep.get("done"):
                break
            time.sleep(1)
        b.close()
    ok = bool(rep and rep.get("done"))
    if not ok:
        print("  requests| " + json.dumps(seen), flush=True)
        for line in console[:12] + ["..."] + console[-12:]:
            print("  console| " + line[:300], flush=True)
    rep = rep or {}
    good = True
    good &= row("green", ok and rep.get("all_green") is True, json.dumps(rep.get("nodes")))
    good &= row("swap", ok and rep.get("epoch_after", 0) > rep.get("epoch_before", 0),
                f"epoch {rep.get('epoch_before')} -> {rep.get('epoch_after')}")
    good &= row("job", ok and str(rep.get("ran_by", "")).startswith("t"), f"ran by {rep.get('ran_by')}")
    good &= row("errors", not errors and not rep.get("error"), "; ".join(errors + [rep.get("error") or ""]).strip("; "))
    return good


def launch(port, hosts, extra):
    """bb net:local in a process group of its own: (process, its ready fields or None)"""
    pr = subprocess.Popen(["bb", "net:local", "--no-open", "--port", str(port), "--hosts", hosts] + extra,
                          cwd=EVE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
    for line in pr.stdout:
        print("  | " + line.rstrip(), flush=True)
        if line.startswith("{:step :ready"):
            return pr, edn_ready(line)
    return pr, None


def halt(pr):
    """SIGINT to the launcher's group; SIGKILL if it is still there after 30 s"""
    try:
        os.killpg(pr.pid, signal.SIGINT)
        pr.wait(30)
    except ProcessLookupError:
        return
    except subprocess.TimeoutExpired:
        os.killpg(pr.pid, signal.SIGKILL)
        pr.wait(10)


def probe(op, ready):
    r = subprocess.run(["bb", "net:probe", op, ready["broker"], ready["domain"], ready["api-key"], "persist"],
                       cwd=EVE, capture_output=True, text=True, timeout=300)
    out = (r.stdout.strip().splitlines() or [""])[-1]
    return r.returncode == 0 and ":ok true" in out, out or r.stderr.strip()[-300:]


def persist_row(port, hosts):
    import shutil
    import tempfile
    d = tempfile.mkdtemp(prefix="net-local-persist-")
    try:
        pr, ready = launch(port, hosts, ["--no-build", "--dir", d])
        try:
            if ready is None:
                return row("persist", False, "the first run did not get ready")
            wrote, w = probe("write", ready)
        finally:
            halt(pr)
        files = sum(len(fs) for _, _, fs in os.walk(os.path.join(d, "store")))
        pr, ready = launch(port, hosts, ["--no-build", "--dir", d])
        try:
            if ready is None:
                return row("persist", False, "the restart did not get ready")
            read, r = probe("read", ready)
        finally:
            halt(pr)
        return row("persist", wrote and read and files > 0,
                   f"write {w}; {files} blob files under D/store; after the restart read {r}")
    finally:
        shutil.rmtree(d, ignore_errors=True)


def main():
    port = int(arg("--port", "8700"))
    hosts = arg("--hosts", "bb")
    pr = subprocess.Popen(["bb", "net:local", "--no-open", "--port", str(port), "--hosts", hosts]
                          + (["--no-build"] if "--no-page" in sys.argv else []),
                          cwd=EVE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, start_new_session=True)
    good = True
    ready = None
    t0 = time.time()
    try:
        for line in pr.stdout:
            print("  | " + line.rstrip(), flush=True)
            if line.startswith("{:step :ready"):
                ready = edn_ready(line)
                break
        if not row("ready", ready is not None, f"{time.time() - t0:.1f} s"):
            return 1
        nodes = [ready["broker"], ready["store"]] + ready["threads"]
        st = {u: get(u + "/health")[0] for u in nodes}
        s, body = get(ready["app"] + "/")
        good &= row("nodes", all(v == 200 for v in st.values()) and s == 200 and "<html" in body.lower(),
                    " ".join(f"{u.rsplit(':', 1)[1]}={v}" for u, v in st.items()) + f" page={s}")
        if "--no-page" not in sys.argv:
            puts0 = json.loads(get(ready["store"] + "/health")[1]).get("puts", 0)
            good &= page_rows(ready["app"])
            print("  broker| " + get(ready["broker"] + "/health")[1], flush=True)
            puts1 = json.loads(get(ready["store"] + "/health")[1]).get("puts", 0)
            good &= row("puts", puts1 > puts0, f"the store logged {puts1 - puts0} signed PUTs during the page's run")
    finally:
        try:
            os.killpg(pr.pid, signal.SIGINT)
        except ProcessLookupError:
            pass
        try:
            pr.wait(30)
        except subprocess.TimeoutExpired:
            pass
        deadline = time.time() + 20
        alive = True
        while time.time() < deadline:
            try:
                os.killpg(pr.pid, 0)
            except ProcessLookupError:
                alive = False
                break
            time.sleep(0.5)
        ports = [port, port + 1] + [port + 2 + i for i in range(len(ready["threads"]) if ready else 3)]
        free = [p for p in ports if port_free(p)]
        good &= row("stop", not alive and len(free) == len(ports),
                    f"group {'alive' if alive else 'gone'}, {len(free)}/{len(ports)} ports free")
        if alive:
            os.killpg(pr.pid, signal.SIGKILL)
    if ready is not None and "--no-persist" not in sys.argv:
        good &= persist_row(port, hosts)
    print(f"NET-LOCAL-CHECK hosts={hosts}: {'PASS' if good else 'FAIL'}", flush=True)
    return 0 if good else 1


if __name__ == "__main__":
    sys.exit(main())
