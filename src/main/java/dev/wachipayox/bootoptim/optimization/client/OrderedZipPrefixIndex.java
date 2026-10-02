package dev.wachipayox.bootoptim.optimization.client;

import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Immutable central-directory lookup; retains original entries and their order. */
public final class OrderedZipPrefixIndex {
    private record Entry(ZipEntry zip, int ordinal) {}
    private final ZipFile owner;
    private final Entry[] sorted;
    private OrderedZipPrefixIndex(ZipFile owner, Entry[] sorted) { this.owner = owner; this.sorted = sorted; }
    public boolean owns(ZipFile zip) { return owner == zip; }
    public static OrderedZipPrefixIndex create(ZipFile zip) {
        var entries = new ArrayList<Entry>();
        var source = zip.entries();
        while (source.hasMoreElements()) {
            if (entries.size() >= 250_000) throw new IllegalArgumentException("ZIP index bound exceeded");
            entries.add(new Entry(source.nextElement(), entries.size()));
        }
        Entry[] sorted = entries.toArray(Entry[]::new);
        Arrays.sort(sorted, Comparator.comparing(e -> e.zip.getName()));
        return new OrderedZipPrefixIndex(zip, sorted);
    }
    public Enumeration<? extends ZipEntry> entries(String prefix) {
        int low = 0, high = sorted.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sorted[mid].zip.getName().compareTo(prefix) < 0) low = mid + 1;
            else high = mid;
        }
        var matches = new ArrayList<Entry>();
        for (int i = low; i < sorted.length; i++) {
            Entry entry = sorted[i];
            if (!entry.zip.getName().startsWith(prefix)) break;
            // Keep directories too: stock filtering remains downstream.
            matches.add(entry);
        }
        matches.sort(Comparator.comparingInt(Entry::ordinal));
        return Collections.enumeration(matches.stream().map(Entry::zip).toList());
    }
    public int size() { return sorted.length; }
}
