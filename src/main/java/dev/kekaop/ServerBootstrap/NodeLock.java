package dev.kekaop.ServerBootstrap;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/** Also excludes the offline installer while the plugin is loaded. */
final class NodeLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    NodeLock(Path root,Path data) throws IOException {
        Path file=SafePaths.resolve(root,root.relativize(data.resolve("node.lock")).toString().replace('\\','/'));
        channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        try {
            lock=channel.tryLock();
            if (lock==null) throw new IOException();
        } catch (IOException | OverlappingFileLockException e) {
            channel.close(); throw new Failure("Another ServerBootstrap instance holds node.lock");
        }
    }
    public void close() throws IOException { lock.release(); channel.close(); }
}
