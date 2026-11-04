// lg-eve: a custom `lg` (docs/guide/custom-lg.md in the let-go module) with
// EVE's native Go package linked in. The blank import registers the
// `eve.native` namespace from its init(); everything else is stock let-go
// 1.13.0 (`cli.Main`: same flags, same REPL, same resolver).
package main

import (
	"os"

	_ "eve/letgo/evenative"

	"github.com/nooga/let-go/pkg/cli"
)

func main() { os.Exit(cli.Main("1.13.0+eve-ext", "none")) }
