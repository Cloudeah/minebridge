package dev.minebridge.mod;

import static dev.minebridge.core.Json.*;

import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ChargedProjectiles;

/** Hand telemetry shared by the client player and the server-side companion. */
final class HeldItem {
  private HeldItem() {}

  private static String id(ItemStack stack) {
    return stack.isEmpty()
        ? "minecraft:air"
        : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
  }

  static JsonObject describe(ItemStack stack) {
    var result =
        object("type", id(stack), "count", stack.getCount(), "damage", stack.getDamageValue());
    if (stack.is(Items.CROSSBOW)) result.add("crossbow", crossbow(stack));
    return result;
  }

  static JsonObject crossbow(ItemStack stack) {
    var projectiles =
        stack
            .getOrDefault(DataComponents.CHARGED_PROJECTILES, ChargedProjectiles.EMPTY)
            .itemCopies();
    return object(
        "charged",
        !projectiles.isEmpty(),
        "projectiles",
        projectiles.stream().map(s -> object("type", id(s), "count", s.getCount())).toList());
  }
}
