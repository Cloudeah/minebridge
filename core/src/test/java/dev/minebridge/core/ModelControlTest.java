package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelControlTest {
    @Test void claimedGoalReturnsOwnerOptionsAndRejectsSecondClaim(){
        ModelControl model=new ModelControl();model.start("Посмотри на сундук",3,true);String goal=model.status().get("goal_id").getAsString();
        var claimed=model.claim(object("goal_id",goal));assertEquals(3,claimed.get("steps").getAsInt());assertTrue(claimed.get("vision").getAsBoolean());
        assertEquals("busy",assertThrows(BridgeException.class,()->model.claim(object("goal_id",goal))).code);
    }
    @Test void cancelledGoalsRejectLateTasksAndReportsEvenAfterNewGoal(){
        ModelControl model=new ModelControl();model.start("old",1,false);String old=model.status().get("goal_id").getAsString();model.claim(object("goal_id",old));model.cancel("stop");
        assertEquals("stale_goal",assertThrows(BridgeException.class,()->model.requireActive(old)).code);
        assertEquals("stale_goal",assertThrows(BridgeException.class,()->model.report(object("goal_id",old,"state","completed"))).code);
        model.start("new",1,false);assertNotEquals(old,model.status().get("goal_id").getAsString());
        assertEquals("stale_goal",assertThrows(BridgeException.class,()->model.claim(object("goal_id",old))).code);
    }
    @Test void busyOrInvalidOwnerGoalsAreRejected(){ModelControl model=new ModelControl();assertThrows(BridgeException.class,()->model.start(" ",1,false));assertThrows(BridgeException.class,()->model.start("goal",31,false));model.start("goal",1,false);assertThrows(BridgeException.class,()->model.start("another",1,false));}
    @Test void stepLimitsAndReportStatesAreValidated(){ModelControl model=new ModelControl();model.start("goal",1,false);String goal=model.status().get("goal_id").getAsString();model.claim(object("goal_id",goal));assertThrows(BridgeException.class,()->model.report(object("goal_id",goal,"state","completed","step",2)));assertThrows(BridgeException.class,()->model.report(object("goal_id",goal,"state","unknown")));model.report(object("goal_id",goal,"state","waiting","step",1,"detail","Нужны материалы"));assertFalse(model.active());}
}
