package dev.minebridge.core;

import com.google.gson.JsonObject;
import java.util.UUID;
import static dev.minebridge.core.Json.*;

/** One melee request awaits its own server damage receipt before another is allowed. */
public final class MeleeSequence {
    public record Update(boolean ready,boolean done,String error,String outcome) {}
    private final int requested;
    private long pendingAt=-1,lastTick=-1;
    private int baseline,lastHits;
    private boolean interrupted;
    private String failure;

    /** Zero preserves the legacy goal: defeat the target. */
    public MeleeSequence(int requested) {
        if(requested<0 || requested>64)throw new BridgeException("invalid_request","Melee hits must be 1..64 when specified");
        this.requested=requested;
    }
    public static int requestedHits(JsonObject p){return p.has("hits")?integer(p,"hits",1,1,64):0;}
    public static void validate(JsonObject p) {
        String id=string(p,"entity_id");
        if(!UUID.fromString(id).toString().equalsIgnoreCase(id))throw new BridgeException("invalid_request","Entity ID must be a canonical UUID");
        requestedHits(p);bool(p,"chase",false);MovementCommands.validateTravel(p);
        double limit=number(p,"max_chase_distance",16),health=number(p,"stop_health",4);
        if(limit<1 || limit>32 || health<0 || health>1024)throw new BridgeException("invalid_request","Melee chase distance must be 1..32 and stop health 0..1024");
        if(!string(p,"hand","main").equals("main") || p.has("count") || p.has("shots"))throw new BridgeException("invalid_request","Melee uses the main hand; use hits for a bounded goal");
    }
    public static boolean withinChase(double actorDistance,double targetDistance,double limit) {
        return Double.isFinite(actorDistance) && Double.isFinite(targetDistance) && actorDistance>=0 && targetDistance>=0 && actorDistance<=limit && targetDistance<=limit;
    }
    public Update observe(long tick,int hits,boolean defeated) {
        if(failure!=null)return new Update(false,false,failure,"stopped");
        if(tick<lastTick)return new Update(false,false,null,"stale_sample");
        lastTick=tick;
        if(hits<lastHits)return failed("combat_unconfirmed","receipt_regressed");
        lastHits=hits;
        if(defeated)return new Update(false,true,null,"defeated");
        if(requested>0 && hits>=requested)return new Update(false,true,null,"hits_completed");
        if(interrupted)return failed("attack_interrupted","interrupted");
        if(pendingAt>=0) {
            if(hits>baseline)pendingAt=-1;
            else if(tick-pendingAt>=20)return failed("hit_unconfirmed","unconfirmed");
            else return new Update(false,false,null,"confirming_hit");
        }
        return new Update(true,false,null,"ready");
    }
    public void sent(long tick,int hits) {
        if(pendingAt>=0 || interrupted || failure!=null)throw new IllegalStateException("Melee request already pending or interrupted");
        pendingAt=tick;baseline=hits;
    }
    public void released(){if(pendingAt>=0)interrupted=true;}
    private Update failed(String error,String outcome){failure=error;return new Update(false,false,error,outcome);}
}
