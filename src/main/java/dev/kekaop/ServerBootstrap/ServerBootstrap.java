package dev.kekaop.ServerBootstrap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;

public class ServerBootstrap extends JavaPlugin {
    private static ServerBootstrap instance;
    private SetupCommand setupCommand;
    private Downloader downloader;
    private UpdateListener listener;

    @Override
    public void onEnable() {
        getComponentLogger().info(getPluginPrefix().append(Component.text(" Plugin enabled")));
        instance = this;
        saveDefaultConfig();
        listener = new UpdateListener(this);
        listener.start(); // ensure config.yml is created
        getCommand("setup-server").setExecutor(new SetupCommand(this));
        new UpdateListener(this);

        setupCommand = new SetupCommand(this);
            getConfig().set("first-install", false);
            saveConfig();
        }

    @Override
    public void onDisable() {
        if (listener != null)
            listener.stop();

        instance = null;
        getComponentLogger().info(getPluginPrefix().append(Component.text(" Plugin disabling")));
        }


    public Component getPluginPrefix(){
        final Component pluginName = MiniMessage.miniMessage().deserialize(
                "<gradient:#99ff99:#f79459>[ServerBootstrap]</gradient>"
        );
        return pluginName;
    }
    public static ServerBootstrap getInstance() {
        return instance;
    }



}


