# M1 native acceptance — manual evidence form

> Historical. Native packaging was retired on October 3, 2026; the product is a runnable JAR on Windows and macOS. See [WB-04](wb-04.md). The native scripts referenced below were removed.

Scope: Windows x64 with Chrome and Edge. The owner removed macOS support from the MVP. Automated Windows results are recorded in [the M1 register](m1.md); fill in remaining manual checks below. A successful JVM suite or native compilation is insufficient.

## Build preparation

Use the exact source checkout being qualified on Windows x64. Install GraalVM Community 25.0.2, Maven (wrapper pins 3.9.11), and the Windows C/C++ toolchain. Set `JAVA_HOME` and `PATH` for GraalVM. Windows requires Visual Studio C++ Build Tools/SDK. See [GraalVM prerequisites](https://www.graalvm.org/latest/getting-started/).

From the repository root, provision the pinned core without adding Workbench to the root reactor:

```powershell
git rev-parse HEAD
mvn -B -ntp -pl jworkflow-core -am install -DskipTests
cd jworkflow-workbench
mvn -B -ntp dependency:tree '-DoutputFile=target/dependencies.txt'
```

Record SHA256 of the installed `~/.m2/repository/org/jworkflow/jworkflow-core/0.1.0-SNAPSHOT/jworkflow-core-0.1.0-SNAPSHOT.jar` (use `Get-FileHash -Algorithm SHA256` in PowerShell). A snapshot coordinate alone is not reproducible evidence; retain this hash with the source revision and dirty-tree patch.

Build with `powershell -File scripts/native-build.ps1`. Retain the command output, JaCoCo XML and executable/ZIP hashes. This script does not mark the manual checks passed.

## Run on Windows x64

1. Extract the ZIP into a directory containing spaces and Unicode, for example `Workbench proof 漢字`. Launch the executable offline with provider keys unset and authoring JDK/Node absent from PATH. The application must open its default browser. Verify the no-browser fallback separately in an interactive terminal.
2. Test Chrome and Edge on Windows. Record exact browser/OS versions. One workbench process owns one browser tab; relaunch for each browser run.
3. `/help` returns Spring Shell output. `/core` reports `org.jworkflow:jworkflow-core:0.1.0-SNAPSHOT` and `org.jworkflow.application.Command`.
4. Enter `exit`, `exit criteria`, `/exitSomething`, Ctrl+C, and a Unicode message. Only exact `/exit` shuts down the entire app; `/exitSomething` reports an unknown command. No message contacts a provider in M1.
5. Resize, refresh and verify the owner reconnects without replaying commands. Open a second tab and verify it cannot control or stop the owner. Also duplicate the owner tab during refresh: record any ownership weakness as a blocker.
6. Close the owner tab. The process must exit after the 5-second disconnect grace plus the 1-second check interval and bounded cleanup. Simulate browser termination; heartbeat loss has a 20-second bound plus the check interval. Relaunch and verify `/exit` closes the process.
7. Confirm there are no provider keys/bootstrap credentials in retained logs, dependency reports or artifacts. Native build must not require or freeze runtime provider credentials. Repeat with a **fake, nonsecret** runtime sentinel and verify startup remains keyless/offline; never submit a real provider request for this test.
8. Record whether executable startup requires any compiler/SDK DLL absent from a clean target. If so, ZIP packaging is incomplete and must include licensed runtime prerequisites or documented supported-system requirements.

### Runtime settings check in plain language

Settings must be read when the executable starts, rather than permanently copied from the machine that built it. The automated `node scripts/native-smoke.mjs` check launches the native application with a different `SPRING_APPLICATION_NAME` for each browser and verifies that startup uses that runtime name. For a build made with the harmless `workbench-build-sentinel-not-a-real-key` build key, it also checks that the literal fake key is absent from UTF-8/UTF-16 executable strings and starts without a runtime provider key. This is bounded foundation evidence, not proof of the unfinished provider adapter's credential handling.

No real credentials are needed for manual confirmation. From PowerShell in the extracted ZIP directory:

```powershell
./jworkflow-workbench.exe --spring.application.name=manual-runtime-check
```

The startup log should identify the application as `manual-runtime-check`, and the browser should open normally. Use `/exit` to stop it. The provider-backed assistant remains intentionally disabled in M1.

## Evidence record (one per Windows/browser configuration)

Owner report, September 20, 2026: default-browser launch, exact `/exit` and owner-tab close work. Browser version and tested artifact hash were not supplied; this report does not establish clean-machine deployment, browser-crash cleanup or runtime sentinel checks.

Subsequent owner report: the rebuilt executable closes after `/exit` and owner-tab close, and Ctrl+C copies successfully. Requested refinement: clear the terminal selection after copying. Verify both clipboard contents and removal of the highlight, with no command cancellation or shutdown.

- Tester/date:
- OS/build/CPU architecture (real hardware):
- Source revision and uncommitted-patch hash:
- Core coordinate/hash:
- JDK/GraalVM/Maven/C++ toolchain versions:
- Native build command/exit status:
- Executable/ZIP SHA256:
- Browser/version:
- Default launch/fallback:
- Authenticated `/help` and `/core`:
- Exact routing/Unicode/resize:
- Refresh/secondary tab/duplicate-tab race:
- `/exit` and owner-close observed process exit times:
- Runtime configuration/offline/no-authoring-tools checks:
- Logs/screenshots (redacted):
- Failures/blockers:

Do not fill unrun checks with “pass.” This foundation does not claim editor, real AI, target builds or sandbox delivery.
