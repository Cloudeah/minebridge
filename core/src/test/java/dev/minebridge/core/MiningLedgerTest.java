package dev.minebridge.core;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;
class MiningLedgerTest {
    final JsonObject pos=object("x",1,"y",64,"z",2),stone=object("type","minecraft:stone","properties",object()),air=object("type","minecraft:air","properties",object());
    final JsonObject tool=object("type","minecraft:iron_pickaxe","count",1,"damage",0);
    MiningLedger started(){var ledger=new MiningLedger("owner","overworld");ledger.begin("task",pos,stone,air,10);return ledger;}
    String attempt(MiningLedger ledger,long tick){return ledger.attempt("owner","overworld",pos,stone,tool,0,tick);}
    boolean complete(MiningLedger ledger,JsonObject block,long tick,boolean canceled){return ledger.complete("task","owner","overworld",block,object("type","minecraft:iron_pickaxe","count",1,"damage",1),0,tick,canceled);}
    @Test void disappearanceAloneDoesNotConfirmMining(){var l=started();assertFalse(complete(l,air,11,false));assertNull(l.peek("task"));}
    @Test void exactEventAndReplacementConfirmToolWearWithoutInventingDrops(){var l=started();assertEquals("task",attempt(l,11));assertTrue(complete(l,air,11,false));JsonObject r=l.peek("task");assertEquals("integrated_server_block_break",string(r,"source"));assertEquals("not_confirmed",string(r,"drops"));assertEquals(1,child(r,"tool_after").get("damage").getAsInt());}
    @Test void wrongActorDimensionPositionAndStateCannotClaimAnEvent(){var l=started();assertNull(l.attempt("other","overworld",pos,stone,tool,0,11));assertNull(l.attempt("owner","nether",pos,stone,tool,0,11));assertNull(l.attempt("owner","overworld",object("x",2,"y",64,"z",2),stone,tool,0,11));assertNull(l.attempt("owner","overworld",pos,air,tool,0,11));}
    @Test void canceledAndUnremovedBlocksCannotSucceed(){var l=started();attempt(l,11);assertFalse(complete(l,air,11,true));assertEquals("permission_denied",l.error("task"));assertNull(l.peek("task"));l=started();attempt(l,11);assertFalse(complete(l,stone,11,false));assertEquals("mining_unconfirmed",l.error("task"));}
    @Test void staleFutureAndDuplicateEventsAreRejected(){var l=started();assertNull(attempt(l,9));assertNull(attempt(l,31));assertTrue(l.arm("task",40));assertEquals("task",attempt(l,41));assertNull(attempt(l,41));assertFalse(l.arm("task",41));assertFalse(complete(l,air,40,false));assertFalse(complete(l,air,62,false));assertTrue(complete(l,air,41,false));assertFalse(complete(l,air,41,false));}
    @Test void ambiguousTasksDoNotShareOneBreakEvent(){var l=started();l.begin("second",pos,stone,air,10);assertNull(attempt(l,11));assertNull(l.peek("task"));assertNull(l.peek("second"));}
    @Test void longMiningRefreshesTheEventWindowOnlyWhileActive(){var l=started();assertTrue(l.arm("task",200));assertEquals("task",attempt(l,201));assertTrue(complete(l,air,201,false));}
    @Test void pausePreventsLaterManualMiningButRetainsCompletedReceipt(){var l=started();l.suspend();assertTrue(l.interrupted("task"));assertFalse(l.arm("task",11));assertNull(attempt(l,11));l=started();attempt(l,11);complete(l,air,11,false);l.suspend();assertFalse(l.interrupted("task"));assertNotNull(l.peek("task"));}
    @Test void pauseBetweenEventAndResultDoesNotAttributeLateEffects(){var l=started();attempt(l,11);l.suspend();assertFalse(complete(l,air,12,false));assertNull(l.peek("task"));}
    @Test void changedSelectedSlotOrForeignCompletionCannotSucceed(){var l=started();attempt(l,11);assertFalse(l.complete("task","owner","overworld",air,tool,1,11,false));assertFalse(l.complete("task","other","overworld",air,tool,0,11,false));assertFalse(l.complete("task","owner","nether",air,tool,0,11,false));}
    @Test void finishIsAtomicAndCopiesAreIndependent(){var l=started();attempt(l,11);complete(l,air,11,false);l.peek("task").addProperty("source","changed");JsonObject receipt=l.finish("task");assertEquals("integrated_server_block_break",string(receipt,"source"));assertNull(l.finish("task"));}
    @Test void replacementFluidMustMatchItsProperties(){var water=object("type","minecraft:water","properties",object("level","0"));var l=new MiningLedger("owner","overworld");l.begin("task",pos,stone,water,10);attempt(l,11);assertTrue(complete(l,water,11,false));}
}
