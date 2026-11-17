STATUS: PARTIAL

# R1 — numbers and what they measure (adversarial review)

Reviewer lens: every figure in MEASUREMENTS.md against `doc/static/probes/*.out.txt`; every derived figure recomputed;
probe sources read for what each arm really times. Trees: eve `4a92129` + doc/static; atom.broker `163cb656c`.

| # | doc | claim | verdict | evidence | fix | severity |
|---|---|---|---|---|---|---|
| 1 | MEASUREMENTS.md M4 table (50k records, lines 112-121) | Host row "22.90 / 16.21 / 0.052 / 59.4"; Transit "214.5 ms, 4.03 us/row"; flat codec "104.8, 2.20"; packed 4 KiB "9.0 / 0.054 / 0.85 / 18.29 / 90.67 / 24.01 / 0.985 / 6.27 / 151.5" ... one chunk "... 49.3" | refuted (not in the raw output) | `probes/p4_access.out.txt:4-14` has host `scan sum 23.34`, `filter count 17.86`, `point row 0.054`; `transit-msgpack 226.3 ms (4.18 us/row)`, `eve flat codec 109.7 ms (2.18 us/row)`; 4K `5.7 / 0.053 0.82 / 20.30 92.41 / 23.75 / 0.844 6.39 / 155.8`; one `7.5 / 42.040 0.84 / 1.29 1.45 / 2.62 / 0.595 8.39 / 68.2`. About 60 of the table's 66 cells differ from the committed output; the 8K row (`12.9 / 0.120 / 3.08 50.06 / 4.79 / 0.782 8.34 / 144.4`) is absent from the table. | Re-transcribe the table from `p4_access.out.txt` (or commit the output of the run the table came from and say which run it is). | high |
| 2 | MEASUREMENTS.md M5 line 149-151 | "the run claim is 5–12× the per-block path net of the domain" | refuted (arithmetic) | `p3_transform.out.txt:11-13,40-42,69-71,98-100,127-129`: (2.22−0.80)/(1.25−0.80)=3.2; (27.69−2.12)/(4.92−2.12)=9.1; (9.72−1.55)/(2.49−1.55)=8.7; (8.21−1.51)/(2.38−1.51)=7.7; (23.16−3.14)/(5.29−3.14)=9.3. Net range 3.2–9.3×; gross 1.8–5.6×. No row reaches 12×. | "3–9× net of the domain (1.8–5.6× including it)". | medium |
