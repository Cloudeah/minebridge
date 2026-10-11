package dev.minebridge.core;

import com.google.gson.JsonObject;
import java.util.*;
import static dev.minebridge.core.Json.*;

/** A block-break event plus its deferred server result, scoped to one actor and cell. */
public final class MiningLedger {
    private final String actor,dimension;
    private final LinkedHashMap<String,Receipt> receipts=new LinkedHashMap<>();
    public MiningLedger(String actor,String dimension){this.actor=actor;this.dimension=dimension;}
    public synchronized void begin(String task,JsonObject position,JsonObject original,JsonObject replacement,long tick) {
        if(receipts.containsKey(task))throw new IllegalArgumentException("Duplicate mining task");
        if(receipts.size()>=16)throw new BridgeException("busy","Too many pending mining receipts");
        receipts.put(task,new Receipt(position.deepCopy(),original.deepCopy(),replacement.deepCopy(),tick));
    }
    public synchronized boolean arm(String task,long tick) {
        Receipt r=receipts.get(task);if(r==null || r.interrupted || r.attempt!=null || tick<r.armed)return false;
        r.armed=tick;return true;
    }
    public synchronized String attempt(String actor,String dimension,JsonObject position,JsonObject original,JsonObject tool,int selected,long tick) {
        if(!this.actor.equals(actor) || !this.dimension.equals(dimension))return null;
        List<Map.Entry<String,Receipt>> matches=receipts.entrySet().stream().filter(e->{Receipt r=e.getValue();return !r.interrupted && r.attempt==null && r.position.equals(position) && r.original.equals(original) && tick>=r.armed && tick-r.armed<=20;}).toList();
        if(matches.size()!=1)return null;
        var match=matches.get(0);Receipt r=match.getValue();r.attempt=tool.deepCopy();r.selected=selected;r.attemptTick=tick;return match.getKey();
    }
    public synchronized boolean complete(String task,String actor,String dimension,JsonObject actual,JsonObject tool,int selected,long tick,boolean canceled) {
        Receipt r=receipts.get(task);
        if(!this.actor.equals(actor) || !this.dimension.equals(dimension) || r==null || r.attempt==null || r.interrupted || r.result!=null || r.error!=null || tick<r.attemptTick || tick-r.attemptTick>20 || selected!=r.selected)return false;
        if(canceled){r.error="permission_denied";return false;}
        if(!actual.equals(r.replacement)){r.error="mining_unconfirmed";return false;}
        r.result=object("position",r.position,"before",r.original,"after",actual,"tool_before",r.attempt,"tool_after",tool,"tick",tick,"source","integrated_server_block_break","drops","not_confirmed");
        return true;
    }
    public synchronized JsonObject peek(String task){Receipt r=receipts.get(task);return r==null || r.result==null?null:r.result.deepCopy();}
    public synchronized boolean pending(String task){Receipt r=receipts.get(task);return r!=null && r.attempt!=null && r.result==null && r.error==null;}
    public synchronized boolean interrupted(String task){Receipt r=receipts.get(task);return r!=null && r.interrupted;}
    public synchronized String error(String task){Receipt r=receipts.get(task);return r==null?null:r.error;}
    public synchronized void suspend(){receipts.values().forEach(r->{if(r.result==null)r.interrupted=true;});}
    public synchronized JsonObject finish(String task){JsonObject result=peek(task);receipts.remove(task);return result;}
    public synchronized void end(String task){receipts.remove(task);}
    private static final class Receipt {
        final JsonObject position,original,replacement;
        long armed,attemptTick;int selected;boolean interrupted;JsonObject attempt,result;String error;
        Receipt(JsonObject p,JsonObject old,JsonObject replacement,long tick){position=p;original=old;this.replacement=replacement;armed=tick;}
    }
}
