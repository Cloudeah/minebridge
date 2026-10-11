package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.minebridge.core.CrossbowSequence.Command.*;

class CrossbowSequenceTest {
    private CrossbowSequence.Sample sample(long tick,boolean using,boolean charged){return new CrossbowSequence.Sample(tick,using,charged,charged?1:0,charged);}
    @Test void UnloadedCrossbowWaitsForServerChargeReleaseAndClientSynchronization() {
        var s=new CrossbowSequence();assertEquals(draw,s.observe(sample(10,false,false),false,false).command());s.sent(draw,10);
        assertEquals(wait,s.observe(sample(15,true,false),true,false).command());
        var loaded=s.observe(sample(35,true,true),true,false);assertEquals(release,loaded.command());assertEquals(1,loaded.confirmedLoads());s.sent(release,35);
        assertEquals(wait,s.observe(sample(36,true,true),false,true).command());
        assertEquals(wait,s.observe(sample(37,false,true),false,false).command());
        assertEquals(fire,s.observe(sample(38,false,true),false,true).command());s.sent(fire,38);
        assertEquals(wait,s.observe(sample(100,false,true),false,true).command());
    }
    @Test void PreloadedWeaponFiresOnceWithoutInventingALoad() {
        var s=new CrossbowSequence();var d=s.observe(sample(10,false,true),false,true);
        assertEquals(fire,d.command());assertEquals(0,d.confirmedLoads());s.sent(fire,10);
        assertEquals(wait,s.observe(sample(20,false,true),false,true).command());
    }
    @Test void PreloadedWeaponWaitsForTheClientInventoryUpdate() {
        var s=new CrossbowSequence();assertEquals(wait,s.observe(sample(10,false,true),false,false).command());
        assertEquals(fire,s.observe(sample(12,false,true),false,true).command());
    }
    @Test void StaleClientChargeCannotTurnALoadInputIntoAFireInput() {
        var s=new CrossbowSequence();assertEquals(wait,s.observe(sample(10,false,false),false,true).command());
        assertEquals(draw,s.observe(sample(12,false,false),false,false).command());
    }
    @Test void NoObservationCannotAuthorizeInput() {
        var s=new CrossbowSequence();assertEquals(wait,s.observe(null,false,true).command());assertEquals(0,s.observe(null,false,true).confirmedLoads());
    }
    @Test void AbortBeforeLoadingNeverFiresOnResume() {
        var s=new CrossbowSequence();s.sent(draw,10);s.released();
        var d=s.observe(sample(50,true,true),true,true);assertEquals("draw_interrupted",d.error());assertEquals(wait,d.command());assertEquals(0,d.confirmedLoads());
    }
    @Test void AbortAfterConfirmedLoadingPreservesTheReceiptWithoutFiring() {
        var s=new CrossbowSequence();s.sent(draw,10);s.observe(sample(35,true,true),true,true);s.sent(release,35);s.released();
        var d=s.observe(sample(50,false,true),false,true);assertEquals("draw_interrupted",d.error());assertEquals(1,d.confirmedLoads());assertEquals(wait,d.command());
    }
    @Test void FireworksAndMultipleProjectilesAreRejected() {
        for(var sample:java.util.List.of(new CrossbowSequence.Sample(10,false,true,1,false),new CrossbowSequence.Sample(10,false,true,3,true)))
            assertEquals("unsupported_ammo",new CrossbowSequence().observe(sample,false,true).error());
    }
    @Test void StaleSamplesAndBusyItemsCannotAuthorizeInput() {
        var s=new CrossbowSequence();s.sent(draw,10);assertEquals(wait,s.observe(sample(9,true,true),true,true).command());
        assertEquals("item_busy",new CrossbowSequence().observe(sample(10,true,true),true,true).error());
    }
    @Test void InterruptedOrUnconfirmedReleaseFailsWithNoAutomaticRetry() {
        var s=new CrossbowSequence();s.sent(draw,10);assertEquals("draw_interrupted",s.observe(sample(25,false,false),true,false).error());
        s=new CrossbowSequence();s.sent(draw,10);s.sent(release,35);assertEquals("load_unconfirmed",s.observe(sample(40,false,false),false,false).error());
        assertEquals("draw_interrupted",s.observe(sample(60,true,true),true,true).error());
        var idle=new CrossbowSequence();assertThrows(BridgeException.class,()->idle.sent(release,10));
    }
}
