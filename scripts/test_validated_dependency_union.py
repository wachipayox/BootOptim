"""Compile real helper: current callbacks, identity/order, mutations, aliasing and bounded scopes."""
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
STUB = '''package com.mojang.logging;
public class LogUtils {
 public static final java.util.List<Object[]> records=new java.util.ArrayList<>();
 public static LogUtils getLogger(){return new LogUtils();}
 public void info(String fmt,Object...args){records.add(args);}
}'''
TEST = r'''
import dev.wachipayox.bootoptim.optimization.client.ValidatedDependencyUnion;
import com.mojang.logging.LogUtils;
import java.util.*;
import java.util.stream.*;
public class CheckUnion {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static class Key {
  final int id;
  Key(int i){id=i;}
  public int hashCode(){return id%3;} // deliberate collisions
  public boolean equals(Object o){return o instanceof Key k&&id==k.id;}
 }
 static class Custom extends Key {Custom(int i){super(i);}}
 static int callbacks;
 static Set<Key> query(Object owner,List<Key> values,boolean parallel) {
  Stream<Key> stream=(parallel?values.parallelStream():values.stream()).peek(v->callbacks++);
  var c=ValidatedDependencyUnion.collector(owner,Key.class,Collectors.<Key>toSet());
  return stream.collect(ValidatedDependencyUnion.forStream(c,stream.isParallel()));
 }
 static void equivalent(Set<Key> actual,List<Key> values){
  var expected=values.stream().collect(Collectors.toSet());
  var a=new ArrayList<>(actual);var b=new ArrayList<>(expected);
  check(a.size()==b.size());for(int i=0;i<a.size();i++)check(a.get(i)==b.get(i));
 }
 public static void main(String[] args){
  Object owner=new Object();Key a=new Key(1),b=new Key(2),equalA=new Key(1),c=new Key(4);
  List<Key> first=List.of(a,b,a,equalA,c,b);
  ValidatedDependencyUnion.loadAll(()->{
   Set<Key> original=query(owner,first,false);equivalent(original,first);
   int previous=callbacks;Set<Key> hit=query(owner,first,false);check(callbacks-previous==first.size());equivalent(hit,first);
   check(hit!=original);original.clear();equivalent(query(owner,first,false),first);
   for(List<Key> changed:List.of(List.of(b,a,c),List.of(a,b),List.of(a,b,c,a),List.of(equalA,b,c),List.of(a,b,c,new Key(7)))){
    equivalent(query(owner,changed,false),changed);equivalent(query(owner,changed,false),changed);
   }
   List<Key> unsafe=Arrays.asList(a,null,new Custom(5));equivalent(query(owner,unsafe,false),unsafe);equivalent(query(owner,first,false),first);
   query(owner,first,true); // exact stock collector in parallel
   List<Key> large=new ArrayList<>();for(int i=0;i<4100;i++)large.add(a);equivalent(query(owner,large,false),large);
   equivalent(query(owner,first,false),first);
   Random random=new Random(35119);
   List<Key> pool=new ArrayList<>();for(int i=0;i<100;i++)pool.add(new Key(i));
   for(int trial=0;trial<120;trial++){
    List<Key> changed=new ArrayList<>();for(int i=0,n=random.nextInt(180);i<n;i++)changed.add(pool.get(random.nextInt(pool.size())));
    equivalent(query(owner,changed,false),changed);equivalent(query(owner,changed,false),changed);
   }
   equivalent(query(owner,List.of(),false),List.of());equivalent(query(owner,List.of(),false),List.of());
   RuntimeException expected=new RuntimeException();
   try {first.stream().peek(v->{throw expected;}).collect(ValidatedDependencyUnion.collector(owner,Key.class,Collectors.<Key>toSet()));throw new AssertionError();}catch(RuntimeException actual){check(actual==expected);}
   equivalent(query(owner,first,false),first);
   Object nested=new Object();ValidatedDependencyUnion.loadAll(()->{equivalent(query(nested,first,false),first);equivalent(query(nested,first,false),first);});
   equivalent(query(owner,first,false),first);
  });
  Object[] report=LogUtils.records.get(LogUtils.records.size()-1);
  check((long)report[4]>0&&(long)report[6]>0&&(long)report[7]>0&&(long)report[8]>0&&(long)report[10]==0);
  check(ValidatedDependencyUnion.collector(owner,Key.class,Collectors.<Key>toSet()).characteristics().contains(Collector.Characteristics.IDENTITY_FINISH));
  RuntimeException expected=new RuntimeException();try{ValidatedDependencyUnion.loadAll(()->{throw expected;});throw new AssertionError();}catch(RuntimeException actual){check(actual==expected);}
  check(ValidatedDependencyUnion.collector(owner,Key.class,Collectors.<Key>toSet()).characteristics().contains(Collector.Characteristics.IDENTITY_FINISH));
  System.out.println("PASS current callbacks, live mutations, identity/order/collisions, fresh ownership, parallel/unsafe/large fallback and scope cleanup");
 }
}'''

with tempfile.TemporaryDirectory(prefix='bootoptim-union-') as tmp:
    directory = Path(tmp)
    (directory / 'LogUtils.java').write_text(STUB)
    (directory / 'CheckUnion.java').write_text(TEST)
    helper = ROOT / 'src/main/java/dev/wachipayox/bootoptim/optimization/client/ValidatedDependencyUnion.java'
    subprocess.run([shutil.which('javac'), '-d', tmp, str(helper), str(directory / 'LogUtils.java'), str(directory / 'CheckUnion.java')], check=True)
    for verify in ('true', 'false'):
        subprocess.run([shutil.which('java'), '-Dboot_optim.multipartValidatedUnion=true', '-Dboot_optim.verifyMultipartUnion='+verify, '-cp', tmp, 'CheckUnion'], check=True)
