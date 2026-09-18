package dev.kekaop.ServerBootstrap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class InstallServiceTest {
    @TempDir Path root;
    Path data; Path zip;
    @BeforeEach void setup() throws Exception {
        data=Files.createDirectories(root.resolve("plugins/ServerBootstrap")); zip=TestSupport.zip(root,Map.of("plugins/test.txt","new"));
        Files.writeString(root.resolve("plugins/test.txt"),"old");
    }
    InstallService service(BootstrapConfig config,InstallService.Fetch fetch,java.util.function.Consumer<String> log,InstallService.Guard guard) throws Exception {
        return new InstallService(config,new TransactionInstaller(root,data.resolve("transactions")),root,data,Set.of(),guard,log,()->{},fetch);
    }
    @Test void checkDoesNotChangeFilesOrBinding() throws Exception {
        try (var service=service(TestSupport.config(),TestSupport.local(zip),s->{},p->{})) {
            CompletableFuture<InstallService.Status> done=new CompletableFuture<>(); service.start("lobby",true,done::complete);
            assertEquals("CHECKED",done.get(5,TimeUnit.SECONDS).state()); assertEquals("old",Files.readString(root.resolve("plugins/test.txt")));
            assertEquals("none",service.installed().profile()); assertFalse(Files.exists(data.resolve("installed.properties")));
        }
    }
    @Test void checksumFailureAndNetworkFailureKeepState() throws Exception {
        var badChecksum=TestSupport.config(TestSupport.YAML+"    sha256: '"+"0".repeat(64)+"'\n");
        for (boolean network : List.of(false,true)) {
            try (var service=service(network ? TestSupport.config() : badChecksum,network ? (r,p,l)->{throw new Failure("Download failed: HTTP 500");} : TestSupport.local(zip),s->{},p->{})) {
                CompletableFuture<InstallService.Status> done=new CompletableFuture<>(); service.start("lobby",false,done::complete);
                assertEquals("FAILED",done.get(5,TimeUnit.SECONDS).state()); assertEquals("none",service.installed().profile());
                assertEquals("old",Files.readString(root.resolve("plugins/test.txt"))); assertFalse(Files.exists(data.resolve("installed.properties")));
            }
        }
    }
    @Test void repeatedRequestsAndChecksAreSerializedGlobally() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        InstallService.Fetch blocking=(r,p,l)-> {
            entered.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new Failure("Interrupted"); }
            return TestSupport.local(zip).get(r,p,l);
        };
        try (var service=service(TestSupport.config(),blocking,s->{},p->{})) {
            CompletableFuture<InstallService.Status> done=new CompletableFuture<>(); service.start("lobby",false,done::complete);
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertThrows(Failure.class,()->service.start("lobby",false,s->{})); assertThrows(Failure.class,()->service.start("lobby",true,s->{}));
            release.countDown(); assertEquals("SUCCEEDED",done.get(5,TimeUnit.SECONDS).state());
        } finally { release.countDown(); }
    }
    @Test void versionAndBindingPersistOnlyAfterCommit() throws Exception {
        try (var service=service(TestSupport.config(),TestSupport.local(zip),s->{},p->{})) {
            CompletableFuture<InstallService.Status> done=new CompletableFuture<>(); service.start("lobby",false,done::complete);
            assertEquals("SUCCEEDED",done.get(5,TimeUnit.SECONDS).state());
        }
        try (var service=service(TestSupport.config(),TestSupport.local(zip),s->{},p->{})) {
            assertEquals("lobby",service.installed().profile()); assertEquals("v1",service.installed().version());
            assertEquals(TestSupport.digest(zip),service.installed().sha256()); assertEquals("new",Files.readString(root.resolve("plugins/test.txt")));
        }
    }
    @Test void failedStateWriteRollsBackPayloadAndState() throws Exception {
        TransactionInstaller installer=new TransactionInstaller(root,data.resolve("transactions"),(i,p)->{if(p.equals(data.resolve("installed.properties"))) throw new java.io.IOException();});
        try (var service=new InstallService(TestSupport.config(),installer,root,data,Set.of(),p->{},s->{},()->{},TestSupport.local(zip))) {
            CompletableFuture<InstallService.Status> done=new CompletableFuture<>(); service.start("lobby",false,done::complete);
            assertEquals("FAILED",done.get(5,TimeUnit.SECONDS).state()); assertFalse(Files.exists(data.resolve("installed.properties")));
            assertEquals("none",service.installed().profile()); assertEquals("old",Files.readString(root.resolve("plugins/test.txt")));
        }
    }
    @Test void guardFailurePreventsApplyAndLogsNoSecrets() throws Exception {
        String secret="my-secret-token";
        var config=TestSupport.config(TestSupport.YAML.replace("type: https","type: https\n      auth:\n        token: "+secret)
                .replace("profile.zip","profile.zip?signed=secret-query"));
        List<String> logs=new CopyOnWriteArrayList<>();
        try (var service=service(config,TestSupport.local(zip),logs::add,p->{throw new java.io.IOException("secret-query "+secret);})) {
            CompletableFuture<InstallService.Status> done=new CompletableFuture<>(); service.start("lobby",false,done::complete);
            var result=done.get(5,TimeUnit.SECONDS); assertEquals("FAILED",result.state());
            assertFalse(String.join("\n",logs).contains(secret)); assertFalse(String.join("\n",logs).contains("secret-query"));
            assertFalse(result.message().contains(secret)); assertEquals("old",Files.readString(root.resolve("plugins/test.txt")));
        }
    }
    @Test void runtimeJarWithCustomFilenameCannotBeOverwritten() throws Exception {
        Path dangerous=TestSupport.zip(root,Map.of("plugins/test.txt","new","plugins/custom-name.jar","self"));
        try (var service=new InstallService(TestSupport.config(),new TransactionInstaller(root,data.resolve("transactions")),root,data,
                Set.of("plugins/custom-name.jar"),p->{},s->{},()->{},TestSupport.local(dangerous))) {
            CompletableFuture<InstallService.Status> done=new CompletableFuture<>(); service.start("lobby",false,done::complete);
            assertEquals("FAILED",done.get(5,TimeUnit.SECONDS).state()); assertEquals("old",Files.readString(root.resolve("plugins/test.txt")));
        }
    }
}
