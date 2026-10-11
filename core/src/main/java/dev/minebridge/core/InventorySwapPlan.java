package dev.minebridge.core;

import java.util.List;

/** Whole-stack exchanges use vanilla SWAP packets; no stack is put on the cursor. */
public final class InventorySwapPlan {
    public record Click(int slot,int inventoryButton) {}
    private InventorySwapPlan() {}
    public static boolean direct(int inventorySlot){return inventorySlot>=0 && inventorySlot<=8 || inventorySlot==40;}
    public static List<Click> plan(int from,int to,int sourceInventory,int targetInventory,int staging,int bufferInventory) {
        if(from<0 || to<0 || from==to)throw new BridgeException("invalid_request","Choose two different menu slots");
        if(direct(sourceInventory))return List.of(new Click(to,sourceInventory));
        if(direct(targetInventory))return List.of(new Click(from,targetInventory));
        if((staging!=from && staging!=to) || bufferInventory<0 || bufferInventory>8)
            throw new BridgeException("inventory_full","No suitable hotbar buffer for the exchange");
        int other=staging==from?to:from;
        return List.of(new Click(staging,bufferInventory),new Click(other,bufferInventory),new Click(staging,bufferInventory));
    }
}
