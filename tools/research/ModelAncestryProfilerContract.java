import dev.wachipayox.bootoptim.profiling.client.ModelAncestryProfiler;
import dev.wachipayox.bootoptim.profiling.client.ModelAncestryProfiler.Kind;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.LongAdder;
/** Actual diagnostic helper contract for phase routing, recursion, probe accounting and exception cleanup. */
public class ModelAncestryProfilerContract {
    static void require(boolean v){if(!v)throw new AssertionError();}
    static Object row(Kind kind,boolean bake)throws Exception{
        Field f=ModelAncestryProfiler.class.getDeclaredField("ROWS");f.setAccessible(true);
        return ((Object[])f.get(null))[kind.ordinal()+(bake?Kind.values().length:0)];
    }
    static long count(Object row,String field)throws Exception{Field f=row.getClass().getDeclaredField(field);f.setAccessible(true);return ((LongAdder)f.get(row)).sum();}
    public static void main(String[]args)throws Exception{
        for(int i=0;i<10000;i++){
            int p=ModelAncestryProfiler.begin(Kind.PARENTS,true);
            int n=ModelAncestryProfiler.begin(Kind.PARENTS,false);ModelAncestryProfiler.end(n,false);ModelAncestryProfiler.end(p,false);
            ModelAncestryProfiler.enterBake();
            int m=ModelAncestryProfiler.begin(Kind.MATERIAL,false);
            int t=ModelAncestryProfiler.begin(Kind.TEXTURE_ENTRY,false);
            ModelAncestryProfiler.textureProbe(false);ModelAncestryProfiler.textureProbe(true);ModelAncestryProfiler.end(t,false);
            if(i%2==0)ModelAncestryProfiler.aliasCheck();
            ModelAncestryProfiler.end(m,false);ModelAncestryProfiler.exitBake();
        }
        Object parents=row(Kind.PARENTS,false),material=row(Kind.MATERIAL,true),entry=row(Kind.TEXTURE_ENTRY,true);
        require(count(parents,"calls")==20000 && count(parents,"nested")==10000 && count(parents,"linked")==10000);
        require(count(parents,"samples")>0 && count(parents,"samples")<10000);
        require(count(material,"calls")==10000 && count(material,"direct")==5000 && count(material,"aliased")==5000);
        require(count(entry,"mapProbes")==20000 && count(entry,"mapHits")==10000);
        int d=ModelAncestryProfiler.begin(Kind.DEPENDENCIES,false);
        try{throw new IllegalStateException("sentinel");}catch(IllegalStateException e){require(e.getMessage().equals("sentinel"));}finally{ModelAncestryProfiler.end(d,true);}
        require(count(row(Kind.DEPENDENCIES,false),"failures")==1);
        Field f=ModelAncestryProfiler.class.getDeclaredField("INFLIGHT");f.setAccessible(true);require(((LongAdder)f.get(null)).sum()==0);
        require(count(row(Kind.PARENTS,true),"calls")==0 && count(row(Kind.MATERIAL,false),"calls")==0);
        System.out.println("MODEL_ANCESTRY_CONTRACT PASS");
    }
}
