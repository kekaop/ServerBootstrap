package dev.kekaop.ServerBootstrap;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class InstallService implements AutoCloseable {
    public record Status(String id,String profile,String state,String message) {}
    public record Installed(String profile,String version,String sha256,String installedAt) {}
    @FunctionalInterface public interface Guard { void validate(Set<String> paths) throws IOException; }
    @FunctionalInterface interface Fetch { ArtifactDownloader.Download get(ArtifactSource.Request request,Path target,BootstrapConfig.Limits limits) throws IOException; }
    private final BootstrapConfig config;
    private final TransactionInstaller installer;
    private final Path stateFile;
    private final String stateRelative;
    private final Set<String> protectedPaths;
    private final Guard guard;
    private final Consumer<String> log;
    private final Runnable restart;
    private final Fetch fetch;
    private final MaintenanceCoordinator coordinator;
    private final Runnable maintenanceStop;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"ServerBootstrap-install"); t.setDaemon(true); return t; });
    private final AtomicBoolean busy=new AtomicBoolean();
    private volatile Status status=new Status("none","none","IDLE","No operation");
    private volatile Installed installed;
    private volatile boolean closed, recoveryRequired, restartPending, maintenance;

    public InstallService(BootstrapConfig config,TransactionInstaller installer,Path root,Path data,Set<String> protectedPaths,
                          Guard guard,Consumer<String> log,Runnable restart) throws IOException {
        this(config,installer,root,data,protectedPaths,guard,log,restart,new ArtifactDownloader(config.limits())::download);
    }
    InstallService(BootstrapConfig config,TransactionInstaller installer,Path root,Path data,Set<String> protectedPaths,
                   Guard guard,Consumer<String> log,Runnable restart,Fetch fetch) throws IOException {
        this(config,installer,root,data,protectedPaths,guard,log,restart,fetch,null,()->{});
    }
    InstallService(BootstrapConfig config,TransactionInstaller installer,Path root,Path data,Set<String> protectedPaths,
                   Guard guard,Consumer<String> log,Runnable restart,Fetch fetch,MaintenanceCoordinator coordinator,Runnable maintenanceStop) throws IOException {
        this.coordinator=coordinator; this.maintenanceStop=maintenanceStop;
        this.config=config; this.installer=installer; this.stateFile=data.resolve("installed.properties");
        this.stateRelative=root.relativize(stateFile).toString().replace('\\','/');
        this.protectedPaths=Set.copyOf(protectedPaths); this.guard=guard; this.log=log; this.restart=restart; this.fetch=fetch;
        if (Files.exists(SafePaths.resolve(root,stateRelative))) {
            Properties p=FileOps.read(stateFile);
            String name=p.getProperty("profile","");
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw new Failure("Invalid installed state");
            installed=new Installed(name,p.getProperty("version","unknown"),p.getProperty("sha256",""),p.getProperty("installed-at","unknown"));
        } else {
            installed=new Installed(config.legacyBound(),"unknown","","unknown");
            if (config.isLegacy() && !installed.profile().equals("none")) {
                Properties imported=new Properties(); imported.setProperty("profile",installed.profile());
                imported.setProperty("version","unknown"); imported.setProperty("installed-at","unknown");
                imported.setProperty("origin","legacy-binding-import"); FileOps.properties(stateFile,imported);
                log.accept("event=legacy-binding-import profile="+installed.profile()+" version=unknown");
            }
        }
    }
    public Status status() { return status; }
    public Installed installed() { return installed; }
    public boolean isBusy() { return busy.get(); }
    public boolean inMaintenance() { return maintenance; }
    public String start(String name,boolean checkOnly,Consumer<Status> completion) throws Failure {
        BootstrapConfig.Profile profile=config.profile(name);
        if (closed) throw new Failure("Plugin is stopping");
        if (recoveryRequired) throw new Failure("Recovery required; inspect transaction backups and restart the server");
        if (restartPending || (coordinator!=null && coordinator.pending())) throw new Failure("Restart or finish pending maintenance before another operation");
        if (profile.maintenance() && coordinator==null && !checkOnly) throw new Failure("Maintenance profiles require the server plugin; use direct mode with the offline installer");
        if (!busy.compareAndSet(false,true)) throw new Failure("Another operation is already running");
        // Recheck flags after acquisition: the previous operation may have committed between the reads and CAS.
        if (closed || recoveryRequired || restartPending) {
            busy.set(false); throw new Failure("Node is stopping or requires recovery/restart before another operation");
        }
        if (!checkOnly && !installed.profile().equals("none") && !installed.profile().equals(name)) {
            busy.set(false); throw new Failure("Node is bound to profile " + installed.profile());
        }
        String id=UUID.randomUUID().toString(); status=new Status(id,name,"QUEUED","Operation accepted");
        try { worker.execute(() -> run(profile,checkOnly,completion,id)); }
        catch (RejectedExecutionException e) { busy.set(false); throw new Failure("Plugin is stopping"); }
        return id;
    }
    private void run(BootstrapConfig.Profile p,boolean checkOnly,Consumer<Status> completion,String id) {
        Path tx=null; boolean shouldRestart=false;
        try {
            tx=installer.create(id);
            update(id,p,"DOWNLOADING","Downloading artifact");
            ArtifactDownloader.Download download=fetch.get(ArtifactSource.forType(p.source().type()).resolve(p.source()),tx.resolve("artifact.zip"),config.limits());
            ArtifactDownloader.verify(download,p.sha256());
            update(id,p,"VERIFYING","bytes="+download.bytes()+" sha256="+download.sha256()+" checksum="+(p.sha256().isEmpty()?"not-configured":"passed"));
            ArchiveVerifier.Plan plan=new ArchiveVerifier().extract(tx.resolve("artifact.zip"),tx.resolve("staging"),p,config.limits());
            Map<String,Path> files=new LinkedHashMap<>(plan.files());
            for (String file : files.keySet()) {
                if (protectedPaths.stream().anyMatch(protectedPath -> SafePaths.under(file,protectedPath))) throw new Failure("Archive targets a protected runtime file");
                installer.ensureDestination(file);
            }
            Properties preview=new Properties(); int index=0;
            for (String path : files.keySet()) preview.setProperty("file."+(index++),path);
            preview.setProperty("count",Integer.toString(files.size()));
            preview.setProperty("bytes",Long.toString(download.bytes())); preview.setProperty("sha256",download.sha256());
            preview.setProperty("profile",p.name()); preview.setProperty("version",p.version()); preview.setProperty("source",p.source().type());
            FileOps.properties(tx.resolve("preview.properties"),preview);
            if (p.maintenance()) MaintenanceCoordinator.validateSnapshot(p.worlds(),files);
            if (checkOnly) update(id,p,"CHECKED","Artifact valid; planned files="+files.size()+"; no server files changed");
            else if (p.maintenance()) {
                if (closed || Thread.currentThread().isInterrupted()) throw new Failure("Operation interrupted before maintenance preparation");
                maintenance=true;
                coordinator.prepare(p,files,tx,download);
                restartPending=true; shouldRestart=p.restart();
                update(id,p,"WAITING_FOR_MAINTENANCE","World snapshot prepared; two server starts required; automatic stop="+p.restart());
            }
            else {
                if (closed || Thread.currentThread().isInterrupted()) throw new Failure("Operation interrupted before apply");
                maintenance=true;
                guard.validate(Collections.unmodifiableSet(files.keySet()));
                Properties state=new Properties(); state.setProperty("profile",p.name()); state.setProperty("version",p.version());
                state.setProperty("sha256",download.sha256()); state.setProperty("installed-at",Instant.now().toString());
                state.setProperty("operation",id); state.setProperty("bytes",Long.toString(download.bytes()));
                Path stagedState=tx.resolve("installed.properties"); FileOps.properties(stagedState,state);
                files.put(stateRelative,stagedState); // binding/version commit is the final member of the same transaction
                update(id,p,"APPLYING","Applying files="+(files.size()-1));
                installer.apply(files,tx);
                installed=new Installed(p.name(),p.version(),download.sha256(),state.getProperty("installed-at"));
                update(id,p,"SUCCEEDED","Installed files="+(files.size()-1)+"; restart="+p.restart());
                shouldRestart=p.restart(); restartPending=shouldRestart;
            }
        } catch (Exception e) {
            recoveryRequired=(tx!=null && installer.needsRecovery(tx)) || (coordinator!=null && coordinator.pending());
            String message=e instanceof Failure ? e.getMessage() : "I/O or internal error; inspect disk space, permissions and transaction journal";
            update(id,p,"FAILED",message);
        } finally {
            Status finished=status;
            maintenance=restartPending || recoveryRequired;
            busy.set(false);
            try { completion.accept(finished); } catch (RuntimeException e) { log.accept("event=notification-failed operation="+id); }
        }
        if (shouldRestart && !closed) {
            try { if (p.maintenance()) maintenanceStop.run(); else restart.run(); }
            catch (RuntimeException e) { update(id,p,"RESTART_REQUIRED","Automatic stop/restart failed. Restart the server manually"); }
        }
    }
    private void update(String id,BootstrapConfig.Profile profile,String phase,String message) {
        status=new Status(id,profile.name(),phase,message);
        log.accept("event=installation operation="+id+" profile="+profile.name()+" version="+profile.version()+" source="+profile.source().type()+" phase="+phase+" message="+message);
    }
    @Override public void close() {
        closed=true; worker.shutdownNow();
        try { if (!worker.awaitTermination(30,TimeUnit.SECONDS)) log.accept("event=shutdown-timeout recovery=required-on-next-start"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
