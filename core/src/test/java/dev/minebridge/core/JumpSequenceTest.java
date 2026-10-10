package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.minebridge.core.Json.*;

class JumpSequenceTest {
    @Test void tenJumpsRequireTenTakeoffsAndLandingsWithNoExtraPulse() {
        var jumps=new JumpSequence(10);
        int pulses=0;
        for(int i=0;i<10;i++) {
            long tick=i*4;
            var start=jumps.observe(tick,new JumpSequence.Sample(tick,true,64),true);
            assertTrue(start.pressJump());pulses++;
            assertFalse(start.done());assertEquals(i,jumps.completed());
            assertFalse(jumps.observe(tick+1,new JumpSequence.Sample(tick+1,false,64.42),false).pressJump());
            assertFalse(jumps.observe(tick+2,new JumpSequence.Sample(tick+2,false,65.1),false).done());
            var landed=jumps.observe(tick+3,new JumpSequence.Sample(tick+3,true,64),true);
            assertEquals(i+1,jumps.completed());assertFalse(landed.pressJump());
            assertEquals(i==9,landed.done());assertNull(landed.error());
        }
        assertEquals(10,pulses);
        for(int tick=40;tick<60;tick++)assertFalse(jumps.observe(tick,new JumpSequence.Sample(tick,true,64),true).pressJump());
    }
    @Test void existingAirborneMotionIsNotCountedAndBothClientAndServerMustBeReady() {
        var jumps=new JumpSequence(1);
        assertFalse(jumps.observe(0,new JumpSequence.Sample(0,false,65),false).pressJump());
        assertFalse(jumps.observe(1,new JumpSequence.Sample(1,true,64),false).pressJump());
        assertEquals(0,jumps.completed());
        assertTrue(jumps.observe(2,new JumpSequence.Sample(2,true,64),true).pressJump());
    }
    @Test void blockedOrMissedTakeoffFailsWithoutRepeatingThePress() {
        var jumps=new JumpSequence(10);
        assertTrue(jumps.observe(0,new JumpSequence.Sample(0,true,64),true).pressJump());
        for(int tick=1;tick<=20;tick++) {
            var update=jumps.observe(tick,new JumpSequence.Sample(tick,true,64),true);
            assertFalse(update.pressJump());assertFalse(update.done());
        }
        var failed=jumps.observe(21,new JumpSequence.Sample(21,true,64),true);
        assertTrue(failed.done());assertEquals("jump_unconfirmed",failed.error());assertEquals(0,jumps.completed());
        assertFalse(jumps.observe(22,new JumpSequence.Sample(22,true,64),true).pressJump());
    }
    @Test void fallingWithoutRisingDoesNotConfirmAJump() {
        var jumps=new JumpSequence(1);
        jumps.observe(0,new JumpSequence.Sample(0,true,64),true);
        jumps.observe(1,new JumpSequence.Sample(1,false,63.5),false);
        jumps.observe(2,new JumpSequence.Sample(2,true,63),true);
        assertEquals(0,jumps.completed());
        assertEquals("jump_unconfirmed",jumps.observe(21,null,false).error());
    }
    @Test void oldAndDuplicateServerSamplesCannotConfirmALanding() {
        var jumps=new JumpSequence(1);
        jumps.observe(100,new JumpSequence.Sample(100,true,64),true);
        jumps.observe(102,new JumpSequence.Sample(102,false,64.42),false);
        assertFalse(jumps.observe(103,new JumpSequence.Sample(101,true,64),true).done());
        assertFalse(jumps.observe(104,new JumpSequence.Sample(102,true,64),true).done());
        assertEquals(0,jumps.completed());
        assertTrue(jumps.observe(105,new JumpSequence.Sample(103,true,64),true).done());
        assertEquals(1,jumps.completed());
    }
    @Test void anAirborneJumpIsIncompleteUntilLandingIsObserved() {
        var jumps=new JumpSequence(1);
        jumps.observe(0,new JumpSequence.Sample(0,true,64),true);
        jumps.observe(1,new JumpSequence.Sample(1,false,64.42),false);
        assertFalse(jumps.observe(200,null,false).done());
        assertEquals(0,jumps.completed());
        assertTrue(jumps.observe(201,new JumpSequence.Sample(201,true,64),true).done());
    }
    @Test void countIsBoundedAndMustBeAnInteger() {
        assertEquals(1,JumpSequence.count(object()));
        assertEquals(10,JumpSequence.count(object("count",10)));
        assertEquals(30,JumpSequence.count(object("count",30)));
        for(Object count:new Object[]{0,-1,31,1.5,"10",true})
            assertThrows(BridgeException.class,()->JumpSequence.count(object("count",count)));
        assertThrows(BridgeException.class,()->new JumpSequence(0));
        assertThrows(BridgeException.class,()->new JumpSequence(31));
    }
}
