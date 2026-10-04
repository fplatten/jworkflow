import {copyFile, cp, mkdir} from 'node:fs/promises';
const destination = new URL('../src/main/resources/static/vendor/', import.meta.url);
await mkdir(destination, {recursive: true});
for (const [source, name] of [['lib/xterm.js', 'xterm.js'], ['css/xterm.css', 'xterm.css'], ['LICENSE', 'xterm-LICENSE.txt']]) {
  await copyFile(new URL(`../node_modules/@xterm/xterm/${source}`, import.meta.url), new URL(name, destination));
}
for (const [source, name] of [['blockly.min.js', 'blockly.js'], ['blocks_compressed.js', 'blockly-blocks.js'], ['msg/en.js', 'blockly-en.js'], ['LICENSE', 'blockly-LICENSE.txt']]) {
  await copyFile(new URL(`../node_modules/blockly/${source}`, import.meta.url), new URL(name, destination));
}
await cp(new URL('../node_modules/blockly/media/', import.meta.url), new URL('blockly-media/', destination), {recursive: true});
