package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class CraftSequenceTest {
    @Test void waitsForExactGroupAndThenAllowsNextGroup(){var s=new CraftSequence(3);s.begin(CraftSequence.Operation.FILL,10);assertFalse(s.confirm(3,11,false));assertThrows(BridgeException.class,()->s.begin(CraftSequence.Operation.OUTPUT,11));assertTrue(s.confirm(3,12,true));s.begin(CraftSequence.Operation.OUTPUT,13);assertTrue(s.confirm(3,14,true));}
    @Test void changedMenuFailsEvenWhenCountsMatch(){var s=new CraftSequence(3);s.begin(CraftSequence.Operation.OUTPUT,10);assertEquals("interface_closed",assertThrows(BridgeException.class,()->s.confirm(4,11,true)).code);assertThrows(BridgeException.class,()->s.confirm(3,12,true));}
    @Test void timeoutIsStickyAndNeverAllowsResending(){var s=new CraftSequence(0);s.begin(CraftSequence.Operation.CLEAR,10);assertFalse(s.confirm(0,30,false));assertEquals("craft_unconfirmed",assertThrows(BridgeException.class,()->s.confirm(0,31,false)).code);assertThrows(BridgeException.class,()->s.confirm(0,32,true));assertThrows(BridgeException.class,()->s.begin(CraftSequence.Operation.CLEAR,32));}
    @Test void pauseDuringInputDoesNotAttributeLaterManualChanges(){var s=new CraftSequence(2);s.begin(CraftSequence.Operation.FILL,10);s.released();assertEquals("craft_interrupted",assertThrows(BridgeException.class,()->s.confirm(2,11,true)).code);}
    @Test void pauseBetweenConfirmedGroupsCanResume(){var s=new CraftSequence(2);s.begin(CraftSequence.Operation.FILL,10);s.confirm(2,11,true);s.released();s.checkMenu(2);s.begin(CraftSequence.Operation.OUTPUT,12);assertTrue(s.confirm(2,13,true));}
    @Test void oldSampleCannotConfirmInput(){var s=new CraftSequence(0);s.begin(CraftSequence.Operation.FILL,10);assertFalse(s.confirm(0,9,true));assertEquals(CraftSequence.Operation.FILL,s.pending());}
    @Test void validatesStationFlagsAndCompleteCoordinates(){CraftSequence.validate(object("station_position",object("x",1,"y",64,"z",-2)));CraftSequence.validate(object("auto_station",false));for(var p:new com.google.gson.JsonObject[]{object("auto_station","yes"),object("auto_station",1),object("station_position",object("x",1,"y",64)),object("station_position",object("x",1.5,"y",64,"z",0)),object("station_position",object("x",true,"y",64,"z",0)),object("station_position",object("x",30000001,"y",64,"z",0))})assertThrows(BridgeException.class,()->CraftSequence.validate(p));}
}
