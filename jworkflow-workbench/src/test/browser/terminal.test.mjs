import {test} from 'node:test';
import assert from 'node:assert/strict';
import {plainText, terminalSize} from '../../main/resources/static/terminal-text.mjs';
test('provider text cannot inject terminal controls but preserves Unicode', () => {
  assert.equal(plainText('\x1b]52;c;clipboard\x07\u009bé漢字\nnext'), ']52;c;clipboardé漢字\r\nnext');
});
test('resize stays inside transport bounds', () => {
  assert.deepEqual(terminalSize(0, 0), {columns: 10, rows: 2});
  assert.deepEqual(terminalSize(100000, 100000), {columns: 500, rows: 200});
});
