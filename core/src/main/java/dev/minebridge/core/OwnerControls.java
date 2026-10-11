package dev.minebridge.core;

import com.google.gson.JsonObject;

/** Owner UI access; server actors supply a queued proxy and immutable status copies. */
public interface OwnerControls {
  boolean armed();

  JsonObject modelStatus();

  void ownerArm();

  void ownerStop();

  void ownerModelStart(String goal, int steps, boolean vision);

  default String actorMode() {
    return "existing_player";
  }

  default boolean visionAvailable() {
    return true;
  }
}
