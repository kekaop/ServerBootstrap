package dev.kekaop.ServerBootstrap;

import java.io.IOException;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.Locale;

public final class SafePaths {
    private SafePaths() {}

    public static String relative(String value) throws Failure {
        if (value == null || value.isEmpty() || value.length() > 1024 || value.contains("\\")
                || value.startsWith("/") || value.endsWith("/") || value.split("/", -1).length > 32)
            throw new Failure("Unsafe relative path");
        for (String part : value.split("/", -1)) {
            if (part.isBlank() || part.equals(".") || part.equals("..") || part.endsWith(".")
                    || part.endsWith(" ") || part.chars().anyMatch(c -> c < 32 || c == 127 || ":*?\"<>|".indexOf(c) >= 0)
                    || part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
                throw new Failure("Unsafe relative path");
        }
        return value;
    }

    public static String key(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    public static Path resolve(Path root, String relative) throws IOException {
        Path result = root.resolve(relative(relative)).normalize();
        if (!result.startsWith(root)) throw new Failure("Path escapes server directory");
        Path current = root;
        if (Files.isSymbolicLink(root)) throw new Failure("Symbolic link in destination");
        for (Path part : root.relativize(result)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current) || (Files.exists(current,LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(current,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).isOther()))
                throw new Failure("Symbolic link, junction or special file in destination");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !current.toRealPath().startsWith(root.toRealPath()))
                throw new Failure("Destination resolves outside server directory");
        }
        return result;
    }

    public static boolean under(String path, String prefix) {
        String p = key(path), base = key(prefix);
        return p.equals(base) || p.startsWith(base + "/");
    }

    public static boolean protectedPath(String path) {
        String p = key(path);
        return under(p, "plugins/ServerBootstrap") || under(p, "plugins/NodeMetrics")
                || p.matches("plugins/(serverbootstrap|nodemetrics)[^/]*\\.jar")
                || under(p, ".git");
    }
}
