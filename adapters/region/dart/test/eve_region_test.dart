/// Standalone test of the eve_region Dart natives (no ClojureDart):
/// heap + mmap regions, the atomic ops and their witness semantics,
/// bytes, sync, and cross-process CAS on one mapping (a child process
/// races this one, 1000 increments each; the word must end at 2000).
///
///   dart eve_region_test.dart [workdir]
import 'dart:io';
import 'dart:typed_data';

import '../dart/lib/eve_region.dart' as r;

int failures = 0;

void check(String name, Object? got, Object? want) {
  if (got == want) {
    print('ok   $name');
  } else {
    failures++;
    print('FAIL $name: got $got, want $want');
  }
}

Future<void> main(List<String> args) async {
  final dir = args.isNotEmpty ? args[0] : Directory.systemTemp.path;

  // --- heap region ---
  final h = r.eveHeapRegion(4096);
  check('heap zero-init', r.eveLoad32(h), 0);
  r.eveStore32(h, 305419896); // 0x12345678
  check('store/load i32', r.eveLoad32(h), 305419896);
  check('cas witness on mismatch', r.eveCas32(h, 7, 9), 305419896);
  check('cas witness on match', r.eveCas32(h, 305419896, 42), 305419896);
  check('load after cas', r.eveLoad32(h), 42);
  check('add returns old', r.eveAdd32(h, 8), 42);
  check('exchange returns old', r.eveExchange32(h, 100), 50);
  // unaligned i32 at off 3
  r.eveStore32(h + 3, -1);
  check('unaligned load', r.eveLoad32(h + 3), -1);
  // i64
  r.eveStore64(h + 16, 9007199254740991); // 2^53 - 1
  check('store/load i64', r.eveLoad64(h + 16), 9007199254740991);
  check('cas64 witness', r.eveCas64(h + 16, 1, 2), 9007199254740991);
  check('add64 returns old', r.eveAdd64(h + 16, -1), 9007199254740991);
  // bytes
  r.eveWriteBytes(h, 64, Uint8List.fromList([1, 2, 3, 250]));
  check('bytes roundtrip', r.eveReadBytes(h, 64, 4).join(','), '1,2,3,250');

  // --- wait/notify on the heap region (same process, an isolate-free
  // check of the contract values): word already differs -> :not-equal ---
  // (a word of its own at +32: the unaligned store above overlaps +0)
  r.eveStore32(h + 32, 100);
  check('wait not-equal', r.eveWait32(h + 32, 12345, 50), 1);
  final sw = Stopwatch()..start();
  check('wait timed-out', r.eveWait32(h + 32, 100, 60), 2);
  if (sw.elapsedMilliseconds < 50) {
    failures++;
    print('FAIL wait timed-out returned early: ${sw.elapsedMilliseconds}ms');
  }

  // --- mmap region: persistence + cross-process CAS ---
  final path = '$dir/eve-region-test.bin';
  final f = File(path);
  if (f.existsSync()) f.deleteSync();
  final m = r.eveMmapRegion(path, 4096);
  check('mmap zero-init', r.eveLoad32(m), 0);
  final mirrored = r.eveIsMirrored(m);
  print(mirrored
      ? 'note mmap is MIRRORED (the OS refused MAP_SHARED|PROT_WRITE); cross-process race skipped, persistence still checked'
      : 'note mmap is MAP_SHARED');
  r.eveStore32(m, 0); // the race word
  r.eveSync(m, 0, 4096);

  var want = 2000;
  if (!mirrored) {
    final child = await Process.start(
        Platform.resolvedExecutable,
        [
          File.fromUri(Platform.script.resolve('eve_region_child.dart')).path,
          path,
          '4096',
          '1000'
        ],
        mode: ProcessStartMode.inheritStdio);
    // race the child: 1000 CAS increments of the word at 0
    var done = 0;
    while (done < 1000) {
      final cur = r.eveLoad32(m);
      if (r.eveCas32(m, cur, cur + 1) == cur) done++;
    }
    final childExit = await child.exitCode;
    check('child exit', childExit, 0);
    check('cross-process CAS total', r.eveLoad32(m), 2000);
  } else {
    // mirrored: this process alone increments, sync publishes
    var done = 0;
    while (done < 2000) {
      final cur = r.eveLoad32(m);
      if (r.eveCas32(m, cur, cur + 1) == cur) done++;
    }
    check('mirrored CAS total', r.eveLoad32(m), 2000);
  }
  r.eveSync(m, 0, 4096);
  r.eveCloseRegion(m, 4096, true);

  // reopen: the value survives the mapping's close
  final m2 = r.eveMmapRegion(path, 4096);
  check('reopen reads durable word', r.eveLoad32(m2), want);
  r.eveCloseRegion(m2, 4096, true);
  r.eveCloseRegion(h, 4096, false);

  print(failures == 0 ? 'ALL PASS' : '$failures FAILURES');
  exit(failures == 0 ? 0 : 1);
}
