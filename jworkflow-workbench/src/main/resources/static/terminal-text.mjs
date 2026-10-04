/** Display untrusted output as plain text, never terminal escape instructions. */
export function plainText(value) {
  return String(value).replace(/[\u0000-\u0008\u000b-\u001f\u007f-\u009f]/g, '').replace(/\n/g, '\r\n');
}
export function terminalSize(width, height) {
  return {columns: Math.max(10, Math.min(500, Math.floor(width / 9))), rows: Math.max(2, Math.min(200, Math.floor(height / 19)))};
}
