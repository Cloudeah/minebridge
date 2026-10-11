package dev.minebridge.core;

import com.google.gson.JsonObject;
import static dev.minebridge.core.Json.*;

/** Counts server-confirmed crouching time and confirms release before success. */
public final class CrouchSequence {
    public record Sample(long tick,boolean crouching) {}
    public record Update(boolean holdCrouch,boolean done,String error,JsonObject progress) {}
    private enum Phase { starting,holding,releasing,done,failed }
    private final int requested;
    private Phase phase=Phase.starting;
    private long elapsed,lastSample=-1,lastHeld=-1,sentAt=-1;
    private String error;
    public CrouchSequence(int ticks) {
        if(ticks<1 || ticks>200)throw new BridgeException("invalid_request","Crouch ticks must be 1..200");
        requested=ticks;
    }
    /** A pause or stop releases controls; elapsed pause time must not count as crouching. */
    public void released() {
        if(phase==Phase.done || phase==Phase.failed)return;
        phase=elapsed>=requested?Phase.releasing:Phase.starting;
        lastSample=-1;lastHeld=-1;sentAt=-1;
    }
    public Update observe(long now,Sample sample) {
        if(phase==Phase.done || phase==Phase.failed)return update();
        if(sentAt<0)sentAt=now;
        if(sample!=null && sample.tick()>lastSample) {
            lastSample=sample.tick();
            if(phase==Phase.releasing) {
                if(!sample.crouching())phase=Phase.done;
            } else if(sample.crouching()) {
                if(phase==Phase.holding && lastHeld>=0)elapsed+=sample.tick()-lastHeld;
                phase=Phase.holding;lastHeld=sample.tick();
                if(elapsed>=requested){phase=Phase.releasing;sentAt=now;}
            } else if(phase==Phase.holding)fail("crouch_unconfirmed");
        }
        if(phase==Phase.starting && now-sentAt>20)fail("crouch_unconfirmed");
        if(phase==Phase.releasing && now-sentAt>20)fail("crouch_release_unconfirmed");
        return update();
    }
    private void fail(String code){phase=Phase.failed;error=code;}
    private Update update(){return new Update(phase==Phase.starting || phase==Phase.holding,phase==Phase.done,error,
            object("stage",phase.name(),"requested_ticks",requested,"confirmed_crouch_ticks",elapsed,"source","integrated_server"));}
}
