package dev.kekaop.ServerBootstrap;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;

final class FileOps {
    private FileOps() {}
    static void force(Path file) throws IOException {
        try (FileChannel channel=FileChannel.open(file,StandardOpenOption.WRITE)) { channel.force(true); }
    }
    static void replace(Path from, Path to) throws IOException {
        Path temp=Files.createTempFile(to.getParent(),".serverbootstrap-",".tmp");
        try {
            Files.copy(from,temp,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
            force(temp);
            // Fail closed on filesystems without atomic rename; the transaction will roll back.
            Files.move(temp,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static void properties(Path to, Properties p) throws IOException {
        Path temp=Files.createTempFile(to.getParent(),".journal-",".tmp");
        try {
            try (OutputStream out=Files.newOutputStream(temp)) { p.store(out,"ServerBootstrap transaction v1"); }
            force(temp);
            Files.move(temp,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    static Properties read(Path file) throws IOException {
        Properties p=new Properties();
        try (InputStream in=Files.newInputStream(file)) { p.load(in); }
        return p;
    }
    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try (InputStream in=Files.newInputStream(file)) {
                byte[] buffer=new byte[8192]; int n;
                while ((n=in.read(buffer))!=-1) digest.update(buffer,0,n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
