package dev.kekaop.ServerBootstrap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MaintenanceCoordinatorTest {
    @TempDir Path root;
    Path data,tx; Map<String,Path> files; MaintenanceCoordinator coordinator;
    static String yaml() { return TestSupport.YAML.replace("include: [plugins]","mode: maintenance\n      worlds: [world]\n      include: [world]").replace("plugins/test.txt","world/level.dat"); }
    @BeforeEach void setup() throws Exception {
        data=Files.createDirectories(root.resolve("plugins/ServerBootstrap"));
        tx=Files.createDirectories(data.resolve("transactions/"+UUID.randomUUID()));
        Files.createDirectory(root.resolve("world")); Files.writeString(root.resolve("world/level.dat"),"old");
        Files.writeString(root.resolve("world/stale.mca"),"stale");
        Files.writeString(root.resolve("server.properties"),"level-name=world\nmotd=keep me\n");
        Path staged=Files.writeString(tx.resolve("staged.dat"),"new"); files=Map.of("world/level.dat",staged);
        coordinator=new MaintenanceCoordinator(root,data,s->{},p->{});
    }
    FileChannel lock(String world) throws Exception {
        Files.createDirectories(root.resolve(world));
        var channel=FileChannel.open(root.resolve(world+"/session.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        channel.lock(); return channel;
    }
    String selected() throws Exception { return FileOps.read(root.resolve("server.properties")).getProperty("level-name"); }
    String prepare() throws Exception {
        try (var channel=lock("world")) {
            coordinator.prepare(TestSupport.config(yaml()).profile("lobby"),files,tx,new ArtifactDownloader.Download(3,"a".repeat(64)));
        }
        SessionLocks.held(root,"world"); return selected();
    }
    @AfterEach void closeProbes() throws Exception {
        try (var dirs=Files.list(root)) { for (Path dir : dirs.filter(Files::isDirectory).toList()) SessionLocks.held(root,dir.getFileName().toString()); }
    }
    @Test void twoRestartsSwapAndReturnWithDelayedBinding() throws Exception {
        String temporary=prepare();
        assertNotEquals("world",temporary); assertFalse(coordinator.automaticStop());
        assertFalse(Files.exists(data.resolve("installed.properties")));
        assertEquals("old",Files.readString(root.resolve("world/level.dat")));
        try (var channel=lock(temporary)) {
            assertTrue(coordinator.boot()); assertEquals("WAITING_FOR_RETURN",coordinator.status().state());
            assertEquals("world",selected()); assertEquals("new",Files.readString(root.resolve("world/level.dat")));
            assertFalse(Files.exists(root.resolve("world/stale.mca")));
            assertEquals("v1",FileOps.read(data.resolve("installed.properties")).getProperty("version"));
            coordinator.beforeStop(); assertEquals("world",selected());
        }
        try (var channel=lock("world")) { assertFalse(coordinator.boot()); assertFalse(coordinator.pending()); }
        assertEquals("keep me",FileOps.read(root.resolve("server.properties")).getProperty("motd"));
    }
    @Test void ignoredSelectorNeverChangesWorldAndDoesNotAutomaticallyRetry() throws Exception {
        String temporary=prepare();
        try (var channel=lock("world")) {
            assertTrue(coordinator.boot()); assertEquals("MAINTENANCE_FAILED",coordinator.status().state());
            assertEquals("old",Files.readString(root.resolve("world/level.dat")));
        }
        try (var channel=lock(temporary)) { assertTrue(coordinator.boot()); assertEquals("MAINTENANCE_FAILED",coordinator.status().state()); }
        assertFalse(Files.exists(data.resolve("installed.properties")));
    }
    @Test void editedPreparedSnapshotIsRejected() throws Exception {
        String temporary=prepare(); Files.writeString(tx.resolve("prepared-worlds/world/level.dat"),"tampered");
        try (var channel=lock(temporary)) { assertTrue(coordinator.boot()); }
        assertEquals("MAINTENANCE_FAILED",coordinator.status().state());
        assertEquals("old",Files.readString(root.resolve("world/level.dat")));
    }
    @Test void cancellationAndReturnRetryNeverInstall() throws Exception {
        prepare(); coordinator.cancel(); coordinator.retry();
        assertEquals("world",selected());
        try (var channel=lock("world")) { assertFalse(coordinator.boot()); }
        assertEquals("old",Files.readString(root.resolve("world/level.dat")));
        assertFalse(Files.exists(data.resolve("installed.properties")));
        assertEquals("cancelled",FileOps.read(tx.resolve("maintenance-result.properties")).getProperty("result"));
    }
    @Test void crashedRenameIsRecoveredBeforeRetryAndCommitIsNotAppliedTwice() throws Exception {
        String temporary=prepare();
        var crashing=new MaintenanceCoordinator(root,data,s->{},p->{},new WorldSwap(root,data,(phase,i)->{
            if (phase.equals("after-backup")) throw new AssertionError("crash");
        }));
        try (var channel=lock(temporary)) {
            assertThrows(AssertionError.class,crashing::boot); assertFalse(Files.exists(root.resolve("world")));
            assertTrue(coordinator.boot()); assertEquals("new",Files.readString(root.resolve("world/level.dat")));
            assertThrows(Failure.class,coordinator::cancel);
            coordinator.retry(); assertEquals("WAITING_FOR_RETURN",coordinator.status().state());
        }
        try (var channel=lock("world")) { assertFalse(coordinator.boot()); }
    }
    @Test void targetLoadedByAnotherPluginPreventsSwap() throws Exception {
        String temporary=prepare();
        try (var maintenance=lock(temporary); var working=lock("world")) { assertTrue(coordinator.boot()); }
        assertEquals("MAINTENANCE_FAILED",coordinator.status().state());
        assertEquals("old",Files.readString(root.resolve("world/level.dat")));
    }
    @Test void serviceQueuesButDoesNotCommitOrUseDirectGuard() throws Exception {
        Path zip=TestSupport.zip(root,Map.of("world/level.dat","new","world/session.lock","ignore"));
        var config=TestSupport.config(yaml());
        try (var channel=lock("world"); var service=new InstallService(config,new TransactionInstaller(root,data.resolve("transactions")),root,data,
                Set.of(),p->{throw new Failure("direct guard must not run");},s->{},()->fail("direct restart must not run"),TestSupport.local(zip),coordinator,()->fail("restart disabled"))) {
            var done=new CompletableFuture<InstallService.Status>(); service.start("lobby",false,done::complete);
            assertEquals("WAITING_FOR_MAINTENANCE",done.get(10,TimeUnit.SECONDS).state());
            assertEquals("none",service.installed().profile()); assertTrue(service.inMaintenance());
            assertThrows(Failure.class,()->service.start("lobby",false,s->{}));
            String operation=coordinator.status().id();
            assertFalse(Files.exists(data.resolve("transactions/"+operation+"/prepared-worlds/world/session.lock")));
        }
    }
    @Test void rejectsMissingMetadataReservedNamesAndMismatchedIncludes() throws Exception {
        assertThrows(Failure.class,()->MaintenanceCoordinator.validateSnapshot(List.of("world"),Map.of("world/chunk.mca",tx)));
        for (String bad : List.of(yaml().replace("worlds: [world]","worlds: [world, World]"),
                yaml().replace("include: [world]","include: [plugins]"),yaml().replace("world", "plugins"),
                yaml().replace("mode: maintenance","mode: unknown"),yaml().replace("mode: maintenance","mode: direct")))
            assertThrows(Failure.class,()->TestSupport.config(bad));
    }
}
