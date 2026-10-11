package dev.minebridge.core;

/** Read-only verification around Minecraft's actual item/player collision. */
public record PickupAttempt(String item,int groundBefore,int inventoryBefore,long tick) {
    public int collected(String currentItem,int groundAfter,int inventoryAfter,long currentTick,boolean entityPresent){
        // Disappearance or an inventory change alone cannot identify who picked up an item.
        if(!entityPresent || !item.equals(currentItem) || currentTick!=tick || groundAfter<0)return 0;
        int removed=groundBefore-groundAfter,gained=inventoryAfter-inventoryBefore;
        return removed>0 && removed==gained?removed:0;
    }
}
