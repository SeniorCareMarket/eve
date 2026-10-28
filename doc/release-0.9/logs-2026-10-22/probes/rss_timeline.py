"""rss_timeline.py <cmd...>: runs cmd, and every second prints the process tree's RSS (MB) and its thread count;
at the end the peak, the low after the peak, and the last sample. For nbb's per-isolate memory (doc/hosts/nbb.md)."""
import os, subprocess, sys, time
p = subprocess.Popen(sys.argv[1:]); page = os.sysconf('SC_PAGE_SIZE'); rows = []; t0 = time.time()
def tree(root):
    kids = {}; info = {}
    for d in os.listdir('/proc'):
        if not d.isdigit(): continue
        try:
            st = open(f'/proc/{d}/stat').read(); f = st[st.rindex(')') + 2:].split()
            kids.setdefault(int(f[1]), []).append(int(d)); info[int(d)] = (int(f[21]) * page, int(f[17]))
        except Exception: pass
    seen, stack, rss, thr = set(), [root], 0, 0
    while stack:
        x = stack.pop()
        if x in seen or x not in info: continue
        seen.add(x); rss += info[x][0]; thr += info[x][1]; stack.extend(kids.get(x, []))
    return rss >> 20, thr
while p.poll() is None:
    r, t = tree(p.pid); rows.append((round(time.time() - t0), r, t)); print('rss-timeline', *rows[-1], flush=True); time.sleep(1)
if rows:
    pk = max(range(len(rows)), key=lambda i: rows[i][1])
    print('rss-timeline peak', rows[pk], 'low after peak', min(rows[pk:], key=lambda x: x[1]), 'last', rows[-1], 'exit', p.returncode)
sys.exit(p.returncode)
