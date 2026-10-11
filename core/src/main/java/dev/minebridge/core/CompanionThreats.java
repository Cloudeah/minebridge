package dev.minebridge.core;

import java.util.*;

/** Bounded, short-lived evidence of actual attacks and current mob targets. */
public final class CompanionThreats {
  public static final int MEMORY_TICKS = 200;
  private static final int LIMIT = 32;

  public record Candidate(
      String id,
      boolean player,
      boolean allied,
      boolean alive,
      boolean visible,
      double distance,
      double ownerDistance,
      boolean targetsOwner,
      boolean targetsCompanion) {}

  public record Threat(String id, String reason, int priority) {}

  private record Damage(long ownerTick, long selfTick) {}

  private final Map<String, Damage> damage = new LinkedHashMap<>();
  private final Map<String, Long> retries = new LinkedHashMap<>();

  public void record(String attacker, boolean owner, double amount, long tick) {
    if (attacker == null
        || attacker.isBlank()
        || tick < 0
        || !Double.isFinite(amount)
        || amount <= 0) return;
    Damage previous = damage.getOrDefault(attacker, new Damage(-1, -1));
    damage.remove(attacker);
    damage.put(
        attacker, new Damage(owner ? tick : previous.ownerTick, owner ? previous.selfTick : tick));
    trim(damage);
  }

  public void cooldown(String target, long tick) {
    retries.remove(target);
    retries.put(target, tick + 100);
    trim(retries);
  }

  public Optional<Threat> select(long tick, Collection<Candidate> candidates, String preferred) {
    damage.replaceAll(
        (id, evidence) ->
            new Damage(validTick(evidence.ownerTick, tick), validTick(evidence.selfTick, tick)));
    damage.entrySet().removeIf(e -> e.getValue().ownerTick < 0 && e.getValue().selfTick < 0);
    retries.entrySet().removeIf(e -> tick >= e.getValue());
    return candidates.stream()
        .filter(c -> eligible(c, tick))
        .flatMap(
            c -> {
              Threat threat = classify(c);
              return threat == null
                  ? java.util.stream.Stream.empty()
                  : java.util.stream.Stream.of(Map.entry(c, threat));
            })
        .min(
            Comparator.<Map.Entry<Candidate, Threat>>comparingInt(e -> e.getValue().priority)
                .thenComparingInt(e -> Objects.equals(e.getKey().id, preferred) ? 0 : 1)
                .thenComparingDouble(e -> e.getKey().distance)
                .thenComparing(e -> e.getKey().id))
        .map(Map.Entry::getValue);
  }

  private boolean eligible(Candidate c, long tick) {
    if (c.id == null
        || c.id.isBlank()
        || c.player
        || c.allied
        || !c.alive
        || !c.visible
        || !Double.isFinite(c.distance)
        || c.distance < 0
        || c.distance > 12
        || !Double.isFinite(c.ownerDistance)
        || c.ownerDistance < 0) return false;
    Long until = retries.get(c.id);
    Damage recent = damage.get(c.id);
    return until == null
        || recent != null && Math.max(recent.ownerTick, recent.selfTick) > until - 100;
  }

  private Threat classify(Candidate c) {
    Damage recent = damage.get(c.id);
    if (recent != null && recent.ownerTick >= 0 && c.ownerDistance <= 12)
      return new Threat(c.id, "owner_hurt", 0);
    if (c.targetsOwner && c.ownerDistance <= 12) return new Threat(c.id, "owner_targeted", 1);
    if (recent != null && recent.selfTick >= 0) return new Threat(c.id, "self_hurt", 2);
    if (c.targetsCompanion) return new Threat(c.id, "self_targeted", 3);
    return null;
  }

  private static void trim(Map<String, ?> map) {
    while (map.size() > LIMIT) map.remove(map.keySet().iterator().next());
  }

  private static long validTick(long evidence, long now) {
    return evidence >= 0 && evidence <= now && now - evidence <= MEMORY_TICKS ? evidence : -1;
  }
}
