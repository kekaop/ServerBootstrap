package dev.kekaop.ServerBootstrap;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class NetworkTest {
    @TempDir static Path certFolder;
    @TempDir Path temp;
    static SSLContext tls;
    HttpsServer server; ExecutorService workers;
    ArtifactDownloader downloader;
    @BeforeAll static void certificate() throws Exception {
        Path store=certFolder.resolve("test.p12");
        Path keytool=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        Process p=new ProcessBuilder(keytool.toString(),"-genkeypair","-alias","test","-keyalg","RSA","-keysize","2048","-validity","2",
                "-storetype","PKCS12","-keystore",store.toString(),"-storepass","test-password","-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true).redirectOutput(certFolder.resolve("keytool.txt").toFile()).start();
        assertTrue(p.waitFor(20,TimeUnit.SECONDS)); assertEquals(0,p.exitValue());
        KeyStore keys=KeyStore.getInstance("PKCS12"); try (InputStream in=Files.newInputStream(store)) { keys.load(in,"test-password".toCharArray()); }
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys,"test-password".toCharArray());
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
        tls=SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(),tm.getTrustManagers(),new SecureRandom());
    }
    @BeforeEach void start() throws Exception {
        server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setHttpsConfigurator(new HttpsConfigurator(tls));
        workers=Executors.newCachedThreadPool(); server.setExecutor(workers); server.start();
        downloader=new ArtifactDownloader(HttpClient.newBuilder().sslContext(tls).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(2)).build());
    }
    @AfterEach void stop() { server.stop(0); workers.shutdownNow(); }
    URI uri(String path) { return URI.create("https://localhost:"+server.getAddress().getPort()+path); }
    ArtifactDownloader.Download get(String path,long max,Duration timeout) throws Exception {
        return downloader.download(new ArtifactSource.Request(uri(path),new BootstrapConfig.Auth("Authorization","Bearer test-secret")),temp.resolve(UUID.randomUUID()+".zip"),
                new BootstrapConfig.Limits(max,100000,100,Duration.ofSeconds(2),timeout));
    }
    static void reply(HttpExchange e,int status,byte[] bytes) throws IOException {
        try(e) { e.sendResponseHeaders(status,bytes.length); e.getResponseBody().write(bytes); }
    }
    @Test void httpsDownloadAndSameOriginRedirect() throws Exception {
        Path zip=TestSupport.zip(temp,Map.of("plugins/test.txt","content")); byte[] bytes=Files.readAllBytes(zip);
        AtomicReference<String> auth=new AtomicReference<>();
        server.createContext("/zip",e->{auth.set(e.getRequestHeaders().getFirst("Authorization")); reply(e,200,bytes);});
        server.createContext("/redirect",e->{e.getResponseHeaders().set("Location","/zip"); e.sendResponseHeaders(302,-1); e.close();});
        var downloaded=get("/redirect",100000,Duration.ofSeconds(5));
        assertEquals(bytes.length,downloaded.bytes()); assertEquals(TestSupport.digest(zip),downloaded.sha256()); assertEquals("Bearer test-secret",auth.get());
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,404,429,500,503})
    void httpErrorsContainStatusButNeverBody(int status) throws Exception {
        server.createContext("/error",e->reply(e,status,"test-secret response private".getBytes()));
        Failure error=assertThrows(Failure.class,()->get("/error",10000,Duration.ofSeconds(5)));
        assertTrue(error.getMessage().contains("HTTP "+status)); assertFalse(error.getMessage().contains("test-secret"));
    }
    @Test void advertisedAndStreamingSizeAreLimited() throws Exception {
        server.createContext("/large",e->reply(e,200,new byte[100]));
        server.createContext("/chunked",e->{try(e) { e.sendResponseHeaders(200,0); e.getResponseBody().write(new byte[100]); }});
        assertThrows(Failure.class,()->get("/large",10,Duration.ofSeconds(5)));
        assertThrows(Failure.class,()->get("/chunked",10,Duration.ofSeconds(5)));
    }
    @Test void timeoutsCoverHeadersAndStalledBody() throws Exception {
        server.createContext("/slow",e->{try { Thread.sleep(3000); reply(e,200,new byte[5]); } catch (InterruptedException ignored) { e.close(); }});
        server.createContext("/stall",e->{try(e) { e.sendResponseHeaders(200,0); e.getResponseBody().write(1); e.getResponseBody().flush(); try { Thread.sleep(3000); } catch (InterruptedException ignored) {} }});
        for (String path : List.of("/slow","/stall")) {
            long started=System.nanoTime(); assertThrows(Failure.class,()->get(path,10000,Duration.ofMillis(350)));
            assertTrue(Duration.ofNanos(System.nanoTime()-started).toMillis()<2500);
        }
    }
    @Test void downgradeAndRedirectLoopRejected() throws Exception {
        server.createContext("/downgrade",e->{e.getResponseHeaders().set("Location","http://localhost/artifact.zip?test-secret"); e.sendResponseHeaders(302,-1); e.close();});
        server.createContext("/loop",e->{e.getResponseHeaders().set("Location","/loop"); e.sendResponseHeaders(302,-1); e.close();});
        Failure f=assertThrows(Failure.class,()->get("/downgrade",10000,Duration.ofSeconds(5))); assertFalse(f.getMessage().contains("test-secret"));
        assertThrows(Failure.class,()->get("/loop",10000,Duration.ofSeconds(5)));
    }
    @Test void credentialsNeverCrossOriginEvenIfRedirectReturns() throws Exception {
        HttpsServer other=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0); other.setHttpsConfigurator(new HttpsConfigurator(tls)); other.setExecutor(workers);
        AtomicReference<String> externalAuth=new AtomicReference<>("unset"),returnedAuth=new AtomicReference<>("unset");
        other.createContext("/bounce",e->{ externalAuth.set(e.getRequestHeaders().getFirst("Authorization")); e.getResponseHeaders().set("Location",uri("/final").toString()); e.sendResponseHeaders(302,-1); e.close(); });
        other.start();
        try {
            server.createContext("/external",e->{e.getResponseHeaders().set("Location","https://localhost:"+other.getAddress().getPort()+"/bounce"); e.sendResponseHeaders(302,-1); e.close();});
            server.createContext("/final",e->{returnedAuth.set(e.getRequestHeaders().getFirst("Authorization")); reply(e,200,"zip".getBytes());});
            get("/external",10000,Duration.ofSeconds(5)); assertNull(externalAuth.get()); assertNull(returnedAuth.get());
        } finally { other.stop(0); }
    }
    @Test void refusedConnectionAndTlsFailureAreSafe() throws Exception {
        int port;
        try (ServerSocket socket=new ServerSocket(0)) { port=socket.getLocalPort(); }
        var limits=new BootstrapConfig.Limits(10000,10000,100,Duration.ofSeconds(1),Duration.ofSeconds(2));
        Failure f=assertThrows(Failure.class,()->downloader.download(new ArtifactSource.Request(URI.create("https://localhost:"+port+"/test?test-secret"),new BootstrapConfig.Auth("Authorization","")),temp.resolve("refused"),limits));
        assertFalse(f.getMessage().contains("test-secret"));
        server.createContext("/tls",e->reply(e,200,"data".getBytes()));
        assertThrows(Failure.class,()->new ArtifactDownloader(limits).download(new ArtifactSource.Request(uri("/tls"),new BootstrapConfig.Auth("Authorization","")),temp.resolve("tls"),limits));
    }
}
