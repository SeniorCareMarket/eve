// Node ESM has no global `require`, but Eve's js-host sources use
// the ClojureScript idiom (js/require "worker_threads") — nbb
// provides a global require, squint's ESM output does not, and the
// calls compile to a bare free `require(...)`. Loading this module
// first (node --import) installs a cwd-based require on the global,
// the same resolution base nbb's require uses. Eve code that wraps
// its requires in try/catch (the region addon's auto-load) is
// unaffected; unwrapped call sites (eve.util's worker_threads) get
// the builtin they asked for.
import { createRequire } from 'node:module';

if (typeof globalThis.require !== 'function') {
  globalThis.require = createRequire(process.cwd() + '/noop.js');
}
