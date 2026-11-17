package dev.jharness.spike;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resident memory of this process, read in-process so the spike starts no child process
 * (constitution Article IV): {@code /proc/self/status} on Linux, {@code proc_pid_rusage} on macOS.
 */
final class Rss {

    private static final boolean LINUX = System.getProperty("os.name").startsWith("Linux");
    private static final boolean MAC = System.getProperty("os.name").startsWith("Mac");

    // struct rusage_info_v0: uint8_t ri_uuid[16]; then uint64_t fields. ri_resident_size is the 7th.
    private static final long RI_RESIDENT_SIZE_OFFSET = 16 + 6 * 8;
    private static final int RUSAGE_INFO_V0 = 0;
    private static final long RUSAGE_INFO_V0_SIZE = 16 + 12 * 8;

    private static MethodHandle procPidRusage;

    private Rss() {}

    /** Resident set size in bytes, or -1 if this platform can't report it. */
    static long bytes() {
        try {
            if (LINUX) {
                for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                    if (line.startsWith("VmRSS:")) {
                        return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
                    }
                }
                return -1;
            }
            if (MAC) {
                return macResident();
            }
        } catch (IOException | RuntimeException e) {
            return -1;
        } catch (Throwable t) {
            return -1;
        }
        return -1;
    }

    static String method() {
        return LINUX ? "/proc/self/status VmRSS" : MAC ? "proc_pid_rusage ri_resident_size" : "unsupported";
    }

    private static long macResident() throws Throwable {
        if (procPidRusage == null) {
            Linker linker = Linker.nativeLinker();
            procPidRusage = linker.downcallHandle(
                    linker.defaultLookup().find("proc_pid_rusage").orElseThrow(),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(RUSAGE_INFO_V0_SIZE, 8);
            int rc = (int) procPidRusage.invokeExact((int) ProcessHandle.current().pid(), RUSAGE_INFO_V0, info);
            return rc == 0 ? info.get(JAVA_LONG, RI_RESIDENT_SIZE_OFFSET) : -1;
        }
    }
}
