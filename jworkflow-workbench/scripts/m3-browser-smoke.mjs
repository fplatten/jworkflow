import {chromium,expect} from '@playwright/test';
import {readFile} from 'node:fs/promises';

const launch=process.env.WORKBENCH_TEST_URL,root=process.env.WORKBENCH_PROJECT_ROOT,channel=process.env.WORKBENCH_BROWSER||'chrome';
if(!launch||!root)throw new Error('M3 smoke requires launch URL and fixture root');
const browser=await chromium.launch({channel,headless:true});
try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}}),errors=[];let ending=false;
  page.on('pageerror',error=>{if(!ending)errors.push(error.message)});page.on('console',message=>{if(!ending&&message.type()==='error')errors.push(message.text())});
  await page.goto(launch);await expect(page.locator('#status')).toHaveText('Connected locally');
  await page.locator('#project-root').fill(root);await page.getByRole('button',{name:'Confirm project'}).click();
  await expect(page.locator('#build-system')).toHaveText('Maven');await expect(page.locator('.binding')).toHaveCount(3);
  await page.locator('#java-package').fill('sample.workflow');await page.getByRole('button',{name:'Confirm packages and output'}).click();
  await page.locator('#workflow-name').fill('Order flow');await expect(page.locator('#engine-id')).toHaveValue('Order-flow');await page.locator('#workflow-version').fill('1.0.0');
  const original=await page.evaluate(()=>{
    const ws=window.JWorkflowWorkbench.workspace;ws.clear();
    const make=(type,id,fields)=>{const block=ws.newBlock(type,id);for(const [name,value] of Object.entries(fields))block.setFieldValue(value,name);block.initSvg();block.render();return block};
    const start=make('workflow_start','start-id',{START:'first'}),step=make('workflow_step','step-id',{NAME:'first',ACTION:'OrderCommand',SUCCESS:'route',FAILURE:'failed'}),branch=make('workflow_branch','branch-id',{NAME:'route',VARIABLE:'approved',OPERATOR:'eq',VALUE_TYPE:'BOOLEAN',VALUE:'true',TRUE_TARGET:'done',FALSE_TARGET:'failed'}),done=make('workflow_end','done-id',{NAME:'done'}),failed=make('workflow_end','failed-id',{NAME:'failed'});
    start.getInput('NODES').connection.connect(step.previousConnection);step.nextConnection.connect(branch.previousConnection);branch.nextConnection.connect(done.previousConnection);done.nextConnection.connect(failed.previousConnection);
    start.moveBy(80,60);step.setCommentText('Discovered command action');branch.setCollapsed(true);ws.clearUndo();return Blockly.serialization.workspaces.save(ws);
  });
  await page.getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#save-status')).toHaveText(/Saved revision \d+\./);
  await page.reload();await expect(page.locator('#status')).toHaveText('Connected locally');await page.locator('#project-root').fill(root);await page.getByRole('button',{name:'Confirm project'}).click();await expect(page.locator('#save-status')).toContainText('Saved revision');
  const reopened=await page.evaluate(()=>Blockly.serialization.workspaces.save(window.JWorkflowWorkbench.workspace));
  expect(reopened).toEqual(original);
  await page.getByRole('button',{name:'Confirm packages and output'}).click();await page.getByRole('button',{name:'Generate'}).click();
  await expect(page.locator('#preview-state')).toHaveText('Ready');await expect(page.locator('#problem-state')).toHaveText('Current');await expect(page.locator('#wasm-status')).toContainText('matched the backend');await expect(page.locator('#groovy-preview')).toContainText("onFailure goTo: 'failed'");
  const generated=await page.locator('#groovy-preview').textContent();
  await page.evaluate(()=>{const block=window.JWorkflowWorkbench.workspace.getBlockById('start-id');block.moveBy(200,150)});await page.getByRole('button',{name:'Generate'}).click();await expect(page.locator('#preview-state')).toHaveText('Ready');expect(await page.locator('#groovy-preview').textContent()).toBe(generated);
  const before=await page.evaluate(()=>window.JWorkflowWorkbench.workspace.getAllBlocks(false).length);await page.evaluate(()=>window.JWorkflowWorkbench.workspace.getBlockById('step-id').select());await page.getByRole('button',{name:'Duplicate selected'}).click();const after=await page.evaluate(()=>({count:window.JWorkflowWorkbench.workspace.getAllBlocks(false).length,ids:window.JWorkflowWorkbench.workspace.getAllBlocks(false).map(b=>b.id)}));expect(after.count).toBeGreaterThan(before);expect(new Set(after.ids).size).toBe(after.ids.length);await page.getByRole('button',{name:'Undo'}).click();await expect.poll(()=>page.evaluate(()=>window.JWorkflowWorkbench.workspace.getAllBlocks(false).length)).toBe(before);
  // Keyboard-only authoring: outline selection, Add block insertion, inspector fields and diagnostic-to-field focus.
  const ws=()=>window.JWorkflowWorkbench.workspace;
  await page.getByRole('button',{name:'step first',exact:true}).click();await expect(page.locator('#inspector-field-ACTION')).toHaveValue('OrderCommand');
  await page.locator('#insert-type').selectOption('workflow_end');await page.getByRole('button',{name:'Insert',exact:true}).click();
  await expect(page.locator('#inspector-field-NAME')).toBeFocused();await page.keyboard.type('cancelled');await page.keyboard.press('Enter');
  await expect.poll(()=>page.evaluate(()=>{const step=window.JWorkflowWorkbench.workspace.getBlockById('step-id'),inserted=step.getNextBlock();return [inserted.type,inserted.getFieldValue('NAME'),inserted.getNextBlock()?.id]})).toEqual(['workflow_end','cancelled','branch-id']);
  await page.getByRole('button',{name:'step first',exact:true}).click();await page.locator('#inspector-field-ACTION').fill('missing command');await page.locator('#inspector-field-ACTION').press('Enter');
  await expect.poll(()=>page.evaluate(()=>window.JWorkflowWorkbench.workspace.getBlockById('step-id').getFieldValue('ACTION'))).toBe('missing command');
  await page.getByRole('button',{name:'Generate'}).click();await expect(page.locator('#preview-state')).toHaveText('Blocked');
  await page.locator('#problems .problem',{hasText:'ACTION_UNKNOWN'}).click();await expect(page.locator('#inspector-field-ACTION')).toBeFocused();
  await page.locator('#inspector-field-ACTION').fill('OrderCommand');await page.locator('#inspector-field-ACTION').press('Enter');await expect(page.locator('#preview-state')).toHaveText('Stale');
  await page.getByRole('button',{name:'end cancelled',exact:true}).click();await page.getByRole('button',{name:'Delete selected'}).click();
  await expect.poll(()=>page.evaluate(()=>window.JWorkflowWorkbench.workspace.getBlockById('step-id').getNextBlock().id)).toBe('branch-id');
  await page.getByRole('button',{name:'Generate'}).click();await expect(page.locator('#preview-state')).toHaveText('Ready');expect(await page.locator('#groovy-preview').textContent()).toBe(generated);
  // Duplicate assigns fresh IDs and a unique node name; Undo removes the copy as one group.
  await page.getByRole('button',{name:'step first',exact:true}).click();const blocksBefore=await page.evaluate(()=>window.JWorkflowWorkbench.workspace.getAllBlocks(false).length);
  await page.getByRole('button',{name:'Duplicate selected'}).click();await expect(page.getByRole('button',{name:'step first-copy',exact:true})).toBeVisible();
  expect(await page.evaluate(()=>window.JWorkflowWorkbench.workspace.getAllBlocks(false).length)).toBe(blocksBefore+1);
  await page.getByRole('button',{name:'Undo'}).click();await expect.poll(()=>page.evaluate(()=>window.JWorkflowWorkbench.workspace.getAllBlocks(false).length)).toBe(blocksBefore);
  // Unsaved changes request the browser's leave-page warning; a saved document does not.
  const leaveWarning=()=>page.evaluate(()=>{const event=new Event('beforeunload',{cancelable:true});dispatchEvent(event);return event.defaultPrevented});
  await page.locator('#workflow-version').fill('1.0.0-dirty');expect(await leaveWarning()).toBe(true);await page.locator('#workflow-version').fill('1.0.0');
  await page.getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#save-status')).toHaveText(/Saved revision \d+\./);expect(await leaveWarning()).toBe(false);
  await page.route('**/vendor/jworkflow-rules-v1.wasm',route=>route.abort());await page.getByRole('button',{name:'Generate'}).click();await expect(page.locator('#wasm-status')).toContainText('Wasm unavailable');await page.unroute('**/vendor/jworkflow-rules-v1.wasm');
  const mismatch=await readFile('target/test-classes/wasm-mismatch.wasm');await page.route('**/vendor/jworkflow-rules-v1.wasm',route=>route.fulfill({status:200,contentType:'application/wasm',body:mismatch}));await page.getByRole('button',{name:'Generate'}).click();await expect(page.locator('#wasm-status')).toContainText('differed');await page.unroute('**/vendor/jworkflow-rules-v1.wasm');
  await page.route('**/vendor/jworkflow-rules-v1.wasm',async route=>{await new Promise(resolve=>setTimeout(resolve,1800));try{await route.continue()}catch{ /* Worker timeout cancels this intentionally delayed request. */ }});await page.getByRole('button',{name:'Generate'}).click();await expect(page.locator('#wasm-status')).toContainText('exceeded 1500 ms');await page.unroute('**/vendor/jworkflow-rules-v1.wasm');
  await page.locator('#workflow-version').fill('1.0.1');const terminal=page.getByRole('textbox',{name:'Terminal input'});await terminal.pressSequentially('/exit');await terminal.press('Enter');await expect(page.locator('#exit-dialog')).toBeVisible();await page.getByRole('button',{name:'Cancel'}).click();await expect(page.locator('#status')).toHaveText('Connected locally');await terminal.pressSequentially('/exit');await terminal.press('Enter');ending=true;await page.getByRole('button',{name:'Save and exit'}).click();await expect(page.locator('.xterm-accessibility-tree')).toContainText('shutting down');
  if(errors.length)throw new Error(`Browser errors: ${errors.join('\n')}`);
  console.log(`PASS M3 ${channel}: Blockly round trip, deterministic Generate, Wasm parity/fallback/mismatch/timeout, grouped Undo, keyboard insert/inspector/diagnostic focus, duplicate rename, leave warning, dirty exit`);
}finally{await browser.close()}
