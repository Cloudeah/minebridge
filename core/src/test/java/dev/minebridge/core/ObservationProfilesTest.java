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
        assertEquals(4,result.getAsJsonArray("entities").size());assertEquals(17,result.getAsJsonArray("inventory").get(0).getAsJsonObject().get("slot").getAsInt());
        JsonObject context=result.getAsJsonObject("context");assertEquals(8,context.get("menu_id").getAsInt());assertEquals(99,context.get("menu_revision").getAsInt());
        assertEquals(5,context.getAsJsonArray("menu_slots").size());assertEquals(50,context.getAsJsonArray("menu_slots").get(4).getAsJsonObject().get("slot").getAsInt());
        assertEquals(7,result.get("control_epoch").getAsInt());assertEquals("unknown",result.getAsJsonObject("observation_limits").get("missing_data").getAsString());
    }
    @Test void fullProfileDoesNotChangeExistingShape(){JsonObject source=object("actor",object("health",20));assertEquals(source,ObservationProfiles.apply(source,"full"));}
    @Test void unknownProfileIsRejected(){assertEquals("unsupported_capability",assertThrows(BridgeException.class,()->ObservationProfiles.apply(object(),"huge")).code);}
}
