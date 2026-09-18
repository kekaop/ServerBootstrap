package dev.kekaop.ServerBootstrap;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** A write-ahead journal with per-file atomic replacement and durable backups. */
public final class TransactionInstaller {
    @FunctionalInterface interface BeforeWrite { void run(int index, Path destination) throws IOException; }
    private final Path root;
    private final Path transactions;
    private final BeforeWrite beforeWrite;

    public TransactionInstaller(Path root, Path transactions) throws IOException { this(root,transactions,(i,p)->{}); }
    TransactionInstaller(Path root, Path transactions, BeforeWrite beforeWrite) throws IOException {
        this.root=root.toRealPath(); this.transactions=transactions.toAbsolutePath().normalize(); this.beforeWrite=beforeWrite;
        if (!this.transactions.startsWith(this.root)) throw new Failure("Transactions must be inside server root");
        SafePaths.resolve(this.root,relative(this.transactions));
        Files.createDirectories(this.transactions);
    }
    public Path create(String id) throws IOException {
        if (!id.matches("[a-f0-9-]{36}")) throw new Failure("Invalid operation ID");
        return Files.createDirectory(SafePaths.resolve(root,relative(transactions.resolve(id))));
    }
    public void ensureDestination(String path) throws IOException {
        Path dest=SafePaths.resolve(root,path);
        if (Files.exists(dest,LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(dest,LinkOption.NOFOLLOW_LINKS))
            throw new Failure("Destination conflicts with a directory or special file");
        Path parent=dest.getParent();
        while (!parent.equals(root)) {
            if (Files.exists(parent,LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(parent,LinkOption.NOFOLLOW_LINKS))
                throw new Failure("Destination parent is not a directory");
            parent=parent.getParent();
        }
    }
    public void apply(Map<String,Path> changes, Path tx) throws IOException {
        Properties plan=new Properties(); List<String> paths=new ArrayList<>(changes.keySet());
        Set<String> dirs=new LinkedHashSet<>();
        Path backup=Files.createDirectory(tx.resolve("backup"));
        for (int i=0;i<paths.size();i++) {
            String path=paths.get(i); ensureDestination(path);
            Path dest=SafePaths.resolve(root,path);
            boolean exists=Files.exists(dest,LinkOption.NOFOLLOW_LINKS);
            plan.setProperty("file."+i,path); plan.setProperty("existed."+i,Boolean.toString(exists));
            if (exists) {
                Path saved=backup.resolve(Integer.toString(i));
                Files.copy(dest,saved,StandardCopyOption.COPY_ATTRIBUTES); FileOps.force(saved);
                plan.setProperty("backup-sha256."+i,FileOps.sha256(saved));
            }
            for (Path parent=dest.getParent(); !parent.equals(root) && !Files.exists(parent,LinkOption.NOFOLLOW_LINKS); parent=parent.getParent())
                dirs.add(relative(parent));
        }
        List<String> directories=dirs.stream().sorted(Comparator.comparingInt(s -> s.split("/").length)).toList();
        plan.setProperty("count",Integer.toString(paths.size()));
        plan.setProperty("directory-count",Integer.toString(directories.size()));
        for (int i=0;i<directories.size();i++) plan.setProperty("directory."+i,directories.get(i));
        FileOps.properties(tx.resolve("plan.properties"),plan);
        phase(tx,"PREPARED",0);
        int intended=0;
        try {
            for (String dir : directories) Files.createDirectories(SafePaths.resolve(root,dir));
            for (int i=0;i<paths.size();i++) {
                if (Thread.currentThread().isInterrupted()) throw new Failure("Installation interrupted");
                // Persist intent before replacing: recovery is safe even if a process stops between these steps.
                phase(tx,"APPLYING",i+1); intended=i+1;
                Path dest=SafePaths.resolve(root,paths.get(i));
                ensureDestination(paths.get(i)); beforeWrite.run(i,dest);
                FileOps.replace(changes.get(paths.get(i)),dest);
            }
            phase(tx,"COMMITTED",paths.size());
        } catch (IOException | RuntimeException failure) {
            boolean interrupted=Thread.interrupted();
            try { rollback(tx,plan,intended); }
            catch (IOException rollback) {
                throw new Failure("Apply failed; rollback incomplete. Backups and failed paths: transactions/"+tx.getFileName()+"/rollback.properties");
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
            throw new Failure("Apply failed; all applied files rolled back. Backups: transactions/"+tx.getFileName()+"/backup");
        }
    }
    private void rollback(Path tx, Properties plan, int intended) throws IOException {
        Properties failures=new Properties(); int failed=0;
        int count=parseCount(plan,"count",1000000);
        if (intended<0 || intended>count) throw new Failure("Invalid transaction journal; manual recovery required");
        for (int i=intended-1;i>=0;i--) {
            String path=plan.getProperty("file."+i);
            try {
                Path dest=SafePaths.resolve(root,path);
                if (Boolean.parseBoolean(plan.getProperty("existed."+i))) {
                    Path backup=SafePaths.resolve(root,relative(tx.resolve("backup/"+i)));
                    if (!FileOps.sha256(backup).equals(plan.getProperty("backup-sha256."+i))) throw new Failure("Backup checksum mismatch");
                    FileOps.replace(backup,dest);
                } else Files.deleteIfExists(dest);
            } catch (IOException | RuntimeException e) { failures.setProperty("failed."+(failed++),path==null ? "invalid journal path" : path); }
        }
        int directories=parseCount(plan,"directory-count",1000000);
        for (int i=directories-1;i>=0;i--) {
            String path=plan.getProperty("directory."+i);
            try { Files.deleteIfExists(SafePaths.resolve(root,path)); }
            catch (DirectoryNotEmptyException e) { /* preserve unrelated files created by the server */ }
            catch (IOException | RuntimeException e) { failures.setProperty("failed."+(failed++),path==null ? "invalid journal directory" : path); }
        }
        failures.setProperty("failed-count",Integer.toString(failed));
        FileOps.properties(tx.resolve("rollback.properties"),failures);
        phase(tx,failed==0 ? "ROLLED_BACK" : "ROLLBACK_FAILED",intended);
        if (failed>0) throw new Failure("Rollback incomplete");
    }
    public void recover(Consumer<String> log) throws IOException {
        try (var stream=Files.list(transactions)) {
            for (Path tx : stream.sorted().toList()) {
                SafePaths.resolve(root,relative(tx));
                if (!Files.isDirectory(tx,LinkOption.NOFOLLOW_LINKS)) throw new Failure("Unexpected file in transactions directory");
                Path phasePath=SafePaths.resolve(root,relative(tx.resolve("phase.properties")));
                if (!Files.exists(phasePath)) continue; // download/validation/backup only; live files untouched
                Properties phase=FileOps.read(phasePath);
                String state=phase.getProperty("state","");
                if (Set.of("COMMITTED","ROLLED_BACK").contains(state)) continue;
                if (!Set.of("PREPARED","APPLYING","ROLLBACK_FAILED").contains(state)) throw new Failure("Unknown transaction phase; manual recovery required");
                rollback(tx,FileOps.read(SafePaths.resolve(root,relative(tx.resolve("plan.properties")))),parseCount(phase,"intended",1000000));
                log.accept("event=recovered operation="+tx.getFileName());
            }
        }
    }
    public boolean needsRecovery(Path tx) {
        try {
            Path phase=tx.resolve("phase.properties");
            if (!Files.exists(phase)) return false;
            return !Set.of("COMMITTED","ROLLED_BACK").contains(FileOps.read(phase).getProperty("state",""));
        } catch (IOException e) { return true; }
    }
    public void assertClean() throws IOException {
        try (var stream=Files.list(transactions)) {
            for (Path tx : stream.toList()) {
                SafePaths.resolve(root,relative(tx));
                if (needsRecovery(tx)) throw new Failure("Unfinished direct transaction; stop the server and run offline recover (docs/OPERATIONS.md)");
            }
        }
    }
    private static int parseCount(Properties p,String field,int max) throws Failure {
        try { int n=Integer.parseInt(p.getProperty(field)); if (n<0 || n>max) throw new NumberFormatException(); return n; }
        catch (RuntimeException e) { throw new Failure("Invalid transaction journal; manual recovery required"); }
    }
    private void phase(Path tx,String state,int intended) throws IOException {
        Properties p=new Properties(); p.setProperty("state",state); p.setProperty("intended",Integer.toString(intended));
        FileOps.properties(tx.resolve("phase.properties"),p);
    }
    private String relative(Path path) { return root.relativize(path).toString().replace('\\','/'); }
}
