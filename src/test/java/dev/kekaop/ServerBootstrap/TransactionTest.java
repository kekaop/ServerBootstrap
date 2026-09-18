package dev.kekaop.ServerBootstrap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {
    @TempDir Path root;
    private Path transactions() throws IOException { return Files.createDirectories(root.resolve("plugins/ServerBootstrap/transactions")); }
    private Path input(String name,String value) throws IOException { return Files.writeString(root.resolve(name),value); }
    @Test void commitOverwritesOnlyListedFiles() throws Exception {
        Path existing=Files.createDirectories(root.resolve("plugins")); Files.writeString(existing.resolve("old.txt"),"old");
        Files.writeString(existing.resolve("keep.txt"),"keep");
        var installer=new TransactionInstaller(root,transactions()); Path tx=installer.create(UUID.randomUUID().toString());
        installer.apply(Map.of("plugins/old.txt",input("new.txt","new"),"plugins/new/file.txt",input("new2.txt","added")),tx);
        assertEquals("new",Files.readString(existing.resolve("old.txt"))); assertEquals("keep",Files.readString(existing.resolve("keep.txt")));
        assertEquals("COMMITTED",FileOps.read(tx.resolve("phase.properties")).getProperty("state"));
    }
    @Test void failureBeforeApplyPreservesServer() throws Exception {
        transactions(); Files.writeString(root.resolve("plugins/old.txt"),"old"); Files.createDirectory(root.resolve("plugins/conflict.txt"));
        var installer=new TransactionInstaller(root,transactions()); Path tx=installer.create(UUID.randomUUID().toString());
        LinkedHashMap<String,Path> changes=new LinkedHashMap<>(); changes.put("plugins/old.txt",input("a","new")); changes.put("plugins/conflict.txt",input("b","b"));
        assertThrows(IOException.class,()->installer.apply(changes,tx)); assertEquals("old",Files.readString(root.resolve("plugins/old.txt")));
    }
    @Test void failureDuringApplyRestoresExistingAndRemovesNewFilesAndDirectories() throws Exception {
        transactions(); Files.writeString(root.resolve("plugins/old.txt"),"old");
        var installer=new TransactionInstaller(root,transactions(),(i,p)->{if(i==2) throw new IOException("secret cause");});
        Path tx=installer.create(UUID.randomUUID().toString()); LinkedHashMap<String,Path> changes=new LinkedHashMap<>();
        changes.put("plugins/old.txt",input("a","new")); changes.put("plugins/created/deep/file",input("b","b")); changes.put("plugins/final",input("c","c"));
        Failure failure=assertThrows(Failure.class,()->installer.apply(changes,tx));
        assertFalse(failure.getMessage().contains("secret cause")); assertEquals("old",Files.readString(root.resolve("plugins/old.txt")));
        assertFalse(Files.exists(root.resolve("plugins/created"))); assertFalse(Files.exists(root.resolve("plugins/final")));
        assertEquals("ROLLED_BACK",FileOps.read(tx.resolve("phase.properties")).getProperty("state"));
    }
    @Test void processDeathRecoveredFromWriteAheadJournal() throws Exception {
        transactions(); Files.writeString(root.resolve("plugins/old.txt"),"old");
        var installer=new TransactionInstaller(root,transactions(),(i,p)->{if(i==1) throw new SimulatedCrash();});
        Path tx=installer.create(UUID.randomUUID().toString()); LinkedHashMap<String,Path> changes=new LinkedHashMap<>();
        changes.put("plugins/old.txt",input("a","new")); changes.put("plugins/added",input("b","added"));
        assertThrows(SimulatedCrash.class,()->installer.apply(changes,tx)); assertEquals("new",Files.readString(root.resolve("plugins/old.txt")));
        var recovery=new TransactionInstaller(root,transactions()); recovery.recover(s->{}); recovery.recover(s->{});
        assertEquals("old",Files.readString(root.resolve("plugins/old.txt"))); assertFalse(Files.exists(root.resolve("plugins/added")));
    }
    @Test void failedRollbackReportsExactPathsAndKeepsBackups() throws Exception {
        transactions(); Files.writeString(root.resolve("plugins/old.txt"),"old");
        final Path[] tx={null};
        var installer=new TransactionInstaller(root,transactions(),(i,p)-> {
            if(i==1) { Files.writeString(tx[0].resolve("backup/0"),"corrupted"); throw new IOException(); }
        });
        tx[0]=installer.create(UUID.randomUUID().toString()); LinkedHashMap<String,Path> changes=new LinkedHashMap<>();
        changes.put("plugins/old.txt",input("a","new")); changes.put("plugins/added",input("b","added"));
        assertThrows(Failure.class,()->installer.apply(changes,tx[0]));
        assertEquals("plugins/old.txt",FileOps.read(tx[0].resolve("rollback.properties")).getProperty("failed.0"));
        assertTrue(installer.needsRecovery(tx[0])); assertTrue(Files.exists(tx[0].resolve("backup/0")));
    }
    @Test void symlinkDestinationsRejected() throws Exception {
        transactions(); Path external=Files.createDirectory(root.resolve("outside"));
        try { Files.createSymbolicLink(root.resolve("plugins/link"),external); }
        catch (IOException | UnsupportedOperationException e) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw e;
            Process junction=new ProcessBuilder("cmd.exe","/c","mklink","/J",root.resolve("plugins/link").toString(),external.toString())
                    .redirectErrorStream(true).start();
            String output=new String(junction.getInputStream().readAllBytes());
            assertEquals(0,junction.waitFor(),output);
        }
        try {
            var installer=new TransactionInstaller(root,transactions());
            assertThrows(Failure.class,()->installer.ensureDestination("plugins/link/file")); assertFalse(Files.exists(external.resolve("file")));
        } finally { Files.deleteIfExists(root.resolve("plugins/link")); }
    }
    @Test void nodeLockRejectsAnotherInstance() throws Exception {
        Path data=Files.createDirectories(root.resolve("plugins/ServerBootstrap"));
        try (NodeLock ignored=new NodeLock(root,data)) { assertThrows(Failure.class,()->new NodeLock(root,data)); }
        try (NodeLock ignored=new NodeLock(root,data)) { assertNotNull(ignored); }
    }
    private static class SimulatedCrash extends Error {}
}
