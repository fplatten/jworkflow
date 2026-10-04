import {chromium,expect} from '@playwright/test';
import {existsSync,readFileSync} from 'node:fs';
import {resolve} from 'node:path';

// M4: whole-change-set review, stale invalidation, reject, approve, plan and guarded revert in a real browser.
const launch=process.env.WORKBENCH_TEST_URL,root=process.env.WORKBENCH_PROJECT_ROOT,channel=process.env.WORKBENCH_BROWSER||'chrome';
if(!launch||!root)throw new Error('M4 smoke requires launch URL and fixture root');
const file=relative=>resolve(root,relative);
const generatedFiles=['src/main/resources/workflows/order-flow.groovy','src/main/java/sample/OrderCommandListener.java','src/main/java/sample/flow/Ship.java','src/main/java/sample/flow/ShipListener.java'];
const browser=await chromium.launch({channel,headless:true});
try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];let ending=false;
  page.on('pageerror',error=>{if(!ending)errors.push(error.message)});page.on('console',message=>{if(!ending&&message.type()==='error')errors.push(message.text())});
  await page.goto(launch);await expect(page.locator('#status')).toHaveText('Connected locally');
  await page.locator('#project-root').fill(root);await page.getByRole('button',{name:'Confirm project'}).click();
  await expect(page.locator('.binding')).toHaveCount(3);
  await page.locator('#java-package').fill('sample.flow');await page.getByRole('button',{name:'Confirm packages and output'}).click();
  await page.locator('#workflow-name').fill('Order flow');await page.locator('#engine-id').fill('order-flow');await page.locator('#workflow-version').fill('1.0.0');
  await page.evaluate(()=>{
    const ws=window.JWorkflowWorkbench.workspace;ws.clear();
    const make=(type,id,fields)=>{const block=ws.newBlock(type,id);for(const [name,value] of Object.entries(fields))block.setFieldValue(value,name);block.initSvg();block.render();return block};
    const start=make('workflow_start','start',{START:'charge'}),charge=make('workflow_step','charge',{NAME:'charge',ACTION:'OrderCommand',SUCCESS:'ship',FAILURE:'failed'}),ship=make('workflow_step','ship',{NAME:'ship',ACTION:'Ship',SUCCESS:'done',FAILURE:'failed'}),done=make('workflow_end','done',{NAME:'done'}),failed=make('workflow_end','failed',{NAME:'failed'});
    start.getInput('NODES').connection.connect(charge.previousConnection);charge.nextConnection.connect(ship.previousConnection);ship.nextConnection.connect(done.previousConnection);done.nextConnection.connect(failed.previousConnection);
  });
  await page.getByRole('button',{name:'Generate'}).click();await expect(page.locator('#preview-state')).toHaveText('Ready');
  await expect(page.locator('#problems')).toContainText('COMMAND_WILL_BE_CREATED');
  await expect(page.locator('#integration-plan')).toContainText('Proposed only; nothing has been applied');

  // Review shows every file with its full diff; nothing is written by reviewing.
  await page.getByRole('button',{name:'Review source changes'}).click();
  await expect(page.locator('#changes-files details')).toHaveCount(4);
  for(const path of generatedFiles)await expect(page.locator('#changes-files')).toContainText(path);
  await expect(page.locator('#changes-files .diff .add').first()).toBeVisible();
  for(const path of generatedFiles)if(existsSync(file(path)))throw new Error(`Review wrote ${path}`);

  // Editing after review invalidates the approval.
  await page.locator('#workflow-version').fill('1.0.1');await expect(page.locator('#changes-state')).toHaveText('Stale');await expect(page.locator('#changes-review')).toBeHidden();
  await page.getByRole('button',{name:'Generate'}).click();await expect(page.locator('#preview-state')).toHaveText('Ready');
  await page.getByRole('button',{name:'Review source changes'}).click();await page.getByRole('button',{name:'Reject'}).click();
  await expect(page.locator('#changes-status')).toHaveText('Rejected. No source files were written.');
  for(const path of generatedFiles)if(existsSync(file(path)))throw new Error(`Reject wrote ${path}`);

  await page.getByRole('button',{name:'Review source changes'}).click();await page.getByRole('button',{name:/Approve and apply all 4 file/}).click();
  await expect(page.locator('#changes-state')).toHaveText('Applied');await expect(page.locator('#changes-status')).toContainText('Build and tests were not run');
  await expect(page.locator('#integration-plan')).toContainText('Applied for revision');await expect(page.locator('#integration-plan')).toContainText('new ShipListener(yourService::handle)');
  for(const path of generatedFiles)if(!existsSync(file(path)))throw new Error(`Approve did not write ${path}`);
  if(!readFileSync(file('src/main/java/sample/OrderCommandListener.java'),'utf8').includes('implements StepHandler'))throw new Error('Listener is not a StepHandler');

  // Guarded revert restores the pre-apply state; the user's own command is untouched.
  await expect(page.getByRole('button',{name:'Review revert of last applied change'})).toBeVisible();
  await page.getByRole('button',{name:'Review revert of last applied change'}).click();await expect(page.locator('#changes-files details')).toHaveCount(4);
  await page.getByRole('button',{name:'Approve revert'}).click();await expect(page.locator('#changes-state')).toHaveText('Reverted');
  for(const path of generatedFiles)if(existsSync(file(path)))throw new Error(`Revert left ${path}`);
  if(!existsSync(file('src/main/java/sample/OrderCommand.java')))throw new Error('Revert removed the user command');
  await expect(page.getByRole('button',{name:'Review revert of last applied change'})).toBeHidden();

  const terminal=page.getByRole('textbox',{name:'Terminal input'});await terminal.pressSequentially('/exit');await terminal.press('Enter');ending=true;
  await expect(page.locator('.xterm-accessibility-tree')).toContainText('shutting down');
  if(errors.length)throw new Error(`Browser errors: ${errors.join('\n')}`);
  console.log(`PASS M4 ${channel}: full diff review, stale invalidation, reject writes nothing, whole-set apply, plan, guarded revert`);
}finally{await browser.close()}
