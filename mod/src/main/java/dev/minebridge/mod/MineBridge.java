package dev.minebridge.mod;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod("minebridge")
public final class MineBridge {
  public static final String ID = "minebridge";

  public MineBridge() {
    DedicatedBridge dedicated = new DedicatedBridge();
    NeoForge.EVENT_BUS.addListener(dedicated::commands);
    NeoForge.EVENT_BUS.addListener(dedicated::tick);
    NeoForge.EVENT_BUS.addListener(dedicated::stopped);
    CompanionManager companion = CompanionManager.INSTANCE;
    // Covers sweeping and modded damage sources as well as explicit primary attacks.
    NeoForge.EVENT_BUS.addListener(
        net.neoforged.bus.api.EventPriority.HIGHEST,
        (net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent e) -> {
          if (e.getEntity() instanceof net.minecraft.world.entity.player.Player
              && e.getSource().getEntity() instanceof CompanionPlayer) e.setCanceled(true);
        });
    NeoForge.EVENT_BUS.addListener(companion::started);
    NeoForge.EVENT_BUS.addListener(companion::stopping);
    NeoForge.EVENT_BUS.addListener(companion::stopped);
    NeoForge.EVENT_BUS.addListener(companion::tick);
    NeoForge.EVENT_BUS.addListener(companion::commands);
    NeoForge.EVENT_BUS.addListener(
        (net.neoforged.neoforge.event.entity.EntityJoinLevelEvent e) -> {
          var w = companion.world();
          if (w != null) w.arrowSpawn(e);
        });
    NeoForge.EVENT_BUS.addListener(
        (net.neoforged.neoforge.event.tick.EntityTickEvent.Pre e) -> {
          var w = companion.world();
          if (w != null) w.projectileTick(e.getEntity());
        });
    NeoForge.EVENT_BUS.addListener(
        (net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post e) -> {
          var w = companion.world();
          if (w != null) w.damage(e);
        });
    NeoForge.EVENT_BUS.addListener(
        (net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post e) -> {
          var w = companion.world();
          if (w != null) w.pickupEvent(e);
        });
    NeoForge.EVENT_BUS.addListener(
        (net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre e) -> {
          var w = companion.world();
          if (w != null) w.pickupAttempt(e);
        });
    NeoForge.EVENT_BUS.addListener(
        (net.neoforged.neoforge.event.tick.PlayerTickEvent.Post e) -> {
          var w = companion.world();
          if (w != null) w.pickupAfterTick(e.getEntity());
        });
    NeoForge.EVENT_BUS.addListener(
        (net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock e) -> {
          var w = companion.world();
          if (w != null) w.clickBlock(e);
        });
    NeoForge.EVENT_BUS.addListener(
        net.neoforged.bus.api.EventPriority.LOWEST,
        true,
        (net.neoforged.neoforge.event.level.block.BreakBlockEvent e) -> {
          var w = companion.world();
          if (w != null) w.blockBreak(e);
        });
    NeoForge.EVENT_BUS.addListener(
        net.neoforged.bus.api.EventPriority.LOWEST,
        true,
        (net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent e) -> {
          var w = companion.world();
          if (w != null) w.blockPlace(e);
        });
  }
}
