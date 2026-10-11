package dev.minebridge.core;

import com.google.gson.*;
import java.util.*;
import static dev.minebridge.core.Json.*;

/** Confirms one targeted interaction from a matching server event and its state changes. */
public final class InteractionLedger {
    private final String actor,dimension;
    private final Map<String,Receipt> receipts=new LinkedHashMap<>();
    public InteractionLedger(String actor,String dimension){this.actor=actor;this.dimension=dimension;}
    public synchronized void begin(String task,String target,String hand,long tick) {
        if(!Set.of("main","off").contains(hand))throw new BridgeException("invalid_request","Interaction hand must be main/off");
        if(receipts.containsKey(task))throw new BridgeException("invalid_state","Interaction was already sent");
        if(receipts.size()>=16)throw new BridgeException("limit_exceeded","Interaction receipt limit reached");
        receipts.put(task,new Receipt(target,hand,tick));
    }
    public synchronized boolean recordAttempt(String actor,String dimension,String target,String hand,long tick,JsonObject before) {
        if(!this.actor.equals(actor) || !this.dimension.equals(dimension))return false;
        Receipt found=null;
        for(Receipt r:receipts.values()) {
            if(r.interrupted || r.result!=null || !r.target.equals(target) || !r.hand.equals(hand) || tick<r.start || tick-r.start>20)continue;
            if(found!=null)return false;
            found=r;
        }
        if(found==null || !target.equals(string(child(before,"target"),"key","")))return false;
        if(found.before==null){found.before=before.deepCopy();found.attemptTick=tick;}
        return true;
    }
    public record Pending(String task,String target,String hand) {}
    public synchronized List<Pending> pending(String actor,String dimension) {
        if(!this.actor.equals(actor) || !this.dimension.equals(dimension))return List.of();
        return receipts.entrySet().stream().filter(e->e.getValue().before!=null && e.getValue().result==null && !e.getValue().interrupted)
                .map(e->new Pending(e.getKey(),e.getValue().target,e.getValue().hand)).toList();
    }
    public synchronized boolean recordResult(String task,String actor,String dimension,long tick,JsonObject after) {
        Receipt r=receipts.get(task);
        if(!this.actor.equals(actor) || !this.dimension.equals(dimension) || r==null || r.before==null || r.interrupted || r.result!=null
                || tick<r.attemptTick || tick-r.attemptTick>20 || !r.target.equals(string(child(after,"target"),"key","")))return false;
        JsonObject before=r.before;
        if(number(before,"menu_id",-1)!=0 || !before.get("selected_slot").equals(after.get("selected_slot")))return false;
        JsonObject oldTarget=child(before,"target"),newTarget=child(after,"target");
        String outcome=null;
        if(number(after,"menu_id",0)>0)outcome="menu_opened";
        else if(r.target.startsWith("block:") && !oldTarget.equals(newTarget))outcome="block_changed";
        JsonObject oldHand=child(before,r.hand+"_hand"),newHand=child(after,r.hand+"_hand");
        boolean heldChanged=number(oldHand,"count",0)>0 &&
                (!string(oldHand,"type").equals(string(newHand,"type")) || number(newHand,"count",0)<number(oldHand,"count",0)
                 || number(newHand,"damage",0)>number(oldHand,"damage",0));
        if(outcome==null && r.target.startsWith("entity:") && sameEntity(oldTarget,newTarget)) {
            boolean sheared=oldTarget.has("sheared") && !bool(oldTarget,"sheared",true) && bool(newTarget,"sheared",false) && string(oldHand,"type").equals("minecraft:shears");
            boolean fed=oldTarget.has("in_love") && !bool(oldTarget,"in_love",true) && bool(newTarget,"in_love",false) && actor.equals(string(newTarget,"love_cause",""));
            boolean dyed=oldTarget.has("color") && newTarget.has("color") && !oldTarget.get("color").equals(newTarget.get("color")) && string(oldHand,"type").endsWith("_dye");
            if(sheared || fed || dyed)outcome="entity_changed";
        }
        if(outcome==null && heldChanged)outcome="held_item_changed";
        if(outcome==null)return false;
        r.result=object("outcome",outcome,"target",r.target,"hand",r.hand,"tick",tick,"source","integrated_server_interaction",
                "held_item_changed",heldChanged,"before",summary(before,r.hand),"after",summary(after,r.hand));
        return true;
    }
    private static boolean sameEntity(JsonObject before,JsonObject after) {
        return before.has("type") && before.get("type").equals(after.get("type")) && bool(before,"alive",false) && bool(after,"alive",false);
    }
    private static JsonObject summary(JsonObject sample,String hand){return object("held",sample.get(hand+"_hand"),"menu_id",sample.get("menu_id"),"target",sample.get("target"));}
    public synchronized JsonObject peek(String task){Receipt r=receipts.get(task);return r==null || r.result==null?null:r.result.deepCopy();}
    public synchronized boolean interrupted(String task){Receipt r=receipts.get(task);return r!=null && r.interrupted;}
    /** Keep confirmed effects; stop attributing later manual interactions to paused tasks. */
    public synchronized void suspend(){receipts.values().forEach(r->{if(r.result==null)r.interrupted=true;});}
    public synchronized JsonObject finish(String task){JsonObject result=peek(task);receipts.remove(task);return result;}
    public synchronized void end(String task){receipts.remove(task);}
    private static final class Receipt {
        final String target,hand;final long start;
        long attemptTick;boolean interrupted;JsonObject before,result;
        Receipt(String target,String hand,long start){this.target=target;this.hand=hand;this.start=start;}
    }
}
