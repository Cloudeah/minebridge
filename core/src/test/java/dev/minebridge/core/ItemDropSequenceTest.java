package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.minebridge.core.Json.*;

class ItemDropSequenceTest {
    private ItemDropSequence.Sample sample(long tick,int count){return new ItemDropSequence.Sample(tick,2,count==0?"minecraft:air":"minecraft:stone",count);}
    @Test void exactCountRequiresSeparateServerDecrementsAndNeverDropsAgainAfterCompletion(){
        var drops=new ItemDropSequence(3,"minecraft:stone");int requests=0;
        for(int i=0;i<3;i++){
            assertTrue(drops.observe(i*3,sample(i*3,3-i)).pressDrop());requests++;
            assertTrue(drops.awaitingConfirmation());
            assertFalse(drops.observe(i*3+1,sample(i*3+1,3-i)).pressDrop());
            var confirmed=drops.observe(i*3+2,sample(i*3+2,2-i));
            assertFalse(confirmed.pressDrop());assertEquals(i+1,drops.completed());
            assertEquals(i==2,confirmed.done());assertFalse(drops.awaitingConfirmation());
        }
        assertEquals(3,requests);assertFalse(drops.observe(20,sample(20,0)).pressDrop());
    }
    @Test void insufficientCountAndUnexpectedItemSendNoRequest(){
        var missing=new ItemDropSequence(5,"");
        var result=missing.observe(0,sample(0,4));assertEquals("missing_materials",result.error());assertFalse(result.pressDrop());
        var wrong=new ItemDropSequence(1,"minecraft:dirt");
        result=wrong.observe(0,sample(0,4));assertEquals("target_changed",result.error());assertFalse(result.pressDrop());
    }
    @Test void duplicateAndOlderSamplesCannotCreditOrRepeatADrop(){
        var drops=new ItemDropSequence(2,"");drops.observe(10,sample(10,4));
        assertEquals(0,drops.observe(11,sample(10,3)).progress().get("completed").getAsInt());
        assertEquals(0,drops.observe(12,sample(9,3)).progress().get("completed").getAsInt());
        assertFalse(drops.observe(13,sample(11,3)).pressDrop());assertEquals(1,drops.completed());
        assertFalse(drops.observe(14,sample(11,3)).pressDrop());
        assertTrue(drops.observe(15,sample(12,3)).pressDrop());
    }
    @Test void missingConfirmationNeverRetriesAndPreservesPartialCount(){
        var drops=new ItemDropSequence(3,"");drops.observe(0,sample(0,4));drops.observe(1,sample(1,3));
        assertTrue(drops.observe(2,sample(2,3)).pressDrop());
        var failed=drops.observe(23,null);assertEquals("drop_unconfirmed",failed.error());assertEquals(1,drops.completed());
        assertFalse(failed.pressDrop());assertFalse(drops.observe(24,sample(24,2)).pressDrop());
    }
    @Test void ownerPauseRestartsOnlyConfirmationWaitWithoutResendingTheDrop(){
        var drops=new ItemDropSequence(1,"");assertTrue(drops.observe(0,sample(0,4)).pressDrop());
        drops.resumed(100);assertNull(drops.observe(100,null).error());
        var confirmed=drops.observe(102,sample(102,3));assertTrue(confirmed.done());assertFalse(confirmed.pressDrop());assertEquals(1,drops.completed());
    }
    @Test void SlotChangesOrAmbiguousInventoryDecrementsAreNotAttributedToDrop(){
        for(var changed:new ItemDropSequence.Sample[]{new ItemDropSequence.Sample(1,3,"minecraft:stone",3),new ItemDropSequence.Sample(1,2,"minecraft:dirt",3)}){
            var drops=new ItemDropSequence(2,"");drops.observe(0,sample(0,4));
            assertEquals("target_changed",drops.observe(1,changed).error());assertEquals(0,drops.completed());
        }
        for(int count:new int[]{2,5}){
            var drops=new ItemDropSequence(2,"");drops.observe(0,sample(0,4));
            assertEquals("drop_unconfirmed",drops.observe(1,sample(1,count)).error());assertEquals(0,drops.completed());
        }
    }
    @Test void countIsBoundedAndStrictlyNumeric(){
        assertEquals(1,ItemDropSequence.count(object()));assertEquals(64,ItemDropSequence.count(object("count",64)));
        for(Object count:new Object[]{0,65,-1,1.5,"2",true})assertThrows(BridgeException.class,()->ItemDropSequence.count(object("count",count)));
    }
}
