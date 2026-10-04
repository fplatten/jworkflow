// WB-24 release packaging and qualification checks. Builds the JAR offline with a sentinel OPENAI_API_KEY in the
// environment, verifies the JAR's required contents, proves neither the sentinel nor the developer's real key is
// embedded, and writes target/release/{JAR, .sha256, THIRD-PARTY.txt, INSTALL.txt}. Run from jworkflow-workbench/.
import {spawnSync} from 'node:child_process';
import {createHash, randomBytes} from 'node:crypto';
import {copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';

const windows = process.platform === 'win32';
const fail = message => { console.error(`RELEASE FAILED: ${message}`); process.exit(1); };
const run = (command, args, options = {}) => {
  // Windows .cmd files need a shell; pass one fixed, quoted command line rather than an argument array.
  const viaShell = windows && command.endsWith('.cmd');
  const result = viaShell ? spawnSync(`"${command}" ${args.join(' ')}`, {encoding: 'utf8', shell: true, maxBuffer: 64 * 1024 * 1024, ...options})
      : spawnSync(command, args, {encoding: 'utf8', maxBuffer: 64 * 1024 * 1024, ...options});
  if (result.status !== 0) fail(`${command} ${args.join(' ')} exited ${result.status}\n${result.stdout}\n${result.stderr}`);
  return result.stdout;
};

const version = /<artifactId>jworkflow-workbench<\/artifactId><version>([^<]+)<\/version>/.exec(readFileSync('pom.xml', 'utf8'))?.[1] ?? fail('version not found');
const releaseVersion = version.replace(/-SNAPSHOT$/, '');
const sentinel = `jworkflow-sentinel-${randomBytes(12).toString('hex')}`;
const realKey = process.env.OPENAI_API_KEY || '';

// 1. Clean offline build (no stale outputs) with a sentinel key in the environment (tests are run separately by `mvnw verify`).
run(windows ? path.resolve('mvnw.cmd') : './mvnw', ['-o', '-B', '-ntp', '-q', 'clean', 'package', '-DskipTests'], {env: {...process.env, OPENAI_API_KEY: sentinel}});
const jar = path.join('target', `jworkflow-workbench-${version}.jar`);
if (!existsSync(jar)) fail(`${jar} was not built`);

// 2. Required contents.
const javaHome = process.env.JAVA_HOME || path.dirname(path.dirname(spawnSync(windows ? 'where' : 'which', ['java'], {encoding: 'utf8'}).stdout.split(/\r?\n/)[0]));
const jarTool = path.join(javaHome, 'bin', windows ? 'jar.exe' : 'jar');
const entries = new Set(run(jarTool, ['tf', jar]).split(/\r?\n/).filter(Boolean));
const required = ['BOOT-INF/classes/static/index.html', 'BOOT-INF/classes/static/guide.html', 'BOOT-INF/classes/static/vendor/blockly.js',
  'BOOT-INF/classes/static/vendor/xterm.js', 'BOOT-INF/classes/static/vendor/blockly-LICENSE.txt', 'BOOT-INF/classes/static/vendor/xterm-LICENSE.txt',
  'BOOT-INF/classes/static/vendor/jworkflow-rules-v1.wasm', 'META-INF/LICENSE', 'META-INF/NOTICE',
  'BOOT-INF/classes/schemas/workflow-document-v1.schema.json', 'BOOT-INF/classes/schemas/source-change-proposal-v1.schema.json',
  'BOOT-INF/classes/org/jworkflow/workbench/SandboxProbe.class', 'BOOT-INF/classes/org/jworkflow/workbench/SandboxProbe$Attempt.class'];
const missing = required.filter(entry => !entries.has(entry));
if (missing.length) fail(`missing entries: ${missing.join(', ')}`);
const libraries = [...entries].filter(e => e.startsWith('BOOT-INF/lib/') && e.endsWith('.jar')).map(e => e.slice('BOOT-INF/lib/'.length)).sort();
if (!libraries.some(l => l.startsWith('jworkflow-core-'))) fail('jworkflow-core is not bundled');
if (libraries.some(l => l.startsWith('jworkflow-jdbc'))) fail('jworkflow-jdbc must not be bundled');
if (libraries.some(l => /native-image|graal/i.test(l)) || [...entries].some(e => e.includes('native-image'))) fail('native-image artifacts must not be bundled');

// 3. No key in any extracted entry (nested library JARs are scanned as bytes; text resources as text).
const extracted = mkdtempSync(path.join(tmpdir(), 'jworkflow-release-'));
try {
  run(jarTool, ['xf', path.resolve(jar)], {cwd: extracted});
  const needles = [sentinel, realKey].filter(value => value.length >= 16).map(value => Buffer.from(value));
  let scanned = 0;
  const walk = directory => { for (const name of readdirSync(directory)) {
    const file = path.join(directory, name);
    if (statSync(file).isDirectory()) { walk(file); continue; }
    const bytes = readFileSync(file); scanned++;
    for (const needle of needles) if (bytes.includes(needle)) fail(`a key value was found in ${path.relative(extracted, file)} (value not printed)`);
  } };
  walk(extracted);
  console.log(`Key scan: ${scanned} entries searched for the build-time sentinel${realKey ? ' and the developer key' : ''}; none found.`);
} finally { rmSync(extracted, {recursive: true, force: true}); }

// 4. Release directory.
const release = path.join('target', 'release');
mkdirSync(release, {recursive: true});
const name = `jworkflow-workbench-${releaseVersion}.jar`;
copyFileSync(jar, path.join(release, name));
const sha = createHash('sha256').update(readFileSync(path.join(release, name))).digest('hex');
writeFileSync(path.join(release, `${name}.sha256`), `${sha}  ${name}\n`);
writeFileSync(path.join(release, 'THIRD-PARTY.txt'), `Libraries bundled in ${name} (BOOT-INF/lib):\n${libraries.join('\n')}\n`);
writeFileSync(path.join(release, 'INSTALL.txt'), `JWorkflow Workbench ${releaseVersion}

Requirements: JDK 21 or later; Chrome or Edge; Windows 10/11 x64 or macOS on Apple Silicon. No Node or database.
Verify: compare the SHA-256 of ${name} with ${name}.sha256
  Windows:  certutil -hashfile ${name} SHA256
  macOS:    shasum -a 256 ${name}
Start:  java -jar ${name} [projectDirectory]
        Your browser opens; the guide is linked at the bottom of the page and works offline.
Windows: start from a folder whose path uses only characters of your system code page, or pass relative paths.
Build and test run only after the in-app confinement self-test passes; on Windows they support Maven projects only.
The JAR is unsigned. OPENAI_API_KEY is read from the environment at runtime and is never stored.
`);
console.log(`RELEASE OK: ${path.join(release, name)} (${statSync(path.join(release, name)).size} bytes) SHA-256 ${sha}; ${libraries.length} libraries listed.`);
