package dev.minebridge.core;

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
        WorldMemory memory=new WorldMemory(dir,"first");memory.observe(object("blocks",List.of(object("dimension","overworld","x",0,"y",0,"z",0,"type","stone"))),12);
        memory.annotate(object("text","base"),"overworld");memory.flush();
        var loaded=new WorldMemory(dir,"first").query(object(),"overworld",20);assertEquals(1,loaded.getAsJsonArray("blocks").size());assertEquals("remembered",loaded.getAsJsonArray("blocks").get(0).getAsJsonObject().get("freshness").getAsString());
        assertTrue(new WorldMemory(dir,"second").query(object(),"overworld",20).getAsJsonArray("blocks").isEmpty());
        assertTrue(memory.query(object(),"nether",20).getAsJsonArray("blocks").isEmpty());assertTrue(memory.query(object(),"nether",20).getAsJsonArray("notes").isEmpty());
    }
    @Test void ownerTokenSurvivesReload() throws Exception {Path file=dir.resolve("config/owner.json");OwnerPolicy a=OwnerPolicy.load(file),b=OwnerPolicy.load(file);assertEquals(a.token,b.token);assertTrue(a.token.length()>=32);assertFalse(a.publicProfile().toString().contains(a.token));}
    @Test void packetHasBoundedMetadataAndLittleEndianDepth(){byte[] p=VisionPacket.encode(object("frame_id","f"),new byte[]{1,2},new float[]{2.5f,Float.NaN},2,1);ByteBuffer b=ByteBuffer.wrap(p);assertEquals(0x4d425631,b.getInt());int json=b.getInt();b.position(8+json+2);b.order(ByteOrder.LITTLE_ENDIAN);assertEquals(2.5f,b.getFloat());assertTrue(Float.isNaN(b.getFloat()));}
    @Test void skyDepthIsInvalid(){float[] inverse=new float[16];inverse[0]=inverse[5]=inverse[10]=inverse[15]=1;assertTrue(Float.isNaN(DepthMath.axial(1,0,0,inverse,false)));assertTrue(Float.isNaN(DepthMath.axial(Float.NaN,0,0,inverse,false)));}
    @Test void depthUsesInverseProjection(){float[] inverse=new float[16];inverse[0]=inverse[5]=inverse[10]=inverse[15]=1;assertEquals(0.5f,DepthMath.axial(0.25f,0,0,inverse,false));}
}
