package dev.kekaop.ServerBootstrap;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateListenerTest {
    @TempDir Path root;
    static final String SECRET="listener-secret-12345678901234567890";
    InstallService service; UpdateListener listener;
    CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);
    List<String> logs=new CopyOnWriteArrayList<>(); HttpClient client=HttpClient.newHttpClient();
    @BeforeEach void start() throws Exception {
        Path data=Files.createDirectories(root.resolve("plugins/ServerBootstrap"));
        Properties state=new Properties(); state.setProperty("profile","lobby"); FileOps.properties(data.resolve("installed.properties"),state);
        Path zip=TestSupport.zip(root,Map.of("plugins/test.txt","new"));
        service=new InstallService(TestSupport.config(),new TransactionInstaller(root,data.resolve("transactions")),root,data,Set.of(),p->{},logs::add,()->{},(r,p,l)->{
            entered.countDown(); try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { throw new Failure("Interrupted"); }
            return TestSupport.local(zip).get(r,p,l);
        });
        listener=new UpdateListener(new BootstrapConfig.Listener(true,"127.0.0.1",0,SECRET),service,logs::add); listener.start();
    }
    @AfterEach void stop() { release.countDown(); listener.close(); service.close(); }
    HttpResponse<String> request(String method,String path,String token,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+listener.port()+path)).timeout(Duration.ofSeconds(3))
                .method(method,body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token!=null) builder.header("Authorization","Bearer "+token);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void authenticationMethodAndExactPathRequired() throws Exception {
        assertEquals(401,request("POST","/update",null,"").statusCode());
        assertEquals(401,request("POST","/update","wrong","").statusCode());
        assertEquals(405,request("GET","/update",SECRET,"").statusCode());
        assertEquals(404,request("POST","/update/extra",SECRET,"").statusCode());
        assertEquals(400,request("POST","/update?secret="+SECRET,SECRET,"").statusCode());
        assertEquals(413,request("POST","/update",SECRET,"{\"secret\":\""+SECRET+"\"}").statusCode());
        assertFalse(String.join("\n",logs).contains(SECRET)); assertFalse(service.isBusy());
    }
    @Test void acceptsOnceReportsConflictAndStatus() throws Exception {
        var accepted=request("POST","/update",SECRET,""); assertEquals(202,accepted.statusCode()); assertTrue(accepted.body().contains("operation"));
        assertTrue(entered.await(3,TimeUnit.SECONDS)); assertEquals(409,request("POST","/update",SECRET,"").statusCode());
        var status=request("GET","/status",SECRET,""); assertEquals(200,status.statusCode()); assertTrue(status.body().contains("DOWNLOADING"));
        assertEquals(401,request("GET","/status",null,"").statusCode()); assertFalse(String.join("\n",logs).contains(SECRET));
    }
    @Test void jsonEscapingIsValid() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"",UpdateListener.quote("a\"b\\c\n"));
    }
}
