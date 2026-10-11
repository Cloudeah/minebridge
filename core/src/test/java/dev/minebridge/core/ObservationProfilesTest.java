package dev.minebridge.core;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservationProfilesTest {
    @Test void compactChoosesNearestObjectsAndPreservesSlotIdsAndEpoch() {
        JsonArray blocks=new JsonArray(),entities=new JsonArray(),slots=new JsonArray();
        for(int i=20;i>0;i--){blocks.add(object("x",i,"y",64,"z",0,"type","minecraft:stone"));entities.add(object("entity_id","entity-"+i,"position",object("x",i,"y",64,"z",0)));}
        for(int i=0;i<60;i++)slots.add(object("slot",i,"type",i==50?"minecraft:stick":"minecraft:air","count",i==50?2:0,"player_inventory",i>=27));
        JsonObject source=object("control_epoch",7,"revision",42,"actor",object("position",object("x",0,"y",64,"z",0)),
                "blocks",blocks,"entities",entities,"inventory",List.of(object("slot",17,"type","minecraft:stick","count",2),object("slot",0,"type","minecraft:air","count",0)),
                "context",object("menu_id",8,"menu_revision",99,"menu_slots",slots));
        String original=source.toString();JsonObject result=ObservationProfiles.apply(source,"compact");
        assertEquals(original,source.toString());assertEquals(6,result.getAsJsonArray("blocks").size());
        assertEquals(1,result.getAsJsonArray("blocks").get(0).getAsJsonObject().get("x").getAsInt());
        assertEquals("entity-1",result.getAsJsonArray("entities").get(0).getAsJsonObject().get("entity_id").getAsString());
        assertEquals(4,result.getAsJsonArray("entities").size());assertEquals(1,result.getAsJsonArray("inventory").size());assertEquals(17,result.getAsJsonArray("inventory").get(0).getAsJsonObject().get("slot").getAsInt());
        JsonObject context=result.getAsJsonObject("context");assertEquals(8,context.get("menu_id").getAsInt());assertEquals(99,context.get("menu_revision").getAsInt());
        assertEquals(5,context.getAsJsonArray("menu_slots").size());for(int i=0;i<4;i++)assertEquals(i,context.getAsJsonArray("menu_slots").get(i).getAsJsonObject().get("slot").getAsInt());assertEquals(50,context.getAsJsonArray("menu_slots").get(4).getAsJsonObject().get("slot").getAsInt());
        assertEquals(7,result.get("control_epoch").getAsInt());assertEquals("unknown",result.getAsJsonObject("observation_limits").get("missing_data").getAsString());
    }
    @Test void compactMenuSpendsEmptySlotBudgetOnlyOnUsableDestinationsAndKeepsPublicRoles() {
        JsonArray menu=new JsonArray();
        menu.add(menuSlot(0,"craft_grid",0,"minecraft:air",null));menu.add(menuSlot(1,"armor",0,"minecraft:air",36));
        menu.add(menuSlot(2,"offhand",0,"minecraft:air",40));menu.add(menuSlot(3,"craft_output",0,"minecraft:air",null));
        menu.add(menuSlot(4,"player_special",0,"minecraft:air",41));menu.add(menuSlot(5,"main",0,"minecraft:air",10));
        menu.add(menuSlot(6,"hotbar",0,"minecraft:air",3));menu.add(menuSlot(7,"container",0,"minecraft:air",null));
        menu.add(menuSlot(8,"main",0,"minecraft:air",11));menu.add(menuSlot(9,"hotbar",0,"minecraft:air",4));
        menu.add(menuSlot(10,"armor",1,"minecraft:iron_helmet",39));
        JsonObject source=object("actor",object("position",object("x",0,"y",64,"z",0)),"context",object("menu_slots",menu));
        JsonArray compact=ObservationProfiles.apply(source,"compact").getAsJsonObject("context").getAsJsonArray("menu_slots");
        assertEquals(5,compact.size());
        int[] expectedSlots={5,6,7,8,10};String[] expectedRoles={"main","hotbar","container","main","armor"};
        for(int i=0;i<expectedSlots.length;i++){JsonObject slot=compact.get(i).getAsJsonObject();assertEquals(expectedSlots[i],slot.get("slot").getAsInt());assertEquals(expectedRoles[i],slot.get("role").getAsString());}
        assertEquals(10,compact.get(0).getAsJsonObject().get("inventory_slot").getAsInt());assertEquals(3,compact.get(1).getAsJsonObject().get("inventory_slot").getAsInt());
        assertFalse(compact.get(2).getAsJsonObject().has("inventory_slot"));assertEquals(11,compact.get(3).getAsJsonObject().get("inventory_slot").getAsInt());
        assertEquals(39,compact.get(4).getAsJsonObject().get("inventory_slot").getAsInt());assertEquals(1,compact.get(4).getAsJsonObject().get("count").getAsInt());
    }
    @Test void fullProfileDoesNotChangeExistingShape(){JsonObject source=object("actor",object("health",20));assertEquals(source,ObservationProfiles.apply(source,"full"));}
    @Test void unknownProfileIsRejected(){assertEquals("unsupported_capability",assertThrows(BridgeException.class,()->ObservationProfiles.apply(object(),"huge")).code);}
    private static JsonObject menuSlot(int index,String role,int count,String type,Integer inventorySlot){JsonObject slot=object("slot",index,"role",role,"count",count,"type",type);if(inventorySlot!=null)slot.addProperty("inventory_slot",inventorySlot);return slot;}
}
