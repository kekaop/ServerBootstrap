package dev.kekaop.ServerBootstrap;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

public class Downloader {
    Component prefix = ServerBootstrap.getInstance().getPluginPrefix();
    private final JavaPlugin plugin;

    public Downloader(JavaPlugin plugin) {
        this.plugin = plugin;
    }


    public void download(String profile){
        String provider = plugin.getConfig().getString("source.provider", "gitlab")
                .toLowerCase(Locale.ROOT);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient client = HttpClient.newHttpClient();

                HttpRequest request;
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
                            .uri(URI.create(apiBase + "/projects/" + encodedProject + "/repository/archive.zip"))
                            .GET();
                    addAuthIfPresent(requestBuilder, "PRIVATE-TOKEN", token);
                    request = requestBuilder.build();
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
                            .uri(URI.create(apiBase + "/repos/" + owner + "/" + repoName + "/zipball"))
                            .GET();
                    addAuthIfPresent(requestBuilder, "Authorization", token == null ? null : "Bearer " + token);
                    request = requestBuilder.build();
                } else {
                    plugin.getComponentLogger().warn(prefix.append(Component.text(" Unknown provider: " + provider)));
                    return;
                }

                String safeName = profile.replace("\\", "_").replace("/", "_");
                Path output = Path.of(plugin.getDataFolder().getParent(), safeName + ".zip");

                HttpResponse<Path> response =
                        client.send(request, HttpResponse.BodyHandlers.ofFile(output));

                if (response.statusCode() != 200) {
                    plugin.getComponentLogger().warn(prefix.append(Component.text(" ZIP download failed: HTTP " + response.statusCode())));
                    return;
                }

                plugin.getComponentLogger().info(prefix.append(Component.text(" ZIP downloaded: " + output.toAbsolutePath())));

                new ZipExtractor(plugin).extract(output);

            } catch (Exception e) {
                plugin.getComponentLogger().warn(prefix.append(Component.text(" IO error: " + e.getMessage())));
            }
        });

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
