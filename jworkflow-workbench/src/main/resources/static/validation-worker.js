const RULESET_VERSION = 'm3-rules-1';
const REQUIRED = 1, MAX_BLOCKS = 2, MAX_DEPTH = 3;

const inspectBlocks = state => {
  let count = 0, maxDepth = 0;
  const visit = (block, depth) => {
    if (!block) return;
    count++; maxDepth = Math.max(maxDepth, depth);
    for (const input of Object.values(block.inputs || {})) visit(input.block, depth + 1);
    visit(block.next?.block, depth);
  };
  for (const block of state?.blocks?.blocks || []) visit(block, 1);
  return {count, maxDepth};
};

self.onmessage = async event => {
  try {
    const response = await fetch('/vendor/jworkflow-rules-v1.wasm', {cache: 'no-store'});
    if (!response.ok) throw new Error(`module HTTP ${response.status}`);
    const {instance} = await WebAssembly.instantiateStreaming(response);
    const check = instance.exports.check;
    if (typeof check !== 'function') throw new Error('check export is missing');
    const {metadata, blockly} = event.data;
    const shape = inspectBlocks(blockly);
    const item = (rule, location, ruleId, value) => ({rule, location, value, result: check(ruleId, value)});
    postMessage({rulesetVersion: RULESET_VERSION, outcomes: [
      item('required', 'name', REQUIRED, metadata.name.length),
      item('required', 'engineId', REQUIRED, metadata.engineId.length),
      item('required', 'version', REQUIRED, metadata.version.length),
      item('maximum', 'blocks', MAX_BLOCKS, shape.count),
      item('maximum', 'depth', MAX_DEPTH, shape.maxDepth)
    ]});
  } catch (error) { postMessage({error: error.message || String(error)}); }
};
