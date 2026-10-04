import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

// JVM/Wasm parity: SharedValidationRulesTests reads the same fixture for the Java rules.
test('bundled Wasm rules match the shared fixture', async () => {
  const fixture = JSON.parse(await readFile(new URL('../resources/wasm/shared-rules-v1.json', import.meta.url)));
  const {instance} = await WebAssembly.instantiate(await readFile(new URL('../../main/resources/static/vendor/jworkflow-rules-v1.wasm', import.meta.url)));
  for (const {rule, value, result} of fixture.cases) assert.equal(instance.exports.check(rule, value), result, `rule ${rule}, value ${value}`);
});

test('worker and fixture declare the same ruleset version', async () => {
  const fixture = JSON.parse(await readFile(new URL('../resources/wasm/shared-rules-v1.json', import.meta.url)));
  const worker = await readFile(new URL('../../main/resources/static/validation-worker.js', import.meta.url), 'utf8');
  assert.match(worker, new RegExp(`RULESET_VERSION = '${fixture.rulesetVersion}'`));
});
