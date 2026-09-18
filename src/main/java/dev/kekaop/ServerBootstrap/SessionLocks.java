package dev.kekaop.ServerBootstrap;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

final class SessionLocks {
    private SessionLocks() {}
    // Closing a second channel can release the server's lock on POSIX. Retain overlapping probes
    // until the server releases that lock (or JVM exit); never close these during plugin disable.
    private static final Map<Path,FileChannel> overlapping=new HashMap<>();
    static boolean held(Path root,String world) throws IOException { return probe(root,world)!=0; }
    static boolean heldByServer(Path root,String world) throws IOException { return probe(root,world)==2; }
    private static synchronized int probe(Path root,String world) throws IOException {
        Path file=SafePaths.resolve(root,world+"/session.lock");
        if (!Files.exists(file)) return 0;
        FileChannel channel=overlapping.get(file);
        if (channel==null) channel=FileChannel.open(file,StandardOpenOption.WRITE);
        try {
            try (FileLock lock=channel.tryLock()) {
                overlapping.remove(file); return lock==null ? 1 : 0;
            } catch (OverlappingFileLockException heldByThisJvm) {
                overlapping.put(file,channel); return 2;
            }
        } catch (IOException e) { throw new Failure("Cannot inspect world session.lock; check permissions"); }
        finally { if (!overlapping.containsKey(file)) channel.close(); }
    }
    static void requireFree(Path root,Iterable<String> worlds) throws IOException {
        for (String world : worlds) if (held(root,world)) throw new Failure("A target world is loaded or locked; maintenance apply refused");
    }
}
