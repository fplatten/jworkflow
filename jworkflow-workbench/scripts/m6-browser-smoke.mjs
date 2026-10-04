import {chromium,expect} from '@playwright/test';

// M6: self-test gating, exact-command review, deny, approve with streamed output, busy rejection, /cancel and plan outcome.
const launch=process.env.WORKBENCH_TEST_URL,root=process.env.WORKBENCH_PROJECT_ROOT,channel=process.env.WORKBENCH_BROWSER||'chrome';
if(!launch||!root)throw new Error('M6 smoke requires launch URL and fixture root');
const browser=await chromium.launch({channel,headless:true});
try{
  const page=await browser.newPage({viewport:{width:1440,height:1300}}),errors=[];let ending=false;
  page.on('pageerror',error=>{if(!ending)errors.push(error.message)});page.on('console',message=>{if(!ending&&message.type()==='error')errors.push(message.text())});
  await page.goto(launch);await expect(page.locator('#status')).toHaveText('Connected locally');
  await page.locator('#project-root').fill(root);await page.getByRole('button',{name:'Confirm project'}).click();
  await expect(page.locator('#build-system')).toHaveText('Maven');await expect(page.locator('#build-status')).toContainText('stay disabled until the self-test');
  const terminal=page.getByRole('textbox',{name:'Terminal input'}),tree=page.locator('.xterm-accessibility-tree');
  const say=async text=>{await terminal.pressSequentially(text);await terminal.press('Enter')};

  await say('/build');
  await expect(tree).toContainText('stay disabled until the confinement self-test passes');
  await page.getByRole('button',{name:'Run confinement self-test'}).click();
  await expect(page.locator('#build-state')).toHaveText('Ready');
  await expect(page.locator('#build-setup')).toContainText('outsideRead: denied');

  // Every run needs approval of the exact command; denial runs nothing.
  await say('/build');
  await expect(page.locator('#command-review')).toBeVisible();
  await expect(page.locator('#command-line')).toContainText('--offline');
  await expect(page.locator('#command-line')).toContainText('-Dmaven.test.skip=true');
  await expect(page.locator('#command-details')).toContainText('denied');
  await page.getByRole('button',{name:'Deny'}).click();
  await expect(page.locator('#build-status')).toHaveText('Denied. Nothing was run.');

  await say('/build');
  await page.getByRole('button',{name:'Approve and run this command'}).click();
  await expect(tree).toContainText('BUILD OUTPUT line');
  await expect(page.locator('#build-state')).toHaveText('Passed');
  await expect(page.locator('#integration-plan')).toContainText('Last `/build` passed');
  // AI-05: preview the exact output excerpt, then approve it for the next assistant message only.
  await page.getByRole('button',{name:'Share output with the assistant…'}).click();
  await expect(page.locator('#excerpt-text')).toContainText('BUILD OUTPUT line');
  await expect(page.locator('#excerpt-meta')).toContainText('/build (passed)');
  await page.getByRole('button',{name:'Share with my next message'}).click();
  await expect(page.locator('#build-status')).toContainText('next assistant message only');

  // While /test runs: AI input is rejected without queueing; /cancel stops only the process tree.
  await say('/test');
  await expect(page.locator('#command-line')).toContainText('verify');
  await page.getByRole('button',{name:'Approve and run this command'}).click();
  await expect(page.locator('#build-state')).toHaveText('Running');
  await say('please explain the failure');
  await expect(tree).toContainText('A /test is running; the AI request was not queued.');
  await say('/cancel');
  await expect(tree).toContainText('Stopping /test');
  await expect(page.locator('#build-state')).toHaveText('Cancelled');
  await expect(tree).toContainText('process tree was stopped');

  await say('/exit');ending=true;
  if(await page.locator('#exit-dialog').isVisible())await page.getByRole('button',{name:'Discard and exit'}).click();
  await expect(tree).toContainText('shutting down');
  if(errors.length)throw new Error(`Browser errors: ${errors.join('\n')}`);
  console.log(`PASS M6 ${channel}: self-test gating, exact review, deny, approved build with output, busy AI rejection, /cancel, plan outcome`);
}finally{await browser.close()}
