import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFlags;
public class Audit {
 static volatile long sink;
 static final java.lang.management.ThreadMXBean CPU=java.lang.management.ManagementFactory.getThreadMXBean();
 static final class Quad implements ModelQuadView, dev.wachipayox.bootoptim.optimization.client.QuadCoordinateView {
  final float[] v; boolean track; int read; int failAt=-1; Quad(float[] v){this.v=v;}
  float at(int i,int a){if(track && i*3+a!=read++)throw new AssertionError("read order");if(track && read-1==failAt)throw new IllegalStateException("getter-"+failAt);return v[i*3+a];}
  public float getX(int i){return at(i,0);}public float getY(int i){return at(i,1);}public float getZ(int i){return at(i,2);}
  public int getColor(int i){return 0;}public float getTexU(int i){return 0;}public float getTexV(int i){return 0;}
  public int getVertexNormal(int i){return 0;}public int getFaceNormal(){return 0;}public int getLight(int i){return 0;}
  public int getFlags(){return 0;}public int getColorIndex(){return 0;}public TextureAtlasSprite getSprite(){return null;}
  public Direction getLightFace(){return null;}
 }
 static int candidate(Quad q,Direction face){return dev.wachipayox.bootoptim.optimization.client.SodiumQuadFlagClassifier.classify(q,face.ordinal());}
 static long checked;
 static void check(float[] v){
  Quad q=new Quad(v);q.track=true;
  for(Direction d:Direction.values()){
   q.read=0;int a=ModelQuadFlags.getQuadFlags(q,d);if(q.read!=12)throw new AssertionError("stock reads");
   q.read=0;int b=candidate(q,d);if(q.read!=12)throw new AssertionError("candidate reads");
   if(a!=b)throw new AssertionError("mismatch "+d+" "+a+" != "+b+" "+Arrays.toString(v));checked++;
  }
 }
 public static void main(String[] args){
  float[] special={-Float.MAX_VALUE,Float.NEGATIVE_INFINITY,Float.NaN,Float.POSITIVE_INFINITY,Float.MAX_VALUE,-33,-32,Math.nextDown(-32f),Math.nextUp(-32f),32,Math.nextDown(32f),Math.nextUp(32f),-0f,0f,1f,Math.nextDown(1.0E-4f),1.0E-4f,Math.nextUp(1.0E-4f),Math.nextDown(0.9999f),0.9999f,Math.nextUp(0.9999f),-1f};
  // Every pair across a face's four normal coordinates; each special coordinate also appears tangentially.
  for(float a:special)for(float b:special)for(int axis=0;axis<3;axis++){
   float[] v={0,0,0,1,1,1,0,1,0,1,0,1};for(int i=0;i<4;i++)v[i*3+axis]=(i&1)==0?a:b;check(v);
  }
  for(float s:special)for(int pos=0;pos<12;pos++){
   float[] v={0,0,0,1,1,1,0,1,0,1,0,1};v[pos]=s;check(v);
  }
  Random r=new Random(0x534f4449554dL);
  for(int k=0;k<400_000;k++){
   float[] v=new float[12];for(int i=0;i<12;i++)v[i]=Float.intBitsToFloat(r.nextInt());
   if((k&3)==0){int axis=k%3;float plane=v[axis];for(int i=1;i<4;i++)v[i*3+axis]=plane;}check(v);
  }
  for(Direction d:Direction.values())for(int fail=0;fail<12;fail++){
   Quad q=new Quad(new float[12]);q.track=true;q.failAt=fail;
   q.read=0;try{ModelQuadFlags.getQuadFlags(q,d);throw new AssertionError("stock did not throw");}catch(IllegalStateException good){if(q.read!=fail+1)throw new AssertionError("stock throw position");}
   q.read=0;try{candidate(q,d);throw new AssertionError("candidate did not throw");}catch(IllegalStateException good){if(q.read!=fail+1)throw new AssertionError("candidate throw position");}
  }
  System.out.println("semantic_cases="+checked+" mismatches=0 accessor_order=pass getter_exception_cases=72");
  if(args.length>0 && args[0].equals("semantic-only"))return;
  if(!CPU.isCurrentThreadCpuTimeSupported())throw new AssertionError("CPU clock unavailable");
  if(!CPU.isThreadCpuTimeEnabled())CPU.setThreadCpuTimeEnabled(true);
  Quad[] corpus=new Quad[8192];Direction[] dirs=Direction.values();
  for(int k=0;k<corpus.length;k++){
   float[] v=new float[12];for(int i=0;i<12;i++)v[i]=r.nextFloat()*1.5f-.25f;
   if((k&3)!=0){int axis=k%3;float plane=(k&1)==0?0f:1f;for(int i=0;i<4;i++)v[i*3+axis]=plane;}
   corpus[k]=new Quad(v);
  }
  for(int k=0;k<16;k++){bench(corpus,dirs,false,300);bench(corpus,dirs,true,300);}
  for(int k=0;k<8;k++){
   boolean first=(k&1)==0;long one=bench(corpus,dirs,first,1000),two=bench(corpus,dirs,!first,1000);
   System.out.println("pair="+k+" stock_cpu_ns="+(first?two:one)+" candidate_cpu_ns="+(first?one:two));
  }
 }
 static long bench(Quad[] qs,Direction[] ds,boolean c,int reps){
  long sum=0,start=CPU.getCurrentThreadCpuTime();for(int n=0;n<reps;n++)for(int i=0;i<qs.length;i++)sum+=c?candidate(qs[i],ds[i%6]):ModelQuadFlags.getQuadFlags(qs[i],ds[i%6]);
  long elapsed=CPU.getCurrentThreadCpuTime()-start;sink=sum;return elapsed;
 }
}
