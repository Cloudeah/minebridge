package dev.minebridge.core;

import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CompanionCareTest {
  private CompanionCare.Armor armor(int slot, String place, double protection, int remaining) {
    return new CompanionCare.Armor(slot, place, protection, 0, remaining, false);
  }

  @Test
  void idleCareRequiresAutonomyAndNoPendingTask() {
    assertTrue(CompanionCare.available(true, false, object("state", "idle"), null));
    assertFalse(CompanionCare.available(false, false, object("state", "idle"), null));
    assertFalse(CompanionCare.available(true, true, object("state", "idle"), null));
  }

  @Test
  void explicitOwnerGoalsHavePriorityInEveryActiveState() {
    for (String state : List.of("queued", "thinking", "executing")) {
      assertFalse(
          CompanionCare.available(
              true, false, object("state", state, "goal_id", "explicit"), "autonomous"));
      assertTrue(
          CompanionCare.available(
              true, false, object("state", state, "goal_id", "autonomous"), "autonomous"));
    }
    assertTrue(
        CompanionCare.available(
            true, false, object("state", "completed", "goal_id", "explicit"), "autonomous"));
  }

  @Test
  void identicalGoalTextCannotImpersonateAnAutonomousCycle() {
    var model = object("state", "thinking", "goal", CompanionProfile.GOAL, "goal_id", "explicit");
    assertFalse(CompanionCare.available(true, false, model, "autonomous"));
    assertFalse(CompanionCare.available(true, false, model, null));
  }

  @Test
  void regroupHasHysteresisAndLoadedLocalRange() {
    assertFalse(CompanionCare.regroup(24, true, true));
    assertTrue(CompanionCare.regroup(24.01, true, true));
    assertTrue(CompanionCare.regroup(96, true, true));
    assertFalse(CompanionCare.regroup(96.01, true, true));
    assertFalse(CompanionCare.regroup(30, false, true));
    assertFalse(CompanionCare.regroup(30, true, false));
    assertFalse(CompanionCare.regroup(Double.NaN, true, true));
  }

  @Test
  void equipmentNeverReplacesAnOccupiedSlot() {
    assertTrue(CompanionCare.armor(List.of(armor(0, "head", 3, 200)), Set.of("head")).isEmpty());
    assertEquals(
        "chest",
        CompanionCare.armor(
                List.of(armor(0, "head", 3, 200), armor(1, "chest", 8, 300)), Set.of("head"))
            .orElseThrow()
            .equipment());
  }

  @Test
  void choosesActualProtectionThenToughnessDurabilityAndStableSlot() {
    var iron = armor(5, "chest", 6, 200);
    var diamond = new CompanionCare.Armor(8, "chest", 8, 2, 100, false);
    assertEquals(diamond, CompanionCare.armor(List.of(iron, diamond), Set.of()).orElseThrow());
    assertEquals(
        2,
        CompanionCare.armor(
                List.of(armor(1, "head", 2, 1), armor(2, "head", 2, 50), armor(3, "head", 2, 50)),
                Set.of())
            .orElseThrow()
            .slot());
  }

  @Test
  void excludesBindingBrokenInvalidAndNonArmorCandidates() {
    var invalid =
        List.of(
            new CompanionCare.Armor(0, "head", 3, 0, 200, true),
            armor(1, "head", 3, 0),
            armor(2, "body", 5, 50),
            armor(36, "head", 2, 50),
            armor(3, "head", Double.NaN, 50),
            new CompanionCare.Armor(4, "feet", 2, Double.POSITIVE_INFINITY, 50, false));
    assertTrue(CompanionCare.armor(invalid, Set.of()).isEmpty());
  }
}
