package dev.minebridge.core;

/** Drives one crossbow load/release/fire cycle using fresh server observations. */
public final class CrossbowSequence {
    public enum Command { wait, draw, release, fire }
    public record Sample(long tick,boolean using,boolean charged,int projectiles,boolean plainArrow) {}
    public record Decision(Command command,String error,int confirmedLoads,String stage) {}
    private enum Phase { idle, loading, releasing, fired, interrupted }
    private Phase phase=Phase.idle;
    private long sentTick=-1;
    private int loads;

    public Decision observe(Sample sample,boolean clientUsing,boolean clientCharged) {
        if(phase==Phase.interrupted)return decision(Command.wait,"draw_interrupted");
        if(phase==Phase.fired || sample==null || sample.tick()<sentTick)return decision(Command.wait,null);
        if(sample.charged() && (sample.projectiles()!=1 || !sample.plainArrow()))
            return decision(Command.wait,"unsupported_ammo");
        if(phase==Phase.idle) {
            if(sample.using() || clientUsing)return decision(Command.wait,"item_busy");
            if(sample.charged()!=clientCharged)return decision(Command.wait,null);
            if(sample.charged())return decision(clientCharged?Command.fire:Command.wait,null);
            return decision(Command.draw,null);
        }
        if(phase==Phase.loading) {
            if(sample.charged()) {
                loads=1;
                if(!clientUsing)return decision(Command.wait,"draw_interrupted");
                return decision(Command.release,null);
            }
            if(!clientUsing || !sample.using() && sample.tick()-sentTick>10)return decision(Command.wait,"draw_interrupted");
        }
        if(phase==Phase.releasing) {
            if(!sample.charged())return decision(Command.wait,"load_unconfirmed");
            if(!sample.using() && !clientUsing && clientCharged)return decision(Command.fire,null);
            if(sample.tick()-sentTick>20)return decision(Command.wait,"draw_interrupted");
        }
        return decision(Command.wait,null);
    }
    /** Called only after the corresponding normal game input has been sent. */
    public void sent(Command command,long serverTick) {
        boolean valid=switch(command){
            case draw->phase==Phase.idle;
            case release->phase==Phase.loading;
            case fire->phase==Phase.idle || phase==Phase.releasing;
            case wait->false;
        };
        if(!valid)throw new BridgeException("invalid_state","Invalid crossbow input transition");
        phase=switch(command){case draw->Phase.loading;case release->Phase.releasing;case fire->Phase.fired;default->throw new AssertionError();};
        sentTick=serverTick;
    }
    /** An interrupted load never resumes or fires automatically. */
    public void released() {if(phase==Phase.loading || phase==Phase.releasing)phase=Phase.interrupted;}
    private Decision decision(Command command,String error){return new Decision(command,error,loads,phase.name());}
}
