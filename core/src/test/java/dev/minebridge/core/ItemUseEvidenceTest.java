package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.minebridge.core.Json.*;

class ItemUseEvidenceTest {
    @Test void usingOtherHandDoesNotConfirmRequestedUse(){
        var view=object("using_item",true,"use_hand","off");
        assertTrue(ItemUseEvidence.using(view,"off"));assertFalse(ItemUseEvidence.using(view,"main"));
        view.addProperty("using_item",false);assertFalse(ItemUseEvidence.using(view,"off"));
    }
    @Test void HeldCountDamageOrTypeChangesAreEvidenceButHungerAloneIsNot(){
        var before=object("main_hand",object("type","minecraft:apple","count",2,"damage",0),"food",10,"item_uses",0);
        var after=before.deepCopy();after.addProperty("food",14);
        assertFalse(ItemUseEvidence.itemChanged(before,after,"main"));assertFalse(ItemUseEvidence.recordedUse(before,after));
        after.getAsJsonObject("main_hand").addProperty("count",1);assertTrue(ItemUseEvidence.itemChanged(before,after,"main"));
        after=before.deepCopy();after.getAsJsonObject("main_hand").addProperty("damage",1);assertTrue(ItemUseEvidence.itemChanged(before,after,"main"));
        after=before.deepCopy();after.getAsJsonObject("main_hand").addProperty("type","minecraft:bowl");assertTrue(ItemUseEvidence.itemChanged(before,after,"main"));
        after=before.deepCopy();after.addProperty("item_uses",1);assertTrue(ItemUseEvidence.recordedUse(before,after));
    }
}
