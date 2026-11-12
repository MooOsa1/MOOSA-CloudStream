const { chromium } = require('playwright');

(async function main() {
  const source = 'https://raw.githubusercontent.com/MooOsa1/MOOSA-CloudStream/experiment/moosa-hub-aiostreams-20261018/AIOStreamsHost/moosa-hub-template.json';
  const host = 'https://aiostreams-nightly.fortheweak.cloud/stremio/configure';
  const url = new URL(host);
  url.searchParams.set('menu', 'about');
  url.searchParams.set('template', source);

  const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1365, height: 900 } });
  let fetched = null;
  const failures = [];
  page.on('response', r => {
    if (r.url().startsWith(source)) fetched = { status: r.status(), ok: r.ok() };
  });
  page.on('pageerror', err => failures.push(err.message.slice(0, 160)));
  try {
    const navigation = await page.goto(url.href, { waitUntil: 'domcontentloaded', timeout: 45000 });
    await page.waitForTimeout(13000);
    const body = await page.locator('body').innerText({ timeout: 10000 });
    const loaded = /MOOSA Hub/i.test(body);
    const configPanel = /AIOStreams|Set up AIOStreams|Templates/i.test(body);
    console.log('MOOSA_AIOSTREAMS_IMPORT_UI ' + JSON.stringify({
      hostStatus: navigation?.status() ?? null,
      templateFetchStatus: fetched?.status ?? null,
      templateListedOnPage: loaded,
      configPanel,
      pageErrors: failures.slice(0, 3)
    }));
    if (!navigation?.ok() || !configPanel || !loaded || (fetched && !fetched.ok)) {
      console.log('UI_PREVIEW_TEXT ' + body.slice(0, 520).replace(/\s+/g, ' '));
      process.exitCode = 1;
    }
  } finally { await browser.close(); }
})().catch(err => {
  console.error('MOOSA_AIOSTREAMS_IMPORT_UI_ERROR', err.message);
  process.exitCode = 1;
});
