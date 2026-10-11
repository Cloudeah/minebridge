package dev.minebridge.core;

import com.google.gson.*;
import java.util.*;
import static dev.minebridge.core.Json.*;

/** Bounded views of already observed data; selection never adds knowledge of the world. */
public final class ObservationProfiles {
    public static final List<String> NAMES=List.of("full","compact");
    private ObservationProfiles() {}
    public static String validate(String profile) {
        if(!NAMES.contains(profile))throw new BridgeException("unsupported_capability","Unknown observation profile: "+profile);
        return profile;
    }
    public static JsonObject apply(JsonObject source,String profile) {
        validate(profile);
        JsonObject result=source.deepCopy();
        if(profile.equals("full"))return result;
        JsonObject origin=source.has("actor") && source.getAsJsonObject("actor").has("position")
                ? child(child(source,"actor"),"position") : object("x",0,"y",0,"z",0);
        result.add("blocks",nearest(source,"blocks",origin,6,false));
        result.add("entities",entities(source,origin,4));
        if(source.has("inventory"))result.add("inventory",slots(source.getAsJsonArray("inventory"),0));
        if(source.has("context") && source.getAsJsonObject("context").has("menu_slots"))
            result.getAsJsonObject("context").add("menu_slots",slots(source.getAsJsonObject("context").getAsJsonArray("menu_slots"),4));
        result.addProperty("observation_profile","compact");
        result.add("observation_limits",object("blocks",6,"entities",4,"empty_menu_slots",4,
                "blocks_available",size(source,"blocks"),"entities_available",size(source,"entities"),"entity_selection","active target, living/item diversity, then distance; projectiles last","missing_data","unknown"));
        return result;
    }
    private static JsonArray entities(JsonObject source,JsonObject origin,int limit) {
        List<JsonObject> entries=new ArrayList<>();
        if(source.has("entities"))for(JsonElement entry:source.getAsJsonArray("entities"))entries.add(entry.getAsJsonObject());
        String target="";
        if(source.has("active_task") && source.get("active_task").isJsonObject())target=string(child(child(source,"active_task"),"parameters"),"entity_id","");
        String activeTarget=target;
        entries.sort(Comparator.comparingInt((JsonObject e)->string(e,"entity_id","").equals(activeTarget) && !activeTarget.isEmpty()?-1:entityRank(e))
                .thenComparingDouble(e->distance(child(e,"position"),origin)));
        LinkedHashSet<JsonObject> selected=new LinkedHashSet<>();
        for(JsonObject entry:entries)if(!activeTarget.isEmpty() && string(entry,"entity_id","").equals(activeTarget)){selected.add(entry);break;}
        for(String kind:List.of("living","item"))for(JsonObject entry:entries)if(selected.size()<limit && string(entry,"kind",string(entry,"type","").equals("minecraft:item")?"item":"other").equals(kind)){selected.add(entry);break;}
        for(JsonObject entry:entries)if(selected.size()<limit)selected.add(entry);
        JsonArray result=new JsonArray();for(JsonObject entry:entries)if(selected.contains(entry))result.add(entry.deepCopy());return result;
    }
    private static int entityRank(JsonObject e) {
        return switch(string(e,"kind",string(e,"type","").equals("minecraft:item")?"item":"other")){case "living"->0;case "item"->1;case "projectile"->3;default->2;};
    }
    private static int size(JsonObject source,String field){return source.has(field)?source.getAsJsonArray(field).size():0;}
    private static JsonArray nearest(JsonObject source,String field,JsonObject origin,int limit,boolean nested) {
        List<JsonObject> entries=new ArrayList<>();
        if(source.has(field))for(JsonElement item:source.getAsJsonArray(field))entries.add(item.getAsJsonObject());
        entries.sort(Comparator.comparingDouble(item->distance(nested?child(item,"position"):item,origin)));
        JsonArray result=new JsonArray();entries.stream().limit(limit).forEach(item->result.add(item.deepCopy()));return result;
    }
    private static double distance(JsonObject point,JsonObject origin) {
        double sum=0;for(String axis:List.of("x","y","z")){double d=number(point,axis,0)-number(origin,axis,0);sum+=d*d;}return sum;
    }
    private static JsonArray slots(JsonArray source,int emptyLimit) {
        JsonArray result=new JsonArray();int empty=0;
        for(JsonElement item:source) {
            JsonObject slot=item.getAsJsonObject();
            if(number(slot,"count",0)<=0) {
                if(emptyLimit<=0)continue;
                if(slot.has("role") && !slot.get("role").isJsonNull()) {
                    String role=slot.get("role").isJsonPrimitive()&&slot.getAsJsonPrimitive("role").isString()
                            ?slot.get("role").getAsString():"";
                    if(!Set.of("main","hotbar","container").contains(role))continue;
                }
                if(empty++>=emptyLimit)continue;
            }
            JsonObject concise=object();
            for(String field:List.of("slot","type","count","player_inventory","role","inventory_slot"))if(slot.has(field))concise.add(field,slot.get(field).deepCopy());
            result.add(concise);
        }
        return result;
    }
}
