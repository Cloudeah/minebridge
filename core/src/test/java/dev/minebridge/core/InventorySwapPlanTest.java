package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InventorySwapPlanTest {
    // Model vanilla SWAP, including occupied buffers and identical item types with different counts.
    private static void exchange(int sourceInventory,int targetInventory,int staging,String source,String target,String buffer) {
        var plan=InventorySwapPlan.plan(12,5,sourceInventory,targetInventory,staging,3);
        Map<Integer,String> slots=new HashMap<>(Map.of(12,source,5,target,103,buffer));
        Map<Integer,Integer> inventory=new HashMap<>(Map.of(3,103));
        if(InventorySwapPlan.direct(sourceInventory))inventory.put(sourceInventory,12);
        if(InventorySwapPlan.direct(targetInventory))inventory.put(targetInventory,5);
        for(var click:plan){int held=inventory.get(click.inventoryButton());String before=slots.get(held);slots.put(held,slots.get(click.slot()));slots.put(click.slot(),before);}
        assertEquals(target,slots.get(12));assertEquals(source,slots.get(5));assertEquals(buffer,slots.get(103));
        assertEquals(InventorySwapPlan.direct(sourceInventory)||InventorySwapPlan.direct(targetInventory)?1:3,plan.size());
    }
    @Test void hotbarAndOffhandExchangeInOnePacket(){exchange(0,39,12,"helmet","old helmet","stone64");exchange(10,40,12,"shield","torch64","stone64");exchange(0,1,12,"stone20","stone8","stone64");}
    @Test void fullMainInventoryUsesAndRestoresOccupiedBuffer(){exchange(10,39,12,"helmet","old helmet","stone64");exchange(39,10,5,"helmet","empty","stone64");exchange(10,11,12,"stone20","stone8","stone64");}
    @Test void emptyEndpointsAndBuffersRemainExact(){exchange(10,39,12,"helmet","empty","empty");exchange(39,10,5,"helmet","empty","empty");}
    @Test void armorNeverReceivesTheUnrelatedBufferItem(){var plan=InventorySwapPlan.plan(5,12,39,10,12,3);assertEquals(12,plan.get(0).slot());assertEquals(5,plan.get(1).slot());assertEquals(12,plan.get(2).slot());}
    @Test void invalidSlotsAndBuffersAreRejected(){assertThrows(BridgeException.class,()->InventorySwapPlan.plan(5,5,10,11,5,3));assertThrows(BridgeException.class,()->InventorySwapPlan.plan(5,12,10,11,9,3));assertThrows(BridgeException.class,()->InventorySwapPlan.plan(5,12,10,11,5,40));}
}
