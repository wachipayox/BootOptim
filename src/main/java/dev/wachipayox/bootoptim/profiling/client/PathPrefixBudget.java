package dev.wachipayox.bootoptim.profiling.client;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Counts original lexical resolutions; pure exact-UnionPath replay happens after menu. */
public final class PathPrefixBudget {
 public static final boolean ENABLED=Boolean.getBoolean("boot_optim.profilePathPrefixBudget");
 private static final ConcurrentHashMap<Key,Stats> CALLS=new ConcurrentHashMap<>();
 private static volatile Path sink;
 private PathPrefixBudget(){}
 private record Key(Path root,String directory){
  @Override public boolean equals(Object o){return o instanceof Key k && root==k.root && directory.equals(k.directory);}
  @Override public int hashCode(){return 31*System.identityHashCode(root)+directory.hashCode();}
 }
 private static final class Stats { final LongAdder count=new LongAdder(); final Path observed; volatile boolean consistent=true; Stats(Path observed){this.observed=observed;} }
 public static void record(Path root,String directory,Path observed){
  if(root.getClass().getName().equals("cpw.mods.niofs.union.UnionPath")){var stats=CALLS.computeIfAbsent(new Key(root,directory),k->new Stats(observed));stats.count.increment();if(!stats.observed.equals(observed))stats.consistent=false;}
 }
 public static void report(){
  if(!ENABLED)return;
  var logger=LogUtils.getLogger();var cpu=ManagementFactory.getThreadMXBean();
  long total=CALLS.values().stream().mapToLong(v->v.count.sum()).sum();
  if(!cpu.isCurrentThreadCpuTimeSupported()||!cpu.isThreadCpuTimeEnabled()||total==0||total>2000000){logger.info("BOOTOPTIM_PATH_PREFIX status=unavailable calls={}",total);return;}
  long[] times=new long[4];boolean equivalent=true;
  // Warm both sides; outside all observed startup timing boundaries.
  for(var item:CALLS.entrySet()){
   var k=item.getKey();Path prefix=k.root.resolve(k.directory);
   equivalent &= item.getValue().consistent && prefix.equals(item.getValue().observed);
   for(int i=0;i<5000;i++){sink=k.root.resolve(k.directory);sink=prefix;}
  }
  for(int block=0;block<4;block++){
   long elapsed=0;
   for(var item:CALLS.entrySet()){
    var k=item.getKey();long count=item.getValue().count.sum();Path prefix=k.root.resolve(k.directory);
    long start=cpu.getCurrentThreadCpuTime();
    for(long i=0;i<count;i++)sink=(block==0||block==3)?k.root.resolve(k.directory):prefix;
    elapsed+=cpu.getCurrentThreadCpuTime()-start;
   }
   times[block]=elapsed;
  }
  logger.info("BOOTOPTIM_PATH_PREFIX status=complete rows={} calls={} equivalent={} c1_cpu_ns={} b1_cpu_ns={} b2_cpu_ns={} c2_cpu_ns={}",CALLS.size(),total,equivalent,times[0],times[1],times[2],times[3]);
 }
}
