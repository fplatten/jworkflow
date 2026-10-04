// Release-shape smoke: launches the packaged JAR with `java -jar` from a working directory containing
// spaces and Unicode, captures the interactive fallback address through a PTY, and drives Chrome
// (/exit) and Edge (owner-tab close) through scripts/browser-smoke.mjs. Run after `mvnw package`.
// The JAR path and project are relative because the Windows java launcher converts arguments
// through the ANSI code page, which cannot carry arbitrary Unicode.
import {spawn as spawnPty} from 'node-pty';
import {spawn} from 'node:child_process';
import {copyFile, mkdir, mkdtemp, readdir, readFile, rm, writeFile} from 'node:fs/promises';
import {randomBytes} from 'node:crypto';
import {tmpdir} from 'node:os';
import path from 'node:path';

const jar = (await readdir('target')).find(name => /^jworkflow-workbench-.*\.jar$/.test(name) && !name.endsWith('-plain.jar'));
if (!jar) throw new Error('Run the Maven package phase first');
const proof = path.resolve('target', 'jar proof 漢字');
await mkdir(proof, {recursive: true});
await copyFile(path.join('target', jar), path.join(proof, 'jworkflow-workbench.jar'));
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : (process.platform === 'win32' ? 'java.exe' : 'java');
// A sentinel key proves the runtime key never reaches console output or files Workbench writes (AT-23).
const sentinel = `jworkflow-runtime-sentinel-${randomBytes(12).toString('hex')}`;
const env = {...process.env, OPENAI_API_KEY: sentinel};
const redact = text => text.replace(/#[A-Za-z0-9_-]{16,}/g, '#<redacted>');

async function run(channel, stop, script = 'scripts/browser-smoke.mjs', projectRoot = null) {
  const preferences = await mkdtemp(path.join(tmpdir(), 'workbench-pref-'));
  const launched = Date.now();
  const app = spawnPty(java, ['-jar', 'jworkflow-workbench.jar', '.', '--workbench.browser.enabled=false',
    `--workbench.preference-file=${path.join(preferences, 'last-project.txt')}`], {cols: 400, rows: 40, cwd: proof, env});
  let output = '', ready = false;
  const exited = new Promise(resolve => app.onExit(({exitCode}) => resolve(exitCode)));
  const address = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`No fallback address within 30s:\n${redact(output)}`)), 30000);
    app.onData(data => {
      output += data;
      const match = output.replace(/\x1b\[[0-9;?]*[A-Za-z]/g, '').match(/http:\/\/127\.0\.0\.1:\d+\/#[A-Za-z0-9_-]+/);
      if (match && !ready) { ready = true; clearTimeout(timer); startup.push(Date.now() - launched); resolve(match[0]); }
    });
    exited.then(code => reject(new Error(`JAR exited (${code}) before readiness:\n${redact(output)}`)));
  });
  const browser = spawn(process.execPath, [script], {stdio: 'inherit',
    env: {...env, WORKBENCH_TEST_URL: address, WORKBENCH_BROWSER: channel, WORKBENCH_STOP: stop, WORKBENCH_PROJECT_ROOT: projectRoot ?? ''}});
  const browserCode = await new Promise(resolve => browser.on('exit', resolve));
  if (browserCode !== 0) { app.kill(); throw new Error(`${channel} browser suite failed (${browserCode})`); }
  const finished = Date.now();
  const code = await Promise.race([exited, new Promise(resolve => setTimeout(() => resolve('timeout'), 30000))]);
  if (code === 'timeout') { app.kill(); throw new Error(`${channel}: JAR did not stop after ${stop}`); }
  if (code !== 0) throw new Error(`${channel}: JAR exit code ${code}:\n${redact(output)}`);
  if (output.includes(sentinel)) throw new Error(`${channel}: the runtime key appeared in console output`);
  console.log(`PASS java -jar ${channel} ${path.basename(script)}: stopped by ${stop} in ${Date.now() - finished}ms, exit 0, path "${proof}"`);
}

const startup = [];
const channels = (process.env.WORKBENCH_BROWSERS || 'chrome,msedge').split(',');
for (const [index, channel] of channels.entries()) await run(channel, index % 2 === 0 ? '/exit' : 'tab-close');

// AT-33: the existing JAR generates, reviews, applies and reverts new sources for a fresh project without rebuilding Workbench.
const fixture = path.join(proof, 'M4 fixture 漢字');
await rm(fixture, {recursive: true, force: true});   // Every run starts from a fresh project.
await mkdir(path.join(fixture, 'src/main/java/sample'), {recursive: true});
await writeFile(path.join(fixture, 'pom.xml'), '<project><properties><maven.compiler.release>17</maven.compiler.release></properties><dependencies><dependency><groupId>org.jworkflow</groupId><artifactId>jworkflow-core</artifactId><version>0.1.0-SNAPSHOT</version></dependency></dependencies></project>');
await writeFile(path.join(fixture, 'src/main/java/sample/OrderEvent.java'), 'package sample; public record OrderEvent(String id) {}');
await writeFile(path.join(fixture, 'src/main/java/sample/OrderCommand.java'), 'package sample; import org.jworkflow.application.Command; public record OrderCommand(String id) implements Command {}');
await writeFile(path.join(fixture, 'src/main/java/sample/OrderListener.java'), 'package sample; public final class OrderListener {}');
await run(channels[0], '/exit', 'scripts/m4-browser-smoke.mjs', fixture);
for (const file of ['.jworkflow/workflow.jworkflow.json', '.jworkflow/integration-plan.md'])
  if ((await readFile(path.join(fixture, file), 'utf8')).includes(sentinel)) throw new Error(`The runtime key appeared in ${file}`);
console.log(`PASS runtime key sentinel absent from console output and project files; JAR startup to ready address ${startup.join(', ')} ms (budget 10000 ms)`);
if (startup.some(ms => ms >= 10000)) throw new Error('Startup exceeded 10 seconds');
// node-pty's Windows console handles keep the event loop alive after every child has exited.
process.exit(0);
