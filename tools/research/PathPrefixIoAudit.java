import cpw.mods.niofs.union.UnionFileSystem;
import cpw.mods.niofs.union.UnionFileSystemProvider;
import cpw.mods.niofs.union.UnionPathFilter;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Offline SJH3.0.8 contract audit. No Minecraft hook or performance claim. */
public final class PathPrefixIoAudit {
    static int checks;
    static void require(boolean ok, String context) {
        if (!ok) throw new AssertionError(context);
        checks++;
    }
    static String outcome(Path path) {
        try { return "bytes:" + Base64.getEncoder().encodeToString(Files.readAllBytes(path)); }
        catch (Exception e) { return e.getClass().getName() + ":" + e.getMessage(); }
    }
    static String existence(Path path) {
        try { return Boolean.toString(Files.exists(path)); }
        catch (Exception e) { return e.getClass().getName() + ":" + e.getMessage(); }
    }
    static void compare(Path fresh, Path reused, List<String> visits) throws Exception {
        require(fresh.equals(reused) && fresh.hashCode()==reused.hashCode()
                && fresh.toString().equals(reused.toString())
                && fresh.getFileSystem()==reused.getFileSystem(), "lexical identity");
        visits.clear(); String a=existence(fresh); List<String> va=List.copyOf(visits);
        visits.clear(); String b=existence(reused); List<String> vb=List.copyOf(visits);
        require(a.equals(b) && va.equals(vb), "existence/error/filter sequence");
        visits.clear(); String ra=outcome(fresh); va=List.copyOf(visits);
        visits.clear(); String rb=outcome(reused); vb=List.copyOf(visits);
        require(ra.equals(rb) && va.equals(vb), "open bytes/error/filter sequence");
        if(a.equals("true")){
            visits.clear(); var aa=Files.readAttributes(fresh,BasicFileAttributes.class); va=List.copyOf(visits);
            visits.clear(); var ab=Files.readAttributes(reused,BasicFileAttributes.class); vb=List.copyOf(visits);
            require(aa.size()==ab.size() && aa.isDirectory()==ab.isDirectory()
                    && aa.lastModifiedTime().equals(ab.lastModifiedTime())
                    && Objects.equals(aa.fileKey(),ab.fileKey()) && va.equals(vb), "fresh attributes/filter sequence");
        }
    }
    static void put(Path base,String path,String content) throws Exception {
        Path file=base.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file,content);
    }
    public static void main(String[] args) throws Exception {
        Path temp=Files.createTempDirectory("bootoptim-prefix-io-");
        Path low=temp.resolve("low"), high=temp.resolve("high"), zip=temp.resolve("pack.zip");
        Files.createDirectories(low); Files.createDirectories(high);
        put(low,"assets/test/common.json","low"); put(high,"assets/test/common.json","high");
        put(low,"data/test/common.json","low-data"); put(high,"data/test/common.json","high-data");
        put(high,"assets/test/denied.json","denied"); put(low,"assets/test/denied.json","allowed-low");
        try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))){
            for(String dir:List.of("assets","data")){
                out.putNextEntry(new ZipEntry(dir+"/test/common.json")); out.write(("zip-"+dir).getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
                out.putNextEntry(new ZipEntry(dir+"/test/zip-only.json")); out.write("zip-only".getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
            }
        }
        List<String> visits=Collections.synchronizedList(new ArrayList<>());
        boolean[] deny={false};
        UnionPathFilter filter=(entry,base)->{
            visits.add(base.getFileName()+":"+entry);
            return !(deny[0] && base.equals(high.toAbsolutePath()) && entry.endsWith("denied.json"));
        };
        UnionFileSystemProvider provider=new UnionFileSystemProvider();
        for(Path[] bases:List.of(new Path[]{low,high},new Path[]{high,low},new Path[]{low,zip},new Path[]{zip,high})){
            UnionFileSystem fs=provider.newFileSystem(filter,bases);
            Path root=fs.getRoot(); Path assets=root.resolve("assets"), data=root.resolve("data");
            for(String dir:List.of("assets","data")){
                Path prefix=dir.equals("assets")?assets:data;
                for(String name:List.of("common.json","zip-only.json","missing.json","denied.json")){
                    compare(root.resolve(dir).resolve("test").resolve(name),prefix.resolve("test").resolve(name),visits);
                }
            }
            // Never cache winning base/content/existence: the same prefix must see fresh changes.
            Path dynamic=assets.resolve("test/dynamic.json");
            String before=outcome(dynamic);
            put(low,"assets/test/dynamic.json","created"); put(high,"assets/test/dynamic.json","created");
            require(!before.equals(outcome(dynamic)) && outcome(dynamic).startsWith("bytes:"),"dynamic create");
            put(low,"assets/test/dynamic.json","edited"); put(high,"assets/test/dynamic.json","edited");
            compare(root.resolve("assets").resolve("test/dynamic.json"),dynamic,visits);
            Files.delete(low.resolve("assets/test/dynamic.json")); Files.delete(high.resolve("assets/test/dynamic.json"));
            require(outcome(dynamic).equals(before),"dynamic delete");
            deny[0]=true;
            compare(root.resolve("assets").resolve("test/denied.json"),assets.resolve("test/denied.json"),visits);
            deny[0]=false;
            fs.close();
            compare(root.resolve("assets").resolve("test/common.json"),assets.resolve("test/common.json"),visits);
            // A new pack instance must not share the old prefix/filesystem.
            try(UnionFileSystem next=provider.newFileSystem(null,bases)){
                Path nr=next.getRoot().resolve("assets");
                require(nr.getFileSystem()!=assets.getFileSystem() && !nr.equals(assets),"pack lifetime isolation");
            }
        }
        // Shared lexical path is safe to resolve concurrently; no global namespace map needed.
        try(UnionFileSystem fs=provider.newFileSystem(null,low)){
            Path root=fs.getRoot(), prefix=root.resolve("assets");
            ExecutorService pool=Executors.newFixedThreadPool(4);
            try{
                List<Callable<Boolean>> jobs=new ArrayList<>();
                for(int j=0;j<4;j++)jobs.add(()->{
                    for(int i=0;i<1000;i++){
                        Path a=root.resolve("assets").resolve("test/common.json"),b=prefix.resolve("test/common.json");
                        if(!a.equals(b)||!outcome(a).equals(outcome(b)))return false;
                    }
                    return true;
                });
                for(Future<Boolean> result:pool.invokeAll(jobs))require(result.get(),"concurrent lexical/read equality");
            }finally{pool.shutdownNow();}
        }
        System.out.println("PASS contract_checks="+checks+" concurrent_reads=8000 directories/ZIP/priority/filters/dynamic-files/errors/close/new-pack; no game/performance evidence");
        System.out.println("Fixture retained: "+temp);
    }
}
