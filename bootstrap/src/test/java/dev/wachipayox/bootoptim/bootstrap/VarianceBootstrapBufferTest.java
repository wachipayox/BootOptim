package dev.wachipayox.bootoptim.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VarianceBootstrapBufferTest {
    @TempDir Path directory;

    @Test void boundsRecordsAndPreservesOrderWithoutOverwritingEvidence() throws Exception {
        var buffer = new VarianceBootstrapBuffer();
        for (int i = 0; i < 130; i++) buffer.add("record=" + i);
        Path output = directory.resolve("early.log");
        assertFalse(Files.exists(output));
        buffer.write(output);
        var rows = Files.readAllLines(output);
        assertEquals(129, rows.size());
        assertEquals("record=0", rows.getFirst());
        assertEquals("record=127", rows.get(127));
        assertEquals("BOOTOPTIM_VARIANCE_BUFFER rows=128 dropped=2", rows.getLast());
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> buffer.write(output));
        assertEquals(rows, Files.readAllLines(output));
    }
}
