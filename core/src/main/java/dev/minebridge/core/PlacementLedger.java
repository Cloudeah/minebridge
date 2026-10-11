package dev.minebridge.core;

import com.google.gson.*;
import java.util.*;
import static dev.minebridge.core.Json.*;

/** Attributes one placement to its exact main-hand click and deferred server result. */
public final class PlacementLedger {
    private final String actor,dimension;
    private final LinkedHashMap<String,Receipt> receipts=new LinkedHashMap<>();
    public PlacementLedger(String actor,String dimension){this.actor=actor;this.dimension=dimension;}
    public synchronized void begin(String task,JsonObject position,JsonObject original,String type,JsonObject properties,JsonObject support,String face,JsonObject material,int selected,long tick) {
        if(receipts.containsKey(task))throw new BridgeException("invalid_state","Placement already sent");
        if(receipts.size()>=16)throw new BridgeException("busy","Too many placement receipts");
        receipts.put(task,new Receipt(position,original,type,properties,support,face,material,selected,tick));
    }
    public synchronized String clicked(String actor,String dimension,String hand,JsonObject support,String face,JsonObject material,int selected,long tick) {
        if(!this.actor.equals(actor) || !this.dimension.equals(dimension) || !hand.equals("main"))return null;
        var candidates=receipts.entrySet().stream().filter(e->{var r=e.getValue();return !r.interrupted && r.error==null && !r.clicked && r.support.equals(support) && r.face.equals(face) && r.material.equals(material) && r.selected==selected && tick>=r.start && tick-r.start<=20;}).toList();
        if(candidates.size()!=1)return null;
        var found=candidates.getFirst();found.getValue().clicked=true;found.getValue().clickTick=tick;return found.getKey();
    }
    public synchronized String attempt(String actor,String dimension,JsonArray cells,long tick) {
        if(!this.actor.equals(actor) || !this.dimension.equals(dimension) || cells.isEmpty() || cells.size()>64)return null;
        var candidates=receipts.entrySet().stream().filter(e->{var r=e.getValue();return r.clicked && !r.interrupted && r.error==null && r.cells==null && tick>=r.clickTick && tick-r.clickTick<=20 && primary(cells,r)!=null;}).toList();
        if(candidates.size()!=1)return null;
        var found=candidates.getFirst();var r=found.getValue();r.cells=cells.deepCopy();r.eventTick=tick;return found.getKey();
    }
    private static JsonObject primary(JsonArray cells,Receipt r) {
        JsonObject found=null;Set<JsonElement> positions=new HashSet<>();
        for(JsonElement element:cells) {
            if(!element.isJsonObject())return null;JsonObject cell=element.getAsJsonObject();
            if(!cell.has("position") || !cell.has("before") || !cell.has("after") || !positions.add(cell.get("position")))return null;
            if(cell.get("position").equals(r.position) && cell.get("before").equals(r.original) && !cell.get("before").equals(cell.get("after")))found=cell;
        }
        return found;
    }
    public synchronized boolean complete(String task,String actor,String dimension,JsonArray actual,JsonObject material,int selected,long tick,boolean canceled,boolean exactMaterial) {
        var r=receipts.get(task);
        if(!this.actor.equals(actor) || !this.dimension.equals(dimension) || r==null || r.cells==null || r.interrupted || r.result!=null || r.error!=null || tick<r.eventTick || tick-r.eventTick>20)return false;
        if(canceled){r.error="permission_denied";return false;}
        if(selected!=r.selected || !exactMaterial || !actual.equals(r.cells)){r.error="placement_unconfirmed";return false;}
        JsonObject primary=primary(actual,r),after=child(primary,"after");
        r.result=object("receipt_id",r.id,"position",r.position,"before",r.original,"after",after,"cells",actual,"material_before",r.material,"material_after",material,"tick",tick,"source","integrated_server_block_place");
        if(!string(after,"type").equals(r.type) || !matchesProperties(child(after,"properties"),r.properties))r.error="placement_mismatch";
        return true;
    }
    private static boolean matchesProperties(JsonObject actual,JsonObject wanted){for(var e:wanted.entrySet())if(!actual.has(e.getKey()) || !actual.get(e.getKey()).getAsString().equals(e.getValue().getAsString()))return false;return true;}
    public synchronized void reject(String task,String error){var r=receipts.get(task);if(r!=null && r.result==null && r.error==null)r.error=error;}
    public synchronized JsonObject peek(String task){var r=receipts.get(task);return r==null || r.result==null?null:r.result.deepCopy();}
    public synchronized String error(String task){var r=receipts.get(task);return r==null?null:r.error;}
    public synchronized boolean interrupted(String task){var r=receipts.get(task);return r!=null && r.interrupted;}
    public synchronized void suspend(){receipts.values().forEach(r->{if(r.result==null)r.interrupted=true;});}
    public synchronized JsonObject finish(String task){JsonObject result=peek(task);receipts.remove(task);return result;}
    public synchronized void end(String task){receipts.remove(task);}
    private static final class Receipt {
        final String id=Json.id(),type,face;
        final JsonObject position,original,properties,support,material;
        final int selected;final long start;
        long clickTick,eventTick;boolean clicked,interrupted;JsonArray cells;JsonObject result;String error;
        Receipt(JsonObject p,JsonObject old,String type,JsonObject properties,JsonObject support,String face,JsonObject material,int selected,long tick){position=p.deepCopy();original=old.deepCopy();this.type=type;this.properties=properties.deepCopy();this.support=support.deepCopy();this.face=face;this.material=material.deepCopy();this.selected=selected;start=tick;}
    }
}
