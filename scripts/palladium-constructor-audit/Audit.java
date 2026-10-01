import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.mr_toad.palladium.core.Palladium;
import com.mr_toad.palladium.common.Deduplicator;
import com.mr_toad.palladium.core.mixin.ModelResourceLocationMixin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public class Audit {
    static class Target extends ModelResourceLocationMixin {}
    static class Trace extends Deduplicator<String> {
        final List<String> calls = new ArrayList<>();
        final int throwAt;
        Trace(int throwAt) { super(60, TimeUnit.MINUTES); this.throwAt = throwAt; }
        @Override public synchronized String deduplicate(String value) {
            calls.add(value);
            if (calls.size() == throwAt) throw new IllegalStateException("injected failure");
            return super.deduplicate(value);
        }
    }
    static Field variant, properties;
    static Method declare, getter;
    static long checks;
    static Object invoke(Method m, Object target, Object... args) throws Exception {
        try { return m.invoke(target, args); }
        catch (InvocationTargetException e) { throw (Exception)e.getCause(); }
    }
    // Audit candidate only: preserves stock split, receiver check and ordered calls.
    static void candidate(Target target) throws Exception {
        if (!Palladium.enabled) return;
        String[] values = ((String)variant.get(target)).split(",");
        Deduplicator<String> dedup = Objects.requireNonNull(Palladium.PROPERTIES);
        for (int i = 0; i < values.length; i++) values[i] = dedup.deduplicate(values[i]);
        properties.set(target, values); // Publish only after all calls complete.
    }
    static String error(Target target, boolean stock) throws Exception {
        try { if(stock) invoke(declare,target); else candidate(target); return "ok"; }
        catch(Exception e) { return e.getClass().getName(); }
    }
    static void check(String text, int throwAt, boolean enabled, boolean nullReceiver) throws Exception {
        Target a = new Target(), b = new Target();
        String[] oldA={"previous"}, oldB={"previous"};
        variant.set(a,text); variant.set(b,text);
        properties.set(a,oldA); properties.set(b,oldB);
        Palladium.enabled=enabled;
        Trace ta=new Trace(throwAt), tb=new Trace(throwAt);
        Palladium.PROPERTIES=nullReceiver?null:ta;
        String ea=error(a,true);
        Palladium.PROPERTIES=nullReceiver?null:tb;
        String eb=error(b,false);
        require(ea.equals(eb),"exception");
        require(ta.calls.equals(tb.calls),"ordered dedup calls");
        require(Arrays.equals(a.palladium$properties(),b.palladium$properties()),"properties");
        require((properties.get(a)==oldA)==(properties.get(b)==oldB),"publication");
        for(int i=0;i<a.palladium$properties().length;i++) {
            for(int j=0;j<a.palladium$properties().length;j++)
                require((a.palladium$properties()[i]==a.palladium$properties()[j])==
                        (b.palladium$properties()[i]==b.palladium$properties()[j]),"canonical identity");
        }
        require(ta.toString().equals(tb.toString()),"actual dedup counters");
        if(ea.equals("ok") && enabled && a.palladium$properties().length>0) {
            a.palladium$properties()[0]=null; b.palladium$properties()[0]=null;
            var ca=new CallbackInfoReturnable<String>("fallback");
            var cb=new CallbackInfoReturnable<String>("fallback");
            invoke(getter,a,ca); invoke(getter,b,cb);
            require(Objects.equals(ca.getReturnValue(),cb.getReturnValue()),"live mutation getter");
        }
        checks++;
    }
    static void require(boolean ok,String why) { if(!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        var type=ModelResourceLocationMixin.class;
        variant=type.getDeclaredField("variant"); properties=type.getDeclaredField("palladium$properties");
        declare=type.getDeclaredMethod("palladium$declareProperties");
        getter=type.getDeclaredMethod("getVariantByProperties",CallbackInfoReturnable.class);
        for(var a:List.of(variant,properties,declare,getter)) a.setAccessible(true);
        for(String text:Arrays.asList(null,"",",",",,","a","a,",",a","a,,","a,a","a,,b,","á,😀,á"))
            for(int thrown=0;thrown<5;thrown++) for(boolean enabled:new boolean[]{false,true})
                for(boolean nr:new boolean[]{false,true}) check(text,thrown,enabled,nr);
        Random r=new Random(20261002L);
        for(int n=0;n<20000;n++) {
            StringBuilder s=new StringBuilder();
            int length=r.nextInt(30);
            for(int i=0;i<length;i++) s.append("a,b=á".charAt(r.nextInt(5)));
            check(s.toString(),r.nextInt(8),true,false);
        }
        String pooled=new String("axis=y");
        boolean same=String.join(",",new String[]{pooled})==pooled;
        System.out.println("PASS actual binary constructor equivalence cases="+checks);
        System.out.println("stock single-component join returns pooled reference="+same);
        System.out.println("NO GAME TIMING; stubs replace config/MC/callback shells, not mixin or deduplicator");
    }
}
