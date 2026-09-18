package dev.kekaop.ServerBootstrap;

import org.bukkit.command.*;
import java.io.IOException;
import java.util.*;

public final class SetupCommand implements CommandExecutor, TabCompleter {
    private final ServerBootstrap plugin;
    private final InstallService service;
    private final BootstrapConfig config;
    public SetupCommand(ServerBootstrap plugin,InstallService service,BootstrapConfig config) {
        this.plugin=plugin; this.service=service; this.config=config;
    }
    static boolean allowed(CommandSender sender) {
        return sender instanceof ConsoleCommandSender || sender.hasPermission("serverbootstrap.admin");
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if (!allowed(sender)) { sender.sendMessage("[ServerBootstrap] Required permission: serverbootstrap.admin"); return true; }
        try {
            if (command.getName().equals("setup-server")) {
                if (args.length!=1) { sender.sendMessage("/setup-server <profile>"); return true; }
                start(sender,args[0],false); return true;
            }
            if (args.length==0 || args[0].equalsIgnoreCase("help")) {
                sender.sendMessage(new String[]{"/setup-server <profile> — install/update the bound profile",
                        "/serverbootstrap install <profile> | update [profile] | check <profile>",
                        "/serverbootstrap current | status | help",
                        "/serverbootstrap maintenance-status | maintenance-retry | maintenance-cancel",
                        "Configuration is validated at startup. Restart after editing config.yml."});
                return true;
            }
            if (args[0].equalsIgnoreCase("maintenance-status") || (args[0].equalsIgnoreCase("status") && service==null)) {
                sender.sendMessage("[ServerBootstrap] "+UpdateListener.json(plugin.maintenanceStatus())); return true;
            }
            if (args[0].equalsIgnoreCase("maintenance-retry") || args[0].equalsIgnoreCase("maintenance-cancel")) {
                plugin.maintenanceAction(args[0].equalsIgnoreCase("maintenance-cancel"));
                sender.sendMessage("[ServerBootstrap] "+UpdateListener.json(plugin.maintenanceStatus())+". Restart/start from the hosting panel if automatic stop is disabled."); return true;
            }
            requireService();
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "current" -> {
                    InstallService.Installed s=service.installed();
                    sender.sendMessage("[ServerBootstrap] profile="+s.profile()+" version="+s.version()+" sha256="+s.sha256()+" installed-at="+s.installedAt());
                }
                case "status" -> sender.sendMessage("[ServerBootstrap] "+UpdateListener.json(service.status()));
                case "check", "install", "update" -> {
                    String profile=args.length==2 ? args[1] : args.length==1 && args[0].equalsIgnoreCase("update") ? service.installed().profile() : null;
                    if (profile==null || profile.equals("none")) throw new Failure("Specify a profile: /serverbootstrap "+args[0]+" <profile>");
                    start(sender,profile,args[0].equalsIgnoreCase("check"));
                }
                default -> sender.sendMessage("[ServerBootstrap] Unknown command. /serverbootstrap help");
            }
        } catch (IOException failure) { sender.sendMessage("[ServerBootstrap] "+(failure instanceof Failure ? failure.getMessage() : "Cannot read/write maintenance state; inspect storage and permissions")); }
        return true;
    }
    private void start(CommandSender sender,String profile,boolean check) throws Failure {
        requireService();
        String id=service.start(profile,check,status -> {
            if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin,
                    () -> sender.sendMessage("[ServerBootstrap] "+status.state()+" operation="+status.id()+": "+status.message()));
        });
        sender.sendMessage("[ServerBootstrap] Accepted operation="+id+". /serverbootstrap status");
    }
    private void requireService() throws Failure {
        if (service==null) throw new Failure("Installation unavailable during maintenance/startup failure; use maintenance-status");
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String label,String[] args) {
        if (!allowed(sender)) return List.of();
        Collection<String> names=config==null ? List.of() : config.names();
        Collection<String> choices=command.getName().equals("setup-server") && args.length==1 ? names
                : args.length==1 ? List.of("install","update","check","current","status","help","maintenance-status","maintenance-retry","maintenance-cancel") : args.length==2 ? names : List.of();
        String prefix=args.length==0 ? "" : args[args.length-1].toLowerCase(Locale.ROOT);
        return choices.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
