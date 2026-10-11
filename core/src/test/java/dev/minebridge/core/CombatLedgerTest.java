package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CombatLedgerTest {
    private static final String ACTOR = "actor-1";
    private static final String DIMENSION = "minecraft:overworld";

    @Test void meleeRequiresOwnedTargetDirectDamageAndClosesEachExpectedHit() {
        CombatLedger ledger = new CombatLedger(ACTOR, DIMENSION);
        ledger.begin("melee-1", "target-1", "melee", 10);
        CombatLedger.Snapshot initial = ledger.peek("melee-1");
        assertEquals(0, initial.shots());
        assertEquals(0, initial.hits());
        assertEquals(0, initial.damage());
        assertEquals(-1, initial.remainingHealth());
        assertFalse(initial.defeated());

        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-1", ACTOR, false, 2, 0, 10));
        assertTrue(ledger.expectMelee("melee-1", 10));
        assertFalse(damage(ledger, "stranger", DIMENSION, "target-1", ACTOR, false, 2, 0, 10));
        assertFalse(damage(ledger, ACTOR, "minecraft:the_nether", "target-1", ACTOR, false, 2, 0, 10));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "other-target", ACTOR, false, 2, 0, 10));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-1", "another-entity", false, 2, 0, 10));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-1", ACTOR, true, 2, 0, 10));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-1", ACTOR, false, 0, 0, 10));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-1", ACTOR, false, Double.NaN, 0, 10));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-1", ACTOR, false, Double.POSITIVE_INFINITY, 0, 10));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-1", ACTOR, false, 2, 0, 9));
        assertEquals(-1, ledger.peek("melee-1").remainingHealth());
        assertFalse(ledger.peek("melee-1").defeated());

        assertTrue(damage(ledger, ACTOR, DIMENSION, "target-1", ACTOR, false, 3.5, 8.5, 11));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-1", ACTOR, false, 3.5, 5, 11));
        assertTrue(ledger.expectMelee("melee-1", 12));
        assertTrue(damage(ledger, ACTOR, DIMENSION, "target-1", ACTOR, false, 1.5, 7, 12));
        CombatLedger.Snapshot result = ledger.peek("melee-1");
        assertEquals(2, result.hits());
        assertEquals(5, result.damage());
        assertEquals(7, result.remainingHealth());
        assertEquals(12, result.tick());
        assertNull(result.projectileId());
        assertFalse(result.defeated());
    }

    @Test void onlyAnAcceptedLethalHitMarksTheTargetDefeated() {
        CombatLedger ledger = new CombatLedger(ACTOR, DIMENSION);
        ledger.begin("melee", "target", "melee", 3);
        assertFalse(damage(ledger, "other", DIMENSION, "target", ACTOR, false, 2, 0, 4));
        assertFalse(ledger.peek("melee").defeated());
        assertEquals(-1, ledger.peek("melee").remainingHealth());
        assertTrue(ledger.expectMelee("melee", 4));
        assertTrue(damage(ledger, ACTOR, DIMENSION, "target", ACTOR, false, 2, 0, 4));
        assertTrue(ledger.peek("melee").defeated());
        assertEquals(0, ledger.peek("melee").remainingHealth());
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target", ACTOR, false, 1, 2, 5));
        assertTrue(ledger.peek("melee").defeated());
    }

    @Test void bowDamageRequiresOneFreshOwnedArrowAndCanConfirmAtMostOneHit() {
        CombatLedger ledger = new CombatLedger(ACTOR, DIMENSION);
        String arrow = uuid("arrow-one");
        ledger.begin("bow-1", "target-1", "bow", 100);
        assertFalse(ledger.expectShot("missing", 100));
        assertFalse(ledger.expectShot("bow-1", 99));
        assertTrue(ledger.expectShot("bow-1", 100));
        assertFalse(ledger.recordShot("stranger", DIMENSION, uuid("foreign"), 101));
        assertFalse(ledger.recordShot(ACTOR, "minecraft:the_nether", uuid("wrong-dim"), 101));
        assertFalse(ledger.recordShot(ACTOR, DIMENSION, "not-a-uuid", 101));
        assertFalse(ledger.recordShot(ACTOR, DIMENSION, uuid("too-early"), 99));
        assertFalse(ledger.recordShot(ACTOR, DIMENSION, uuid("too-late"), 121));
        assertTrue(ledger.recordShot(ACTOR, DIMENSION, arrow, 120)); // The window end is inclusive.
        assertFalse(ledger.recordShot(ACTOR, DIMENSION, arrow, 120));
        CombatLedger.Snapshot shot = ledger.peek("bow-1");
        assertEquals(1, shot.shots());
        assertEquals(0, shot.hits());
        assertEquals(arrow, shot.projectileId());
        assertEquals(-1, shot.remainingHealth());

        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-1", arrow, false, 4, 6, 121));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-1", uuid("other-arrow"), true, 4, 6, 121));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "other-target", arrow, true, 4, 6, 121));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-1", arrow, true, 4, 6, 119));
        assertTrue(damage(ledger, ACTOR, DIMENSION, "target-1", arrow, true, 4, 6, 121));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-1", arrow, true, 4, 2, 122));
        assertFalse(ledger.expectShot("bow-1", 123));
        CombatLedger.Snapshot hit = ledger.peek("bow-1");
        assertEquals(1, hit.shots());
        assertEquals(1, hit.hits());
        assertEquals(4, hit.damage());
        assertEquals(6, hit.remainingHealth());
        assertEquals(121, hit.tick());
        assertEquals(arrow, hit.projectileId());
        assertFalse(hit.defeated());
    }

    @Test void bowReceiptCannotBeRearmedAfterItsSingleShotOrAfterTheShotMisses() {
        CombatLedger ledger = new CombatLedger(ACTOR, DIMENSION);
        String firstArrow = uuid("first-arrow");
        String secondArrow = uuid("second-arrow");
        ledger.begin("bow-miss", "target", "bow", 10);
        assertTrue(ledger.expectShot("bow-miss", 10));
        assertTrue(ledger.recordShot(ACTOR, DIMENSION, firstArrow, 11));

        // The receipt remains single-shot even before damage has arrived.
        assertFalse(ledger.expectShot("bow-miss", 12));
        assertFalse(ledger.recordShot(ACTOR, DIMENSION, secondArrow, 12));
        CombatLedger.Snapshot afterRetry = ledger.peek("bow-miss");
        assertEquals(1, afterRetry.shots());
        assertEquals(0, afterRetry.hits());
        assertEquals(firstArrow, afterRetry.projectileId());

        // Once the first arrow has missed and its window has expired, it still cannot be rearmed.
        assertFalse(ledger.expectShot("bow-miss", 32));
        assertFalse(ledger.recordShot(ACTOR, DIMENSION, secondArrow, 32));
        CombatLedger.Snapshot afterMiss = ledger.peek("bow-miss");
        assertEquals(1, afterMiss.shots());
        assertEquals(0, afterMiss.hits());
        assertEquals(firstArrow, afterMiss.projectileId());
    }

    @Test void nonFiniteRemainingHealthCannotChangeTheReceipt() {
        CombatLedger ledger = new CombatLedger(ACTOR, DIMENSION);
        ledger.begin("melee-health", "target", "melee", 10);
        assertTrue(ledger.expectMelee("melee-health", 10));

        assertFalse(damage(ledger, ACTOR, DIMENSION, "target", ACTOR, false, 2, Double.NaN, 11));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target", ACTOR, false, 2, Double.POSITIVE_INFINITY, 12));
        CombatLedger.Snapshot snapshot = ledger.peek("melee-health");
        assertEquals(0, snapshot.hits());
        assertEquals(0, snapshot.damage());
        assertEquals(-1, snapshot.remainingHealth());
        assertFalse(snapshot.defeated());
    }

    @Test void suspendMeleeClosesPendingRequestsButLeavesBowReceiptActive() {
        CombatLedger ledger = new CombatLedger(ACTOR, DIMENSION);
        ledger.begin("melee", "target-a", "melee", 10);
        ledger.begin("melee-2", "target-b", "melee", 10);
        ledger.begin("bow", "target-c", "bow", 10);
        assertTrue(ledger.expectMelee("melee", 10));
        assertTrue(ledger.expectMelee("melee-2", 10));
        assertTrue(ledger.expectShot("bow", 30));

        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-a", ACTOR, false, 1, 9, 31));
        assertTrue(ledger.expectMelee("melee", 31));
        ledger.suspendMelee();
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-a", ACTOR, false, 1, 9, 32));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target-b", ACTOR, false, 1, 9, 32));

        String arrow = uuid("suspend-preserves-arrow");
        assertTrue(ledger.recordShot(ACTOR, DIMENSION, arrow, 31));
        assertTrue(damage(ledger, ACTOR, DIMENSION, "target-c", arrow, true, 2, 8, 32));

        assertTrue(ledger.expectMelee("melee", 33));
        assertTrue(damage(ledger, ACTOR, DIMENSION, "target-a", ACTOR, false, 1, 8, 34));
        assertEquals(1, ledger.peek("melee").hits());
        assertEquals(0, ledger.peek("melee-2").hits());
        assertEquals(1, ledger.peek("bow").hits());
    }

    @Test void receiptsAreTaskIsolatedResetOnBeginAndRemovedByEnd() {
        CombatLedger ledger = new CombatLedger(ACTOR, DIMENSION);
        ledger.begin("melee", "target-a", "melee", 1);
        ledger.begin("bow", "target-b", "bow", 1);
        assertTrue(ledger.expectMelee("melee", 2));
        assertTrue(ledger.expectShot("bow", 2));
        String arrow = uuid("isolated-arrow");
        assertTrue(ledger.recordShot(ACTOR, DIMENSION, arrow, 2));
        assertTrue(damage(ledger, ACTOR, DIMENSION, "target-a", ACTOR, false, 1, 9, 2));
        assertTrue(damage(ledger, ACTOR, DIMENSION, "target-b", arrow, true, 2, 8, 3));
        assertEquals(1, ledger.peek("melee").hits());
        assertEquals(1, ledger.peek("bow").hits());
        assertEquals(1, ledger.peek("melee").damage());
        assertEquals(2, ledger.peek("bow").damage());

        ledger.begin("melee", "new-target", "melee", 20);
        CombatLedger.Snapshot reset = ledger.peek("melee");
        assertEquals(0, reset.hits());
        assertEquals(0, reset.damage());
        assertEquals(-1, reset.remainingHealth());
        assertFalse(reset.defeated());
        assertEquals(1, ledger.peek("bow").hits());
        ledger.end("melee");
        assertNull(ledger.peek("melee"));
        assertNotNull(ledger.peek("bow"));
        ledger.end("bow");
        assertNull(ledger.peek("bow"));
    }

    @Test void finishAtomicallyReturnsAndRemovesTheReceipt() {
        CombatLedger ledger = new CombatLedger(ACTOR, DIMENSION);
        String arrow = uuid("terminal-arrow");
        ledger.begin("terminal", "target", "bow", 20);
        assertTrue(ledger.expectShot("terminal", 20));
        assertTrue(ledger.recordShot(ACTOR, DIMENSION, arrow, 21));
        assertTrue(damage(ledger, ACTOR, DIMENSION, "target", arrow, true, 3, 7, 22));

        CombatLedger.Snapshot finished = ledger.finish("terminal");
        assertNotNull(finished);
        assertEquals(1, finished.shots());
        assertEquals(1, finished.hits());
        assertEquals(3, finished.damage());
        assertEquals(7, finished.remainingHealth());
        assertEquals(arrow, finished.projectileId());
        assertNull(ledger.peek("terminal"));

        assertFalse(ledger.expectShot("terminal", 23));
        assertFalse(ledger.recordShot(ACTOR, DIMENSION, uuid("late-arrow"), 23));
        assertFalse(damage(ledger, ACTOR, DIMENSION, "target", arrow, true, 5, 2, 23));
        assertNull(ledger.peek("terminal"));
        assertNull(ledger.finish("terminal"));
        ledger.end("terminal");
        ledger.end("terminal");
    }

    @Test void capacityIsExplicitAndDoesNotDiscardActiveReceipts() {
        CombatLedger ledger = new CombatLedger(ACTOR, DIMENSION);
        for (int i = 0; i < 16; i++) ledger.begin("task-" + i, "target-" + i, "melee", i);
        BridgeException overflow = assertThrows(BridgeException.class, () -> ledger.begin("overflow", "target", "melee", 20));
        assertEquals("limit_exceeded", overflow.code);
        for (int i = 0; i < 16; i++) assertNotNull(ledger.peek("task-" + i));
        ledger.end("task-7");
        ledger.begin("overflow", "target", "melee", 20);
        assertNotNull(ledger.peek("task-0"));
        assertNotNull(ledger.peek("overflow"));
        assertNull(ledger.peek("missing"));
    }

    private static boolean damage(CombatLedger ledger, String attacker, String dimension, String target,
                                  String directEntityId, boolean projectile, double damage,
                                  double remainingHealth, long tick) {
        return ledger.recordDamage(attacker, dimension, target, directEntityId, projectile, damage, remainingHealth, tick);
    }

    private static String uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
