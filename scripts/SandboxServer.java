import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.*;
import java.io.*;

/** Local fixture only. Start with: java SandboxServer.java <sandbox> [port]. */
public class SandboxServer {
    public static void main(String[] args) throws Exception {
        Path base=Path.of(args[0]).toRealPath();
        if (!Files.isRegularFile(base.resolve(".serverbootstrap-sandbox"))) throw new IOException("Not a sandbox");
        KeyStore store=KeyStore.getInstance("PKCS12");
        try (InputStream in=Files.newInputStream(base.resolve("sandbox.p12"))) { store.load(in,"sandbox-only".toCharArray()); }
        KeyManagerFactory keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store,"sandbox-only".toCharArray());
        SSLContext context=SSLContext.getInstance("TLS"); context.init(keys.getKeyManagers(),null,null);
        HttpsServer server=HttpsServer.create(new InetSocketAddress("127.0.0.1",args.length>1 ? Integer.parseInt(args[1]) : 18443),4);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/",exchange -> {
            try (exchange) {
                if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getRawPath().equals("/profile.zip")) {
                    exchange.sendResponseHeaders(404,-1); return;
                }
                byte[] bytes=Files.readAllBytes(base.resolve("profile.zip"));
                exchange.getResponseHeaders().set("Content-Type","application/zip");
                exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        System.out.println("Sandbox HTTPS ready on "+server.getAddress().getPort()+". Press Enter to stop.");
        try { System.in.read(); } finally { server.stop(0); }
    }
}
