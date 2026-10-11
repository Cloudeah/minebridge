package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class MeleeSequenceTest {
    @Test void oneConfirmedHitCompletesWithoutASecondRequest(){var s=new MeleeSequence(1);assertTrue(s.observe(10,0,false).ready());s.sent(10,0);var done=s.observe(11,1,false);assertTrue(done.done());assertFalse(done.ready());assertEquals("hits_completed",done.outcome());}
    @Test void legacyModeRequiresConfirmedDefeat(){var s=new MeleeSequence(0);s.sent(10,0);assertTrue(s.observe(11,1,false).ready());s.sent(11,1);assertEquals("defeated",s.observe(12,2,true).outcome());}
    @Test void unconfirmedAttackIsNotRetried(){var s=new MeleeSequence(2);s.sent(10,0);assertFalse(s.observe(29,0,false).ready());var failed=s.observe(30,0,false);assertEquals("hit_unconfirmed",failed.error());assertFalse(failed.ready());assertThrows(IllegalStateException.class,()->s.sent(30,0));assertEquals("hit_unconfirmed",s.observe(31,1,false).error());}
    @Test void aMatchingReceiptUnlocksOnlyTheNextHit(){var s=new MeleeSequence(3);s.sent(10,0);assertFalse(s.observe(11,0,false).ready());assertTrue(s.observe(12,1,false).ready());s.sent(12,1);assertFalse(s.observe(13,1,false).ready());assertTrue(s.observe(14,2,false).ready());}
    @Test void pauseDoesNotRepeatPendingHitButKeepsAlreadyCompletedGoal(){var s=new MeleeSequence(2);s.sent(10,0);s.released();assertEquals("attack_interrupted",s.observe(11,1,false).error());assertThrows(IllegalStateException.class,()->s.sent(11,1));var one=new MeleeSequence(1);one.sent(10,0);one.released();assertTrue(one.observe(11,1,false).done());}
    @Test void pauseBetweenHitsRetainsProgress(){var s=new MeleeSequence(2);s.sent(10,0);assertTrue(s.observe(11,1,false).ready());s.released();assertTrue(s.observe(40,1,false).ready());s.sent(40,1);assertTrue(s.observe(41,2,false).done());}
    @Test void staleAndRegressedReceiptsCannotTriggerInput(){var s=new MeleeSequence(2);assertTrue(s.observe(11,1,false).ready());assertFalse(s.observe(10,0,false).ready());assertEquals("combat_unconfirmed",s.observe(12,0,false).error());}
    @Test void earlyDefeatCompletesEvenWhenMoreHitsWereRequested(){var s=new MeleeSequence(64);s.sent(10,0);var done=s.observe(11,1,true);assertTrue(done.done());assertEquals("defeated",done.outcome());}
    @Test void chaseIsBoundedForBothActorAndTarget(){assertTrue(MeleeSequence.withinChase(16,15,16));assertFalse(MeleeSequence.withinChase(16.01,15,16));assertFalse(MeleeSequence.withinChase(0,16.01,16));assertFalse(MeleeSequence.withinChase(Double.NaN,1,16));}
    @Test void explicitHitsAndPoliciesAreValidatedBeforeExecution(){String id="418f10e9-bc78-4d9c-bb26-6a9cfa352b69";MeleeSequence.validate(object("entity_id",id,"hits",1,"chase",true,"max_chase_distance",16));assertEquals(0,MeleeSequence.requestedHits(object()));for(var p:new com.google.gson.JsonObject[]{object("entity_id",id,"hits",0),object("entity_id",id,"hits",true),object("entity_id",id,"chase",1),object("entity_id",id,"stop_health",-1),object("entity_id",id,"max_chase_distance",33),object("entity_id",id,"sprint",true,"crouch",true),object("entity_id",id,"hand","off"),object("entity_id",id,"count",1),object("entity_id","0-0-0-0-0")})assertThrows(BridgeException.class,()->MeleeSequence.validate(p));}
}
