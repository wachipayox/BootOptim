import dev.wachipayox.bootoptim.compat.client.ParentCycleSetTransform;
import dev.wachipayox.bootoptim.optimization.ParentCycleSet;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import javax.tools.ToolProvider;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Actual patched class shape plus executable original/transformed local semantics. */
public class ParentCycleSetContract {
    static int checks;
    static void require(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    static ClassNode read(byte[] bytes) { var n = new ClassNode(); new ClassReader(bytes).accept(n,0); return n; }
    static Class<?> load(ClassNode n) {
        var w = new ClassWriter(0); n.accept(w); byte[] bytes = w.toByteArray();
        return new ClassLoader(ParentCycleSetContract.class.getClassLoader()) {
            Class<?> define() { return defineClass(ParentCycleSetTransform.OWNER.replace('/','.'), bytes,0,bytes.length); }
        }.define();
    }
    public static void main(String[] args) throws Exception {
        // Prove recognition on the actual NeoForge-patched class, not just a toy signature.
        try (var in = ParentCycleSetContract.class.getClassLoader().getResourceAsStream(ParentCycleSetTransform.OWNER+".class")) {
            require(in != null); ClassNode real = read(in.readAllBytes());
            boolean active=ParentCycleSetTransform.rewrite(real); if(!active) for(MethodNode m:real.methods) if(m.desc.equals("(Ljava/util/function/Function;)V")) { System.out.println("METHOD "+m.name); for(AbstractInsnNode n:m.instructions) { System.out.println(n.getOpcode()+" "+(n instanceof MethodInsnNode c?c.owner+"."+c.name+c.desc:n instanceof VarInsnNode v?"local="+v.var:n.getClass().getSimpleName())); }} require(active); require(!ParentCycleSetTransform.rewrite(real));
        }
        Path dir = Files.createTempDirectory("parent-cycle-contract");
        Path source = dir.resolve("net/minecraft/client/renderer/block/model/BlockModel.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package net.minecraft.client.renderer.block.model;
            import java.util.*; import java.util.function.Function;
            import com.google.common.collect.Sets;
            public class BlockModel {
                public int steps; public int visited; public long unique; public int callbacks;
                public void resolveParents(Function<Object,Object> resolver) {
                    Set<Object> set = Sets.newLinkedHashSet();
                    for(int i=0;i<steps;i++) {
                        Object key=i; set.add(key);
                        Object result=resolver.apply(i);
                        if(set.contains(result)) { unique=set.stream().count(); break; }
                        visited++;
                    }
                    callbacks++; resolver.apply("geometry");
                    callbacks++; resolver.apply("override");
                }
            }
            """);
        int code=ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),source.toString());
        require(code==0); byte[] bytes=Files.readAllBytes(dir.resolve(ParentCycleSetTransform.OWNER+".class"));
        ClassNode rewritten=read(bytes); require(ParentCycleSetTransform.rewrite(rewritten));
        Class<?> stock=load(read(bytes)), candidate=load(rewritten);
        for(int steps : new int[]{0,1,2,10,100}) for(int cycle : new int[]{-1,0,1,5}) for(int throwing : new int[]{-1,0,1,5}) {
            var results=new ArrayList<String>();
            for(Class<?> type : new Class<?>[]{stock,candidate}) {
                Object model=type.getConstructor().newInstance();type.getField("steps").setInt(model,steps);
                var calls=new ArrayList<Object>();
                Function<Object,Object> resolver=x->{calls.add(x); if(calls.size()==throwing+1) throw new IllegalStateException("callback"); return x instanceof Integer i && i==cycle ? i : -99;};
                String failure="none";
                try {type.getMethod("resolveParents",Function.class).invoke(model,resolver);}
                catch(java.lang.reflect.InvocationTargetException e) {failure=e.getCause().getClass().getName();}
                results.add(calls+":"+type.getField("visited").getInt(model)+":"+type.getField("unique").getLong(model)+":"+type.getField("callbacks").getInt(model)+":"+failure);
            }
            require(results.get(0).equals(results.get(1)));
        }
        ClassNode unknown=read(bytes);
        for(MethodNode m:unknown.methods) if(m.name.equals("resolveParents")) {
            for(AbstractInsnNode n:m.instructions) if(n instanceof MethodInsnNode call && call.name.equals("stream")) {call.name="parallelStream";break;}
        }
        require(!ParentCycleSetTransform.rewrite(unknown));
        unknown=read(bytes);unknown.name="other/BlockModel";require(!ParentCycleSetTransform.rewrite(unknown));
        var set=new LinkedHashSet<>();require(ParentCycleSet.ensure(set)==set);require(ParentCycleSet.ensure(null).isEmpty());
        System.out.println("PARENT_CYCLE_SET_CONTRACT PASS checks="+checks);
    }
}


