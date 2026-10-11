package dev.minebridge.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CompanionThreatsTest {
  private final CompanionThreats threats = new CompanionThreats();

  private CompanionThreats.Candidate mob(String id, double distance, boolean owner, boolean self) {
    return new CompanionThreats.Candidate(id, false, false, true, true, distance, 3, owner, self);
  }

  @Test
  void peacefulNearbyAnimalsAreNotThreats() {
    assertTrue(threats.select(1, List.of(mob("pig", 1, false, false)), null).isEmpty());
  }

  @Test
  void currentMobTargetCanTriggerDefenceBeforeFirstHit() {
    assertEquals(
        "owner_targeted",
        threats.select(1, List.of(mob("zombie", 3, true, false)), null).orElseThrow().reason());
    assertEquals(
        "self_targeted",
        threats.select(1, List.of(mob("zombie", 3, false, true)), null).orElseThrow().reason());
  }

  @Test
  void actualOwnerDamageHasPriorityOverCloserSelfThreats() {
    threats.record("archer", true, 2, 1);
    var selected =
        threats
            .select(
                2,
                List.of(mob("zombie", 1, false, true), mob("archer", 10, false, false)),
                "zombie")
            .orElseThrow();
    assertEquals("archer", selected.id());
    assertEquals("owner_hurt", selected.reason());
  }

  @Test
  void selfHitCannotEraseOwnerDamageEvidence() {
    threats.record("zombie", true, 2, 1);
    threats.record("zombie", false, 2, 2);
    assertEquals(
        "owner_hurt",
        threats.select(3, List.of(mob("zombie", 3, false, false)), null).orElseThrow().reason());
  }

  @Test
  void playersAlliesDeadInvisibleAndFarTargetsAreExcludedEvenAfterDamage() {
    threats.record("target", true, 2, 1);
    for (var candidate :
        List.of(
            new CompanionThreats.Candidate("target", true, false, true, true, 1, 1, true, true),
            new CompanionThreats.Candidate("target", false, true, true, true, 1, 1, true, true),
            new CompanionThreats.Candidate("target", false, false, false, true, 1, 1, true, true),
            new CompanionThreats.Candidate("target", false, false, true, false, 1, 1, true, true),
            new CompanionThreats.Candidate("target", false, false, true, true, 12.1, 1, true, true),
            new CompanionThreats.Candidate(
                "target", false, false, true, true, 1, 12.1, true, false),
            mob("target", Double.NaN, true, true)))
      assertTrue(threats.select(2, List.of(candidate), null).isEmpty(), candidate.toString());
  }

  @Test
  void oldAndFutureDamageCannotRestartAChase() {
    threats.record("zombie", true, 2, 1);
    assertTrue(threats.select(202, List.of(mob("zombie", 3, false, false)), null).isEmpty());
    threats.record("zombie", true, 2, 20);
    assertTrue(threats.select(10, List.of(mob("zombie", 3, false, false)), null).isEmpty());
  }

  @Test
  void zeroCancelledAndInvalidDamageCreateNoEvidence() {
    for (double amount : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
      threats.record("zombie", true, amount, 1);
    assertTrue(threats.select(2, List.of(mob("zombie", 3, false, false)), null).isEmpty());
  }

  @Test
  void currentTargetIsStableUntilHigherPriorityThreatAppears() {
    var candidates = List.of(mob("near", 1, true, false), mob("active", 6, true, false));
    assertEquals("active", threats.select(2, candidates, "active").orElseThrow().id());
    threats.record("near", true, 2, 3);
    assertEquals("near", threats.select(3, candidates, "active").orElseThrow().id());
  }

  @Test
  void cooldownStopsImmediateRetriesButNewRealDamageCanOverrideIt() {
    threats.record("zombie", true, 2, 1);
    threats.cooldown("zombie", 2);
    var candidates = List.of(mob("zombie", 3, true, false));
    assertTrue(threats.select(3, candidates, null).isEmpty());
    threats.record("zombie", true, 2, 4);
    assertTrue(threats.select(4, candidates, null).isPresent());
  }

  @Test
  void newSelfDamageOverridesCooldownWithoutErasingOwnerPriority() {
    threats.record("zombie", true, 2, 1);
    threats.cooldown("zombie", 2);
    threats.record("zombie", false, 2, 4);
    assertEquals(
        "owner_hurt",
        threats.select(4, List.of(mob("zombie", 3, false, false)), null).orElseThrow().reason());
  }

  @Test
  void evidenceCapacityIsBoundedAndNearestThreatWins() {
    for (int i = 0; i < 33; i++) threats.record("mob" + i, true, 2, i);
    assertTrue(threats.select(33, List.of(mob("mob0", 1, false, false)), null).isEmpty());
    assertEquals(
        "mob32",
        threats
            .select(33, List.of(mob("mob1", 5, false, false), mob("mob32", 1, false, false)), null)
            .orElseThrow()
            .id());
  }
}
