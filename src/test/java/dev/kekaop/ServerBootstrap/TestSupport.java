package dev.kekaop.ServerBootstrap;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class TestSupport {
    static final String YAML="""
            config-version: 1
            profiles:
              lobby:
                source:
                  type: https
                  url: https://example.org/profile.zip
                version: 'v1'
                restart: false
                apply:
                  include: [plugins]
                  required-files: [plugins/test.txt]
            """;
    static BootstrapConfig config(String yaml) throws Exception {
        YamlConfiguration c=new YamlConfiguration(); c.loadFromString(yaml);
        return BootstrapConfig.parse(c,k -> "env-secret-12345678901234567890123456789");
    }
    static BootstrapConfig config() throws Exception { return config(YAML); }
    static BootstrapConfig.Profile profile() throws Exception { return config().profile("lobby"); }
    static Path zip(Path folder,Map<String,String> files) throws IOException {
        Path zip=folder.resolve(UUID.randomUUID()+".zip");
        try (ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(zip))) {
            for (var entry : files.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey())); out.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.closeEntry();
            }
        }
        return zip;
    }
    static String digest(Path file) throws IOException { return FileOps.sha256(file); }
    static InstallService.Fetch local(Path zip) {
        return (request,target,limits) -> {
            Files.copy(zip,target); return new ArtifactDownloader.Download(Files.size(zip),digest(zip));
        };
    }
}
