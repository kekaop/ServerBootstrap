package dev.kekaop.ServerBootstrap;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Whole-directory replacement. Intent is durable before either rename; old worlds are never deleted. */
final class WorldSwap {
    @FunctionalInterface interface Step { void at(String phase,int index) throws IOException; }
    private final Path root,data;
    private final Step step;
    WorldSwap(Path root,Path data) { this(root,data,(phase,i)->{}); }
    WorldSwap(Path root,Path data,Step step) { this.root=root; this.data=data; this.step=step; }
    static boolean allowedWorld(String name) {
        return name!=null && name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
                && !name.toLowerCase(Locale.ROOT).startsWith("sb_maintenance_")
                && !Set.of("plugins","logs","config","libraries","versions","cache","crash-reports","bundler").contains(name.toLowerCase(Locale.ROOT));
    }
    void apply(Path attempt,Map<String,Path> worlds,Properties installed) throws IOException {
        SessionLocks.requireFree(root,worlds.keySet());
        Properties plan=new Properties(); int i=0;
        for (var entry : worlds.entrySet()) {
            target(entry.getKey()); checked(entry.getValue());
            if (!Files.isDirectory(entry.getValue(),LinkOption.NOFOLLOW_LINKS)) throw new Failure("Prepared world directory is missing");
            plan.setProperty("world."+i,entry.getKey()); plan.setProperty("source."+i,relative(entry.getValue()));
            plan.setProperty("existed."+i,Boolean.toString(Files.exists(target(entry.getKey()),LinkOption.NOFOLLOW_LINKS))); i++;
        }
        plan.setProperty("count",Integer.toString(i));
        Files.createDirectory(checked(attempt)); Files.createDirectory(attempt.resolve("backup"));
        Path state=checked(data.resolve("installed.properties"));
        plan.setProperty("state-existed",Boolean.toString(Files.exists(state)));
        if (Files.exists(state)) {
            Files.copy(state,attempt.resolve("previous-installed.properties")); FileOps.force(attempt.resolve("previous-installed.properties"));
            plan.setProperty("state-sha256",FileOps.sha256(attempt.resolve("previous-installed.properties")));
        }
        FileOps.properties(attempt.resolve("plan.properties"),plan);
        FileOps.properties(attempt.resolve("next-installed.properties"),installed);
        int intended=0; boolean stateIntent=false;
        phase(attempt,"PREPARED",0,false);
        try {
            for (i=0;i<worlds.size();i++) {
                phase(attempt,"APPLYING",i+1,false); intended=i+1;
                Path destination=target(plan.getProperty("world."+i));
                Path source=checked(root.resolve(plan.getProperty("source."+i)));
                step.at("before-backup",i);
                if (Files.exists(destination)) move(destination,attempt.resolve("backup/"+i));
                step.at("after-backup",i);
                move(source,destination); step.at("after-install",i);
            }
            phase(attempt,"APPLYING",intended,true); stateIntent=true;
            step.at("before-state",i); FileOps.replace(attempt.resolve("next-installed.properties"),state); step.at("after-state",i);
            phase(attempt,"COMMITTED",intended,true);
        } catch (IOException | RuntimeException e) {
            boolean interrupted=Thread.interrupted();
            try { rollback(attempt,plan,intended,stateIntent); }
            catch (IOException failure) { throw new Failure("World rollback incomplete; see maintenance attempt rollback.properties and backup directory"); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw new Failure("World apply failed; previous worlds and installed state restored");
        }
    }
    String state(Path attempt) throws IOException {
        Path file=checked(attempt.resolve("phase.properties"));
        return Files.exists(file) ? FileOps.read(file).getProperty("state","") : "NONE";
    }
    void recover(Path attempt) throws IOException {
        String state=state(attempt);
        if (Set.of("NONE","COMMITTED","ROLLED_BACK").contains(state)) return;
        if (!Set.of("PREPARED","APPLYING","ROLLBACK_FAILED").contains(state)) throw new Failure("Invalid maintenance transaction phase");
        Properties plan=FileOps.read(checked(attempt.resolve("plan.properties")));
        List<String> worlds=new ArrayList<>();
        for (int i=0;i<count(plan,"count");i++) { target(plan.getProperty("world."+i)); worlds.add(plan.getProperty("world."+i)); }
        SessionLocks.requireFree(root,worlds);
        Properties phase=FileOps.read(attempt.resolve("phase.properties"));
        rollback(attempt,plan,count(phase,"intended"),Boolean.parseBoolean(phase.getProperty("state-intent")));
    }
    private void rollback(Path attempt,Properties plan,int intended,boolean stateIntent) throws IOException {
        if (intended>count(plan,"count")) throw new Failure("Invalid maintenance transaction count");
        Properties errors=new Properties(); int failures=0;
        if (stateIntent) try {
            Path current=checked(data.resolve("installed.properties"));
            if (Boolean.parseBoolean(plan.getProperty("state-existed"))) {
                Path saved=checked(attempt.resolve("previous-installed.properties"));
                if (!FileOps.sha256(saved).equals(plan.getProperty("state-sha256"))) throw new Failure("State backup checksum mismatch");
                FileOps.replace(saved,current);
            } else Files.deleteIfExists(current);
        } catch (IOException e) { errors.setProperty("failed."+(failures++),"installed.properties"); }
        for (int i=intended-1;i>=0;i--) {
            String name=plan.getProperty("world."+i);
            try {
                Path destination=target(name), source=checked(root.resolve(plan.getProperty("source."+i))), backup=checked(attempt.resolve("backup/"+i));
                boolean existed=Boolean.parseBoolean(plan.getProperty("existed."+i));
                if (Files.exists(backup)) {
                    if (Files.exists(destination)) {
                        if (Files.exists(source)) throw new Failure("Ambiguous world rollback paths");
                        move(destination,source);
                    }
                    move(backup,destination);
                } else if (!existed && !Files.exists(source) && Files.exists(destination)) move(destination,source);
                else if (!Files.exists(source) || (existed && !Files.exists(destination)) || (!existed && Files.exists(destination)))
                    throw new Failure("Missing backup or unexpected destination during rollback");
            } catch (IOException | RuntimeException e) { errors.setProperty("failed."+(failures++),name==null ? "invalid world" : name); }
        }
        errors.setProperty("failed-count",Integer.toString(failures)); FileOps.properties(attempt.resolve("rollback.properties"),errors);
        phase(attempt,failures==0 ? "ROLLED_BACK" : "ROLLBACK_FAILED",intended,stateIntent);
        if (failures!=0) throw new Failure("Incomplete world rollback");
    }
    private void phase(Path attempt,String state,int intended,boolean stateIntent) throws IOException {
        Properties p=new Properties(); p.setProperty("state",state); p.setProperty("intended",Integer.toString(intended));
        p.setProperty("state-intent",Boolean.toString(stateIntent)); FileOps.properties(attempt.resolve("phase.properties"),p);
    }
    private static int count(Properties p,String name) throws Failure {
        try { int n=Integer.parseInt(p.getProperty(name)); if (n<0 || n>1000) throw new NumberFormatException(); return n; }
        catch (RuntimeException e) { throw new Failure("Invalid maintenance journal count"); }
    }
    private Path target(String name) throws IOException {
        if (!allowedWorld(name)) throw new Failure("Invalid world directory in maintenance transaction");
        Path path=SafePaths.resolve(root,name);
        if (Files.exists(path,LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) throw new Failure("World path is not a directory");
        return path;
    }
    private Path checked(Path path) throws IOException {
        if (!path.normalize().startsWith(data)) throw new Failure("Maintenance journal path escapes plugin data");
        return SafePaths.resolve(root,relative(path));
    }
    private void move(Path from,Path to) throws IOException {
        // Both resolved absolute paths are checked against the server root before a directory move.
        SafePaths.resolve(root,relative(from)); SafePaths.resolve(root,relative(to));
        if (Files.exists(to,LinkOption.NOFOLLOW_LINKS)) throw new Failure("Maintenance destination already exists");
        Files.move(from,to,StandardCopyOption.ATOMIC_MOVE);
    }
    private String relative(Path path) { return root.relativize(path).toString().replace('\\','/'); }
}
