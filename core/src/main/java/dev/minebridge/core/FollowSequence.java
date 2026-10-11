package dev.minebridge.core;

import com.google.gson.JsonObject;
import static dev.minebridge.core.Json.*;

/** Counts only consecutive fresh server samples within the requested follow distance. */
public final class FollowSequence {
    public record Sample(long tick,double distance) {}
    public record Update(boolean done,JsonObject progress) {}
    private final int requested;
    private final double distance;
    private long elapsed,lastSample=-1,lastNear=-1;

    public FollowSequence(int ticks,double distance) {
        if(ticks<1 || ticks>1200 || !Double.isFinite(distance) || distance<0.7 || distance>16)
            throw new BridgeException("invalid_request","Follow ticks must be 1..1200 and distance 0.7..16");
        requested=ticks;this.distance=distance;
    }
    public static void validate(JsonObject p) {
        MovementCommands.validateTravel(p);
        new FollowSequence(integer(p,"ticks",200,1,1200),number(p,"distance",3));
    }
    /** Pauses preserve progress but discard the elapsed gap and old proximity sample. */
    public void released() {lastSample=-1;lastNear=-1;}
    public Update observe(Sample sample) {
        if(elapsed<requested && sample!=null && sample.tick()>lastSample) {
            lastSample=sample.tick();
            if(Double.isFinite(sample.distance()) && sample.distance()>=0 && sample.distance()<=distance) {
                // A delayed observation is insufficient evidence for a long unseen interval.
                if(lastNear>=0 && sample.tick()-lastNear<=10)elapsed=Math.min(requested,elapsed+sample.tick()-lastNear);
                lastNear=sample.tick();
            } else lastNear=-1;
        }
        return new Update(elapsed>=requested,object("stage",elapsed>=requested?"done":"following",
                "requested_ticks",requested,"confirmed_follow_ticks",elapsed,"distance_limit",distance,"source","integrated_server"));
    }
}
