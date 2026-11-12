# contract-test/lib-ds

This in-tree copy of the DataScript-on-Eve port, under the old `datascript.*`
namespaces, is a **test-only fixture** for the contract drivers in
`contract-test/drivers/`. It is not the library consumers should depend on,
and it is not the consume path.

The library lives at [eve.db.ds](https://github.com/johnmn3/eve.db.ds)
(namespaces `eve.db.ds.*`). See Eve's [README](../../README.md) and
[docs/datascript-eve/GUIDE.md](../../docs/datascript-eve/GUIDE.md) for how to
depend on it.
