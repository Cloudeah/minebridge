package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class FollowSequenceTest {
    private long ticks(FollowSequence.Update update){return update.progress().get("confirmed_follow_ticks").getAsLong();}
    @Test void onlyServerProximityCompletesTheTask() {
        var s=new FollowSequence(6,3);
        assertEquals(0,ticks(s.observe(null)));
        assertFalse(s.observe(new FollowSequence.Sample(10,2)).done());
        assertEquals(4,ticks(s.observe(new FollowSequence.Sample(14,3))));
        var done=s.observe(new FollowSequence.Sample(18,2));
        assertTrue(done.done());assertEquals(6,ticks(done));
        assertEquals("integrated_server",done.progress().get("source").getAsString());
    }
    @Test void duplicateAndOldSamplesDoNotCountOrChangeProximity() {
        var s=new FollowSequence(20,3);
        s.observe(new FollowSequence.Sample(10,2));
        s.observe(new FollowSequence.Sample(14,2));
        s.observe(new FollowSequence.Sample(14,10));s.observe(new FollowSequence.Sample(11,10));
        assertEquals(8,ticks(s.observe(new FollowSequence.Sample(18,2))));
    }
    @Test void BeingOutsideDistanceBreaksTheCountedInterval() {
        var s=new FollowSequence(20,3);
        s.observe(new FollowSequence.Sample(10,2));s.observe(new FollowSequence.Sample(14,2));
        s.observe(new FollowSequence.Sample(18,4));s.observe(new FollowSequence.Sample(20,2));
        assertEquals(6,ticks(s.observe(new FollowSequence.Sample(22,2))));
    }
    @Test void PausePreservesProgressWithoutCountingTheGap() {
        var s=new FollowSequence(20,3);
        s.observe(new FollowSequence.Sample(10,2));s.observe(new FollowSequence.Sample(14,2));s.released();
        assertEquals(4,ticks(s.observe(new FollowSequence.Sample(200,2))));
        assertEquals(6,ticks(s.observe(new FollowSequence.Sample(202,2))));
    }
    @Test void SparseObservationsDoNotProveProximityDuringTheGap() {
        var s=new FollowSequence(20,3);
        s.observe(new FollowSequence.Sample(10,2));
        assertEquals(0,ticks(s.observe(new FollowSequence.Sample(100,2))));
        assertEquals(4,ticks(s.observe(new FollowSequence.Sample(104,2))));
    }
    @Test void InvalidDistanceCannotConfirmFollowing() {
        for(double distance:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1}) {
            var s=new FollowSequence(20,3);s.observe(new FollowSequence.Sample(10,2));
            s.observe(new FollowSequence.Sample(12,distance));
            assertEquals(0,ticks(s.observe(new FollowSequence.Sample(14,2))));
        }
    }
    @Test void FinishedProgressIsStableAfterPauseAndFurtherSamples() {
        var s=new FollowSequence(1,3);s.observe(new FollowSequence.Sample(10,2));
        assertTrue(s.observe(new FollowSequence.Sample(12,2)).done());s.released();
        var result=s.observe(new FollowSequence.Sample(1000,20));assertTrue(result.done());assertEquals(1,ticks(result));
    }
    @Test void ParametersAreBoundedAndTypedBeforeExecution() {
        FollowSequence.validate(object());FollowSequence.validate(object("ticks",1200,"distance",0.7,"crouch",true));
        for(var p:java.util.List.of(object("ticks",true),object("ticks",1.5),object("ticks",0),object("ticks",1201),object("distance",0.3),object("distance",17),object("sprint",true,"crouch",true)))
            assertThrows(BridgeException.class,()->FollowSequence.validate(p));
    }
}
