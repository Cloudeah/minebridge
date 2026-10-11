package dev.minebridge.core;

import com.google.gson.JsonObject;
import static dev.minebridge.core.Json.*;

/** Separates observed use from a confirmed change to the held item. */
public final class ItemUseEvidence {
    private ItemUseEvidence() {}
    public static boolean using(JsonObject view,String hand){
        return bool(view,"using_item",false) && hand.equals(string(view,"use_hand","none"));
    }
    public static boolean itemChanged(JsonObject before,JsonObject after,String hand){
        return !before.get(hand+"_hand").equals(after.get(hand+"_hand"));
    }
    public static boolean recordedUse(JsonObject before,JsonObject after){
        return number(after,"item_uses",0)>number(before,"item_uses",0);
    }
    public static JsonObject summary(JsonObject view,String hand){
        return object("held",view.get(hand+"_hand"),"using_item",view.get("using_item"),
                "food",view.get("food"),"health",view.get("health"),"item_uses",view.get("item_uses"));
    }
}
