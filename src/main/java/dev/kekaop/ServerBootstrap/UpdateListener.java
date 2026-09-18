package dev.kekaop.ServerBootstrap;

import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Optional control endpoint. No request bodies, URLs or supplied credentials are logged. */
public final class UpdateListener implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;
    private final InstallService service;
    private final Consumer<String> log;
    private final byte[] authorization;

    public UpdateListener(BootstrapConfig.Listener config,InstallService service,Consumer<String> log) throws IOException {
        this.service=service; this.log=log;
        authorization=("Bearer "+config.token()).getBytes(StandardCharsets.UTF_8);
        server=HttpServer.create(new InetSocketAddress(config.bind(),config.port()),16);
        executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),
                r -> { Thread t=new Thread(r,"ServerBootstrap-http"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(executor); server.createContext("/",this::handle);
    }
    public void start() { server.start(); }
    int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path=exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery()!=null) { reply(exchange,400,"{\"error\":\"query-not-allowed\"}"); return; }
            if (!path.equals("/update") && !path.equals("/status")) { reply(exchange,404,"{\"error\":\"not-found\"}"); return; }
            String method=path.equals("/update") ? "POST" : "GET";
            if (!exchange.getRequestMethod().equals(method)) {
                exchange.getResponseHeaders().set("Allow",method); reply(exchange,405,"{\"error\":\"method-not-allowed\"}"); return;
            }
            var headers=exchange.getRequestHeaders().get("Authorization");
            String received=headers!=null && headers.size()==1 ? headers.get(0) : "";
            if (received.length()>4096 || !MessageDigest.isEqual(authorization,received.getBytes(StandardCharsets.UTF_8))) {
                log.accept("event=http-unauthorized"); reply(exchange,401,"{\"error\":\"unauthorized\"}"); return;
            }
            if (bodyLength(exchange)) { reply(exchange,413,"{\"error\":\"body-not-allowed\"}"); return; }
            if (path.equals("/status")) { reply(exchange,200,json(service.status())); return; }
            String profile=service.installed().profile();
            if (profile.equals("none")) { reply(exchange,409,"{\"error\":\"node-not-bound\"}"); return; }
            try {
                String id=service.start(profile,false,s -> {});
                log.accept("event=http-update-accepted operation="+id);
                reply(exchange,202,"{\"status\":\"accepted\",\"operation\":\""+id+"\"}");
            } catch (Failure failure) {
                log.accept("event=http-update-rejected reason="+failure.getMessage());
                reply(exchange,409,"{\"error\":"+quote(failure.getMessage())+"}");
            }
        } catch (RuntimeException e) { log.accept("event=http-error"); }
    }
    private boolean bodyLength(HttpExchange e) {
        String n=e.getRequestHeaders().getFirst("Content-Length");
        return e.getRequestHeaders().containsKey("Transfer-Encoding") || (n!=null && !n.equals("0"));
    }
    static String json(InstallService.Status s) {
        return "{\"operation\":"+quote(s.id())+",\"profile\":"+quote(s.profile())+",\"status\":"+quote(s.state())+",\"message\":"+quote(s.message())+"}";
    }
    static String quote(String s) {
        StringBuilder out=new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c=='"' || c=='\\') out.append('\\').append(c);
            else if (c<32) out.append(String.format("\\u%04x",(int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
    private void reply(HttpExchange exchange,int code,String body) throws IOException {
        if (code>=400) log.accept("event=http-rejected status="+code);
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.sendResponseHeaders(code,bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
