package dev.minebridge.core;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;
import static dev.minebridge.core.Json.*;

public final class BuildPlanner {
    public record Block(int x,int y,int z,String type,JsonObject properties) {}
    public enum Kind { SINGLE, DOOR, BED }
    public record Placement(List<Block> cells) { public Block primary(){return cells.getFirst();} }
    public record Plan(List<Block> blocks,Map<String,Integer> materials,List<Placement> placements) {}
    public Plan plan(JsonObject data) {
        return plan(data,type->Kind.SINGLE);
    }
    /** The game adapter supplies supported mechanics; names of unknown blocks imply no capability. */
    public Plan plan(JsonObject data,Function<String,Kind> kinds) {
        JsonObject origin=child(data,"origin");int ox=integer(origin,"x",0,-30000000,30000000),oy=integer(origin,"y",0,-2048,2048),oz=integer(origin,"z",0,-30000000,30000000);
        int rotation=integer(data,"rotation",0,0,270);if(rotation%90!=0)throw new BridgeException("invalid_request","Rotation must be 0, 90, 180, 270");
        if(!data.has("blocks") || !data.get("blocks").isJsonArray() || data.getAsJsonArray("blocks").isEmpty() || data.getAsJsonArray("blocks").size()>4096)throw new BridgeException("invalid_request","Blueprint must contain 1..4096 blocks");
        List<Block> result=new ArrayList<>();Set<String> positions=new HashSet<>();
        for(JsonElement e:data.getAsJsonArray("blocks")) {
            if(!e.isJsonObject())throw new BridgeException("invalid_request","Each blueprint block must be an object");
            JsonObject b=e.getAsJsonObject();int x=integer(b,"x",0,-128,128),y=integer(b,"y",0,-128,128),z=integer(b,"z",0,-128,128);
            for(int r=0;r<rotation;r+=90){int temp=x;x=-z;z=temp;}
            String type=string(b,"type");JsonObject properties=b.has("properties")?child(b,"properties").deepCopy():object();
            for(var property:properties.entrySet())if(!property.getValue().isJsonPrimitive() || !property.getValue().getAsJsonPrimitive().isString())throw new BridgeException("invalid_request","Block properties must be strings");
            if(properties.has("facing")) {String[] faces={"north","east","south","west"};int face=Arrays.asList(faces).indexOf(properties.get("facing").getAsString());if(face>=0)properties.addProperty("facing",faces[(face+rotation/90)%4]);}
            if(rotation%180==90 && properties.has("axis")){String a=properties.get("axis").getAsString();if(a.equals("x"))properties.addProperty("axis","z");else if(a.equals("z"))properties.addProperty("axis","x");}
            Block block=new Block(ox+x,oy+y,oz+z,type,properties);
            if(!positions.add(block.x+","+block.y+","+block.z))throw new BridgeException("invalid_request","Duplicate blueprint position");
            checked(block);result.add(block);
        }
        Map<String,List<Block>> groups=new LinkedHashMap<>();
        for(Block b:result){Block primary=primary(b,kinds.apply(b.type()));groups.computeIfAbsent(key(primary),ignored->new ArrayList<>()).add(b);}
        List<Placement> placements=new ArrayList<>();Map<String,Block> occupied=new HashMap<>();Map<String,Integer> materials=new TreeMap<>();
        for(List<Block> members:groups.values()) {
            Block first=members.getFirst();Kind kind=kinds.apply(first.type());Block primary=primary(first,kind);JsonObject shared=object();
            String part=kind==Kind.DOOR?"half":kind==Kind.BED?"part":null;
            for(Block b:members){if(!b.type().equals(first.type()))throw conflict();for(var property:b.properties().entrySet()){
                if(property.getKey().equals(part))continue;
                if(shared.has(property.getKey()) && !shared.get(property.getKey()).equals(property.getValue()))throw conflict();shared.add(property.getKey(),property.getValue().deepCopy());
            }}
            List<Block> cells=new ArrayList<>();JsonObject props=shared.deepCopy();if(part!=null)props.addProperty(part,kind==Kind.DOOR?"lower":"foot");
            cells.add(new Block(primary.x(),primary.y(),primary.z(),first.type(),props));
            if(kind==Kind.DOOR){JsonObject upper=shared.deepCopy();upper.addProperty("half","upper");cells.add(new Block(primary.x(),primary.y()+1,primary.z(),first.type(),upper));}
            if(kind==Kind.BED){int[] offset=facing(shared);JsonObject head=shared.deepCopy();head.addProperty("part","head");cells.add(new Block(primary.x()+offset[0],primary.y(),primary.z()+offset[1],first.type(),head));}
            for(Block b:cells){checked(b);if(occupied.putIfAbsent(key(b),b)!=null)throw conflict();}
            placements.add(new Placement(List.copyOf(cells)));materials.merge(first.type(),1,Integer::sum);
        }
        placements.sort(Comparator.comparingInt(p->p.primary().y()));
        result=new ArrayList<>();for(Placement placement:placements)result.addAll(placement.cells());
        if(result.size()>4096)throw new BridgeException("invalid_request","Expanded blueprint exceeds 4096 cells");
        // Stable sorting keeps a bed's foot before its head, even when input lists the head first.
        result.sort(Comparator.comparingInt(Block::y));return new Plan(List.copyOf(result),Map.copyOf(materials),List.copyOf(placements));
    }
    private static String key(Block b){return b.x()+","+b.y()+","+b.z();}
    private static BridgeException conflict(){return new BridgeException("invalid_request","Overlapping or inconsistent multi-block blueprint; both parts must agree");}
    private static void checked(Block b){if(b.x()<-30000000 || b.x()>30000000 || b.z()<-30000000 || b.z()>30000000 || b.y()<-2048 || b.y()>2048)throw new BridgeException("invalid_request","Expanded blueprint position is out of bounds");}
    private static int[] facing(JsonObject properties){return switch(string(properties,"facing","")){case "north"->new int[]{0,-1};case "east"->new int[]{1,0};case "south"->new int[]{0,1};case "west"->new int[]{-1,0};default->throw new BridgeException("invalid_request","Bed blueprints require a horizontal facing");};}
    private static Block primary(Block b,Kind kind) {
        if(kind==Kind.DOOR){String half=string(b.properties(),"half","lower");if(!Set.of("lower","upper").contains(half))throw conflict();return new Block(b.x(),b.y()-(half.equals("upper")?1:0),b.z(),b.type(),b.properties());}
        if(kind==Kind.BED){int[] offset=facing(b.properties());String part=string(b.properties(),"part","foot");if(!Set.of("foot","head").contains(part))throw conflict();return new Block(b.x()-(part.equals("head")?offset[0]:0),b.y(),b.z()-(part.equals("head")?offset[1]:0),b.type(),b.properties());}
        return b;
    }
}
