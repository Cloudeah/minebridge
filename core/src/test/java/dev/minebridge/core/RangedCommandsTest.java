package dev.minebridge.core;

import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class RangedCommandsTest {
  private JsonObject target() {
    return object("entity_id", "418f10e9-bc78-4d9c-bb26-6a9cfa352b69");
  }

  @Test
  void defaultSingleShotWorksForBothWeapons() {
    assertDoesNotThrow(() -> RangedCommands.validate(target(), false));
    assertDoesNotThrow(() -> RangedCommands.validate(target(), true));
  }

  @Test
  void vanillaCrossbowTimingCannotBeOverriddenWithBowCharging() {
    var p = target();
    p.addProperty("charge_ticks", 20);
    assertDoesNotThrow(() -> RangedCommands.validate(p, false));
    assertThrows(BridgeException.class, () -> RangedCommands.validate(p, true));
  }

  @Test
  void seriesPursuitAndOffhandAreRejected() {
    for (String field : new String[] {"shots", "count", "chase", "hand"}) {
      var p = target();
      p.addProperty(field, "off");
      assertThrows(BridgeException.class, () -> RangedCommands.validate(p, false));
    }
  }

  @Test
  void chargingWaitAndDistanceBoundsAreEnforced() {
    for (String field : new String[] {"charge_ticks", "wait_ticks", "max_distance"}) {
      for (double n : new double[] {0, 12000}) {
        var p = target();
        p.addProperty(field, n);
        assertThrows(BridgeException.class, () -> RangedCommands.validate(p, false));
      }
    }
    var p = target();
    p.addProperty("charge_ticks", 60);
    p.addProperty("wait_ticks", 100);
    p.addProperty("max_distance", 32);
    assertDoesNotThrow(() -> RangedCommands.validate(p, false));
  }
}
