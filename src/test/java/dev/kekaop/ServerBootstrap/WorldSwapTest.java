package dev.kekaop.ServerBootstrap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldSwapTest {
    @TempDir Path root;
    Path data,attempt; Map<String,Path> sources;
    @BeforeEach void setup() throws Exception {
        data=Files.createDirectories(root.resolve("plugins/ServerBootstrap"));
        attempt=data.resolve("attempt"); sources=new LinkedHashMap<>();
        for (String name : List.of("world","world_nether")) {
            Path live=Files.createDirectory(root.resolve(name));
            Files.writeString(live.resolve("level.dat"),"old"); Files.writeString(live.resolve("stale.mca"),"old chunk");
            Path stage=Files.createDirectories(data.resolve("staging/"+name));
            Files.writeString(stage.resolve("level.dat"),"new"); sources.put(name,stage);
        }
        FileOps.properties(data.resolve("installed.properties"),state("v1"));
    }
    static Properties state(String version) { Properties p=new Properties(); p.setProperty("version",version); return p; }
    void oldRestored() throws Exception {
        for (String name : sources.keySet()) {
            assertEquals("old",Files.readString(root.resolve(name+"/level.dat")));
            assertTrue(Files.exists(root.resolve(name+"/stale.mca")));
            assertEquals("new",Files.readString(sources.get(name).resolve("level.dat")));
        }
        assertEquals("v1",FileOps.read(data.resolve("installed.properties")).getProperty("version"));
    }
    @Test void swapsCompleteWorldsRetainsBackupAndCommitsState() throws Exception {
        var swap=new WorldSwap(root,data); swap.apply(attempt,sources,state("v2"));
        for (String name : sources.keySet()) {
            assertEquals("new",Files.readString(root.resolve(name+"/level.dat")));
            assertFalse(Files.exists(root.resolve(name+"/stale.mca")));
        }
        assertEquals("old chunk",Files.readString(attempt.resolve("backup/0/stale.mca")));
        assertEquals("v2",FileOps.read(data.resolve("installed.properties")).getProperty("version"));
        assertEquals("COMMITTED",swap.state(attempt)); swap.recover(attempt);
        assertEquals("new",Files.readString(root.resolve("world/level.dat")));
    }
    @ParameterizedTest @ValueSource(strings={"before-backup","after-backup","after-install","before-state","after-state"})
    void ioFailureRestoresAllWorldsAndState(String fault) throws Exception {
        var swap=new WorldSwap(root,data,(phase,i)->{ if (phase.equals(fault) && i>=1) throw new IOException(); });
        assertThrows(Failure.class,()->swap.apply(attempt,sources,state("v2")));
        oldRestored(); assertEquals("ROLLED_BACK",swap.state(attempt));
    }
    @ParameterizedTest @ValueSource(strings={"before-backup","after-backup","after-install","before-state","after-state"})
    void processCrashRecoversIdempotently(String fault) throws Exception {
        var swap=new WorldSwap(root,data,(phase,i)->{ if (phase.equals(fault) && i>=1) throw new AssertionError("crash"); });
        assertThrows(AssertionError.class,()->swap.apply(attempt,sources,state("v2")));
        var recovery=new WorldSwap(root,data); recovery.recover(attempt); recovery.recover(attempt); oldRestored();
    }
    @Test void failureRestoresAbsentWorldAndAbsentBinding() throws Exception {
        Path extra=Files.createDirectory(data.resolve("extra")); Files.writeString(extra.resolve("level.dat"),"new extra");
        sources.put("extra",extra); Files.delete(data.resolve("installed.properties"));
        var swap=new WorldSwap(root,data,(phase,i)->{if (phase.equals("after-state")) throw new IOException();});
        assertThrows(Failure.class,()->swap.apply(attempt,sources,state("v2")));
        assertFalse(Files.exists(root.resolve("extra"))); assertTrue(Files.exists(extra.resolve("level.dat")));
        assertFalse(Files.exists(data.resolve("installed.properties")));
    }
    @Test void loadedTargetRefusesBeforeAnyRename() throws Exception {
        try (var channel=FileChannel.open(root.resolve("world/session.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
             var lock=channel.lock()) {
            assertThrows(Failure.class,()->new WorldSwap(root,data).apply(attempt,sources,state("v2")));
            assertTrue(lock.isValid()); oldRestored(); assertFalse(Files.exists(attempt));
        } finally { SessionLocks.held(root,"world"); }
    }
    @Test void ambiguousRollbackPreservesBothCopiesAndCanBeRepaired() throws Exception {
        var swap=new WorldSwap(root,data,(phase,i)->{ if (phase.equals("after-install") && i==0) {
            Files.createDirectory(sources.get("world")); throw new IOException();
        }});
        assertThrows(Failure.class,()->swap.apply(attempt,sources,state("v2")));
        assertEquals("ROLLBACK_FAILED",swap.state(attempt));
        assertEquals("old",Files.readString(attempt.resolve("backup/0/level.dat")));
        assertEquals("new",Files.readString(root.resolve("world/level.dat")));
        Files.delete(sources.get("world")); swap.recover(attempt); oldRestored();
    }
}
