package dev.kekaop.ServerBootstrap;

import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.file.*;

final class ConfigFiles {
    private ConfigFiles() {}
    static BootstrapConfig load(Path path) throws Failure {
        try {
            if (Files.size(path)>1048576) throw new Failure("config.yml exceeds 1 MiB");
            String yaml=Files.readString(path);
            LoaderOptions options=new LoaderOptions(); options.setAllowDuplicateKeys(false);
            new Yaml(new SafeConstructor(options)).load(yaml);
            YamlConfiguration c=new YamlConfiguration(); c.loadFromString(yaml);
            return BootstrapConfig.parse(c,System::getenv);
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure("Invalid config.yml: check YAML syntax, duplicate keys and file access (values redacted)"); }
    }
}
