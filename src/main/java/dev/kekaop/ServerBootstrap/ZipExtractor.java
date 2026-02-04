package dev.kekaop.ServerBootstrap;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ZipExtractor {
    Component prefix = ServerBootstrap.getInstance().getPluginPrefix();
    private final JavaPlugin plugin;

    public ZipExtractor(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void extract(Path zipPath) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {

            plugin.getComponentLogger().info(prefix.append(Component.text(" Starting archive extraction: " + zipPath)));

            Path serverRoot = plugin.getServer().getWorldContainer().toPath();

            Path tempDir = serverRoot.resolve("update_temp");

            try {
                if (!Files.exists(tempDir)) {
                    Files.createDirectory(tempDir);
                }

                unzipToTemp(zipPath, tempDir);

                plugin.getComponentLogger().info(prefix.append(Component.text(" Archive extracted. Moving files...")));

                // === 1. plugins folder ===
                Path extractedPlugins = findSubfolder(tempDir, "plugins");
                if (extractedPlugins != null) {
                    copyFolder(extractedPlugins, serverRoot.resolve("plugins"));
                }

                // === 2. Worlds ===
                copyWorldIfExists(tempDir, serverRoot, "world");
                copyWorldIfExists(tempDir, serverRoot, "world_nether");
                copyWorldIfExists(tempDir, serverRoot, "world_the_end");

                plugin.getComponentLogger().info(prefix.append(Component.text(" Files installed successfully.")));

                // Remove temp folder
                deleteRecursive(tempDir);

                plugin.getComponentLogger().info(prefix.append(Component.text(" Installation complete.")));
                new SetupManager(plugin).finishSetup();

            } catch (Exception e) {
                plugin.getComponentLogger().warn(prefix.append(Component.text(" Extraction error: "+ e.getMessage())));
            }
        });
    }

    // -------------------------------

    private void unzipToTemp(Path zipFile, Path outputDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile.toFile()))) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                Path newFile = outputDir.resolve(entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(newFile);
                } else {
                    Files.createDirectories(newFile.getParent());
                    Files.copy(zis, newFile, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private Path findSubfolder(Path root, String name) throws IOException {
        try (var stream = Files.walk(root, 3)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().equals(name))
                    .findFirst()
                    .orElse(null);
        }
    }

    private void copyWorldIfExists(Path tempDir, Path serverRoot, String world) throws IOException {
        Path extractedWorld = findSubfolder(tempDir, world);
        if (extractedWorld != null) {
            copyFolder(extractedWorld, serverRoot.resolve(world));
            plugin.getComponentLogger().warn(prefix.append(Component.text(" World installed: " + world)));
        }
    }

    private void copyFolder(Path src, Path dest) throws IOException {

        Files.walk(src).forEach(path -> {
            try {
                Path target = dest.resolve(src.relativize(path).toString());

                // Skip ServerBootstrap
                String name = path.getFileName().toString();
                if (name.equalsIgnoreCase("ServerBootstrap.jar")) return;
                if (path.toString().contains("ServerBootstrap")) return;
                if (name.equalsIgnoreCase("NodeMetrics.jar")) return;
                if (path.toString().contains("NodeMetrics")) return;

                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    // If file exists, replace it (do not touch other plugins)
                    if (Files.exists(target)) {
                        Files.delete(target);
                    }

                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }

            } catch (IOException ignored) {}
        });
    }



    private void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;

        Files.walk(path)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount()) // files first
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
    }
}
