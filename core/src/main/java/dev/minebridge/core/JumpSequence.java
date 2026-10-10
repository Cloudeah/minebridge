package dev.minebridge.core;

import com.google.gson.JsonObject;
import static dev.minebridge.core.Json.*;

/** Counts integrated-server takeoff/landing pairs, rather than key presses. */
public final class JumpSequence {
    public record Sample(long tick, boolean onGround, double y) {}
    public record Update(boolean pressJump, boolean done, String error, JsonObject progress) {}
    private enum Phase { ground, takeoff, landing, done, failed }
    private final int requested;
    private int completed;
    private Phase phase=Phase.ground;
    private long lastSampleTick=-1, sentAt=-1;
    private double launchY;

    public JumpSequence(int count) {
        if(count<1 || count>30)throw new BridgeException("invalid_request","Jump count must be 1..30");
        requested=count;
    }
    public static int count(JsonObject parameters) {return integer(parameters,"count",1,1,30);}
    public int completed() {return completed;}
    private Update update(boolean press,String error) {
        return new Update(press,phase==Phase.done || phase==Phase.failed,error,
                object("stage",phase.name(),"requested",requested,"completed",completed,"source","integrated_server"));
    }
    public Update observe(long now,Sample sample,boolean ready) {
        if(phase==Phase.done)return update(false,null);
        if(phase==Phase.failed)return update(false,"jump_unconfirmed");
        if(sample!=null && sample.tick()>lastSampleTick) {
            lastSampleTick=sample.tick();
            switch(phase) {
                case ground -> {
                    if(sample.onGround() && ready) {
                        launchY=sample.y();sentAt=now;phase=Phase.takeoff;
                        return update(true,null);
                    }
                }
                case takeoff -> {
                    if(!sample.onGround() && sample.y()>launchY+0.05)phase=Phase.landing;
                }
                case landing -> {
                    if(sample.onGround()) {completed++;phase=completed==requested?Phase.done:Phase.ground;}
                }
                default -> {}
            }
        }
        if(phase==Phase.takeoff && now-sentAt>20) {phase=Phase.failed;return update(false,"jump_unconfirmed");}
        return update(false,null);
    }
}
