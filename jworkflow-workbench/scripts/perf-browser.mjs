import {chromium,expect} from '@playwright/test';
import {writeFileSync} from 'node:fs';

// AT-10 browser timings on a 500-block workflow: open, common edits (p95 budget 100 ms) and Save.
const launch=process.env.WORKBENCH_TEST_URL,root=process.env.WORKBENCH_PROJECT_ROOT,channel=process.env.WORKBENCH_BROWSER||'chrome';
const browser=await chromium.launch({channel,headless:true});
const percentile=(values,p)=>{const sorted=[...values].sort((a,b)=>a-b);return sorted[Math.min(sorted.length-1,Math.ceil(p/100*sorted.length)-1)]};
try{
  const page=await browser.newPage({viewport:{width:1440,height:1000}});
  await page.goto(launch);await expect(page.locator('#status')).toHaveText('Connected locally');
  await page.locator('#project-root').fill(root);
  const opened=Date.now();
  await page.getByRole('button',{name:'Confirm project'}).click();
  await expect(page.locator('#save-status')).toContainText('Saved revision 1 loaded',{timeout:30000});
  await expect.poll(()=>page.evaluate(()=>window.JWorkflowWorkbench.workspace.getAllBlocks(false).length),{timeout:30000}).toBe(500);
  const openMillis=Date.now()-opened;
  // A common edit: change one field, then wait until the editor has processed the change events and rendered.
  const edits=await page.evaluate(async()=>{
    const ws=window.JWorkflowWorkbench.workspace,blocks=ws.getAllBlocks(false).filter(b=>b.type==='workflow_step').slice(0,40),times=[];
    const frame=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    for(const [i,block] of blocks.entries()){
      const started=performance.now();
      block.setFieldValue('DoThing'+(i%2?'':''),'ACTION');block.setFieldValue(`edited-${i}`,'NAME');
      await new Promise(resolve=>setTimeout(resolve,0));await frame();
      times.push(performance.now()-started);
    }
    return times;
  });
  const selections=await page.evaluate(async()=>{
    const ws=window.JWorkflowWorkbench.workspace,blocks=ws.getAllBlocks(false).slice(100,140),times=[];
    const frame=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    for(const block of blocks){const started=performance.now();block.select();await new Promise(resolve=>setTimeout(resolve,0));await frame();times.push(performance.now()-started)}
    return times;
  });
  await page.locator('#java-package').fill('demo');await page.getByRole('button',{name:'Confirm packages and output'}).click();
  const saving=Date.now();
  await page.getByRole('button',{name:'Save',exact:true}).click();await expect(page.locator('#save-status')).toHaveText(/Saved revision 2\./,{timeout:30000});
  const saveMillis=Date.now()-saving;
  const report=`${channel}: open 500 blocks ${openMillis} ms; field edit p50 ${percentile(edits,50).toFixed(1)} ms, p95 ${percentile(edits,95).toFixed(1)} ms (budget 100 ms); `
    +`block selection p95 ${percentile(selections,95).toFixed(1)} ms; Save (browser round trip) ${saveMillis} ms (budget 2000 ms)`;
  console.log('PERF '+report);
  writeFileSync(`target/performance-${channel}.txt`,report+'\n');
  const terminal=page.getByRole('textbox',{name:'Terminal input'});await terminal.pressSequentially('/exit');await terminal.press('Enter');
}finally{await browser.close()}
