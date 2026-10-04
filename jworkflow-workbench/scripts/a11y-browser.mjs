import {chromium,expect} from '@playwright/test';

// AT-16 automated part: the core journey with the keyboard only (every control reached with Tab, activated with keys)
// and an accessible-name audit of every visible control. Screen-reader evaluation remains a manual check.
const launch=process.env.WORKBENCH_TEST_URL,root=process.env.WORKBENCH_PROJECT_ROOT,channel=process.env.WORKBENCH_BROWSER||'chrome';
const browser=await chromium.launch({channel,headless:true});
try{
  const page=await browser.newPage({viewport:{width:1440,height:1100}});
  await page.goto(launch);await expect(page.locator('#status')).toHaveText('Connected locally');
  const tabTo=async selector=>{
    for(let i=0;i<250;i++){
      if(await page.evaluate(s=>document.activeElement?.matches(s),selector))return;
      await page.keyboard.press('Tab');
    }
    throw new Error(`${selector} is not reachable with Tab`);
  };
  const type=async text=>{await page.keyboard.type(text)};
  // Focus starts in the terminal (Workbench focuses it on connect); Tab must be able to leave it.
  await tabTo('#project-root');await type(root);await page.keyboard.press('Enter');
  await expect(page.locator('#build-system')).toHaveText('Maven');
  await tabTo('#java-package');await page.keyboard.press('Control+A');await type('sample.flow');
  await tabTo('#confirm-settings');await page.keyboard.press('Enter');
  await tabTo('#workflow-name');await type('Order flow');
  await tabTo('#workflow-version');await type('1.0.0');
  const insert=async(steps)=>{
    await tabTo('#insert-type');for(const key of steps)await page.keyboard.press(key);await tabTo('#insert-block');await page.keyboard.press('Enter');
    const deadline=Date.now()+3000;let where='';
    while(Date.now()<deadline){where=await page.evaluate(()=>{const e=document.activeElement;return e?.id?.startsWith('inspector-field-')?'ok':`${e?.tagName} id=${e?.id} class=${e?.getAttribute?.('class')} in-canvas=${!!e?.closest?.('#blockly-editor')}`});if(where==='ok')return;await page.waitForTimeout(50)}
    throw new Error(`After Insert, focus was on: ${where}`);
  };
  const fill=async values=>{for(const [index,value] of values.entries()){if(index)await page.keyboard.press('Tab');await type(value);await page.keyboard.press('Enter')}};
  // Start (fourth option), then a step, then two ends, each configured in the inspector without the mouse.
  await insert(['ArrowDown','ArrowDown','ArrowDown']);await expect(page.locator('#inspector-field-START')).toBeFocused();await fill(['charge']);
  await insert(['ArrowUp','ArrowUp','ArrowUp']);await expect(page.locator('#inspector-field-NAME')).toBeFocused();await fill(['charge','OrderCommand','done','failed']);
  await insert(['ArrowDown','ArrowDown']);await expect(page.locator('#inspector-field-NAME')).toBeFocused();await fill(['done']);
  await insert([]);await expect(page.locator('#inspector-field-NAME')).toBeFocused();await fill(['failed']);
  await tabTo('#save-draft');await page.keyboard.press('Enter');await expect(page.locator('#save-status')).toHaveText(/Saved revision \d+\./);
  await tabTo('#generate');await page.keyboard.press('Enter');
  await expect(page.locator('#preview-state')).not.toHaveText(/Generating|Not generated/);
  if(await page.locator('#preview-state').textContent()!=='Ready'){
    const fields=await page.evaluate(()=>window.JWorkflowWorkbench.workspace.getAllBlocks(false).map(b=>`${b.type} ${JSON.stringify(Object.fromEntries(b.inputList.flatMap(i=>i.fieldRow).filter(f=>f.name).map(f=>[f.name,f.getValue()])))}`));
    throw new Error(`Generate blocked. Problems: ${await page.locator('#problems').textContent()}\nBlocks:\n${fields.join('\n')}`);
  }
  await expect(page.locator('#preview-state')).toHaveText('Ready');await expect(page.locator('#groovy-preview')).toContainText("step('charge')");
  await tabTo('#groovy-preview');
  // Every visible control needs an accessible name (label, aria-label, aria-labelledby, title or text).
  const unnamed=await page.evaluate(()=>[...document.querySelectorAll('button,input,select,textarea,[tabindex]:not([tabindex="-1"])')].filter(el=>{
    const style=getComputedStyle(el);if(el.closest('[hidden]')||style.display==='none'||style.visibility==='hidden'||el.closest('.blocklyWidgetDiv,.blocklyDropDownDiv,.xterm-helpers'))return false;
    const labelled=el.getAttribute('aria-labelledby')?.split(' ').map(id=>document.getElementById(id)?.textContent?.trim()).join('');
    const label=el.labels?.length?[...el.labels].map(l=>l.textContent.trim()).join(''):'';
    return !(el.getAttribute('aria-label')||labelled||label||el.getAttribute('title')||el.textContent.trim());
  }).map(el=>el.outerHTML.slice(0,120)));
  if(unnamed.length)throw new Error(`Controls without an accessible name:\n${unnamed.join('\n')}`);
  const focusStyle=await page.evaluate(()=>[...document.styleSheets].some(sheet=>{try{return [...sheet.cssRules].some(rule=>rule.cssText.includes(':focus-visible'))}catch{return false}}));
  if(!focusStyle)throw new Error('No visible focus style');
  await page.keyboard.press('Escape');
  const terminal=page.getByRole('textbox',{name:'Terminal input'});await terminal.focus();await page.keyboard.type('/exit');await page.keyboard.press('Enter');
  if(await page.locator('#exit-dialog').isVisible()){await page.keyboard.press('Tab');await page.keyboard.press('Enter')}
  console.log(`PASS a11y ${channel}: keyboard-only confirm, settings, insert/configure 4 blocks, Save, Generate; all visible controls named; focus style present`);
}finally{await browser.close()}
