package dev.wachipayox.bootoptim.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Bounded coarse early records; filesystem writes happen only on normal JVM shutdown. */
final class VarianceBootstrapBuffer {
    private final List<String> rows = new ArrayList<>();
    private int dropped;

    synchronized void add(String row) {
        if (rows.size() < 128) rows.add(row);
        else dropped++;
    }

    synchronized void write(Path path) throws IOException {
        List<String> output = new ArrayList<>(rows);
        output.add("BOOTOPTIM_VARIANCE_BUFFER rows=" + rows.size() + " dropped=" + dropped);
        Files.write(path, output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
