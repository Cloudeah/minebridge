package dev.minebridge.core;

import static dev.minebridge.core.Json.*;

import com.google.gson.JsonObject;
import java.util.*;

/** Idle survival decisions: preserve owner goals and already equipped items. */
public final class CompanionCare {
  private static final Set<String> ARMOR = Set.of("head", "chest", "legs", "feet");

  public record Armor(
      int slot,
      String equipment,
      double protection,
      double toughness,
      int remaining,
      boolean binding) {}

  private CompanionCare() {}

  public static boolean available(
      boolean autonomous, boolean pending, JsonObject model, String autonomousGoalId) {
    return autonomous
        && !pending
        && (!Set.of("queued", "thinking", "executing").contains(string(model, "state", "idle"))
            || autonomousGoalId != null && autonomousGoalId.equals(string(model, "goal_id", "")));
  }

  public static boolean regroup(double distance, boolean sameDimension, boolean visible) {
    return sameDimension && visible && Double.isFinite(distance) && distance > 24 && distance <= 96;
  }

  public static Optional<Armor> armor(Collection<Armor> candidates, Set<String> occupied) {
    return candidates.stream()
        .filter(
            c ->
                c.slot >= 0
                    && c.slot < 36
                    && c.equipment != null
                    && ARMOR.contains(c.equipment)
                    && !occupied.contains(c.equipment)
                    && !c.binding
                    && c.remaining != 0
                    && Double.isFinite(c.protection)
                    && c.protection > 0
                    && Double.isFinite(c.toughness)
                    && c.toughness >= 0)
        .min(
            Comparator.comparingDouble(Armor::protection)
                .reversed()
                .thenComparing(Comparator.comparingDouble(Armor::toughness).reversed())
                .thenComparing(
                    Comparator.comparingInt(
                            (Armor c) -> c.remaining < 0 ? Integer.MAX_VALUE : c.remaining)
                        .reversed())
                .thenComparingInt(Armor::slot));
  }
}
