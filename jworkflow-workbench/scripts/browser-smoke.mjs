import {chromium, expect} from '@playwright/test';
const launch = process.env.WORKBENCH_TEST_URL;
if (!launch?.startsWith('http://127.0.0.1:')) throw new Error('Expected one-use local test launch URL');
const channel = process.env.WORKBENCH_BROWSER || 'chrome';
const browser = await chromium.launch({channel, headless: true});
try {
  const page = await browser.newPage();
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.addInitScript(() => {
    Object.defineProperty(window, 'Terminal', {
      configurable: true,
      set(Type) {
        Object.defineProperty(window, 'Terminal', {value: class extends Type {
          constructor(...args) {super(...args); window.testTerminal = this;}
        }, configurable: true});
      }
    });
  });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => {if (message.type() === 'error') errors.push(message.text());});
  await page.goto(launch);
  await expect(page.getByRole('status')).toHaveText('Connected locally');
  await expect(page).toHaveURL(new URL('/', launch).toString());
  const input = page.getByRole('textbox', {name: 'Terminal input'});
  await input.pressSequentially('/help'); await input.press('Enter');
  await expect(page.getByRole('list')).toContainText('/core');
  await input.pressSequentially('/core'); await input.press('Enter');
  await expect(page.getByRole('list')).toContainText('org.jworkflow.application.Command');
  for (const text of ['exit', 'exit criteria', '/exitSomething']) {
    await input.pressSequentially(text); await input.press('Enter');
  }
  await expect(page.getByRole('list')).toContainText('Unknown command');
  await input.pressSequentially('/he');
  await input.press('Control+c');
  await input.pressSequentially('lp'); await input.press('Enter');
  await expect(page.getByRole('status')).toHaveText('Connected locally');
  const selected = await page.evaluate(() => {
    window.testTerminal.selectAll();
    return window.testTerminal.getSelection();
  });
  expect(selected).toContain('org.jworkflow.application.Command');
  await page.keyboard.press('Control+c');
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toBe(selected);
  await expect(page.getByRole('list')).not.toContainText('Ctrl+C does not cancel');
  await expect.poll(() => page.evaluate(() => window.testTerminal.hasSelection())).toBe(false);
  await page.setViewportSize({width: 900, height: 650});
  await page.screenshot({path: `target/browser-${channel}.png`});
  await page.reload(); await expect(page.getByRole('status')).toHaveText('Connected locally');
  await expect(page.getByRole('list')).not.toContainText('org.jworkflow.application.Command');
  // A second tab without the owner credential must not gain control.
  const second = await browser.newPage(); await second.goto(new URL('/', launch).toString());
  await expect(second.getByRole('status')).toContainText('launch link'); await second.close();
  await input.pressSequentially('/help'); await input.press('Enter');
  await expect(page.getByRole('list')).toContainText('/core');
  if (errors.length) throw new Error(`Browser errors: ${errors.join('\n')}`);
  if (process.env.WORKBENCH_STOP === 'tab-close') await page.close();
  else {
    await input.pressSequentially('/exit'); await input.press('Enter');
    await expect(page.getByRole('list')).toContainText('shutting down');
  }
  console.log(`PASS ${channel}: authenticated shell, core, exact routing, Ctrl+C, resize, refresh; stop=${process.env.WORKBENCH_STOP || '/exit'}`);
} finally {await browser.close();}
