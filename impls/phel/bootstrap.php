<?php
// phel's launcher (vendor/phel-lang/phel-lang/bin/phel) walks up from the cwd,
// requires the FIRST vendor/autoload.php it finds, and stops. This project's
// autoloader is that file when phel runs in this directory, so it must also
// load phel's own autoloader (PHEL_AUTOLOAD names it).
require_once getenv('PHEL_AUTOLOAD') ?: '/opt/phel/vendor/autoload.php';
