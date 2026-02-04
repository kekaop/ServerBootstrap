package dev.kekaop.ServerBootstrap;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public class SetupManager {

    private final JavaPlugin plugin;
    Component prefix = ServerBootstrap.getInstance().getPluginPrefix();

    public SetupManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }


    public void finishSetup(){

        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getConfig().set("first-install", true);
            plugin.saveConfig();


            if (plugin.getServer().getPluginManager().getPlugin("NodeMetrics") != null) {
                File file = new File("plugins/NodeMetrics/config.yml");

                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);

                yaml.set("nodeName", plugin.getConfig().getString("node-profile")); // or your own key
                try {
                    yaml.save(file);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }


            plugin.getComponentLogger().warn(prefix.append(Component.text(" Restarting server")));
            plugin.getServer().restart();
        });
    }
}
