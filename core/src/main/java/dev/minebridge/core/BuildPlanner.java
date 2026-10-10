package dev.minebridge.core;

import com.google.gson.*;
import java.util.*;
import static dev.minebridge.core.Json.*;

public final class BuildPlanner {
    public record Block(int x,int y,int z,String type,JsonObject properties) {}
    public record Plan(List<Block> blocks,Map<String,Integer> materials) {}
    public Plan plan(JsonObject data) {
        JsonObject origin=child(data,"origin");int ox=integer(origin,"x",0,-30000000,30000000),oy=integer(origin,"y",0,-2048,2048),oz=integer(origin,"z",0,-30000000,30000000);
        int rotation=integer(data,"rotation",0,0,270);if(rotation%90!=0)throw new BridgeException("invalid_request","Rotation must be 0, 90, 180, 270");
        if(!data.has("blocks") || !data.get("blocks").isJsonArray() || data.getAsJsonArray("blocks").isEmpty() || data.getAsJsonArray("blocks").size()>4096)throw new BridgeException("invalid_request","Blueprint must contain 1..4096 blocks");
        List<Block> result=new ArrayList<>();Set<String> positions=new HashSet<>();Map<String,Integer> materials=new HashMap<>();
        for(JsonElement e:data.getAsJsonArray("blocks")) {
            JsonObject b=e.getAsJsonObject();int x=integer(b,"x",0,-128,128),y=integer(b,"y",0,-128,128),z=integer(b,"z",0,-128,128);
            for(int r=0;r<rotation;r+=90){int temp=x;x=-z;z=temp;}
            String type=string(b,"type");JsonObject properties=b.has("properties")?child(b,"properties").deepCopy():object();
            if(properties.has("facing")) {String[] faces={"north","east","south","west"};int face=Arrays.asList(faces).indexOf(properties.get("facing").getAsString());if(face>=0)properties.addProperty("facing",faces[(face+rotation/90)%4]);}
            if(rotation%180==90 && properties.has("axis")){String a=properties.get("axis").getAsString();if(a.equals("x"))properties.addProperty("axis","z");else if(a.equals("z"))properties.addProperty("axis","x");}
            Block block=new Block(ox+x,oy+y,oz+z,type,properties);
            if(!positions.add(block.x+","+block.y+","+block.z))throw new BridgeException("invalid_request","Duplicate blueprint position");
            result.add(block);materials.merge(type,1,Integer::sum);
        }
        result.sort(Comparator.comparingInt(Block::y));return new Plan(List.copyOf(result),Map.copyOf(materials));
    }
}
