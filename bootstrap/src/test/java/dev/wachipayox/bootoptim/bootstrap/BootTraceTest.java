package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootTraceTest {
    @TempDir
    Path tempDirectory;

    @AfterEach
    void resetTraceProperties() {
        BootTrace.resetForTests();
        System.clearProperty(BootTrace.MODE_PROPERTY);
        System.clearProperty(BootTrace.PATH_PROPERTY);
        System.clearProperty(BootTrace.MAX_EVENTS_PROPERTY);
    }

    @Test
    void profileWritesStructuredEscapedJsonlWithoutChangingTheCaller() throws Exception {
        Path trace = tempDirectory.resolve("logs").resolve("trace.jsonl");
        System.setProperty(BootTrace.MODE_PROPERTY, "profile");
        System.setProperty(BootTrace.PATH_PROPERTY, trace.toString());
        BootTrace.configure(tempDirectory);

        BootTrace.event("resource_open", "reload", "task-1", "barrier-0", "example", "a\"b", "line\nnext");

        String line = Files.readString(trace);
        assertTrue(line.contains("\"schema\":1"));
        assertTrue(line.contains("\"kind\":\"resource_open\""));
        assertTrue(line.contains("\"resource\":\"a\\\"b\""));
        assertTrue(line.contains("\"detail\":\"line\\nnext\""));
    }

    @Test
    void benchmarkBuffersUntilExplicitFlush() throws Exception {
        Path trace = tempDirectory.resolve("trace.jsonl");
        System.setProperty(BootTrace.MODE_PROPERTY, "benchmark");
        System.setProperty(BootTrace.PATH_PROPERTY, trace.toString());
        BootTrace.configure(tempDirectory);

        BootTrace.milestone("startup", "before_flush");
        assertFalse(Files.exists(trace));

        BootTrace.flush();
        assertTrue(Files.exists(trace));
        assertTrue(Files.readString(trace).contains("before_flush"));
    }

    @Test
    void benchmarkReportsDroppedEventsWhenTheBoundedBufferIsFull() throws Exception {
        Path trace = tempDirectory.resolve("trace.jsonl");
        System.setProperty(BootTrace.MODE_PROPERTY, "benchmark");
        System.setProperty(BootTrace.PATH_PROPERTY, trace.toString());
        System.setProperty(BootTrace.MAX_EVENTS_PROPERTY, "64");
        BootTrace.configure(tempDirectory);

        for (int index = 0; index < 80; index++) {
            BootTrace.milestone("startup", "event-" + index);
        }
        BootTrace.flush();

        assertTrue(Files.readString(trace).contains("\"kind\":\"trace_drop\""));
    }
}
