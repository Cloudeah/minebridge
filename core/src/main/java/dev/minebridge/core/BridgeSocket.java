package dev.minebridge.core;

import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.extensions.DefaultExtension;
import org.java_websocket.protocols.Protocol;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Loopback only. Critical output is bounded; overload closes with a visible history-gap recovery. */
public final class BridgeSocket extends WebSocketServer implements AutoCloseable {
    private final BridgeEngine engine;
    private final Consumer<String> errors;
    private final Map<WebSocket,Connection> peers=new ConcurrentHashMap<>();
    private final ScheduledExecutorService output=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"minebridge-output");t.setDaemon(true);return t;});
    public BridgeSocket(int port,BridgeEngine engine,Consumer<String> errors) {
        super(new InetSocketAddress("127.0.0.1",port),1,List.of(new Draft_6455(List.of(new DefaultExtension()),List.of(new Protocol("")),65536)));
        this.engine=engine;this.errors=errors;setReuseAddr(true);setConnectionLostTimeout(15);
    }
    @Override public void onOpen(WebSocket socket,ClientHandshake handshake) {
        if(!handshake.getResourceDescriptor().equals("/bridge") || handshake.hasFieldValue("Origin") || peers.size()>=2) { socket.close(1008,"Unsupported path, browser origin or connection limit");return; }
        peers.put(socket,new Connection(socket));
    }
    @Override public void onClose(WebSocket socket,int code,String reason,boolean remote) { Connection c=peers.remove(socket);if(c!=null) engine.disconnected(c); }
    @Override public void onMessage(WebSocket socket,String message) { Connection c=peers.get(socket);if(c!=null) engine.receive(c,message); }
    @Override public void onError(WebSocket socket,Exception error) { errors.accept("WebSocket: "+error.getClass().getSimpleName()); }
    @Override public void onStart() { output.scheduleAtFixedRate(()->{for(Connection c:peers.values()) c.flush();},0,10,TimeUnit.MILLISECONDS); }
    public int boundPort() { return getPort(); }
    private final class Connection implements BridgeEngine.Peer {
        private final WebSocket socket;
        private final ArrayBlockingQueue<String> control=new ArrayBlockingQueue<>(256);
        private final AtomicReference<byte[]> frame=new AtomicReference<>();
        Connection(WebSocket socket) { this.socket=socket; }
        @Override public void text(JsonObject message) { if(!control.offer(message.toString())) close("output_overflow_resume_required"); }
        @Override public void binary(byte[] value) { if(value.length<=8*1024*1024) frame.set(value); }
        @Override public void close(String reason) { socket.close(1013,reason); }
        void flush() {
            if(!socket.isOpen()) return;
            // Java-WebSocket's own queue is not unbounded: stop forwarding while it has buffered data.
            if(socket.hasBufferedData()) return;
            String message=control.poll();
            if(message!=null) {socket.send(message);return;}
            byte[] latest=frame.getAndSet(null);if(latest!=null) socket.send(latest);
        }
    }
    @Override public void close() { output.shutdownNow();try{stop(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();} }
}
