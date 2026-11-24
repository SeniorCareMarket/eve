// bb test:browser with EVE_BROWSER=firefox|webkit|chromium: open a URL in a Playwright browser and keep it open until
// this process is killed; the page's (and its workers') console lines go to stderr.
//   node scripts/browser-open.cjs <engine> <url> [profile-dir]
// With a profile dir, a persistent context there (WebKit's OPFS works only in one: an ephemeral context's
// navigator.storage.getDirectory() fails with UnknownError).
// Playwright is looked up as usual (NODE_PATH works); its browsers under PLAYWRIGHT_BROWSERS_PATH.
const [engine, url, profile] = process.argv.slice(2);
const pw = require('playwright');
(async () => {
  const browser = profile
    ? await pw[engine].launchPersistentContext(profile, { headless: true })
    : await pw[engine].launch({ headless: true });
  const page = profile ? (browser.pages()[0] || await browser.newPage()) : await browser.newPage();
  const log = (m) => process.stderr.write(`CONSOLE: ${m}\n`);
  page.on('console', (m) => log(`[${m.type()}] ${m.text()}`));
  page.on('pageerror', (e) => log(`[pageerror] ${e && e.stack || e}`));
  page.on('worker', (w) => w.on('console', (m) => log(`[worker ${m.type()}] ${m.text()}`)));
  await page.goto(url);
  const stop = async () => { try { await browser.close(); } finally { process.exit(0); } };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
})().catch((e) => { process.stderr.write(`browser-open: ${e && e.stack || e}\n`); process.exit(1); });
