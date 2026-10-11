package dev.minebridge.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class InteractionLedgerTest {
    private static final String ACTOR="owner",DIM="overworld",TARGET="entity:sheep";
    private InteractionLedger ledger(){var ledger=new InteractionLedger(ACTOR,DIM);ledger.begin("t",TARGET,"main",10);return ledger;}
    private JsonObject sample(String item,int count,int damage){return object("target",object("key",TARGET,"type","minecraft:sheep","alive",true,"sheared",false,"in_love",false,"love_cause","none","color","white"),
            "main_hand",object("type",item,"count",count,"damage",damage),"off_hand",object("type","minecraft:air","count",0,"damage",0),"selected_slot",0,"menu_id",0);}
    private boolean attempt(InteractionLedger ledger,JsonObject before){return ledger.recordAttempt(ACTOR,DIM,TARGET,"main",11,before);}
    private boolean result(InteractionLedger ledger,JsonObject after){return ledger.recordResult("t",ACTOR,DIM,11,after);}
    @Test void NoStateChangeCannotConfirmAnInteraction(){var l=ledger();var before=sample("minecraft:stone",8,0);assertTrue(attempt(l,before));assertFalse(result(l,before));assertNull(l.peek("t"));}
    @Test void FeedingRequiresTheActualServerActorAsLoveCause(){var l=ledger();var before=sample("minecraft:wheat",8,0);attempt(l,before);var after=before.deepCopy();child(after,"target").addProperty("in_love",true);child(after,"target").addProperty("love_cause",ACTOR);assertTrue(result(l,after));assertEquals("entity_changed",string(l.peek("t"),"outcome"));}
    @Test void ShearingConfirmsTheExactTargetAndHeldTool(){var l=ledger();var before=sample("minecraft:shears",1,0);attempt(l,before);var after=sample("minecraft:shears",1,1);child(after,"target").addProperty("sheared",true);assertTrue(result(l,after));assertTrue(l.peek("t").get("held_item_changed").getAsBoolean());}
    @Test void MilkBucketReplacementIsAnObservedItemChange(){var l=ledger();attempt(l,sample("minecraft:bucket",1,0));assertTrue(result(l,sample("minecraft:milk_bucket",1,0)));assertEquals("held_item_changed",string(l.peek("t"),"outcome"));}
    @Test void WrongActorDimensionTargetHandOrWindowCannotBind(){var l=ledger();var before=sample("minecraft:wheat",8,0);
        assertFalse(l.recordAttempt("other",DIM,TARGET,"main",11,before));assertFalse(l.recordAttempt(ACTOR,"nether",TARGET,"main",11,before));
        assertFalse(l.recordAttempt(ACTOR,DIM,"entity:cow","main",11,before));assertFalse(l.recordAttempt(ACTOR,DIM,TARGET,"off",11,before));
        assertFalse(l.recordAttempt(ACTOR,DIM,TARGET,"main",9,before));assertFalse(l.recordAttempt(ACTOR,DIM,TARGET,"main",31,before));
        assertFalse(result(l,sample("minecraft:wheat",7,0)));assertTrue(attempt(l,before));
        assertFalse(l.recordResult("t","other",DIM,11,sample("minecraft:wheat",7,0)));assertFalse(l.recordResult("t",ACTOR,DIM,32,sample("minecraft:wheat",7,0)));}
    @Test void AmbiguousConcurrentTasksRejectAttribution(){var l=ledger();l.begin("second",TARGET,"main",10);assertFalse(attempt(l,sample("minecraft:wheat",8,0)));assertTrue(l.pending(ACTOR,DIM).isEmpty());}
    @Test void DuplicateSpecificAndGeneralEventsKeepTheOriginalBaselineAndNeverRepeat(){var l=ledger();attempt(l,sample("minecraft:wheat",8,0));attempt(l,sample("minecraft:wheat",7,0));assertEquals(1,l.pending(ACTOR,DIM).size());assertTrue(result(l,sample("minecraft:wheat",7,0)));assertFalse(result(l,sample("minecraft:wheat",6,0)));assertEquals(8,child(l.peek("t"),"before").getAsJsonObject("held").get("count").getAsInt());}
    @Test void PickupsDamageMovementAndNaturalAnimalTimersDoNotConfirm(){var l=ledger();var before=sample("minecraft:stone",8,0);child(before,"target").addProperty("in_love",true);child(before,"target").addProperty("sheared",true);attempt(l,before);var after=sample("minecraft:stone",9,0);child(after,"target").addProperty("health",1);child(after,"target").addProperty("position","changed");assertFalse(result(l,after));}
    @Test void ExistingInterfaceAndChangedSelectionCannotConfirm(){var l=ledger();var before=sample("minecraft:wheat",8,0);before.addProperty("menu_id",2);attempt(l,before);var after=sample("minecraft:wheat",7,0);after.addProperty("menu_id",2);assertFalse(result(l,after));
        l=ledger();attempt(l,sample("minecraft:wheat",8,0));after=sample("minecraft:wheat",7,0);after.addProperty("selected_slot",1);assertFalse(result(l,after));}
    @Test void NewMenuAndBlockStateHaveExplicitOutcomes(){var l=ledger();var before=sample("minecraft:air",0,0);attempt(l,before);var after=before.deepCopy();after.addProperty("menu_id",3);assertTrue(result(l,after));assertEquals("menu_opened",string(l.peek("t"),"outcome"));
        l=new InteractionLedger(ACTOR,DIM);l.begin("t","block:0,64,0","main",10);before=sample("minecraft:air",0,0);before.add("target",object("key","block:0,64,0","type","minecraft:lever","properties",object("powered",false)));
        l.recordAttempt(ACTOR,DIM,"block:0,64,0","main",11,before);after=before.deepCopy();child(child(after,"target"),"properties").addProperty("powered",true);assertTrue(result(l,after));assertEquals("block_changed",string(l.peek("t"),"outcome"));}
    @Test void OffhandUsesOnlyItsOwnItemChange(){var l=new InteractionLedger(ACTOR,DIM);l.begin("t",TARGET,"off",10);var before=sample("minecraft:wheat",8,0);before.add("off_hand",object("type","minecraft:bucket","count",1,"damage",0));l.recordAttempt(ACTOR,DIM,TARGET,"off",11,before);var after=before.deepCopy();child(after,"main_hand").addProperty("count",7);assertFalse(result(l,after));after.add("off_hand",object("type","minecraft:milk_bucket","count",1,"damage",0));assertTrue(result(l,after));assertEquals("off",string(l.peek("t"),"hand"));}
    @Test void SuspensionRefusesLateEventsAndPreservesAlreadyConfirmedFacts(){var l=ledger();attempt(l,sample("minecraft:wheat",8,0));l.suspend();assertTrue(l.interrupted("t"));assertFalse(result(l,sample("minecraft:wheat",7,0)));assertFalse(attempt(l,sample("minecraft:wheat",8,0)));
        l=ledger();attempt(l,sample("minecraft:wheat",8,0));result(l,sample("minecraft:wheat",7,0));l.suspend();assertFalse(l.interrupted("t"));assertNotNull(l.finish("t"));assertNull(l.finish("t"));}
    @Test void ReceiptsAreDefensiveAndTaskIdsCannotResend(){var l=ledger();var before=sample("minecraft:wheat",8,0);attempt(l,before);child(before,"main_hand").addProperty("count",1);assertTrue(result(l,sample("minecraft:wheat",7,0)));var receipt=l.peek("t");receipt.addProperty("outcome","invented");assertEquals("held_item_changed",string(l.peek("t"),"outcome"));assertThrows(BridgeException.class,()->l.begin("t",TARGET,"main",10));l.end("t");assertNull(l.peek("t"));}
}
