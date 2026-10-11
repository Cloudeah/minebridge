package dev.minebridge.core;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryVisionTest {
    @TempDir Path dir;
    @Test void memoryPersistsAndSeparatesWorldsAndDimensions() throws Exception {
        WorldMemory memory=new WorldMemory(dir,"first");memory.observe(object("blocks",List.of(block("overworld",0,0,0,"minecraft:stone","ray_observation"))),12);
        memory.annotate(object("text","base"),"overworld");memory.flush();
        WorldMemory reloaded=new WorldMemory(dir,"first");var loaded=reloaded.query(queryAt(0,0,0),"overworld",20);assertEquals(1,loaded.getAsJsonArray("blocks").size());assertEquals("remembered",loaded.getAsJsonArray("blocks").get(0).getAsJsonObject().get("freshness").getAsString());
        assertEquals("remembered",reloaded.query(queryAt(0,0,0),"overworld",12).getAsJsonArray("blocks").get(0).getAsJsonObject().get("freshness").getAsString());
        reloaded.observe(object("blocks",List.of(block("overworld",0,0,0,"minecraft:stone","ray_observation"))),12);
        assertEquals("observed",reloaded.query(queryAt(0,0,0),"overworld",12).getAsJsonArray("blocks").get(0).getAsJsonObject().get("freshness").getAsString());
        assertEquals("observed",memory.query(queryAt(0,0,0),"overworld",12).getAsJsonArray("blocks").get(0).getAsJsonObject().get("freshness").getAsString());
        JsonObject seen=loaded.getAsJsonArray("blocks").get(0).getAsJsonObject();assertEquals("ray_observation",seen.get("source").getAsString());assertEquals("ray_observation",seen.get("observation_source").getAsString());assertEquals("engine_observation",seen.get("provenance").getAsString());
        assertTrue(new WorldMemory(dir,"second").query(queryAt(0,0,0),"overworld",20).getAsJsonArray("blocks").isEmpty());
        assertTrue(memory.query(queryAt(0,0,0),"nether",20).getAsJsonArray("blocks").isEmpty());assertTrue(memory.query(queryAt(0,0,0),"nether",20).getAsJsonArray("notes").isEmpty());
    }
    @Test void nearestIsDistanceOrderedAndBreaksTiesDeterministically() throws Exception {
        WorldMemory memory=new WorldMemory(dir,"nearest");
        memory.observe(object("blocks",List.of(block("overworld",1,0,0,"minecraft:stone","ray"),block("overworld",-1,0,0,"minecraft:dirt","ray"),block("overworld",0,2,0,"minecraft:stone","ray"))),10);
        JsonObject nearest=memory.nearest(object("x",0,"y",0,"z",0,"radius",4,"blocks",List.of("minecraft:stone","minecraft:dirt")),"overworld",10);
        assertTrue(nearest.get("found").getAsBoolean());assertEquals(1,nearest.getAsJsonArray("blocks").size());
        JsonObject first=nearest.getAsJsonArray("blocks").get(0).getAsJsonObject();assertEquals(-1,first.get("x").getAsInt());assertEquals(1.0,first.get("distance").getAsDouble());assertEquals("observed",first.get("freshness").getAsString());
        JsonObject stone=memory.query(object("x",0,"y",0,"z",0,"radius",4,"block","minecraft:stone"),"overworld",10);
        assertEquals(2,stone.getAsJsonArray("blocks").size());assertEquals("minecraft:stone",stone.getAsJsonArray("blocks").get(0).getAsJsonObject().get("type").getAsString());
        JsonObject absent=memory.nearest(object("x",0,"y",0,"z",0,"block","minecraft:gold_block"),"overworld",10);
        assertFalse(absent.get("found").getAsBoolean());assertTrue(absent.getAsJsonArray("blocks").isEmpty());assertTrue(absent.has("unknown"));
    }
    @Test void queryRequiresFiniteCoordinatesAndCanFilterByAge() throws Exception {
        WorldMemory memory=new WorldMemory(dir,"age");memory.observe(object("blocks",List.of(block("overworld",0,0,0,"minecraft:stone","ray"))),10);
        assertTrue(memory.query(object("x",0,"y",0,"z",0,"max_age_ticks",0),"overworld",11).getAsJsonArray("blocks").isEmpty());
        JsonObject oneTickOld=memory.query(object("x",0,"y",0,"z",0,"max_age_ticks",1),"overworld",11);
        assertEquals(1,oneTickOld.getAsJsonArray("blocks").size());assertEquals("remembered",oneTickOld.getAsJsonArray("blocks").get(0).getAsJsonObject().get("freshness").getAsString());
        assertThrows(BridgeException.class,()->memory.query(object("y",0,"z",0),"overworld",11));
        JsonObject nonFinite=object("x",0,"y",0,"z",0);nonFinite.addProperty("x",Double.POSITIVE_INFINITY);
        assertThrows(BridgeException.class,()->memory.query(nonFinite,"overworld",11));
    }
    @Test void confirmedAirReplacesAStaleBlockAtTheSameCell() throws Exception {
        WorldMemory memory=new WorldMemory(dir,"air");memory.observe(object("blocks",List.of(block("overworld",4,5,6,"minecraft:oak_log","ray"))),20);
        assertThrows(BridgeException.class,()->memory.confirmBlock(block("overworld",4,5,6,"minecraft:air",null),21,"block.break","task-7"));
        JsonObject actualAir=block("overworld",4,5,6,"minecraft:air",null);actualAir.add("properties",object());
        memory.confirmBlock(actualAir,21,"block.break","task-7");
        JsonObject result=memory.query(queryAt(4,5,6),"overworld",21);assertEquals(1,result.getAsJsonArray("blocks").size());
        JsonObject known=result.getAsJsonArray("blocks").get(0).getAsJsonObject();assertEquals("minecraft:air",known.get("type").getAsString());
        assertEquals("integrated_server_confirmation",known.get("source").getAsString());assertEquals("block.break",known.get("confirmed_action").getAsString());
        assertEquals("task-7",known.get("task_id").getAsString());assertEquals(21,known.get("last_seen_tick").getAsLong());
    }
    @Test void queryBoundsResultsAndReturnsDeepCopies() throws Exception {
        WorldMemory memory=new WorldMemory(dir,"bounded");memory.observe(object("blocks",List.of(block("overworld",0,0,0,"minecraft:stone","ray"),block("overworld",1,0,0,"minecraft:dirt","ray"),block("overworld",2,0,0,"minecraft:grass_block","ray"))),5);
        for(int i=0;i<4;i++)memory.annotate(object("text","note-"+i),"overworld");memory.annotate(object("text","other dimension"),"nether");
        JsonObject limited=memory.query(object("x",0,"y",0,"z",0,"limit",2),"overworld",5);
        assertEquals(3,limited.get("blocks_available").getAsInt());assertEquals(4,limited.get("notes_available").getAsInt());assertTrue(limited.get("truncated").getAsBoolean());
        assertEquals(2,limited.getAsJsonArray("blocks").size());assertEquals(2,limited.getAsJsonArray("notes").size());
        assertEquals("note-2",limited.getAsJsonArray("notes").get(0).getAsJsonObject().get("text").getAsString());
        limited.getAsJsonArray("blocks").get(0).getAsJsonObject().addProperty("type","changed");limited.getAsJsonArray("notes").get(0).getAsJsonObject().addProperty("text","changed");
        JsonObject fresh=memory.query(object("x",0,"y",0,"z",0,"limit",2),"overworld",5);
        assertEquals("minecraft:stone",fresh.getAsJsonArray("blocks").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("note-2",fresh.getAsJsonArray("notes").get(0).getAsJsonObject().get("text").getAsString());
        JsonObject badPosition=object("x",0,"y",0,"z",0);badPosition.addProperty("y",Double.NaN);
        assertThrows(BridgeException.class,()->memory.annotate(object("text","bad","position",badPosition),"overworld"));
    }
    @Test void loadsAndPreservesThePreviousJsonShape() throws Exception {
        String world="legacy";Path legacy=dir.resolve(UUID.nameUUIDFromBytes(world.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".json");
        JsonObject oldBlock=object("dimension","overworld","x",2,"y",3,"z",4,"type","minecraft:chest","last_seen_tick",7,"last_seen_time",1234,"source","engine_observation");
        JsonObject oldNote=object("note_id","old-note","text","old base","dimension","overworld","source","external_ai","time",1200);
        Files.writeString(legacy,GSON.toJson(object("world_id",world,"blocks",List.of(oldBlock),"notes",List.of(oldNote))));
        WorldMemory memory=new WorldMemory(dir,world);JsonObject result=memory.query(queryAt(2,3,4),"overworld",8);
        assertEquals("minecraft:chest",result.getAsJsonArray("blocks").get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("old base",result.getAsJsonArray("notes").get(0).getAsJsonObject().get("text").getAsString());
        memory.annotate(object("text","new note"),"overworld");memory.flush();
        JsonObject persisted=JsonParser.parseString(Files.readString(legacy)).getAsJsonObject();
        assertEquals("engine_observation",persisted.getAsJsonArray("blocks").get(0).getAsJsonObject().get("source").getAsString());
        assertEquals("old-note",persisted.getAsJsonArray("notes").get(0).getAsJsonObject().get("note_id").getAsString());
    }
    @Test void ownerTokenSurvivesReload() throws Exception {Path file=dir.resolve("config/owner.json");OwnerPolicy a=OwnerPolicy.load(file),b=OwnerPolicy.load(file);assertEquals(a.token,b.token);assertTrue(a.token.length()>=32);assertFalse(a.publicProfile().toString().contains(a.token));}
    @Test void packetHasBoundedMetadataAndLittleEndianDepth(){byte[] p=VisionPacket.encode(object("frame_id","f"),new byte[]{1,2},new float[]{2.5f,Float.NaN},2,1);ByteBuffer b=ByteBuffer.wrap(p);assertEquals(0x4d425631,b.getInt());int json=b.getInt();b.position(8+json+2);b.order(ByteOrder.LITTLE_ENDIAN);assertEquals(2.5f,b.getFloat());assertTrue(Float.isNaN(b.getFloat()));}
    @Test void skyDepthIsInvalid(){float[] inverse=new float[16];inverse[0]=inverse[5]=inverse[10]=inverse[15]=1;assertTrue(Float.isNaN(DepthMath.axial(1,0,0,inverse,false)));assertTrue(Float.isNaN(DepthMath.axial(Float.NaN,0,0,inverse,false)));}
    @Test void depthUsesInverseProjection(){float[] inverse=new float[16];inverse[0]=inverse[5]=inverse[10]=inverse[15]=1;assertEquals(0.5f,DepthMath.axial(0.25f,0,0,inverse,false));}

    private static JsonObject block(String dimension,int x,int y,int z,String type,String source){JsonObject block=object("dimension",dimension,"x",x,"y",y,"z",z,"type",type);if(source!=null)block.addProperty("source",source);return block;}
    private static JsonObject queryAt(int x,int y,int z){return object("x",x,"y",y,"z",z);}
}
