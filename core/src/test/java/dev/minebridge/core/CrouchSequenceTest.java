package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CrouchSequenceTest {
    private CrouchSequence.Sample sample(long tick,boolean crouching){return new CrouchSequence.Sample(tick,crouching);}
    @Test void completionRequiresConfirmedDurationThenRelease(){
        var seq=new CrouchSequence(20);
        assertTrue(seq.observe(0,sample(0,false)).holdCrouch());
        seq.observe(1,sample(1,true));
        var releasing=seq.observe(21,sample(21,true));
        assertFalse(releasing.holdCrouch());assertFalse(releasing.done());
        assertEquals(20,releasing.progress().get("confirmed_crouch_ticks").getAsInt());
        assertTrue(seq.observe(22,sample(22,false)).done());
        assertFalse(seq.observe(23,sample(23,true)).holdCrouch());
    }
    @Test void duplicateAndOlderSamplesCannotCountTimeOrConfirmRelease(){
        var seq=new CrouchSequence(1);seq.observe(0,sample(0,true));
        assertEquals(0,seq.observe(1,sample(0,true)).progress().get("confirmed_crouch_ticks").getAsInt());
        assertFalse(seq.observe(1,sample(1,true)).done());
        assertFalse(seq.observe(2,sample(1,false)).done());
        assertTrue(seq.observe(3,sample(2,false)).done());
    }
    @Test void pauseTimeIsExcludedAndCompletedDurationRemains(){
        var seq=new CrouchSequence(20);seq.observe(0,sample(0,true));seq.observe(10,sample(10,true));
        seq.released();seq.observe(100,sample(100,false));seq.observe(101,sample(101,true));
        var resumed=seq.observe(111,sample(111,true));
        assertEquals(20,resumed.progress().get("confirmed_crouch_ticks").getAsInt());assertFalse(resumed.holdCrouch());
        seq.released();assertTrue(seq.observe(112,sample(112,false)).done());
    }
    @Test void missingPressOrReleaseConfirmationFailsWithoutHolding(){
        var press=new CrouchSequence(1);press.observe(0,sample(0,false));
        var bad=press.observe(21,null);assertEquals("crouch_unconfirmed",bad.error());assertFalse(bad.holdCrouch());
        var release=new CrouchSequence(1);release.observe(0,sample(0,true));release.observe(1,sample(1,true));
        assertEquals("crouch_release_unconfirmed",release.observe(22,sample(22,true)).error());
    }
}
