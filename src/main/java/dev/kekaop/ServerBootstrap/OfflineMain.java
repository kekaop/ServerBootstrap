package dev.kekaop.ServerBootstrap;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Entry point of the separate offline distribution; it never starts a Minecraft server. */
public final class OfflineMain {
    private OfflineMain() {}
    public static void main(String[] args) {
        try { System.exit(run(args)); }
        catch (Exception e) {
            System.err.println(e instanceof Failure ? e.getMessage() : "Offline operation failed; check permissions, storage and configuration");
            System.exit(1);
        }
    }
    static int run(String[] args) throws Exception {
        if (args.length!=4 || !Set.of("check","install","recover").contains(args[2]) || !args[3].equals("--server-stopped")) {
            System.err.println("Usage: OfflineMain <server-directory> <profile> <check|install|recover> --server-stopped");
            return 2;
        }
        Path root=Path.of(args[0]).toRealPath();
        Path data=SafePaths.resolve(root,"plugins/ServerBootstrap");
        if (!Files.isDirectory(data)) throw new Failure("Missing plugins/ServerBootstrap directory; create it and config.yml first");
        try (NodeLock ignored=new NodeLock(root,data); WorldLocks worlds=new WorldLocks(root)) {
            TransactionInstaller installer=new TransactionInstaller(root,data.resolve("transactions"));
            if (Files.exists(data.resolve("maintenance.properties"))) throw new Failure("Pending world maintenance; finish or cancel it using the server plugin before offline operations");
            installer.recover(System.out::println);
            if (args[2].equals("recover")) { System.out.println("Recovery completed"); return 0; }
            BootstrapConfig config=ConfigFiles.load(data.resolve("config.yml"));
            try (InstallService service=new InstallService(config,installer,root,data,Set.of("plugins/ServerBootstrap"),
                    paths -> {},System.out::println,() -> System.out.println("Files committed. Start the Minecraft server manually."))) {
                CompletableFuture<InstallService.Status> done=new CompletableFuture<>();
                service.start(args[1],args[2].equals("check"),done::complete);
                return done.get().state().equals("FAILED") ? 1 : 0;
            }
        }
    }
    private static final class WorldLocks implements AutoCloseable {
        private final List<FileChannel> channels=new ArrayList<>();
        WorldLocks(Path root) throws IOException {
            try (var stream=Files.walk(root,32)) {
                for (Path path : stream.filter(p -> p.getFileName().toString().equals("session.lock")
                        && !p.startsWith(root.resolve("plugins"))).toList()) {
                    SafePaths.resolve(root,root.relativize(path).toString().replace('\\','/'));
                    FileChannel channel=FileChannel.open(path,StandardOpenOption.WRITE); channels.add(channel);
                    if (channel.tryLock()==null) throw new Failure("World session.lock is held; stop the server before installing");
                }
            } catch (IOException | RuntimeException e) {
                close(); throw new Failure("Cannot lock worlds; stop the server and check session.lock permissions");
            }
        }
        public void close() throws IOException {
            IOException error=null;
            for (FileChannel channel : channels) try { channel.close(); } catch (IOException e) { error=e; }
            if (error!=null) throw error;
        }
    }
}
