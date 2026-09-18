package dev.kekaop.ServerBootstrap;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class ArchiveVerifier {
    public record Plan(Path contentRoot, Map<String, Path> files, long expandedBytes) {}

    public Plan extract(Path archive, Path staging, BootstrapConfig.Profile profile, BootstrapConfig.Limits limits) throws IOException {
        Files.createDirectory(staging);
        Set<String> entries = new HashSet<>();
        Map<String,String> components = new HashMap<>();
        long total=0; int count=0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var enumeration=zip.entries();
            while (enumeration.hasMoreElements()) {
                if (Thread.currentThread().isInterrupted()) throw new Failure("Extraction interrupted");
                ZipEntry entry=enumeration.nextElement();
                if (++count > limits.entries()) throw new Failure("ZIP contains too many entries");
                String name=entry.getName();
                if (entry.isDirectory()) name=name.substring(0,name.length()-1);
                SafePaths.relative(name);
                if (!entries.add(SafePaths.key(name))) throw new Failure("ZIP contains duplicate paths");
                String prefix="";
                for (String component : name.split("/")) {
                    prefix=prefix.isEmpty() ? component : prefix+"/"+component;
                    String previous=components.putIfAbsent(SafePaths.key(prefix),prefix);
                    if (previous!=null && !previous.equals(prefix)) throw new Failure("ZIP contains ambiguous case or Unicode paths");
                }
                Path dest=SafePaths.resolve(staging,name);
                if (entry.isDirectory()) { Files.createDirectories(dest); continue; }
                if (entry.getSize() > limits.extractedBytes()-total) throw new Failure("ZIP exceeds extracted size limit");
                Files.createDirectories(dest.getParent());
                CRC32 crc=new CRC32(); long size=0;
                try (InputStream in=zip.getInputStream(entry); OutputStream out=Files.newOutputStream(dest,StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer=new byte[8192]; int n;
                    while ((n=in.read(buffer))!=-1) {
                        if (n > limits.extractedBytes()-total) throw new Failure("ZIP exceeds extracted size limit");
                        out.write(buffer,0,n); crc.update(buffer,0,n); total+=n; size+=n;
                    }
                }
                if (entry.getSize()!=size || entry.getCrc()!=crc.getValue()) throw new Failure("ZIP entry checksum or size mismatch");
            }
        } catch (Failure e) { throw e; }
        catch (IOException | IllegalArgumentException e) { throw new Failure("ZIP is corrupt, unreadable, or has conflicting paths"); }
        if (count==0) throw new Failure("ZIP is empty");
        Path root=findRoot(staging,profile);
        Map<String,Path> plan=new TreeMap<>();
        try (var paths=Files.walk(root)) {
            for (Path file : paths.filter(p -> Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList()) {
                String relative=root.relativize(file).toString().replace('\\','/');
                if (profile.include().stream().anyMatch(i -> SafePaths.under(relative,i)) && !SafePaths.protectedPath(relative)) plan.put(relative,file);
            }
        }
        for (int i=0;i<profile.required().size();i++) if (!plan.containsKey(profile.required().get(i)))
            throw new Failure("Required file missing; see profile apply.required-files["+i+"]");
        if (plan.isEmpty()) throw new Failure("Archive contains no installable files matching apply.include");
        return new Plan(root,Collections.unmodifiableMap(plan),total);
    }
    private Path findRoot(Path staging, BootstrapConfig.Profile p) throws IOException {
        if (p.root().equals(".")) return staging;
        if (!p.root().equals("auto")) {
            Path explicit=SafePaths.resolve(staging,p.root());
            if (!Files.isDirectory(explicit)) throw new Failure("archive-root does not exist in ZIP");
            return explicit;
        }
        if (p.include().stream().anyMatch(i -> Files.exists(staging.resolve(i)))) return staging;
        try (var children=Files.list(staging)) {
            List<Path> list=children.toList();
            if (list.size()==1 && Files.isDirectory(list.get(0))) return list.get(0);
        }
        throw new Failure("Cannot determine archive root; configure archive-root explicitly");
    }
}
