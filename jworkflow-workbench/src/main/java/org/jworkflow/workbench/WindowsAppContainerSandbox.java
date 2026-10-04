package org.jworkflow.workbench;

import com.sun.jna.*;
import com.sun.jna.platform.win32.*;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/**
 * Windows confinement: each launch runs inside a dedicated AppContainer with no capabilities, so the network is denied
 * and file access is limited to paths whose ACL grants the container SID (or ALL APPLICATION PACKAGES, as system and
 * Program Files directories do). The process starts suspended inside a kill-on-close Job Object, so every descendant,
 * including detached daemons, dies with the launch. Only the output pipe and NUL input are inherited.
 */
final class WindowsAppContainerSandbox implements Sandbox {
    static final String PROFILE = "JWorkflow.Workbench.Sandbox";
    private static final int PROC_THREAD_ATTRIBUTE_HANDLE_LIST = 0x00020002;
    private static final int PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES = 0x00020009;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000, CREATE_SUSPENDED = 0x4, CREATE_UNICODE_ENVIRONMENT = 0x400, CREATE_NO_WINDOW = 0x08000000;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000, JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION = 0x400;
    private static final int HRESULT_ALREADY_EXISTS = 0x800700B7;

    interface Userenv extends StdCallLibrary {
        Userenv I = Native.load("userenv", Userenv.class, W32APIOptions.DEFAULT_OPTIONS);
        int CreateAppContainerProfile(String name, String displayName, String description, Pointer capabilities, int count, PointerByReference sid);
        int DeriveAppContainerSidFromAppContainerName(String name, PointerByReference sid);
    }

    interface Advapi extends StdCallLibrary {
        Advapi I = Native.load("advapi32", Advapi.class, W32APIOptions.DEFAULT_OPTIONS);
        Pointer FreeSid(Pointer sid);
    }

    /** Windows x64 only: SIZE_T and ULONG_PTR are 64-bit and passed as long. */
    interface Kernel extends StdCallLibrary {
        Kernel I = Native.load("kernel32", Kernel.class, W32APIOptions.DEFAULT_OPTIONS);
        boolean InitializeProcThreadAttributeList(Pointer list, int count, int flags, LongByReference size);
        boolean UpdateProcThreadAttribute(Pointer list, int flags, long attribute, Pointer value, long size, Pointer previous, Pointer returnSize);
        void DeleteProcThreadAttributeList(Pointer list);
        HANDLE CreateJobObjectW(Pointer attributes, String name);
        boolean SetInformationJobObject(HANDLE job, int informationClass, Pointer information, int length);
        boolean AssignProcessToJobObject(HANDLE job, HANDLE process);
        boolean TerminateJobObject(HANDLE job, int exitCode);
        int ResumeThread(HANDLE thread);
        boolean DefineDosDeviceW(int flags, String device, String target);
        int QueryDosDeviceW(String device, char[] target, int max);
        boolean CreateProcessW(String application, char[] commandLine, Pointer processAttributes, Pointer threadAttributes, boolean inherit,
                int flags, Pointer environment, String directory, StartupInfoEx startup, WinBase.PROCESS_INFORMATION information);
    }

    /** SECURITY_CAPABILITIES with no capabilities: no network, no library or device access. */
    @Structure.FieldOrder({"AppContainerSid", "Capabilities", "CapabilityCount", "Reserved"})
    public static final class SecurityCapabilities extends Structure {
        public Pointer AppContainerSid; public Pointer Capabilities; public int CapabilityCount; public int Reserved;
    }

    /** JOBOBJECT_EXTENDED_LIMIT_INFORMATION (x64 layout). */
    @Structure.FieldOrder({"PerProcessUserTimeLimit", "PerJobUserTimeLimit", "LimitFlags", "MinimumWorkingSetSize", "MaximumWorkingSetSize", "ActiveProcessLimit",
            "Affinity", "PriorityClass", "SchedulingClass", "ReadOperationCount", "WriteOperationCount", "OtherOperationCount", "ReadTransferCount",
            "WriteTransferCount", "OtherTransferCount", "ProcessMemoryLimit", "JobMemoryLimit", "PeakProcessMemoryUsed", "PeakJobMemoryUsed"})
    public static final class JobLimits extends Structure {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit; public int LimitFlags; public long MinimumWorkingSetSize, MaximumWorkingSetSize;
        public int ActiveProcessLimit; public long Affinity; public int PriorityClass, SchedulingClass;
        public long ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount, WriteTransferCount, OtherTransferCount;
        public long ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }

    @Structure.FieldOrder({"StartupInfo", "lpAttributeList"})
    public static final class StartupInfoEx extends Structure {
        public WinBase.STARTUPINFO StartupInfo = new WinBase.STARTUPINFO(); public Pointer lpAttributeList;
    }

    private String reason;

    WindowsAppContainerSandbox() {
        try { containerSid(); reason = ""; }
        catch (LinkageError | RuntimeException unavailable) { reason = "Windows AppContainer is unavailable: " + unavailable.getMessage(); }
    }

    @Override public String mechanism() { return "Windows AppContainer + Job Object"; }
    @Override public String unavailableReason() { return reason; }

    /** The container SID string, creating the per-user profile on first use. */
    static String containerSid() {
        PointerByReference sid = new PointerByReference();
        int result = Userenv.I.CreateAppContainerProfile(PROFILE, "JWorkflow Workbench sandbox", "Confines JWorkflow Workbench target builds and tests", null, 0, sid);
        if (result == HRESULT_ALREADY_EXISTS) result = Userenv.I.DeriveAppContainerSidFromAppContainerName(PROFILE, sid);
        if (result != 0) throw new IllegalStateException("AppContainer profile error 0x" + Integer.toHexString(result));
        try { return Advapi32Util.convertSidToStringSid(new WinNT.PSID(sid.getValue())); }
        finally { Advapi.I.FreeSid(sid.getValue()); }
    }

    @Override public Setup setup(Path writableRoot, List<Path> readOnly) {
        String sid = containerSid();
        List<String> commands = new ArrayList<>();
        for (Path path : readOnly) commands.add("icacls \"" + path + "\" /grant \"*" + sid + ":(OI)(CI)(RX)\"");
        return new Setup(List.of("Grant the Workbench sandbox (" + sid + ") modify access to " + writableRoot + " and everything inside it."),
                commands, "Windows denies the sandbox every file that is not explicitly shared with it. Workbench changes only the project folder's permissions; run the read-only grants yourself if a self-test reports them missing. Paths already readable by all app containers (such as Program Files) need nothing.");
    }

    @Override public void applyWorkbenchGrants(Path writableRoot) throws IOException {
        String sid = containerSid();
        Process icacls = new ProcessBuilder("icacls", writableRoot.toString(), "/grant", "*" + sid + ":(OI)(CI)(M)", "/Q").redirectErrorStream(true).start();
        String output = new String(icacls.getInputStream().readAllBytes());
        try { if (icacls.waitFor() != 0) throw new IOException("Could not grant the sandbox access to the project folder: " + output.strip()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while granting access"); }
    }

    /** Grants read-only access to a Workbench-owned path (used for self-test fixtures, never for user folders). */
    static void grantReadOnly(Path path) throws IOException {
        Process icacls = new ProcessBuilder("icacls", path.toString(), "/grant", "*" + containerSid() + ":(OI)(CI)(RX)", "/Q").redirectErrorStream(true).start();
        icacls.getInputStream().readAllBytes();
        try { if (icacls.waitFor() != 0) throw new IOException("icacls failed for " + path); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while granting access"); }
    }

    /**
     * Maps a folder to a free drive letter for one launch. Inside an AppContainer, {@code Path.toRealPath} (used by Maven
     * for its working directory and by javac for the JDK) needs to list every ancestor folder, and {@code C:\} never
     * allows that; a drive root has no ancestors. Access checks still apply to the real folder.
     */
    static synchronized String mapDrive(Path root) throws IOException {
        String target = root.toString();
        for (char letter = 'Z'; letter >= 'H'; letter--) {
            String device = letter + ":";
            char[] current = new char[1024];
            int length = Kernel.I.QueryDosDeviceW(device, current, current.length);
            // QueryDosDevice returns NUL-separated targets; a subst mapping reads "\??\C:\path".
            if (length > 0 && new String(current, 0, length).split("\u0000")[0].equals("\\??\\" + target)) unmapDrive(device, target);
        }
        for (char letter = 'Z'; letter >= 'H'; letter--) {
            String device = letter + ":";
            if (Kernel.I.QueryDosDeviceW(device, new char[1024], 1024) > 0) continue;
            if (Kernel.I.DefineDosDeviceW(0, device, target)) return device;
        }
        throw new IOException("No free drive letter (H: to Z:) is available to run the confined build.");
    }

    static void unmapDrive(String device, String target) {
        Kernel.I.DefineDosDeviceW(2 /* DDD_REMOVE_DEFINITION */ | 4 /* DDD_EXACT_MATCH_ON_REMOVE */, device, target);
    }

    @Override public Running start(Launch launch, Consumer<byte[]> output) throws IOException {
        if (!reason.isEmpty()) throw new IllegalStateException(reason);
        // The project and every read-only folder get their own drive letter for this launch only.
        Map<String, String> drives = new LinkedHashMap<>();
        List<Path> folders = new ArrayList<>(List.of(launch.writableRoot()));
        launch.readOnly().stream().filter(java.nio.file.Files::isDirectory).forEach(folders::add);
        try {
            for (Path folder : folders) if (!drives.containsKey(folder.toString())) drives.put(folder.toString(), mapDrive(folder));
            Map<String, String> environment = new LinkedHashMap<>();
            launch.environment().forEach((key, value) -> environment.put(key, remap(value, drives)));
            Launch mapped = new Launch(launch.command().stream().map(value -> remap(value, drives)).toList(),
                    Path.of(remap(launch.workingDirectory().toString(), drives)), environment, launch.writableRoot(), launch.readOnly());
            Running running = startMapped(mapped, output);
            return new Running() {
                private boolean unmapped;
                @Override public Integer waitFor(Duration timeout) throws InterruptedException {
                    Integer exit = running.waitFor(timeout);
                    if (exit != null) unmap();
                    return exit;
                }
                @Override public void killTree() { running.killTree(); unmap(); }
                private synchronized void unmap() { if (!unmapped) { unmapped = true; drives.forEach((target, device) -> unmapDrive(device, target)); } }
            };
        } catch (IOException | RuntimeException failure) { drives.forEach((target, device) -> unmapDrive(device, target)); throw failure; }
    }

    /** Rewrites folder prefixes (whole value, after '=', or before ';') to their mapped drive roots, longest folder first. */
    static String remap(String value, Map<String, String> drives) {
        List<String> folders = new ArrayList<>(drives.keySet());
        folders.sort(Comparator.comparingInt(String::length).reversed());
        String result = value;
        for (String folder : folders) {
            StringBuilder out = new StringBuilder();
            int from = 0, at;
            while ((at = result.indexOf(folder, from)) >= 0) {
                int end = at + folder.length();
                boolean start = at == 0 || result.charAt(at - 1) == '=' || result.charAt(at - 1) == ';';
                boolean boundary = end == result.length() || result.charAt(end) == '\\' || result.charAt(end) == ';';
                out.append(result, from, at);
                if (start && boundary) out.append(drives.get(folder)).append(end == result.length() || result.charAt(end) == ';' ? "\\" : "");
                else out.append(folder);
                from = end;
            }
            result = out.append(result.substring(from)).toString();
        }
        return result;
    }

    private Running startMapped(Launch launch, Consumer<byte[]> output) throws IOException {
        PointerByReference sid = new PointerByReference();
        int derived = Userenv.I.DeriveAppContainerSidFromAppContainerName(PROFILE, sid);
        if (derived != 0) { containerSid(); derived = Userenv.I.DeriveAppContainerSidFromAppContainerName(PROFILE, sid); }
        if (derived != 0) throw new IOException("AppContainer SID unavailable: 0x" + Integer.toHexString(derived));

        WinBase.SECURITY_ATTRIBUTES inheritable = new WinBase.SECURITY_ATTRIBUTES();
        inheritable.bInheritHandle = true; inheritable.dwLength = new WinDef.DWORD(inheritable.size());
        WinNT.HANDLEByReference readPipe = new WinNT.HANDLEByReference(), writePipe = new WinNT.HANDLEByReference();
        if (!Kernel32.INSTANCE.CreatePipe(readPipe, writePipe, inheritable, 0)) throw new IOException("CreatePipe failed: " + Kernel32.INSTANCE.GetLastError());
        Kernel32.INSTANCE.SetHandleInformation(readPipe.getValue(), WinBase.HANDLE_FLAG_INHERIT, 0);
        HANDLE nul = Kernel32.INSTANCE.CreateFile("NUL", WinNT.GENERIC_READ, WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE, inheritable, WinNT.OPEN_EXISTING, 0, null);

        HANDLE job = Kernel.I.CreateJobObjectW(null, null);
        var limits = new JobLimits();
        limits.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_DIE_ON_UNHANDLED_EXCEPTION;
        limits.write();
        if (job == null || !Kernel.I.SetInformationJobObject(job, 9 /* JobObjectExtendedLimitInformation */, limits.getPointer(), limits.size()))
            throw new IOException("Job Object setup failed: " + Kernel32.INSTANCE.GetLastError());

        LongByReference size = new LongByReference();
        Kernel.I.InitializeProcThreadAttributeList(null, 2, 0, size);
        Memory attributes = new Memory(size.getValue());
        Memory handles = new Memory(2L * Native.POINTER_SIZE);
        handles.setPointer(0, writePipe.getValue().getPointer()); handles.setPointer(Native.POINTER_SIZE, nul.getPointer());
        SecurityCapabilities capabilities = new SecurityCapabilities();
        capabilities.AppContainerSid = sid.getValue(); capabilities.write();
        WinBase.PROCESS_INFORMATION process = new WinBase.PROCESS_INFORMATION();
        try {
            if (!Kernel.I.InitializeProcThreadAttributeList(attributes, 2, 0, size)
                    || !Kernel.I.UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, capabilities.getPointer(), capabilities.size(), null, null)
                    || !Kernel.I.UpdateProcThreadAttribute(attributes, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handles, handles.size(), null, null))
                throw new IOException("Process attribute setup failed: " + Kernel32.INSTANCE.GetLastError());
            StartupInfoEx startup = new StartupInfoEx();
            startup.StartupInfo.cb = new WinDef.DWORD(startup.size());
            startup.StartupInfo.dwFlags = WinBase.STARTF_USESTDHANDLES;
            startup.StartupInfo.hStdInput = nul; startup.StartupInfo.hStdOutput = writePipe.getValue(); startup.StartupInfo.hStdError = writePipe.getValue();
            startup.lpAttributeList = attributes;
            char[] commandLine = (commandLine(launch.command()) + '\0').toCharArray();
            // CreateProcess fails with ERROR_ENVVAR_NOT_FOUND for AppContainers without LOCALAPPDATA; Windows redirects it to the container's own storage.
            Map<String, String> variables = new TreeMap<>(String.CASE_INSENSITIVE_ORDER); variables.putAll(launch.environment());
            variables.putIfAbsent("LOCALAPPDATA", System.getenv("LOCALAPPDATA"));
            Memory environment = environmentBlock(variables);
            if (!Kernel.I.CreateProcessW(launch.command().get(0), commandLine, null, null, true,
                    EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW,
                    environment, launch.workingDirectory().toString(), startup, process))
                throw new IOException("Could not start the confined process (Windows error " + Kernel32.INSTANCE.GetLastError() + ").");
            if (!Kernel.I.AssignProcessToJobObject(job, process.hProcess)) {
                Kernel32.INSTANCE.TerminateProcess(process.hProcess, 1);
                throw new IOException("Could not place the process in its Job Object; it was terminated.");
            }
            if (Kernel.I.ResumeThread(process.hThread) == -1) { Kernel.I.TerminateJobObject(job, 1); throw new IOException("Could not resume the confined process."); }
        } catch (IOException | RuntimeException failure) {
            Kernel32.INSTANCE.CloseHandle(job); Kernel32.INSTANCE.CloseHandle(readPipe.getValue());
            throw failure;
        } finally {
            Kernel.I.DeleteProcThreadAttributeList(attributes);
            Kernel32.INSTANCE.CloseHandle(writePipe.getValue()); Kernel32.INSTANCE.CloseHandle(nul);
            if (process.hThread != null) Kernel32.INSTANCE.CloseHandle(process.hThread);
            Advapi.I.FreeSid(sid.getValue());
        }
        HANDLE root = process.hProcess, read = readPipe.getValue();
        Thread reader = Thread.ofPlatform().daemon().name("sandbox-output").start(() -> {
            byte[] buffer = new byte[8192]; IntByReference count = new IntByReference();
            while (Kernel32.INSTANCE.ReadFile(read, buffer, buffer.length, count, null) && count.getValue() > 0) output.accept(Arrays.copyOf(buffer, count.getValue()));
            Kernel32.INSTANCE.CloseHandle(read);
        });
        return new Running() {
            private boolean closed;
            @Override public Integer waitFor(Duration timeout) throws InterruptedException {
                long deadline = System.nanoTime() + timeout.toNanos();
                while (true) {
                    if (Thread.interrupted()) throw new InterruptedException();
                    int wait = Kernel32.INSTANCE.WaitForSingleObject(root, 100);
                    if (wait == WinBase.WAIT_OBJECT_0) {
                        IntByReference code = new IntByReference();
                        Kernel32.INSTANCE.GetExitCodeProcess(root, code);
                        killTree(); reader.join(5000);
                        return code.getValue();
                    }
                    if (System.nanoTime() >= deadline) return null;
                }
            }
            @Override public synchronized void killTree() {
                if (closed) return;
                closed = true;
                // Terminating and closing the job kills every descendant, including processes started after the root exited.
                Kernel.I.TerminateJobObject(job, 1);
                Kernel32.INSTANCE.CloseHandle(job);
                Kernel32.INSTANCE.CloseHandle(root);
            }
        };
    }

    /** Quotes arguments for CommandLineToArgvW; launches are direct executables, never cmd.exe. */
    static String commandLine(List<String> arguments) {
        StringBuilder line = new StringBuilder();
        for (String argument : arguments) {
            if (argument.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL in argument");
            if (!line.isEmpty()) line.append(' ');
            if (!argument.isEmpty() && argument.chars().noneMatch(c -> c == ' ' || c == '\t' || c == '"')) { line.append(argument); continue; }
            line.append('"');
            int backslashes = 0;
            for (char c : argument.toCharArray()) {
                if (c == '\\') { backslashes++; continue; }
                if (c == '"') { line.append("\\".repeat(backslashes * 2 + 1)).append('"'); backslashes = 0; continue; }
                line.append("\\".repeat(backslashes)).append(c); backslashes = 0;
            }
            line.append("\\".repeat(backslashes * 2)).append('"');
        }
        return line.toString();
    }

    private static Memory environmentBlock(Map<String, String> environment) {
        StringBuilder block = new StringBuilder();
        new TreeMap<>(String.CASE_INSENSITIVE_ORDER) {{ putAll(environment); }}.forEach((key, value) -> block.append(key).append('=').append(value).append('\0'));
        block.append('\0');
        char[] chars = block.toString().toCharArray();
        Memory memory = new Memory((chars.length + 1L) * 2);
        memory.clear();
        memory.write(0, chars, 0, chars.length);
        return memory;
    }
}
