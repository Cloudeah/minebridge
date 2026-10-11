package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PickupLedgerTest {
    @Test void receiptBelongsToExactActorDimensionAndEntity(){
        var ledger=new PickupLedger("player","world");
        ledger.record("other","world","item","stone",3,0,10);
        ledger.record("player","other","item","stone",3,0,10);
        assertNull(ledger.peek("item",0));
        ledger.record("player","world","item","stone",3,0,10);
        assertNull(ledger.peek("other-item",0));assertNull(ledger.peek("item",11));
        var receipt=ledger.take("item",10);assertEquals(3,receipt.collected());assertEquals(0,receipt.remaining());
        assertNull(ledger.peek("item",0));
    }
    @Test void partialReceiptsAccumulateOnlyUntilASeparateTaskStarts(){
        var ledger=new PickupLedger("player","world");
        ledger.record("player","world","item","stone",2,8,10);
        ledger.record("player","world","item","stone",3,5,11);
        assertEquals(5,ledger.peek("item",10).collected());assertEquals(5,ledger.peek("item",10).remaining());
        ledger.begin("item");assertTrue(ledger.watching("item"));assertNull(ledger.peek("item",0));ledger.record("player","world","item","stone",5,0,12);
        assertEquals(5,ledger.peek("item",12).collected());
        ledger.discard("item");assertFalse(ledger.watching("item"));
    }
    @Test void ledgerIsBoundedAndRejectsEmptyOrInvalidReceipts(){
        var ledger=new PickupLedger("player","world");
        for(int i=0;i<129;i++)ledger.record("player","world","item"+i,"stone",1,0,i);
        assertNull(ledger.peek("item0",0));assertNotNull(ledger.peek("item1",0));
        ledger.record("player","world","invalid","stone",0,0,130);
        ledger.record("player","world","invalid","stone",1,-1,130);
        assertNull(ledger.peek("invalid",0));
    }
}
