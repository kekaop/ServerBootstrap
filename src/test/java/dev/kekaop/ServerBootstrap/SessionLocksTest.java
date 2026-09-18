package dev.kekaop.ServerBootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SessionLocksTest {
    @TempDir Path root;
    @Test void probeDoesNotReleaseServersNativeLock() throws Exception {
        Path world=Files.createDirectory(root.resolve("world")), file=world.resolve("session.lock");
        Path helper=Files.writeString(root.resolve("Probe.java"),"""
                import java.nio.channels.*;
                import java.nio.file.*;
                class Probe {
                    public static void main(String[] args) throws Exception {
                        try (var channel=FileChannel.open(Path.of(args[0]),StandardOpenOption.WRITE);
                             var lock=channel.tryLock()) { System.out.print(lock==null ? "HELD" : "FREE"); }
                    }
                }
                """);
        try (var channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE); var lock=channel.lock()) {
            assertTrue(SessionLocks.heldByServer(root,"world")); assertTrue(SessionLocks.held(root,"world"));
            String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            var process=new ProcessBuilder(java,helper.toString(),file.toString()).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(20,TimeUnit.SECONDS)); assertEquals(0,process.exitValue());
                assertEquals("HELD",new String(process.getInputStream().readAllBytes()).trim());
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        } finally { assertFalse(SessionLocks.held(root,"world")); }
    }
}
