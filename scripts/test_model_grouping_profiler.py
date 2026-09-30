"""Exercise the real diagnostic helper with a minimal logging stub, without Minecraft."""
from pathlib import Path
import json
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
config = json.loads((ROOT / 'src/main/resources/boot_optim.mixins.json').read_text())
for name in ('client.ModelGroupingScopeMixin', 'client.ModelGroupingSupplierMixin', 'client.BlockStateWorkAttributionMixin'):
    assert name in config['client'], f'Missing registered diagnostic: {name}'

STUB = r'''
package com.mojang.logging;
public final class LogUtils {
 public static final java.util.List<Object[]> records = new java.util.ArrayList<>();
 public static LogUtils getLogger() { return new LogUtils(); }
 public void info(String format, Object... args) { records.add(args); }
}
'''
TEST = r'''
import dev.wachipayox.bootoptim.profiling.client.ModelGroupingProfiler;
import com.mojang.logging.LogUtils;
public class Check {
 static void require(boolean value) { if (!value) throw new AssertionError(); }
 public static void main(String[] args) {
  Object token = new Object(); int[] calls = {0};
  require(ModelGroupingProfiler.group(() -> {calls[0]++; return token;}) == token);
  require(calls[0] == 1 && LogUtils.records.isEmpty());
  ModelGroupingProfiler.loadAll(() -> {
   require(ModelGroupingProfiler.group(() -> ModelGroupingProfiler.group(() -> token)) == token);
  });
  Object[] first = LogUtils.records.get(0);
  require(first[0].equals(true) && first[1].equals(true));
  require(first[5].equals(2L) && first[6].equals(1L) && first[7].equals(0L));
  RuntimeException failure = new RuntimeException("identity");
  try { ModelGroupingProfiler.loadAll(() -> ModelGroupingProfiler.group(() -> {throw failure;}));
   throw new AssertionError();
  } catch (RuntimeException seen) {require(seen == failure);}
  Object[] failed = LogUtils.records.get(1);
  require(failed[0].equals(false) && failed[7].equals(1L));
  ModelGroupingProfiler.group(() -> token); // no stale failed scope
  ModelGroupingProfiler.loadAll(() -> {});
  require(LogUtils.records.get(2)[1].equals(false));
  ModelGroupingProfiler.loadAll(() -> {
   ModelGroupingProfiler.loadAll(() -> ModelGroupingProfiler.group(() -> token));
   ModelGroupingProfiler.group(() -> token); // outer scope restored
  });
  require(LogUtils.records.get(3)[5].equals(1L));
  require(LogUtils.records.get(4)[5].equals(1L));
  System.out.println("PASS exact result/exception, once-only calls, nesting and scope cleanup");
 }
}
'''

WORK_TEST = r'''
import dev.wachipayox.bootoptim.profiling.client.ModelGroupingProfiler;
import dev.wachipayox.bootoptim.profiling.client.ModelGroupingProfiler.Phase;
import com.mojang.logging.LogUtils;
public class CheckWork {
 static void require(boolean v) { if(!v) throw new AssertionError(); }
 public static void main(String[] args) {
  Object token = new Object(); int[] calls = {0};
  ModelGroupingProfiler.loadAll(() -> {
   require(ModelGroupingProfiler.phase(Phase.LOCATION, () -> token) == token);
   ModelGroupingProfiler.phase(Phase.PUBLICATION, () -> {
    ModelGroupingProfiler.phase(Phase.DISCOVERY, () -> {
     ModelGroupingProfiler.phase(Phase.PARSE, () -> {calls[0]++;return token;});
     return token;
    });
    ModelGroupingProfiler.group(() -> token);
    return token;
   });
   ModelGroupingProfiler.phase(Phase.FINALIZATION, () -> token);
  });
  Object[] r = LogUtils.records.get(1);
  require(r[0].equals(true) && r[1].equals(true) && calls[0] == 1);
  double sum = 0;
  for(int i=3;i<=8;i++) { require((double)r[i]>=0); sum+=(double)r[i]; }
  require(Math.abs(sum+(double)r[9]-(double)r[2]) < 0.00001);
  for(int i=10;i<=15;i++) require(r[i].equals(1L));
  RuntimeException failure = new RuntimeException();
  try { ModelGroupingProfiler.loadAll(() -> ModelGroupingProfiler.phase(Phase.PUBLICATION,
      () -> ModelGroupingProfiler.phase(Phase.PARSE, () -> {throw failure;})));
      throw new AssertionError();
  } catch(RuntimeException seen) {require(seen == failure);}
  ModelGroupingProfiler.loadAll(() -> {});
  require(LogUtils.records.get(5)[1].equals(false));
  for(int i=3;i<=8;i++) require(LogUtils.records.get(5)[i].equals(0.0));
  System.out.println("PASS exclusive nested accounting and failed phase cleanup");
 }
}
'''

with tempfile.TemporaryDirectory(prefix="bootoptim-grouping-") as tmp:
    work = Path(tmp)
    helper = ROOT / "src/main/java/dev/wachipayox/bootoptim/profiling/client/ModelGroupingProfiler.java"
    (work / "LogUtils.java").write_text(STUB)
    (work / "Check.java").write_text(TEST)
    (work / "CheckWork.java").write_text(WORK_TEST)
    subprocess.run([shutil.which("javac"), "-d", str(work), str(helper), str(work / "LogUtils.java"), str(work / "Check.java"), str(work / "CheckWork.java")], check=True)
    subprocess.run([shutil.which("java"), "-Dboot_optim.profileModelGrouping=true", "-cp", str(work), "Check"], check=True)
    subprocess.run([shutil.which("java"), "-Dboot_optim.profileBlockStateWork=true", "-cp", str(work), "CheckWork"], check=True)
