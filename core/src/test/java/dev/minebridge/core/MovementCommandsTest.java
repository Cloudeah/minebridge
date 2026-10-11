package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class MovementCommandsTest {
    @Test void relativeForwardAndRightFollowStartingYaw(){
        var south=MovementCommands.relative(object("forward",2,"right",3),0);
        assertEquals(-3,south.x(),1e-9);assertEquals(2,south.z(),1e-9);
        var west=MovementCommands.relative(object("forward",2,"right",3),90);
        assertEquals(-2,west.x(),1e-9);assertEquals(-3,west.z(),1e-9);
        var back=MovementCommands.relative(object("forward",-2),180);
        assertEquals(2,back.z(),1e-9);
    }
    @Test void relativeDistanceUsesCombinedLengthAndStrictNumbers(){
        assertEquals(-16,MovementCommands.relative(object("right",16),0).x(),1e-9);
        for(var p:java.util.List.of(object(),object("forward",17),object("forward",12,"right",12),object("forward","2"),object("right",true)))
            assertThrows(BridgeException.class,()->MovementCommands.relative(p,0));
    }
    @Test void sprintCrouchConflictAndMalformedFlagsAreRejected(){
        assertThrows(BridgeException.class,()->MovementCommands.validateTravel(object("sprint",true,"crouch",true)));
        assertThrows(BridgeException.class,()->MovementCommands.validateTravel(object("crouch","false")));
        assertThrows(BridgeException.class,()->MovementCommands.validateTravel(object("tolerance",0.2)));
        MovementCommands.validateTravel(object("crouch",true));
    }
    @Test void turnWrapsYawAndClampsPitch(){
        var turn=MovementCommands.turn(object("delta_yaw",90,"delta_pitch",45),150,70);
        assertEquals(-120,turn.yaw());assertEquals(90,turn.pitch());
        assertEquals(-90,MovementCommands.turn(object("delta_pitch",-90),0,-30).pitch());
        assertEquals(179,MovementCommands.turn(object("delta_yaw",-2),-179,0).yaw());
    }
    @Test void boundedNonzeroTurnAndCrouchDurationAreRequired(){
        for(var p:java.util.List.of(object(),object("delta_yaw",181),object("delta_pitch",91),object("delta_yaw","90")))
            assertThrows(BridgeException.class,()->MovementCommands.turn(p,0,0));
        assertEquals(20,MovementCommands.crouchTicks(object()));
        for(Object ticks:java.util.List.of(0,201,1.5,"20",true))assertThrows(BridgeException.class,()->MovementCommands.crouchTicks(object("ticks",ticks)));
    }
}
