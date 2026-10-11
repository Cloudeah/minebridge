package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PickupAttemptTest {
    @Test void partialInsertionRequiresMatchingGroundAndInventoryDeltasInThePickupTick(){
        var attempt=new PickupAttempt("stone",3,63,10);
        assertEquals(1,attempt.collected("stone",2,64,10,true));
        assertEquals(0,attempt.collected("stone",2,64,11,true));
    }
    @Test void disappearanceCannotBeCreditedWithoutASeparatePickupEvent(){
        var attempt=new PickupAttempt("stone",3,63,10);
        assertEquals(0,attempt.collected("stone",0,66,10,false));
    }
    @Test void inventoryOnlyGroundOnlyOrAmbiguousChangesAreNotPickupReceipts(){
        var attempt=new PickupAttempt("stone",3,63,10);
        assertEquals(0,attempt.collected("stone",3,64,10,true));
        assertEquals(0,attempt.collected("stone",2,63,10,true));
        assertEquals(0,attempt.collected("stone",2,65,10,true));
        assertEquals(0,attempt.collected("dirt",2,64,10,true));
        assertEquals(0,attempt.collected("stone",4,64,10,true));
    }
    @Test void deniedPickupWithUnchangedStacksProducesNoReceipt(){
        assertEquals(0,new PickupAttempt("stone",3,63,10).collected("stone",3,63,10,true));
    }
}
