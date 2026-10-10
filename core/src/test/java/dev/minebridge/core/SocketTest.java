package dev.minebridge.core;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class SocketTest {
    @TempDir Path dir;
    @Test void realWebSocketHandshakeAndAuthenticatedSnapshot() throws Exception {
        BridgeEngineTest.FakeWorld world=new BridgeEngineTest.FakeWorld();OwnerPolicy p=new OwnerPolicy();p.token="integration-token";
        BridgeEngine engine=new BridgeEngine(world,p,new WorldMemory(dir,"socket-test"),e->{});
        BlockingQueue<String> received=new LinkedBlockingQueue<>();
        try(BridgeSocket server=new BridgeSocket(0,engine,e->{throw new AssertionError(e);})) {
            server.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(server.boundPort()==0 && System.nanoTime()<deadline)Thread.sleep(10);
            HttpClient http=HttpClient.newBuilder().proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,java.io.IOException e){}}).build();
            WebSocket client=http.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:"+server.boundPort()+"/bridge"),new WebSocket.Listener(){
                StringBuilder text=new StringBuilder();
                public void onOpen(WebSocket socket){socket.request(1);}
                public CompletionStage<?> onText(WebSocket socket,CharSequence part,boolean last){text.append(part);if(last){received.add(text.toString());text.setLength(0);}socket.request(1);return null;}
            }).get(5,TimeUnit.SECONDS);
            client.sendText(object("protocol_version","1.0","session_id",engine.sessionId,"message_id",id(),"request_id","auth","type","session.authenticate","data",object("token",p.token)).toString(),true).join();
            String response=pump(engine,world,received);assertEquals("authenticated",JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("data").get("status").getAsString());
            client.sendText(object("protocol_version","1.0","session_id",engine.sessionId,"message_id",id(),"request_id","snapshot","type","observe.snapshot","data",object()).toString(),true).join();
            response=pump(engine,world,received);assertEquals("player",JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("data").get("actor_id").getAsString());
            client.sendClose(WebSocket.NORMAL_CLOSURE,"done").join();
        }
    }
    private String pump(BridgeEngine engine,BridgeEngineTest.FakeWorld world,BlockingQueue<String> messages) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<until){world.tick++;engine.tick();String r=messages.poll(20,TimeUnit.MILLISECONDS);if(r!=null)return r;}
        fail("No WebSocket response within 5 seconds");return null;
    }
}
