import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.lang.management.*;

/** Offline ordered prefix replay. No game/cache/runtime dependencies. */
public class OrderedPrefixReplay {
    record Entry(ZipEntry zip, int ordinal) {}
    static volatile long sink;
    static final ThreadMXBean CPU = ManagementFactory.getThreadMXBean();
    static Entry[] snapshot(ZipFile zip) {
        var list = new ArrayList<Entry>();
        var it = zip.entries();
        while(it.hasMoreElements()) list.add(new Entry(it.nextElement(), list.size()));
        return list.toArray(Entry[]::new);
    }
    static Entry[] index(Entry[] stock) {
        Entry[] sorted = stock.clone();
        Arrays.sort(sorted, Comparator.comparing(e -> e.zip.getName()));
        return sorted;
    }
    static int lower(Entry[] sorted, String prefix) {
        int low=0, high=sorted.length;
        while(low<high) { int mid=(low+high)>>>1;
            if(sorted[mid].zip.getName().compareTo(prefix)<0) low=mid+1; else high=mid;
        }
        return low;
    }
    static List<Entry> indexed(Entry[] sorted, String prefix) {
        var matches = new ArrayList<Entry>();
        for(int i=lower(sorted,prefix);i<sorted.length;i++) {
            var e=sorted[i]; if(!e.zip.getName().startsWith(prefix)) break;
            if(!e.zip.isDirectory()) matches.add(e);
        }
        matches.sort(Comparator.comparingInt(Entry::ordinal));
        return matches;
    }
    static List<Entry> scan(Entry[] stock, String prefix) {
        var matches = new ArrayList<Entry>();
        for(var e:stock) if(!e.zip.isDirectory() && e.zip.getName().startsWith(prefix)) matches.add(e);
        return matches;
    }
    static long consume(List<Entry> matches) {
        long sum=1;
        for(var e:matches) sum=31*sum+e.ordinal;
        return sum;
    }
    static long run(ZipFile zip, Entry[] snapshot, Entry[] index, List<String> queries, int mode, int repetitions) {
        long value=0;
        for(int r=0;r<repetitions;r++) for(String q:queries) {
            if(mode==0) {
                long sum=1; int ordinal=0; var entries=zip.entries();
                while(entries.hasMoreElements()) { var e=entries.nextElement();
                    if(!e.isDirectory() && e.getName().startsWith(q)) sum=31*sum+ordinal;
                    ordinal++;
                }
                value+=sum;
            } else value+=consume(mode==1?scan(snapshot,q):indexed(index,q));
        }
        sink=value; return value;
    }
    public static void main(String[] args) throws Exception {
        var queries=Files.readAllLines(Path.of(args[1]));
        try(var zip=new ZipFile(args[0])) {
            long start=CPU.getCurrentThreadCpuTime(); var stock=snapshot(zip); var sorted=index(stock);
            long build=CPU.getCurrentThreadCpuTime()-start;
            var exhaustive=new LinkedHashSet<String>(queries);
            for(var e:stock) {
                String name=e.zip.getName();
                for(int i=name.indexOf('/');i>=0;i=name.indexOf('/',i+1)) exhaustive.add(name.substring(0,i+1));
            }
            exhaustive.add("missing/"); exhaustive.add("assets/minecraft//");
            for(String q:exhaustive) {
                var expected=scan(stock,q); var actual=indexed(sorted,q);
                if(!expected.equals(actual)) throw new AssertionError("ordered mismatch: "+q);
            }
            System.out.printf(Locale.ROOT,"semantic_pass entries=%d queries=%d exhaustive=%d index_build_cpu_ms=%.3f%n",stock.length,queries.size(),exhaustive.size(),build/1e6);
            for(int i=0;i<8;i++) for(int mode=0;mode<3;mode++) run(zip,stock,sorted,queries,mode,1);
            for(int mode:new int[]{0,1,2,2,1,0}) {
                long cpu=CPU.getCurrentThreadCpuTime(), wall=System.nanoTime();
                long value=run(zip,stock,sorted,queries,mode,12);
                System.out.printf(Locale.ROOT,"mode=%d repetitions=12 cpu_ms=%.3f wall_ms=%.3f checksum=%d%n",mode,(CPU.getCurrentThreadCpuTime()-cpu)/1e6,(System.nanoTime()-wall)/1e6,value);
            }
        }
    }
}
