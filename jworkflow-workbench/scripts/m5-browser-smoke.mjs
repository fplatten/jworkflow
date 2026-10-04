import {chromium,expect} from '@playwright/test';

// M5: consent, streamed reply, approved proposal as one Undo, stale rejection, /cancel and /clear, conversations.
const launch=process.env.WORKBENCH_TEST_URL,root=process.env.WORKBENCH_PROJECT_ROOT,channel=process.env.WORKBENCH_BROWSER||'chrome';
if(!launch||!root)throw new Error('M5 smoke requires launch URL and fixture root');
const browser=await chromium.launch({channel,headless:true});
try{
  const page=await browser.newPage({viewport:{width:1440,height:1200}}),errors=[];let ending=false;
  page.on('pageerror',error=>{if(!ending)errors.push(error.message)});page.on('console',message=>{if(!ending&&message.type()==='error')errors.push(message.text())});
  page.on('dialog',dialog=>dialog.accept());
  await page.goto(launch);await expect(page.locator('#status')).toHaveText('Connected locally');
  await page.locator('#project-root').fill(root);await page.getByRole('button',{name:'Confirm project'}).click();
  await page.locator('#java-package').fill('sample.flow');await page.getByRole('button',{name:'Confirm packages and output'}).click();
  await page.locator('#workflow-name').fill('Order flow');await page.locator('#workflow-version').fill('1.0.0');
  await page.evaluate(()=>{
    const ws=window.JWorkflowWorkbench.workspace;ws.clear();
    const make=(type,id,fields)=>{const block=ws.newBlock(type,id);for(const [name,value] of Object.entries(fields))block.setFieldValue(value,name);block.initSvg();block.render();return block};
    const start=make('workflow_start','start',{START:'charge'}),charge=make('workflow_step','charge',{NAME:'charge',ACTION:'OrderCommand',SUCCESS:'done',FAILURE:'done'}),done=make('workflow_end','done',{NAME:'done'});
    start.getInput('NODES').connection.connect(charge.previousConnection);charge.nextConnection.connect(done.previousConnection);ws.clearUndo();
  });
  await expect(page.locator('#ai-state')).toHaveText('Ready');
  await expect(page.locator('#ai-status')).toContainText('model gpt-6-astra, store=false');
  await expect(page.locator('#ai-files')).not.toContainText('.env');
  await page.locator('#ai-share-workflow').check();
  await page.locator('#ai-files label',{hasText:'src/main/java/sample/OrderEvent.java'}).locator('input').check();
  const terminal=page.getByRole('textbox',{name:'Terminal input'}),tree=page.locator('.xterm-accessibility-tree');
  const say=async text=>{await terminal.pressSequentially(text);await terminal.press('Enter')};
  const blocks=()=>page.evaluate(()=>window.JWorkflowWorkbench.workspace.getAllBlocks(false).map(b=>`${b.type}:${b.getFieldValue("NAME")??""}:${b.getFieldValue("FAILURE")??""}`).sort());

  // Streamed reply and a reviewed proposal applied as one undoable change; nothing is saved or generated.
  await say('add an archive end');
  await expect(tree).toContainText('Adding an archive end.');
  await expect(page.locator('#ai-proposal')).toBeVisible();
  await expect(page.locator('#ai-proposal-preview')).toContainText("Add end 'archived' after 'done'");
  const before=await blocks();
  await page.getByRole('button',{name:'Approve and apply to editor'}).click();
  await expect(page.locator('#ai-proposal-status')).toContainText('Applied to the editor as one change');
  expect(await blocks()).toEqual(['workflow_end:archived:','workflow_end:done:','workflow_start::','workflow_step:charge:archived'].sort());
  await expect(page.locator('#document-state')).toHaveText('Unsaved changes');
  await expect(page.locator('#preview-state')).not.toHaveText('Ready');
  await page.getByRole('button',{name:'Undo'}).click();
  await expect.poll(blocks).toEqual(before);

  // A proposal for an editor state that has since changed is rejected without touching blocks.
  await say('add it again');
  await expect(page.locator('#ai-proposal')).toBeVisible();
  await page.locator('#workflow-version').fill('1.0.1');
  await page.getByRole('button',{name:'Approve and apply to editor'}).click();
  await expect(page.locator('#ai-proposal-status')).toContainText('stale');
  expect(await blocks()).toEqual(before);

  // Conversations: start a new one, resume and delete the earlier one.
  await page.getByRole('button',{name:'New conversation'}).click();
  await expect(page.locator('#ai-conversations li')).toHaveCount(1);
  await expect(page.locator('#ai-conversations')).toContainText('add an archive end');
  await page.locator('#ai-conversations').getByRole('button',{name:'Resume'}).click();
  await expect(page.locator('#ai-current')).toContainText('add an archive end');
  await page.getByRole('button',{name:'New conversation'}).click();
  await page.locator('#ai-conversations').getByRole('button',{name:'Delete'}).click();
  await expect(page.locator('#ai-conversations li')).toHaveCount(0);

  // /cancel during a streaming request deletes the current conversation; /clear deletes an idle one.
  await say('think slowly');
  await expect(tree).toContainText('Thinking about it');
  await say('/clear');
  await expect(tree).toContainText('Use /cancel first; nothing was cleared');
  await say('/cancel');
  await expect(tree).toContainText('AI request cancelled. The current conversation was deleted');
  await say('/cancel');
  await expect(tree).toContainText('No active operation.');
  await say('short answer');
  await expect(tree).toContainText('Short reply.');
  await say('/clear');
  await expect(tree).toContainText('Deleted the current conversation history');
  await expect(page.locator('#ai-current')).toContainText('new conversation');

  const html=await page.content();if(html.includes('test-key-not-for-browser'))throw new Error('API key reached the browser');
  await page.locator('#workflow-version').fill('1.0.0');
  await say('/exit');ending=true;
  if(await page.locator('#exit-dialog').isVisible())await page.getByRole('button',{name:'Discard and exit'}).click();
  await expect(tree).toContainText('shutting down');
  if(errors.length)throw new Error(`Browser errors: ${errors.join('\n')}`);
  console.log(`PASS M5 ${channel}: consent, streamed reply, approved proposal as one Undo, stale rejection, conversations, /cancel and /clear`);
}finally{await browser.close()}
