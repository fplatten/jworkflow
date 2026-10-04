import {chromium, expect} from '@playwright/test';
import {writeFile} from 'node:fs/promises';
import {resolve} from 'node:path';

const launch=process.env.WORKBENCH_TEST_URL,root=process.env.WORKBENCH_PROJECT_ROOT;
if(!launch||!root)throw new Error('M2 smoke requires launch URL and fixture root');
const browser=await chromium.launch({channel:process.env.WORKBENCH_BROWSER||'chrome',headless:true});
try{
  const page=await browser.newPage();await page.goto(launch);await expect(page.getByRole('status').first()).toHaveText('Connected locally');
  await page.locator('#project-root').fill(root);await page.getByRole('button',{name:'Confirm project'}).click();
  await expect(page.locator('#build-system')).toHaveText('Maven');await expect(page.locator('#core-version')).toHaveText('0.1.0-SNAPSHOT');
  await expect(page.locator('.binding')).toHaveCount(3);await page.locator('#java-package').fill('sample.workflow');
  await page.getByRole('button',{name:'Confirm packages and output'}).click();
  await page.locator('#workflow-name').fill('Incomplete order flow');await page.locator('#workflow-version').fill('0.1');
  await page.evaluate(()=>{const ws=window.JWorkflowWorkbench.workspace;ws.clear();const block=ws.newBlock('workflow_step','draft-one');block.initSvg();block.render()});
  await page.getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#save-status')).toHaveText('Saved revision 1.');
  await page.reload();await expect(page.getByRole('status').first()).toHaveText('Connected locally');
  await page.locator('#project-root').fill(root);await page.getByRole('button',{name:'Confirm project'}).click();await expect(page.locator('#save-status')).toContainText('Saved revision 1');
  await expect(page.locator('#workflow-name')).toHaveValue('Incomplete order flow');
  expect(await page.evaluate(()=>window.JWorkflowWorkbench.workspace.getBlockById('draft-one')?.type)).toBe('workflow_step');
  await page.locator('#java-package').fill('sample.workflow');await page.getByRole('button',{name:'Confirm packages and output'}).click();
  await page.getByRole('button',{name:'Review .gitignore change'}).click();await expect(page.locator('#gitignore-diff')).toContainText('.jworkflow/');await page.getByRole('button',{name:'Approve .gitignore change'}).click();
  await writeFile(resolve(root,'src/main/java/sample/SecondEvent.java'),'package sample; public record SecondEvent(String id) {}');
  await expect(page.locator('.binding')).toHaveCount(4,{timeout:7000});await expect(page.locator('#discovery-status')).toContainText('Source changed');
  const terminal=page.getByRole('textbox',{name:'Terminal input'});await terminal.pressSequentially('/exit');await terminal.press('Enter');await expect(page.locator('.xterm-accessibility-tree')).toContainText('shutting down');
  console.log('PASS M2 browser: confirm, discover, settings, draft save/reopen, reviewed gitignore, automatic refresh');
}finally{await browser.close()}
