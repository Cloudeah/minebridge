package dev.minebridge.core;

import com.google.gson.JsonObject;
import static dev.minebridge.core.Json.*;

/** Small, bounded movement commands expressed in the player's starting frame. */
public final class MovementCommands {
    public record Offset(double x, double z) {}
    public record Turn(double yaw, double pitch) {}
    private MovementCommands() {}

    public static void validateTravel(JsonObject p) {
        double tolerance=number(p,"tolerance",0.7);
        if(tolerance<0.3 || tolerance>16)throw new BridgeException("invalid_request","Movement tolerance must be 0.3..16");
        if(bool(p,"sprint",false) && bool(p,"crouch",false))throw new BridgeException("invalid_request","Sprint and crouch cannot be combined");
        // Validate both flags even when sprint is false.
        bool(p,"crouch",false);
    }
    public static Offset relative(JsonObject p,double yaw) {
        validateTravel(p);
        double forward=number(p,"forward",0),right=number(p,"right",0);
        double distance=Math.hypot(forward,right);
        if(Math.abs(forward)>16 || Math.abs(right)>16 || distance<=0 || distance>16)
            throw new BridgeException("invalid_request","Relative movement must have length greater than zero and at most 16 blocks");
        double angle=Math.toRadians(yaw);
        return new Offset(-Math.sin(angle)*forward-Math.cos(angle)*right,Math.cos(angle)*forward-Math.sin(angle)*right);
    }
    public static int crouchTicks(JsonObject p) { return integer(p,"ticks",20,1,200); }
    public static Turn turn(JsonObject p,double yaw,double pitch) {
        double dy=number(p,"delta_yaw",0),dp=number(p,"delta_pitch",0);
        if(Math.abs(dy)>180 || Math.abs(dp)>90 || (dy==0 && dp==0))
            throw new BridgeException("invalid_request","Turn requires a nonzero delta_yaw (-180..180) or delta_pitch (-90..90)");
        return new Turn(wrapYaw(yaw+dy),Math.max(-90,Math.min(90,pitch+dp)));
    }
    public static double wrapYaw(double yaw) { return ((yaw+180)%360+360)%360-180; }
}
