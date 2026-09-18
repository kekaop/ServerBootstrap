package dev.kekaop.ServerBootstrap;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Durable two-restart protocol. Only a confirmed maintenance-world boot may replace working worlds. */
final class MaintenanceCoordinator {
    private final Path root,data,pendingFile;
    private final Consumer<String> log;
    private final InstallService.Guard prepareGuard;
    private final WorldSwap swap;
    MaintenanceCoordinator(Path root,Path data,Consumer<String> log,InstallService.Guard prepareGuard) {
        this(root,data,log,prepareGuard,new WorldSwap(root,data));
    }
    MaintenanceCoordinator(Path root,Path data,Consumer<String> log,InstallService.Guard prepareGuard,WorldSwap swap) {
        this.root=root; this.data=data; this.pendingFile=data.resolve("maintenance.properties");
        this.log=log; this.prepareGuard=prepareGuard; this.swap=swap;
    }
    boolean pending() { return Files.exists(pendingFile,LinkOption.NOFOLLOW_LINKS); }
    boolean automaticStop() throws IOException { return pending() && Boolean.parseBoolean(read().getProperty("automatic-stop")); }
    InstallService.Status status() throws IOException {
        if (!pending()) return new InstallService.Status("none","none","IDLE","No pending world maintenance");
        Properties p=read();
        return new InstallService.Status(p.getProperty("operation"),p.getProperty("profile"),p.getProperty("phase"),
                "original="+p.getProperty("original")+" maintenance="+p.getProperty("temporary")+"; "+p.getProperty("message",""));
    }

    void prepare(BootstrapConfig.Profile profile,Map<String,Path> files,Path tx,ArtifactDownloader.Download download) throws IOException {
        if (pending()) throw new Failure("World maintenance is already pending");
        prepareGuard.validate(files.keySet());
        Properties server=FileOps.read(SafePaths.resolve(root,"server.properties"));
        String original=server.getProperty("level-name","world");
        if (!WorldSwap.allowedWorld(original) || !SessionLocks.heldByServer(root,original))
            throw new Failure("Cannot confirm primary world from server.properties; custom world-container or hosting override is unsupported");
        String id=tx.getFileName().toString(), temporary="sb_maintenance_"+id.replace("-","").substring(0,12);
        for (String path : List.of(temporary,temporary+"_nether",temporary+"_the_end"))
            if (Files.exists(SafePaths.resolve(root,path),LinkOption.NOFOLLOW_LINKS)) throw new Failure("Maintenance world directory already exists");
        if (profile.worlds().contains(original) && !files.containsKey(original+"/level.dat"))
            throw new Failure("Main world snapshot must contain level.dat");
        Map<String,Path> prepared=prepareWorlds(root,tx,profile.worlds(),files);
        Properties manifest=new Properties(); int count=0;
        for (var entry : prepared.entrySet()) try (var stream=Files.walk(entry.getValue())) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                interrupted();
                String destination=entry.getKey()+"/"+entry.getValue().relativize(file).toString().replace('\\','/');
                manifest.setProperty("file."+count,destination); manifest.setProperty("sha256."+count,FileOps.sha256(file)); count++;
            }
        }
        manifest.setProperty("count",Integer.toString(count));
        FileOps.properties(tx.resolve("maintenance-manifest.properties"),manifest);
        Properties p=new Properties(); p.setProperty("operation",id); p.setProperty("profile",profile.name()); p.setProperty("version",profile.version());
        p.setProperty("original",original); p.setProperty("temporary",temporary); p.setProperty("worlds",String.join(",",profile.worlds()));
        p.setProperty("artifact-sha256",download.sha256()); p.setProperty("bytes",Long.toString(download.bytes()));
        p.setProperty("manifest-sha256",FileOps.sha256(tx.resolve("maintenance-manifest.properties")));
        p.setProperty("automatic-stop",Boolean.toString(profile.restart())); p.setProperty("action","apply");
        p.setProperty("phase","WAITING_FOR_MAINTENANCE"); p.setProperty("message","Start the server with the maintenance world");
        interrupted();
        save(p); // Durable before switching; an interrupted switch never permits writing a loaded world.
        try { switchWorld(temporary); }
        catch (IOException e) { fail(p,"Could not select maintenance world; use maintenance-cancel or repair server.properties"); throw new Failure("Could not select maintenance world; no world files changed"); }
    }

    /** Invoked synchronously from onLoad. Returns true if this boot must stay closed to players. */
    boolean boot() throws IOException {
        if (!pending()) return false;
        Properties p=read(); String phase=p.getProperty("phase");
        if (phase.equals("WAITING_FOR_RETURN")) {
            if (SessionLocks.heldByServer(root,p.getProperty("original")) && !SessionLocks.held(root,p.getProperty("temporary"))) {
                log.accept("event=maintenance-finished operation="+p.getProperty("operation")+" result="+p.getProperty("result","applied"));
                FileOps.properties(transaction(p).resolve("maintenance-result.properties"),p);
                Files.delete(pendingFile); return false;
            }
            fail(p,"Original world was not selected by the hosting startup; correct level-name and start again"); return true;
        }
        if (phase.equals("MAINTENANCE_FAILED")) return true; // No automatic retry loop after an error.
        if (!phase.equals("WAITING_FOR_MAINTENANCE")) throw new Failure("Invalid maintenance phase");
        if (!SessionLocks.heldByServer(root,p.getProperty("temporary")) || SessionLocks.held(root,p.getProperty("original"))) {
            fail(p,"Maintenance world is not active; hosting may override level-name. Working worlds were not touched"); return true;
        }
        try {
            List<String> worlds=worlds(p); SessionLocks.requireFree(root,worlds);
            Path attempt=attempt(p);
            if (attempt!=null) {
                String state=swap.state(attempt);
                if (state.equals("COMMITTED")) { returnToOriginal(p,"applied"); return true; }
                swap.recover(attempt); // Recovery is permitted only while the technical world is confirmed active.
            }
            if (p.getProperty("action").equals("cancel")) { returnToOriginal(p,"cancelled"); return true; }
            Map<String,Path> prepared=verifyPrepared(p);
            attempt=transaction(p).resolve("world-attempt-"+UUID.randomUUID());
            p.setProperty("attempt",attempt.getFileName().toString()); save(p);
            Properties installed=installed(p);
            swap.apply(attempt,prepared,installed);
            returnToOriginal(p,"applied");
        } catch (IOException | RuntimeException e) {
            fail(p,e instanceof Failure ? e.getMessage() : "I/O error in world maintenance; inspect storage, permissions and attempt journal");
        }
        return true;
    }
    void retry() throws IOException {
        Properties p=read(); Path attempt=attempt(p);
        if (p.getProperty("phase").equals("WAITING_FOR_RETURN") || p.getProperty("result","").equals("cancelled") || (attempt!=null && swap.state(attempt).equals("COMMITTED"))) {
            returnToOriginal(p,p.getProperty("result","applied")); return;
        }
        p.setProperty("phase","WAITING_FOR_MAINTENANCE"); p.setProperty("message","Retry armed for a confirmed maintenance-world boot");
        save(p); switchWorld(p.getProperty("temporary"));
    }
    void cancel() throws IOException {
        Properties p=read(); Path attempt=attempt(p);
        if (attempt!=null && swap.state(attempt).equals("COMMITTED")) throw new Failure("Worlds already committed; use maintenance-retry to return to the working world");
        if (attempt!=null && !Set.of("NONE","ROLLED_BACK").contains(swap.state(attempt))) {
            p.setProperty("action","cancel"); p.setProperty("phase","WAITING_FOR_MAINTENANCE");
            p.setProperty("message","Cancellation requires rollback during a maintenance-world boot");
            save(p); switchWorld(p.getProperty("temporary"));
        } else returnToOriginal(p,"cancelled");
    }
    /** Reassert only the intended selector on shutdown; never perform world I/O during shutdown. */
    void beforeStop() throws IOException {
        if (!pending()) return;
        Properties p=read();
        switchWorld(p.getProperty("phase").equals("WAITING_FOR_RETURN") ? p.getProperty("original") : p.getProperty("temporary"));
    }
    private void returnToOriginal(Properties p,String result) throws IOException {
        // Commit is already durable. A crash around this selector change is resolved without applying twice.
        p.setProperty("phase","WAITING_FOR_RETURN"); p.setProperty("result",result);
        p.setProperty("message","Start the server with the original world; operation="+result); save(p);
        switchWorld(p.getProperty("original"));
    }
    private void switchWorld(String name) throws IOException {
        Path file=SafePaths.resolve(root,"server.properties"); Properties properties=FileOps.read(file);
        properties.setProperty("level-name",name); FileOps.properties(file,properties);
    }
    private void fail(Properties p,String message) throws IOException {
        p.setProperty("phase","MAINTENANCE_FAILED"); p.setProperty("message",message); save(p);
        log.accept("event=maintenance-failed operation="+p.getProperty("operation")+" message="+message);
    }
    private Properties read() throws IOException {
        if (!pending()) throw new Failure("No pending world maintenance");
        Properties p=FileOps.read(SafePaths.resolve(root,relative(pendingFile)));
        if (!p.getProperty("operation","").matches("[a-f0-9-]{36}") || !p.getProperty("profile","").matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                || !WorldSwap.allowedWorld(p.getProperty("original")) || !p.getProperty("temporary","").matches("sb_maintenance_[a-f0-9]{12}")
                || !Set.of("WAITING_FOR_MAINTENANCE","WAITING_FOR_RETURN","MAINTENANCE_FAILED").contains(p.getProperty("phase",""))
                || !Set.of("apply","cancel").contains(p.getProperty("action",""))) throw new Failure("Invalid maintenance state; manual recovery required");
        worlds(p); return p;
    }
    private List<String> worlds(Properties p) throws Failure {
        List<String> names=Arrays.asList(p.getProperty("worlds","").split(",",-1));
        if (names.isEmpty() || names.size()>1000 || names.stream().anyMatch(n->!WorldSwap.allowedWorld(n))
                || names.stream().map(SafePaths::key).distinct().count()!=names.size()) throw new Failure("Invalid maintenance world list");
        return names;
    }
    private Path transaction(Properties p) throws IOException { return SafePaths.resolve(root,relative(data.resolve("transactions/"+p.getProperty("operation")))); }
    private Path attempt(Properties p) throws IOException {
        String name=p.getProperty("attempt"); if (name==null) return null;
        if (!name.matches("world-attempt-[a-f0-9-]{36}")) throw new Failure("Invalid maintenance attempt path");
        return SafePaths.resolve(root,relative(transaction(p).resolve(name)));
    }
    private Map<String,Path> verifyPrepared(Properties p) throws IOException {
        Path tx=transaction(p), manifest=SafePaths.resolve(root,relative(tx.resolve("maintenance-manifest.properties")));
        if (!FileOps.sha256(manifest).equals(p.getProperty("manifest-sha256"))) throw new Failure("Prepared manifest SHA-256 mismatch");
        Properties files=FileOps.read(manifest); int count;
        try { count=Integer.parseInt(files.getProperty("count")); if (count<1 || count>1000000) throw new NumberFormatException(); }
        catch (RuntimeException e) { throw new Failure("Invalid prepared file count"); }
        Map<String,Path> result=new LinkedHashMap<>(); Set<String> expected=new HashSet<>(),actual=new HashSet<>();
        for (String world : worlds(p)) result.put(world,SafePaths.resolve(root,relative(tx.resolve("prepared-worlds/"+world))));
        for (int i=0;i<count;i++) {
            String name=SafePaths.relative(files.getProperty("file."+i));
            if (result.keySet().stream().noneMatch(w->name.startsWith(w+"/")) || !expected.add(name)) throw new Failure("Invalid prepared file path");
            Path file=SafePaths.resolve(root,relative(tx.resolve("prepared-worlds/"+name)));
            if (!FileOps.sha256(file).equals(files.getProperty("sha256."+i))) throw new Failure("Prepared world file SHA-256 mismatch");
        }
        for (var entry : result.entrySet()) try (var stream=Files.walk(entry.getValue())) {
            for (Path path : stream.toList()) {
                SafePaths.resolve(root,relative(path));
                if (Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) actual.add(entry.getKey()+"/"+entry.getValue().relativize(path).toString().replace('\\','/'));
            }
        }
        if (!expected.equals(actual)) throw new Failure("Prepared world file set changed");
        return result;
    }
    private Properties installed(Properties p) {
        Properties result=new Properties(); result.setProperty("profile",p.getProperty("profile")); result.setProperty("version",p.getProperty("version"));
        result.setProperty("sha256",p.getProperty("artifact-sha256")); result.setProperty("bytes",p.getProperty("bytes"));
        result.setProperty("operation",p.getProperty("operation")); result.setProperty("installed-at",Instant.now().toString()); return result;
    }
    private void save(Properties p) throws IOException { FileOps.properties(SafePaths.resolve(root,relative(pendingFile)),p); }
    private String relative(Path path) { return root.relativize(path).toString().replace('\\','/'); }

    static Map<String,Path> prepareWorlds(Path root,Path tx,List<String> names,Map<String,Path> files) throws IOException {
        validateSnapshot(names,files);
        Path prepared=Files.createDirectory(SafePaths.resolve(root,root.relativize(tx.resolve("prepared-worlds")).toString().replace('\\','/')));
        Map<String,Path> result=new LinkedHashMap<>();
        for (String world : names) {
            Path directory=Files.createDirectory(prepared.resolve(world)); int count=0;
            for (var file : files.entrySet()) if (file.getKey().startsWith(world+"/") && !file.getKey().equals(world+"/session.lock")) {
                interrupted();
                Path destination=SafePaths.resolve(prepared,file.getKey()); Files.createDirectories(destination.getParent());
                Files.copy(file.getValue(),destination); FileOps.force(destination); count++;
            }
            if (count==0) throw new Failure("A configured world has no files in the profile");
            result.put(world,directory);
        }
        return result;
    }
    static void validateSnapshot(List<String> names,Map<String,Path> files) throws Failure {
        for (String name : names) {
            if (!files.containsKey(name+"/level.dat")) throw new Failure("Every world snapshot must contain level.dat: "+name);
        }
    }
    private static void interrupted() throws Failure {
        if (Thread.currentThread().isInterrupted()) throw new Failure("World preparation interrupted before apply");
    }
}
