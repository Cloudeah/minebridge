package dev.minebridge.core;

import com.google.gson.JsonObject;
import static dev.minebridge.core.Json.*;

/** One drop request at a time, with each decrement confirmed by the server. */
public final class ItemDropSequence {
    public record Sample(long tick,int slot,String item,int count) {}
    public record Update(boolean pressDrop,boolean done,String error,JsonObject progress) {}
    private final int requested;
    private final String expectedItem;
    private int slot=-1,initial,completed,requests;
    private String item,error;
    private long lastTick=-1,sentAt;
    private boolean pending,done;
    public ItemDropSequence(int requested,String expectedItem) {
        if(requested<1 || requested>64)throw new BridgeException("invalid_request","Drop count must be 1..64");
        this.requested=requested;this.expectedItem=expectedItem;
    }
    public static int count(JsonObject parameters){return integer(parameters,"count",1,1,64);}
    public int completed(){return completed;}
    public boolean awaitingConfirmation(){return pending && error==null && !done;}
    public void resumed(long now){if(awaitingConfirmation())sentAt=now;}
    public Update observe(long now,Sample sample) {
        if(done || error!=null)return update(false);
        if(pending && now-sentAt>20){error="drop_unconfirmed";return update(false);}
        if(sample==null || sample.tick()<=lastTick)return update(false);
        lastTick=sample.tick();
        if(slot<0){
            slot=sample.slot();item=sample.item();initial=sample.count();
            if(initial<requested){error="missing_materials";return update(false);}
            if(!expectedItem.isEmpty() && !expectedItem.equals(item)){error="target_changed";return update(false);}
        }
        if(sample.slot()!=slot || sample.count()>0 && !sample.item().equals(item)){
            error="target_changed";return update(false);
        }
        int removed=initial-sample.count();
        if(pending && removed==completed+1){
            completed++;pending=false;done=completed==requested;return update(false);
        }
        if(removed!=completed){error="drop_unconfirmed";return update(false);}
        if(!pending){pending=true;sentAt=now;requests++;return update(true);}
        return update(false);
    }
    private Update update(boolean press){return new Update(press,done,error,object("requested",requested,"completed",completed,
            "drop_requests",requests,"slot",slot,"item",item,"remaining_in_hand",initial-completed,"source","integrated_server"));}
}
