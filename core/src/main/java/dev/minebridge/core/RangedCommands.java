package dev.minebridge.core;

import static dev.minebridge.core.Json.*;

import com.google.gson.JsonObject;
import java.util.UUID;

/** Shared contract for one main-hand bow/crossbow shot without pursuit. */
public final class RangedCommands {
  private RangedCommands() {}

  public static void validate(JsonObject p, boolean crossbow) {
    UUID.fromString(string(p, "entity_id"));
    integer(p, "charge_ticks", 20, 20, 60);
    integer(p, "wait_ticks", 40, 10, 100);
    double range = number(p, "max_distance", 24);
    if (range < 1 || range > 32)
      throw new BridgeException("invalid_request", "max_distance must be 1..32");
    if (!string(p, "hand", "main").equals("main"))
      throw new BridgeException("invalid_request", "Ranged combat supports the main hand only");
    if (p.has("shots") || p.has("count") || p.has("chase"))
      throw new BridgeException("invalid_request", "combat.ranged fires one arrow without chasing");
    if (crossbow && p.has("charge_ticks"))
      throw new BridgeException(
          "invalid_request", "charge_ticks applies to bows; crossbows use vanilla loading time");
  }
}
