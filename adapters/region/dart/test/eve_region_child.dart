/// The cross-process half of eve_region_test.dart: open the same file,
/// CAS-increment the word at 0 exactly [n] times against the parent's
/// own increments. Exit 0 when done.
import 'dart:io';

import '../dart/lib/eve_region.dart' as r;

void main(List<String> args) {
  final path = args[0];
  final size = int.parse(args[1]);
  final n = int.parse(args[2]);
  final m = r.eveMmapRegion(path, size);
  var done = 0;
  while (done < n) {
    final cur = r.eveLoad32(m);
    if (r.eveCas32(m, cur, cur + 1) == cur) done++;
  }
  r.eveSync(m, 0, size);
  r.eveCloseRegion(m, size, true);
  exit(0);
}
