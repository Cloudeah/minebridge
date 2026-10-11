package dev.minebridge.core;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;

/** Bounded server receipts: an entity disappearing alone never confirms pickup. */
public final class PickupLedger {
    public record Receipt(String entityId,String item,int collected,int remaining,long tick,String source) {}
    private final String actor,dimension;
    private final LinkedHashMap<String,Receipt> receipts=new LinkedHashMap<>();
    private final Set<String> watched=new HashSet<>();
    public PickupLedger(String actor,String dimension){this.actor=actor;this.dimension=dimension;}
    public synchronized void record(String player,String dim,String entity,String item,int collected,int remaining,long tick){
        record(player,dim,entity,item,collected,remaining,tick,"integrated_server_pickup_event");
    }
    public synchronized void record(String player,String dim,String entity,String item,int collected,int remaining,long tick,String source){
        if(!actor.equals(player) || !dimension.equals(dim) || collected<=0 || remaining<0)return;
        Receipt previous=receipts.remove(entity);
        int total=previous!=null && previous.item().equals(item)?previous.collected()+collected:collected;
        receipts.put(entity,new Receipt(entity,item,total,remaining,tick,source));
        while(receipts.size()>128)receipts.remove(receipts.keySet().iterator().next());
    }
    public synchronized Receipt peek(String entity,long notBefore){
        Receipt receipt=receipts.get(entity);
        return receipt!=null && receipt.tick()>=notBefore?receipt:null;
    }
    public synchronized Receipt take(String entity,long notBefore){
        Receipt receipt=peek(entity,notBefore);if(receipt!=null)receipts.remove(entity);return receipt;
    }
    public synchronized void begin(String entity){receipts.remove(entity);watched.add(entity);}
    public synchronized boolean watching(String entity){return watched.contains(entity);}
    public synchronized void discard(String entity){receipts.remove(entity);watched.remove(entity);}
}
