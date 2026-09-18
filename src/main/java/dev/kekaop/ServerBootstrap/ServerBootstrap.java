package dev.kekaop.ServerBootstrap;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerLoginEvent;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class ServerBootstrap extends JavaPlugin implements Listener {
    private InstallService service;
    private UpdateListener listener;
    private TransactionInstaller installer;
    private NodeLock lock;
    private Path root,data;
    private String startupFailure;
    private MaintenanceCoordinator coordinator;
    private boolean restricted;

    @Override public void onLoad() {
        try {
            root=Path.of(".").toRealPath(); data=getDataFolder().toPath().toAbsolutePath().normalize();
            if (!data.startsWith(root)) throw new Failure("Plugin data directory must be inside server root");
            SafePaths.resolve(root,root.relativize(data).toString().replace('\\','/')); Files.createDirectories(data);
            lock=new NodeLock(root,data);
            installer=new TransactionInstaller(root,data.resolve("transactions"));
            // Main-world metadata has already been read here. Never recover world files blindly in onLoad.
            installer.assertClean();
            coordinator=new MaintenanceCoordinator(root,data,getLogger()::info,paths -> guard(paths,true));
            restricted=coordinator.boot();
        } catch (Exception e) { startupFailure=e instanceof Failure ? e.getMessage() : "Cannot initialize storage or recover transaction journal"; }
    }
    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this,this);
        if (startupFailure!=null || restricted) {
            registerCommands(null);
            if (startupFailure!=null) getLogger().severe(startupFailure);
            else try {
                getLogger().warning(UpdateListener.json(coordinator.status()));
                if (coordinator.status().state().equals("WAITING_FOR_RETURN") && coordinator.automaticStop()) stopForMaintenance();
            } catch (IOException e) { failStartup("Cannot read pending maintenance; inspect maintenance.properties"); }
            return;
        }
        try {
            saveDefaultConfig();
            BootstrapConfig config=ConfigFiles.load(data.resolve("config.yml"));
            if (config.isLegacy()) getLogger().warning("Legacy configuration: listener disabled; migrate using docs/MIGRATION.md. Default branch and missing checksum are not pinned artifacts.");
            Set<String> protectedPaths=Set.of(root.relativize(getFile().toPath().toAbsolutePath().normalize()).toString().replace('\\','/'),
                    root.relativize(data).toString().replace('\\','/'));
            service=new InstallService(config,installer,root,data,protectedPaths,this::guard,getLogger()::info,
                    () -> getServer().getScheduler().runTask(this,() -> {
                        getLogger().warning("Installation committed; requesting server restart");
                        getServer().spigot().restart();
                    }),new ArtifactDownloader(config.limits())::download,coordinator,this::stopForMaintenance);
            registerCommands(config);
            if (config.listener().enabled()) {
                listener=new UpdateListener(config.listener(),service,getLogger()::info); listener.start();
                getLogger().info("HTTP listener enabled at "+config.listener().bind()+":"+config.listener().port());
            }
            getLogger().info("ServerBootstrap enabled; configuration valid; API baseline=1.20; Java="+Runtime.version().feature());
        } catch (Exception e) { failStartup(e instanceof Failure ? e.getMessage() : "Cannot enable plugin; verify config.yml syntax, permissions, storage and listener port"); }
    }
    private void registerCommands(BootstrapConfig config) {
        SetupCommand command=new SetupCommand(this,service,config);
        for (String name : List.of("setup-server","serverbootstrap")) {
            Objects.requireNonNull(getCommand(name)).setExecutor(command);
            Objects.requireNonNull(getCommand(name)).setTabCompleter(command);
        }
    }
    private void failStartup(String message) {
        startupFailure=message; restricted=true; getLogger().severe(message);
        if (listener!=null) { listener.close(); listener=null; }
        if (service!=null) { service.close(); service=null; }
        registerCommands(null); // Keep admission blocked and diagnostics available instead of silently disabling the guard.
    }
    InstallService.Status maintenanceStatus() throws IOException {
        if (startupFailure!=null) return new InstallService.Status("none","none","STARTUP_FAILED",startupFailure);
        return coordinator.status();
    }
    void maintenanceAction(boolean cancel) throws IOException {
        if (startupFailure!=null || coordinator==null) throw new Failure("Repair the startup failure before maintenance commands");
        if (service!=null && service.isBusy()) throw new Failure("Wait for the current operation to finish");
        if (cancel) coordinator.cancel(); else coordinator.retry();
        restricted=true;
        if (coordinator.automaticStop()) stopForMaintenance();
    }
    private void stopForMaintenance() {
        getServer().getScheduler().runTask(this,() -> {
            getLogger().warning("World maintenance requires another server start. Stopping; use hosting auto-start or the panel Start button.");
            getServer().shutdown();
        });
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (restricted || startupFailure!=null || (coordinator!=null && coordinator.pending()) || (service!=null && service.inMaintenance()))
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,"Server maintenance: profile installation in progress");
    }
    private void guard(Set<String> paths) throws IOException {
        guard(paths,false);
    }
    private void guard(Set<String> paths,boolean preparingWorlds) throws IOException {
        try {
            getServer().getScheduler().callSyncMethod(this,() -> {
                if (!getServer().getOnlinePlayers().isEmpty()) throw new Failure("Players are online; drain the node before applying files");
                if (preparingWorlds) return null;
                for (var world : getServer().getWorlds()) {
                    Path folder=world.getWorldFolder().toPath().toAbsolutePath().normalize();
                    for (String path : paths) if (root.resolve(path).startsWith(folder))
                        throw new Failure("Archive targets a loaded world; use apply.mode: maintenance or the offline installer");
                }
                return null;
            }).get(15,TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure("Apply guard interrupted"); }
        catch (ExecutionException e) { if (e.getCause() instanceof Failure f) throw f; throw new Failure("Server could not validate maintenance state"); }
        catch (TimeoutException e) { throw new Failure("Server did not respond to maintenance check"); }
    }
    @Override public void onDisable() {
        if (listener!=null) listener.close();
        if (service!=null) service.close();
        if (coordinator!=null) try { coordinator.beforeStop(); }
        catch (IOException e) { getLogger().severe("Cannot persist level-name; inspect maintenance.properties and server.properties before starting again"); }
        if (lock!=null) try { lock.close(); } catch (IOException e) { getLogger().warning("Could not release node.lock"); }
    }
}
