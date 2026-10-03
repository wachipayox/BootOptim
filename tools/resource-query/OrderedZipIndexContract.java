import dev.wachipayox.bootoptim.optimization.client.OrderedZipPrefixIndex;
import java.util.*;
import java.util.zip.*;
public class OrderedZipIndexContract {
 public static void main(String[] args) throws Exception {
  ZipFile zip=new ZipFile(args[0]); var index=OrderedZipPrefixIndex.create(zip);
  var prefixes=new LinkedHashSet<String>(List.of("", "absent/", "assets/minecraft//"));
  var original=Collections.list(zip.entries());
  for(var e:original) {String n=e.getName();for(int i=n.indexOf('/');i>=0;i=n.indexOf('/',i+1))prefixes.add(n.substring(0,i+1));}
  for(String p:prefixes) {
   var expected=original.stream().filter(e->e.getName().startsWith(p)).toList();
   var actual=Collections.list(index.entries(p));
   if(expected.size()!=actual.size())throw new AssertionError(p);
   for(int i=0;i<actual.size();i++){
    ZipEntry a=actual.get(i), b=expected.get(i);
    if(!a.getName().equals(b.getName())||a.getCrc()!=b.getCrc()||a.getSize()!=b.getSize()||a.getCompressedSize()!=b.getCompressedSize()||a.getMethod()!=b.getMethod()||a.getTime()!=b.getTime())throw new AssertionError(p);
   }
  }
  var inFlight=index.entries("");zip.close();
  int rejected=0;
  try{index.entries("absent/");}catch(IllegalStateException expected){rejected++;}
  try{inFlight.hasMoreElements();}catch(IllegalStateException expected){rejected++;}
  try{inFlight.nextElement();}catch(IllegalStateException expected){rejected++;}
  if(rejected!=3)throw new AssertionError("closed owner accepted");
  System.out.println("PASS prefixes="+prefixes.size()+" entries="+original.size()+" closed_checks="+rejected);
 }
}
