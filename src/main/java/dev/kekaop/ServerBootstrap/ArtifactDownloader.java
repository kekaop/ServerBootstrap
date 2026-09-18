package dev.kekaop.ServerBootstrap;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

public final class ArtifactDownloader {
    public record Download(long bytes, String sha256) {}
    private final HttpClient client;
    public ArtifactDownloader(BootstrapConfig.Limits limits) {
        this(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(limits.connect()).build());
    }
    ArtifactDownloader(HttpClient client) { this.client = client; }

    public Download download(ArtifactSource.Request source, Path target, BootstrapConfig.Limits limits) throws IOException {
        long deadline = System.nanoTime() + limits.download().toNanos();
        URI uri = source.uri(); boolean authAllowed = true;
        for (int redirects=0; redirects<=5; redirects++) {
            BootstrapConfig.https(uri.toString(),"artifact URL");
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new Failure("Download timed out");
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                    .header("User-Agent","ServerBootstrap/2.1").header("Accept","application/octet-stream")
                    .header("Accept-Encoding","identity").GET();
            if (authAllowed && !source.auth().value().isEmpty()) builder.header(source.auth().header(),source.auth().value());
            LimitedBody body = new LimitedBody(target,limits.downloadBytes());
            CompletableFuture<HttpResponse<Path>> future = client.sendAsync(builder.build(), info -> {
                if (info.statusCode() != 200) return new EmptyBody();
                try {
                    if (info.headers().firstValueAsLong("Content-Length").orElse(0) > limits.downloadBytes())
                        body.fail(new Failure("Download exceeds size limit"));
                    if (!info.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity"))
                        body.fail(new Failure("Unsupported HTTP content encoding"));
                } catch (RuntimeException e) { body.fail(new Failure("Invalid HTTP headers")); }
                return body;
            });
            HttpResponse<Path> response;
            try { response = future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS); }
            catch (TimeoutException e) { body.fail(new Failure("Download timed out")); future.cancel(true); throw new Failure("Download timed out"); }
            catch (InterruptedException e) { body.fail(new Failure("Download interrupted")); future.cancel(true); Thread.currentThread().interrupt(); throw new Failure("Download interrupted"); }
            catch (ExecutionException | RuntimeException e) {
                body.fail(new Failure("Download failed"));
                Throwable cause = e.getCause();
                if (cause instanceof Failure f) throw f;
                throw new Failure(cause instanceof HttpTimeoutException ? "Download timed out" : "Network or TLS error while downloading");
            }
            int status = response.statusCode();
            if (Set.of(301,302,303,307,308).contains(status)) {
                if (redirects == 5) throw new Failure("Too many HTTP redirects");
                URI next;
                try { next = uri.resolve(response.headers().firstValue("Location").orElseThrow()); }
                catch (RuntimeException e) { throw new Failure("Invalid HTTP redirect"); }
                BootstrapConfig.https(next.toString(),"redirect URL");
                authAllowed &= sameOrigin(uri,next);
                uri = next; continue;
            }
            if (status != 200) throw new Failure("Download failed: HTTP " + status);
            if (body.count == 0) throw new Failure("Downloaded artifact is empty");
            return new Download(body.count,HexFormat.of().formatHex(body.digest.digest()));
        }
        throw new Failure("Too many HTTP redirects");
    }
    public static void verify(Download download, String expected) throws Failure {
        if (!expected.isEmpty() && !MessageDigest.isEqual(download.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expected.toLowerCase(Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new Failure("SHA-256 mismatch; installation cancelled");
    }
    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost())
                && (a.getPort()==-1?443:a.getPort()) == (b.getPort()==-1?443:b.getPort());
    }
    private static final class EmptyBody implements HttpResponse.BodySubscriber<Path> {
        public CompletionStage<Path> getBody() { return CompletableFuture.completedFuture(null); }
        public void onSubscribe(Flow.Subscription s) { s.cancel(); }
        public void onNext(List<ByteBuffer> b) {}
        public void onError(Throwable t) {}
        public void onComplete() {}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<Path> {
        private final CompletableFuture<Path> done = new CompletableFuture<>();
        private final Path target; private final long limit; private final MessageDigest digest;
        private Flow.Subscription subscription; private OutputStream out; private long count;
        LimitedBody(Path target, long limit) {
            this.target=target; this.limit=limit;
            try { digest=MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        }
        public CompletionStage<Path> getBody() { return done; }
        public synchronized void onSubscribe(Flow.Subscription s) {
            subscription=s;
            if (done.isDone()) { s.cancel(); return; }
            try { out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW); s.request(1); }
            catch (IOException e) { fail(new Failure("Cannot create temporary download")); }
        }
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (done.isDone()) return;
            try {
                byte[] chunk = new byte[8192];
                for (ByteBuffer b : buffers) while (b.hasRemaining()) {
                    int n=Math.min(chunk.length,b.remaining());
                    if (n > limit-count) throw new Failure("Download exceeds size limit");
                    b.get(chunk,0,n); out.write(chunk,0,n); digest.update(chunk,0,n); count+=n;
                }
                subscription.request(1);
            } catch (IOException e) { fail(e instanceof Failure ? e : new Failure("Cannot write temporary download")); }
        }
        public synchronized void onError(Throwable t) { fail(new Failure("Network error while reading artifact")); }
        public synchronized void onComplete() {
            if (done.isDone()) return;
            try { out.close(); done.complete(target); } catch (IOException e) { fail(new Failure("Cannot finish temporary download")); }
        }
        synchronized void fail(Throwable t) {
            if (done.isDone()) return;
            if (subscription!=null) subscription.cancel();
            try { if (out!=null) out.close(); } catch (IOException ignored) { /* original failure retained */ }
            done.completeExceptionally(t);
        }
    }
}
