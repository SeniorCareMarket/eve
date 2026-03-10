#!/usr/bin/env node
// fill-atom.js — Fast atom builder using Node.js (12x faster swaps than JVM).
// Creates a persistent atom and fills it with rich values using individual assoc.
//
// Usage: node bench/fill-atom.js <base-path> <target-mb>
// Requires: bench-worker compiled (npx shadow-cljs compile bench-worker)
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const basePath = process.argv[2];
const targetMB = parseInt(process.argv[3], 10);

if (!basePath || !targetMB) {
  console.error('Usage: node bench/fill-atom.js <base-path> <target-mb>');
  process.exit(1);
}

const targetBytes = targetMB * 1024 * 1024;
const batchSize = 2000;
const benchWorker = path.resolve(__dirname, '../target/eve-test/bench-worker.js');

function totalDiskBytes() {
  const exts = ['.slab0','.slab1','.slab2','.slab3','.slab4','.slab5',
                '.slab6','.root','.rmap',
                '.slab0.bm','.slab1.bm','.slab2.bm',
                '.slab3.bm','.slab4.bm','.slab5.bm'];
  let total = 0;
  for (const ext of exts) {
    try { total += fs.statSync(basePath + ext).size; } catch {}
  }
  return total;
}

console.log(`\nnative-eve: Building Persistent Atom (Node.js)`);
console.log('========================================');
console.log(`  Path:   ${basePath}`);
console.log(`  Target: ~${targetMB} MB on disk`);
console.log('========================================\n');

const t0 = Date.now();
let offset = 0;

while (totalDiskBytes() < targetBytes) {
  try {
    execSync(`node ${benchWorker} bench-write-rich ${basePath} ${batchSize} fill${offset}`,
             { stdio: 'pipe', timeout: 120000 });
  } catch (e) {
    console.error(`\nBatch at offset ${offset} failed:`, e.message);
    process.exit(1);
  }
  offset += batchSize;
  const diskMB = (totalDiskBytes() / (1024 * 1024)).toFixed(1);
  const elapsedS = ((Date.now() - t0) / 1000).toFixed(1);
  process.stdout.write(`\r  ${offset.toLocaleString().padStart(8)} keys | ${diskMB.padStart(7)} MB on disk | ${elapsedS.padStart(6)}s elapsed`);
}

const elapsedS = ((Date.now() - t0) / 1000).toFixed(1);
const diskBytes = totalDiskBytes();
const diskMB = (diskBytes / (1024 * 1024)).toFixed(1);
console.log('\n');
console.log('Build Complete');
console.log('========================================');
console.log(`  Keys:        ~${offset.toLocaleString()}`);
console.log(`  Disk:        ${diskMB} MB (${diskBytes.toLocaleString()} bytes)`);
console.log(`  Elapsed:     ${elapsedS}s`);
console.log(`  Throughput:  ${Math.round(offset / (elapsedS))} keys/s`);
console.log('========================================\n');
