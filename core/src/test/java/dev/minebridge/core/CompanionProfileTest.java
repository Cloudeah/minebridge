package dev.minebridge.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanionProfileTest {
  @Test
  void worldsReceiveIndependentStableIdentities() {
    UUID owner = UUID.randomUUID();
    var first = CompanionProfile.create(owner, "BridgeBuddy", "minecraft:overworld");
    var second = CompanionProfile.create(owner, "BridgeBuddy", "minecraft:overworld");
    assertNotEquals(owner, first.id());
    assertNotEquals(first.id(), second.id());
    assertEquals(first, CompanionProfile.read(first.json()));
    assertTrue(first.autonomous());
  }

  @Test
  void hidingAndChangingDimensionKeepOwnerAndInventoryIdentity() {
    var profile = CompanionProfile.create(UUID.randomUUID(), "Buddy", "minecraft:overworld");
    var hidden = profile.state("minecraft:the_nether", false, false);
    assertEquals(profile.id(), hidden.id());
    assertEquals(profile.owner(), hidden.owner());
    assertEquals(hidden, CompanionProfile.read(hidden.json()));
    assertFalse(hidden.present());
    assertFalse(hidden.autonomous());
  }

  @Test
  void defenceSettingMigratesAndSurvivesHidingAndDimensionChanges() {
    var profile = CompanionProfile.create(UUID.randomUUID(), "Buddy", "minecraft:overworld");
    var legacy = profile.json();
    legacy.remove("defending");
    assertTrue(CompanionProfile.read(legacy).defending());
    var disabled = profile.defence(false).state("minecraft:the_nether", false, false);
    assertFalse(disabled.defending());
    assertEquals(profile.id(), disabled.id());
    assertEquals(disabled, CompanionProfile.read(disabled.json()));
  }

  @Test
  void corruptedIdentityAndInvalidNamesCannotCreateAnotherActor() {
    var profile = CompanionProfile.create(UUID.randomUUID(), "Buddy", "minecraft:overworld");
    var json = profile.json();
    json.addProperty("id", profile.owner().toString());
    assertThrows(BridgeException.class, () -> CompanionProfile.read(json));
    for (String name :
        new String[] {"", "../../owner", "имя", "name with spaces", "01234567890123456"})
      assertThrows(
          BridgeException.class,
          () -> CompanionProfile.create(profile.owner(), name, "minecraft:overworld"));
    assertThrows(BridgeException.class, () -> profile.state("bad dimension", true, true));
  }
}
