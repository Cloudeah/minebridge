package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class MiningToolsTest {
    private MiningTools.Candidate tool(int slot,double speed,boolean correct,int durability){return new MiningTools.Candidate(slot,"minecraft:pickaxe",1,speed,correct,durability);}
    @Test void unsuitableFastToolCannotDestroyRequiredDrops(){var result=MiningTools.choose(List.of(tool(0,20,false,10),tool(9,4,true,100)),0,true,true);assertEquals(9,result.slot());}
    @Test void selectedToolWinsEqualSpeedWithoutExtraPacket(){assertEquals(0,MiningTools.choose(List.of(tool(9,4,true,100),tool(0,4,true,2)),0,true,true).slot());}
    @Test void equalInventoryToolsPreferDurabilityThenStableSlot(){assertEquals(10,MiningTools.choose(List.of(tool(9,4,true,10),tool(10,4,true,100),tool(11,4,true,100)),0,true,true).slot());}
    @Test void explicitCurrentHandDoesNotSilentlySelectAnotherTool(){assertEquals("missing_tool",assertThrows(BridgeException.class,()->MiningTools.choose(List.of(tool(0,1,false,10),tool(9,4,true,100)),0,true,false)).code);}
    @Test void emptyHandCanMineNonToolBlocksAndBadCandidatesAreIgnored(){var air=new MiningTools.Candidate(0,"minecraft:air",0,1,false,-1);assertEquals(0,MiningTools.choose(List.of(air,tool(9,Double.NaN,true,10),tool(10,4,true,0),tool(39,10,true,100)),0,false,true).slot());}
    @Test void fasterEligibleToolWinsAndCannotRequireNonexistentItems(){assertEquals(12,MiningTools.choose(List.of(tool(0,2,true,10),tool(12,8,true,100)),0,true,true).slot());assertThrows(BridgeException.class,()->MiningTools.choose(List.of(new MiningTools.Candidate(9,"minecraft:air",0,4,true,10)),0,true,true));}
}
