package dev.kekaop.ServerBootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class SetupCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    Component prefix = ServerBootstrap.getInstance().getPluginPrefix();
    public SetupCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }




    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("This command can only be run from the console.");
            return true;
        }

        if (plugin.getConfig().getBoolean("first-install")) {
            sender.sendMessage("A profile is already set for this server. Clean the server or edit the config.");
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage("Specify a profile: /setup-server <profile>");
            return true;
        }

        String profile = args[0];
        String provider = plugin.getConfig().getString("source.provider", "gitlab")
                .toLowerCase(Locale.ROOT);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient client = HttpClient.newHttpClient();

                if (provider.equals("gitlab")) {
                    String apiBase = plugin.getConfig().getString("source.gitlab.api-base");
                    String projectTemplate = plugin.getConfig().getString("source.gitlab.project-path-template");
                    String token = plugin.getConfig().getString("source.gitlab.token");

                    if (apiBase == null || projectTemplate == null) {
                        plugin.getComponentLogger().warn(prefix.append(Component.text(" Check GitLab settings in config.yml")));
                        return;
                    }

                    String projectPath = String.format(projectTemplate, profile);
                    String encodedProject = encodePath(projectPath);

                    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(apiBase + "/projects/" + encodedProject))
                            .GET();
                    addAuthIfPresent(requestBuilder, "PRIVATE-TOKEN", token);

                    HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 404) {
                        plugin.getComponentLogger().warn(prefix.append(Component.text(" Profile not found!")));
                        return;
                    }
                    if (response.statusCode() != 200) {
                        plugin.getComponentLogger().warn(prefix.append(Component.text(" GitLab HTTP error: " + response.statusCode())));
                        return;
                    }

                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    plugin.getComponentLogger().info(prefix.append(Component.text(" Project found: " + json.get("name").getAsString())));
                } else if (provider.equals("github")) {
                    String apiBase = plugin.getConfig().getString("source.github.api-base");
                    String owner = plugin.getConfig().getString("source.github.owner");
                    String repoTemplate = plugin.getConfig().getString("source.github.repo-template");
                    String token = plugin.getConfig().getString("source.github.token");

                    if (apiBase == null || owner == null || repoTemplate == null) {
                        plugin.getComponentLogger().warn(prefix.append(Component.text(" Check GitHub settings in config.yml")));
                        return;
                    }

                    String repoName = String.format(repoTemplate, profile);
                    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(apiBase + "/repos/" + owner + "/" + repoName))
                            .GET();
                    addAuthIfPresent(requestBuilder, "Authorization", token == null ? null : "Bearer " + token);

                    HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 404) {
                        plugin.getComponentLogger().warn(prefix.append(Component.text(" Profile not found!")));
                        return;
                    }
                    if (response.statusCode() != 200) {
                        plugin.getComponentLogger().warn(prefix.append(Component.text(" GitHub HTTP error: " + response.statusCode())));
                        return;
                    }

                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    plugin.getComponentLogger().info(prefix.append(Component.text(" Repository found: " + json.get("full_name").getAsString())));
                } else {
                    plugin.getComponentLogger().warn(prefix.append(Component.text(" Unknown provider: " + provider)));
                    return;
                }

                Downloader downloader = new Downloader(plugin);
                downloader.download(profile);
                plugin.getConfig().set("node-profile", profile);
                plugin.saveConfig();
                plugin.reloadConfig();

            } catch (IOException e) {
                plugin.getComponentLogger().warn(prefix.append(Component.text(" IO error: " + e.getMessage())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                plugin.getComponentLogger().warn(prefix.append(Component.text(" Request was interrupted")));
            }
        });
        return true;
    }

    private static void addAuthIfPresent(HttpRequest.Builder builder, String header, String value) {
        if (value == null) return;
        String trimmed = value.trim();
        if (!trimmed.isEmpty()) {
            builder.header(header, trimmed);
        }
    }

    private static String encodePath(String path) {
        return URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
