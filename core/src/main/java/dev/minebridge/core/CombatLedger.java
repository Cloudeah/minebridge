package dev.minebridge.core;

import java.util.*;

/** Attributes server-confirmed combat events to one actor, dimension, and task. */
public final class CombatLedger {
    private static final int MAX_TASKS = 16;
    private static final long SHOT_WINDOW_TICKS = 20;

    private final String actor;
    private final String dimension;
    private final LinkedHashMap<String, Receipt> receipts = new LinkedHashMap<>();

    public CombatLedger(String actor, String dimension) {
        this.actor = requireText(actor, "actor");
        this.dimension = requireText(dimension, "dimension");
    }

    public synchronized void begin(String taskId, String target, String mode, long tick) {
        taskId = requireText(taskId, "taskId");
        target = requireText(target, "target");
        if (!"melee".equals(mode) && !"bow".equals(mode))
            throw new BridgeException("invalid_request", "Combat mode must be melee or bow");
        if (!receipts.containsKey(taskId) && receipts.size() >= MAX_TASKS)
            throw new BridgeException("limit_exceeded", "Combat ledger task limit reached");
        // Reusing a task ID starts a fresh receipt without disturbing other tasks.
        receipts.put(taskId, new Receipt(target, mode, tick));
    }

    /** Opens an inclusive [tick, tick + 20] window for the next owned arrow. */
    public synchronized boolean expectShot(String taskId, long tick) {
        Receipt receipt = receipts.get(taskId);
        if (receipt == null || !receipt.mode.equals("bow") || receipt.shots > 0
                || receipt.projectileId != null || receipt.hits > 0 || tick < receipt.beginTick)
            return false;
        receipt.awaitingShot = true;
        receipt.windowStart = tick;
        receipt.windowEnd = tick > Long.MAX_VALUE - SHOT_WINDOW_TICKS ? Long.MAX_VALUE : tick + SHOT_WINDOW_TICKS;
        receipt.tick = tick;
        return true;
    }

    /** Opens an inclusive [tick, tick + 20] window for one commanded melee hit. */
    public synchronized boolean expectMelee(String taskId, long tick) {
        Receipt receipt = receipts.get(taskId);
        if (receipt == null || !receipt.mode.equals("melee") || receipt.defeated || tick < receipt.beginTick)
            return false;
        receipt.awaitingMelee = true;
        receipt.meleeWindowStart = tick;
        receipt.meleeWindowEnd = tick > Long.MAX_VALUE - SHOT_WINDOW_TICKS ? Long.MAX_VALUE : tick + SHOT_WINDOW_TICKS;
        receipt.tick = tick;
        return true;
    }

    /** Cancels pending melee attribution while leaving already fired arrows intact. */
    public synchronized void suspendMelee() {
        for (Receipt receipt : receipts.values()) {
            if (receipt.mode.equals("melee")) receipt.awaitingMelee = false;
        }
    }

    /** Binds an owned, fresh projectile to the unique bow task waiting in its shot window. */
    public synchronized boolean recordShot(String owner, String dimension, String projectile, long tick) {
        String projectileUuid = canonicalUuid(projectile);
        if (!this.actor.equals(owner) || !this.dimension.equals(dimension) || projectileUuid == null) return false;
        Receipt candidate = null;
        for (Receipt receipt : receipts.values()) {
            if (!receipt.mode.equals("bow") || !receipt.awaitingShot || receipt.hits > 0
                    || tick < receipt.beginTick || tick < receipt.windowStart || tick > receipt.windowEnd) continue;
            if (receipt.projectileTicks.containsKey(projectileUuid)) return false;
            if (candidate != null) return false; // The event cannot be assigned safely to one task.
            candidate = receipt;
        }
        if (candidate == null || isProjectileBound(projectileUuid)) return false;
        candidate.awaitingShot = false;
        candidate.projectileId = projectileUuid;
        candidate.projectileTicks.put(projectileUuid, tick);
        candidate.shots = increment(candidate.shots);
        candidate.tick = tick;
        return true;
    }

    /** Records damage only when its actor, target, mode, and direct entity match the receipt. */
    public synchronized boolean recordDamage(String attacker, String dimension, String target,
                                             String directEntityId, boolean projectile, double damage,
                                             double remainingHealth, long tick) {
        if (!actor.equals(attacker) || !this.dimension.equals(dimension) || !Double.isFinite(damage) || damage <= 0
                || !Double.isFinite(remainingHealth))
            return false;
        if (directEntityId == null) return false;
        String directProjectile = projectile ? canonicalUuid(directEntityId) : null;
        if (projectile && directProjectile == null) return false;

        Receipt receipt = null;
        String taskProjectile = null;
        for (Receipt candidate : receipts.values()) {
            if (!candidate.target.equals(target) || tick < candidate.beginTick || candidate.defeated) continue;
            if (candidate.mode.equals("melee")) {
                if (projectile || !actor.equals(directEntityId) || !candidate.awaitingMelee
                        || tick < candidate.meleeWindowStart || tick > candidate.meleeWindowEnd) continue;
            } else {
                if (!projectile || candidate.hits > 0) continue;
                Long shotTick = candidate.projectileTicks.get(directProjectile);
                if (shotTick == null || tick < shotTick) continue;
                taskProjectile = directProjectile;
            }
            if (receipt != null) return false; // Avoid attributing an ambiguous hit to the wrong task.
            receipt = candidate;
        }
        if (receipt == null) return false;

        if (receipt.mode.equals("melee")) receipt.awaitingMelee = false;
        receipt.hits = increment(receipt.hits);
        double totalDamage = receipt.damage + damage;
        receipt.damage = Double.isFinite(totalDamage) ? totalDamage : Double.MAX_VALUE;
        receipt.remainingHealth = remainingHealth;
        if (remainingHealth <= 0) receipt.defeated = true;
        receipt.tick = tick;
        if (taskProjectile != null) receipt.projectileId = taskProjectile;
        return true;
    }

    public synchronized Snapshot peek(String taskId) {
        Receipt receipt = receipts.get(taskId);
        return receipt == null ? null : receipt.snapshot();
    }

    /** Atomically snapshots and removes a terminal task receipt. */
    public synchronized Snapshot finish(String taskId) {
        Receipt receipt = receipts.remove(taskId);
        return receipt == null ? null : receipt.snapshot();
    }

    public synchronized void end(String taskId) {
        if (taskId != null) receipts.remove(taskId);
    }

    private boolean isProjectileBound(String projectile) {
        for (Receipt receipt : receipts.values()) if (receipt.projectileTicks.containsKey(projectile)) return true;
        return false;
    }

    private static String canonicalUuid(String value) {
        if (value == null) return null;
        try {
            String canonical = UUID.fromString(value).toString();
            return canonical.equalsIgnoreCase(value) ? canonical : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int increment(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 4096)
            throw new BridgeException("invalid_request", "Invalid " + name);
        return value;
    }

    public record Snapshot(int shots, int hits, double damage, double remainingHealth,
                           long tick, String projectileId, boolean defeated) {}

    private static final class Receipt {
        final String target;
        final String mode;
        final long beginTick;
        final Map<String, Long> projectileTicks = new HashMap<>();
        int shots;
        int hits;
        double damage;
        double remainingHealth = -1;
        long tick;
        long windowStart;
        long windowEnd;
        long meleeWindowStart;
        long meleeWindowEnd;
        String projectileId;
        boolean awaitingShot;
        boolean awaitingMelee;
        boolean defeated;

        Receipt(String target, String mode, long beginTick) {
            this.target = target;
            this.mode = mode;
            this.beginTick = beginTick;
            this.tick = beginTick;
        }

        Snapshot snapshot() {
            return new Snapshot(shots, hits, damage, remainingHealth, tick, projectileId, defeated);
        }
    }
}
