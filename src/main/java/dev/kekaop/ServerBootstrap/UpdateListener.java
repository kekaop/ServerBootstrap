package dev.kekaop.ServerBootstrap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class UpdateListener {
    private final JavaPlugin plugin;
    private HttpServer httpServer;
    Component prefix = ServerBootstrap.getInstance().getPluginPrefix();



    public UpdateListener(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        int port = plugin.getConfig().getInt("port");
        String secret = plugin.getConfig().getString("secret");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                httpServer = HttpServer.create(new InetSocketAddress(port), 0);

                httpServer.createContext("/update", exchange -> {

                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                    JsonObject json;
                    try {
                        json = JsonParser.parseString(body).getAsJsonObject();
                    } catch (Exception e) {
                        plugin.getComponentLogger().info(prefix.append(Component.text(" JSON PARSE ERROR: " + e.getMessage())));
                        exchange.sendResponseHeaders(400, 0);
                        exchange.close();
                        return;
                    }

                    if (!json.has("secret") || !json.get("secret").getAsString().equals(secret)) {
                        exchange.sendResponseHeaders(403, 0);
                        exchange.close();
                        return;
                    }


                    String nodeProfile = plugin.getConfig().getString("node-profile");
                    plugin.getComponentLogger().info(prefix.append(Component.text(" [UPDATE] Update command received: " + nodeProfile)));
                    // Run Downloader asynchronously
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        new Downloader(plugin).download(nodeProfile);
                    });

                    // Respond to backend
                    byte[] ok = "{\"status\":\"update-started\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, ok.length);
                    exchange.getResponseBody().write(ok);
                    exchange.close();
                });

                httpServer.start();
                plugin.getComponentLogger().info(prefix.append(Component.text(" HTTP server started on port: " + port)));
            } catch (IOException e) {
                plugin.getComponentLogger().info(prefix.append(Component.text(" Failed to start HTTP server: " + e.getMessage())));
            }
        });
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            plugin.getComponentLogger().info(prefix.append(Component.text(" HTTP server stopped.")));
        }
    }
}
