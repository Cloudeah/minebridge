package dev.minebridge.core;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.minebridge.core.Json.*;

public final class WorldMemory {
    private final Path file;
    private final LinkedHashMap<String, JsonObject> blocks = new LinkedHashMap<>();
    private final List<JsonObject> notes = new ArrayList<>();
    private boolean dirty;
    public WorldMemory(Path directory, String worldId) throws IOException {
        Files.createDirectories(directory);
        file = directory.resolve(UUID.nameUUIDFromBytes(worldId.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".json");
        if (Files.exists(file)) {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!worldId.equals(string(root,"world_id"))) throw new IOException("World memory identity mismatch");
            for (JsonElement e : root.getAsJsonArray("blocks")) { JsonObject b=e.getAsJsonObject(); blocks.put(key(b),b); }
            for (JsonElement e : root.getAsJsonArray("notes")) notes.add(e.getAsJsonObject());
        }
        this.worldId=worldId;
    }
    private final String worldId;
    private static String key(JsonObject b) { return string(b,"dimension")+":"+b.get("x")+","+b.get("y")+","+b.get("z"); }
    public void observe(JsonObject snapshot, long tick) {
        if (!snapshot.has("blocks")) return;
        for (JsonElement e : snapshot.getAsJsonArray("blocks")) {
            JsonObject b=e.getAsJsonObject().deepCopy(); b.addProperty("last_seen_tick",tick); b.addProperty("last_seen_time",System.currentTimeMillis());
            b.addProperty("source","engine_observation"); blocks.remove(key(b)); blocks.put(key(b),b); dirty=true;
        }
        while (blocks.size()>20000) blocks.remove(blocks.keySet().iterator().next());
    }
    public JsonObject query(JsonObject query, String dimension, long tick) {
        double x=number(query,"x",0),y=number(query,"y",0),z=number(query,"z",0);
        int radius=integer(query,"radius",16,1,64),limit=integer(query,"limit",512,1,2048);
        JsonArray found=new JsonArray();
        String type=string(query,"block","");
        for (JsonObject b : blocks.values()) {
            if (found.size()>=limit) break;
            if (!dimension.equals(string(b,"dimension"))) continue;
            if (!type.isEmpty() && !type.equals(string(b,"type"))) continue;
            if (Math.abs(b.get("x").getAsDouble()-x)>radius || Math.abs(b.get("y").getAsDouble()-y)>radius || Math.abs(b.get("z").getAsDouble()-z)>radius) continue;
            JsonObject copy=b.deepCopy(); copy.addProperty("freshness",b.get("last_seen_tick").getAsLong()==tick ? "observed" : "remembered"); found.add(copy);
        }
        return object("world_id",worldId,"dimension",dimension,"blocks",found,"unknown","Unrecorded cells have no inferred traversability", "notes",notes.stream().filter(n->dimension.equals(string(n,"dimension"))).toList());
    }
    public JsonObject annotate(JsonObject data, String dimension) {
        if (notes.size()>=2000) throw new BridgeException("limit_exceeded","Annotation limit reached");
        JsonObject note=object("note_id",id(),"text",string(data,"text"),"dimension",dimension,"source","external_ai","time",System.currentTimeMillis());
        if (data.has("position")) note.add("position",child(data,"position").deepCopy());
        notes.add(note); dirty=true; return note;
    }
    public void flush() throws IOException {
        if (!dirty) return;
        Path temp=file.resolveSibling(file.getFileName()+".tmp");
        Files.writeString(temp,GSON.toJson(object("world_id",worldId,"blocks",blocks.values(),"notes",notes)));
        try { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
        dirty=false;
    }
}
