# JWorkflow Workbench

A local web application developers run on their own machine to author workflows for their Java project. Status: through M7 (release candidate; see the [acceptance ledger](verification/m7.md) for open manual and macOS evidence). Run it with `java -jar` on JDK 21+ on Windows or macOS. Coordinates: `org.jworkflow:jworkflow-workbench:0.1.0-SNAPSHOT`; Java packages: `org.jworkflow.workbench`. This directory has its own Spring Boot parent/build. It is not a root-reactor module and does not consume JDBC.

## M2 project flow

Launch Workbench, enter one existing single-module Maven or Gradle Java project root, then select **Confirm project**. Workbench reads no project files before that confirmation. Review the detected build/Java/core profile and source bindings, confirm the proposed Java package and in-root workflow output, then save an incomplete draft explicitly. Drafts use `.jworkflow/workflow.jworkflow.json`; `.gitignore` changes are shown and require a separate approval. Relevant Java source changes refresh discovery automatically without executing Maven, Gradle or project classes.

M2 recognizes Java records/classes by source text and proposes `*Event`, `*Listener`, and types implementing core `Command`. It does not resolve arbitrary build logic, inherited build properties or semantic transformations. Java target/core values it cannot prove are shown as unresolved. M3 adds the Blockly editor (keyboard insertion, inspector, outline), explicit Save, and Generate-only validation with a read-only Groovy preview and browser Wasm checks; see [M3 evidence](verification/m3.md). M4 adds reviewed source changes. Each step action gets a generated `StepHandler` listener, and a command record if the action is missing. The complete diff is approved as one set, applied with a journal and recovery, and the latest set can be reverted. The plan is written to `.jworkflow/integration-plan.md`; see [M4 evidence](verification/m4.md). M5 adds the assistant.
- Text typed in the terminal goes to the fixed `gpt-6-astra` model over the OpenAI Responses API (`store=false`), only if `OPENAI_API_KEY` is set when Workbench starts.
- The assistant sees only the files and workflow you share in the Assistant panel for this session.
- It can propose block edits; you approve them, and one Undo reverts them.
- `/clear` deletes the current conversation; `/cancel` stops a request and deletes its conversation.
- Conversations are saved in `.jworkflow/conversations/`.

See [M5 evidence](verification/m5.md).

The offline user guide is bundled at `/guide.html` (linked in the page footer). Build a release with `node scripts/release.mjs` (output in `target/release/`).

M6 adds `/build` and `/test`.
- **Confinement first:** they run only inside an OS sandbox that a self-test has proven for this project in this session. On Windows that's an AppContainer + Job Object; on macOS, `sandbox-exec`.
- **Your approval every time:** each run needs approval of the exact command; output is kept only in memory.
- **Current limits:** on Windows, `/build` and `/test` support Maven projects only (MVP scope; Gradle builds run outside Workbench), and macOS is not yet verified. See [M6 evidence](verification/m6.md).

Requirements reference: [`docs/current-projects/jworkflow-workbench-prd.md`](../docs/current-projects/jworkflow-workbench-prd.md), explicitly confirmed by the owner as authoritative. [M1 evidence and open prerequisites](verification/m1.md) distinguish tested behavior from unfinished work.

## Development

Provision the exact core snapshot from the repository root with `mvn -B -ntp -pl jworkflow-core -am install -DskipTests`. Record the revision and installed core JAR hash. Then, from this directory:

```sh
./mvnw -B -ntp clean package          # mvnw.cmd on Windows
java -jar target/jworkflow-workbench-0.1.0-SNAPSHOT.jar [projectDirectory]
```

The optional project directory is only a suggestion; Workbench reads nothing until you confirm it in the browser. On Windows the `java` launcher passes arguments through the ANSI code page, so a JAR or project path containing characters outside it (for example `漢字`) fails or is mangled; run from inside that folder with relative paths (`java -jar jworkflow-workbench.jar .`) instead.

Java 21 is the source/runtime baseline for contributor builds. The wrapper pins Maven 3.9.11. Ordinary verification is keyless and can use `-o` once dependencies are prepared. Both standalone Java coverage gates are mandatory: line ≥80% **and** branch ≥80%. No core/JDBC counters or production exclusions contribute.

Coverage is required for final MVP acceptance, not after every milestone. Use `mvn test` for correctness checks and `mvn verify` when explicitly checking final coverage.
Bundled xterm.js assets are committed with their MIT license; end users need neither Node nor a CDN. For frontend development run `npm ci --ignore-scripts`, `npm run vendor` and `npm test`. To run the explicit real-browser suite after npm preparation, use `mvn -Dtest=BrowserSmokeIT test`. It defaults to installed Chrome; set `WORKBENCH_BROWSER=msedge` for installed Edge. After `package`, `node scripts/jar-smoke.mjs` launches the real JAR with `java -jar` from a path with spaces and Unicode and runs the same suite on Chrome (`/exit`) and Edge (tab close); it needs `npm rebuild node-pty` once after `npm ci --ignore-scripts`.

## Foundation behavior and boundaries

- The backend binds IPv4 loopback on an ephemeral port and launches the default browser after readiness. Its one-use bootstrap lives in the URL fragment, is immediately removed by the page, and is exchanged for a session credential. The credential travels in WebSocket headers, never a query string. Fallback links go only to an interactive console, never redirected application logs. `--workbench.browser.enabled=false` offers the console link without automatic launch.
- Exact `/help`, `/core`, `/clear`, `/build`, `/test`, `/cancel` and `/exit` route through a bounded Spring Shell registry; unknown slash commands are rejected. Any other text goes to the assistant (see M5). The assistant has no file-edit tools; project commands are limited to the confined `/build` and `/test` templates. Ctrl+C copies selected text and does not cancel.
- One authenticated socket owns the backend. Refresh has a 5-second reconnect grace; heartbeat loss expires at 20 seconds; an unclaimed launch expires at 120 seconds. Cleanup checks run every second. Protocol bounds are implementation values, not approved product limits for later work.
- The frontend sanitizes terminal control characters and bounds scrollback. CSP restricts scripts/connections to this origin; xterm's generated grid styles require inline **styles**, but inline scripts remain forbidden.
- The last-project adapter returns a path suggestion only. M1 does not confirm/open projects or read their source content.
- `ResponsesChatModel` is the single fixed-model adapter (M5). It reads the key only at request time and never contacts the provider at startup.

The MVP ships as a runnable JAR, qualified on Windows x64 and macOS Apple Silicon with Chrome and Edge. Native packaging was retired on October 3, 2026. [Native acceptance](verification/native-acceptance.md) is kept as M1 history; [WB-04](verification/wb-04.md) removed the native build and records the JAR evidence.
