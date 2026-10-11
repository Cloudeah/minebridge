package dev.minebridge.mod;

import static dev.minebridge.core.Json.*;

import com.google.gson.*;
import dev.minebridge.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Server-side player adapter: ordinary physics, menu inputs, game-mode actions and event receipts.
 */
final class CompanionWorld implements WorldAccess {
  private final CompanionPlayer player;
  private final OwnerPolicy policy;
  private final Supplier<ServerPlayer> owner;
  private final String worldId;
  private final MiningLedger mining;
  private final PlacementLedger placement;
  private final CombatLedger combat;
  private final PickupLedger pickups;
  private final Map<String, PickupAttempt> pickupAttempts = new HashMap<>();
  private final Map<String, JumpSequence> jumps = new HashMap<>();
  private final Map<String, FollowSequence> follows = new HashMap<>();
  private final Map<String, MeleeSequence> melee = new HashMap<>();
  private final Map<String, ItemStack> materials = new HashMap<>();
  private final Map<String, JsonArray> placeEvents = new HashMap<>();
  private final Map<String, Boolean> canceledPlaces = new HashMap<>();
  private final CompanionThreats threats = new CompanionThreats();
  private boolean defendingEnabled = true;
  private Task defenceTask;
  private Task careTask;
  private long equipmentRetry, regroupRetry;
  private final Map<String, ItemStack> equipmentExpected = new HashMap<>();
  private JsonObject lastDefence;
  private long lastDefenceTick;

  private record Route(long tick, Vec3 target, List<Pathfinder.Cell> cells) {}

  private final Map<String, Route> routes = new HashMap<>();
  private final Map<String, BuildPlanner.Plan> builds = new HashMap<>();
  private RecipeCatalog recipes;
  private Task current;
  private BlockPos breaking;
  private String reflex = "";
  private int reflexSlot = -1;
  private long reflexEnd, reflexRetry;
  private Task retreat;
  private Vec3 retreatTarget;
  private JsonObject lastTask;

  CompanionWorld(CompanionPlayer player, OwnerPolicy policy, Supplier<ServerPlayer> owner)
      throws Exception {
    this.player = player;
    this.policy = policy;
    this.owner = owner;
    Path marker =
        player
            .level()
            .getServer()
            .getWorldPath(LevelResource.ROOT)
            .resolve("minebridge-world-id.txt");
    if (!Files.exists(marker)) Files.writeString(marker, id(), StandardOpenOption.CREATE_NEW);
    worldId = Files.readString(marker).trim();
    mining = new MiningLedger(actorId(), dimension());
    placement = new PlacementLedger(actorId(), dimension());
    combat = new CombatLedger(actorId(), dimension());
    pickups = new PickupLedger(actorId(), dimension());
  }

  public String actorId() {
    return player.getUUID().toString();
  }

  public String actorMode() {
    return "companion";
  }

  public String worldId() {
    return worldId;
  }

  public String dimension() {
    return player.level().dimension().identifier().toString();
  }

  public long tick() {
    return player.level().getGameTime();
  }

  public Set<String> capabilities() {
    return Set.of(
        "move.to",
        "move.relative",
        "move.follow",
        "move.jump",
        "move.crouch",
        "route.plan",
        "look.at",
        "look.turn",
        "inventory.inspect",
        "inventory.select",
        "inventory.select_item",
        "inventory.swap",
        "inventory.equip",
        "inventory.transfer",
        "item.use",
        "item.drop",
        "item.pickup",
        "combat.attack",
        "block.break",
        "block.place",
        "world.interact",
        "container.open",
        "container.close",
        "craft.plan",
        "craft.execute",
        "build.plan",
        "build.execute");
  }

  public Set<String> actionFeatures(String action) {
    return switch (action) {
      case "combat.attack" -> Set.of("confirmed_melee_v1", "local_defence_v1");
      case "inventory.equip" -> Set.of("local_equipment_v1");
      case "move.follow" -> Set.of("local_regroup_v1");
      case "craft.execute" -> Set.of("craft_station_v1");
      case "block.place" -> Set.of("confirmed_placement_v1");
      case "build.execute" -> Set.of("confirmed_placement_v1", "multiblock_blueprint_v1");
      default -> Set.of();
    };
  }

  public List<String> movementModes() {
    return List.of("walk", "jump", "sprint", "crouch");
  }

  static JsonObject vector(Vec3 p) {
    return object("x", p.x, "y", p.y, "z", p.z);
  }

  static JsonObject pos(BlockPos p) {
    return object("x", p.getX(), "y", p.getY(), "z", p.getZ());
  }

  private static String itemId(ItemStack stack) {
    return stack.isEmpty()
        ? "minecraft:air"
        : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
  }

  private static String blockId(BlockState state) {
    return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
  }

  private static JsonObject stack(ItemStack stack) {
    return object(
        "type", itemId(stack), "count", stack.getCount(), "damage", stack.getDamageValue());
  }

  private static <T extends Comparable<T>> String value(Property<T> p, BlockState s) {
    return p.getName(s.getValue(p));
  }

  private static JsonObject properties(BlockState state) {
    JsonObject out = object();
    for (var p : state.getProperties()) out.addProperty(p.getName(), value(p, state));
    return out;
  }

  private static JsonObject blockState(BlockState state) {
    return object("type", blockId(state), "properties", properties(state));
  }

  private String role(Slot slot) {
    if (slot.container == player.getInventory()) {
      int n = slot.getContainerSlot();
      return n < 9 ? "hotbar" : n < 36 ? "main" : n == 40 ? "offhand" : "armor";
    }
    if (player.containerMenu instanceof AbstractCraftingMenu menu) {
      if (slot == menu.getResultSlot()) return "craft_output";
      if (menu.getInputGridSlots().contains(slot)) return "craft_grid";
    }
    return "container";
  }

  private static String equipment(int n) {
    return switch (n) {
      case 39 -> "head";
      case 38 -> "chest";
      case 37 -> "legs";
      case 36 -> "feet";
      case 40 -> "offhand";
      default -> "";
    };
  }

  public JsonObject snapshot(int radius, boolean telemetry) {
    JsonArray inventory = new JsonArray(),
        menu = new JsonArray(),
        entities = new JsonArray(),
        blocks = new JsonArray();
    for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
      JsonObject s = stack(player.getInventory().getItem(i));
      s.addProperty("slot", i);
      inventory.add(s);
    }
    for (Slot slot : player.containerMenu.slots) {
      JsonObject s = stack(slot.getItem());
      s.addProperty("slot", slot.index);
      s.addProperty("player_inventory", slot.container == player.getInventory());
      s.addProperty("role", role(slot));
      if (slot.container == player.getInventory()) {
        int n = slot.getContainerSlot();
        s.addProperty("inventory_slot", n);
        if (!equipment(n).isEmpty()) s.addProperty("equipment_slot", equipment(n));
      }
      menu.add(s);
    }
    for (Entity e :
        player
            .level()
            .getEntities(
                player,
                player.getBoundingBox().inflate(radius),
                e -> e.isAlive() && !e.isInvisibleTo(player))) {
      boolean seen = player.hasLineOfSight(e);
      if (!seen && !telemetry) continue;
      JsonObject s =
          object(
              "entity_id",
              e.getUUID().toString(),
              "type",
              BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
              "position",
              vector(e.position()),
              "velocity",
              vector(e.getDeltaMovement()),
              "visible",
              seen,
              "source",
              "companion_observation",
              "health",
              "unavailable",
              "kind",
              e instanceof LivingEntity ? "living" : e instanceof ItemEntity ? "item" : "other");
      if (e instanceof ItemEntity item) s.add("item", stack(item.getItem()));
      if (e instanceof Player)
        s.addProperty("owner", owner.get() != null && e.getUUID().equals(owner.get().getUUID()));
      entities.add(s);
    }
    int visited = 0;
    BlockPos center = player.blockPosition();
    for (BlockPos p :
        BlockPos.betweenClosed(
            center.offset(-radius, -4, -radius), center.offset(radius, 4, radius))) {
      if (++visited > 6000) break;
      if (!player.level().hasChunkAt(p)) continue;
      BlockState s = player.level().getBlockState(p);
      if (!telemetry && (s.isAir() || hit(p) == null)) continue;
      JsonObject b = pos(p);
      b.addProperty("type", blockId(s));
      b.addProperty("dimension", dimension());
      b.add("properties", properties(s));
      b.addProperty("solid", !s.getCollisionShape(player.level(), p).isEmpty());
      b.addProperty("fluid", !s.getFluidState().isEmpty());
      b.addProperty("source", telemetry ? "owner_telemetry" : "ray_observation");
      blocks.add(b);
    }
    ServerPlayer human = owner.get();
    JsonObject context =
        object(
            "source",
            "integrated_server_companion",
            "coordinate_system",
            "world_xyz_blocks",
            "telemetry",
            telemetry,
            "menu_id",
            player.containerMenu.containerId,
            "menu_revision",
            player.containerMenu.getStateId(),
            "menu_slots",
            menu,
            "cursor",
            stack(player.containerMenu.getCarried()),
            "raining",
            player.level().isRaining(),
            "day_time",
            player.level().getDefaultClockTime(),
            "reflex",
            reflex);
    if (lastTask != null) context.add("last_task", lastTask.deepCopy());
    context.addProperty("defending", defendingEnabled);
    JsonObject defending = defenceStatus();
    if (!defending.isEmpty()) context.add("defence", defending);
    if (human != null)
      context.add(
          "owner",
          object(
              "entity_id",
              human.getUUID().toString(),
              "position",
              vector(human.position()),
              "dimension",
              human.level().dimension().identifier().toString(),
              "same_dimension",
              human.level() == player.level(),
              "distance",
              human.level() == player.level() ? human.distanceTo(player) : -1));
    return object(
        "actor",
        object(
            "position",
            vector(player.position()),
            "velocity",
            vector(player.getDeltaMovement()),
            "yaw",
            player.getYRot(),
            "pitch",
            player.getXRot(),
            "health",
            player.getHealth(),
            "food",
            player.getFoodData().getFoodLevel(),
            "armor",
            player.getArmorValue(),
            "effects",
            player.getActiveEffects().stream().map(Object::toString).toList(),
            "selected_slot",
            player.getInventory().getSelectedSlot(),
            "main_hand",
            stack(player.getMainHandItem()),
            "off_hand",
            stack(player.getOffhandItem()),
            "using_item",
            player.isUsingItem(),
            "on_ground",
            player.onGround(),
            "flying",
            false,
            "in_water",
            player.isInWater(),
            "in_lava",
            player.isInLava(),
            "riding",
            player.isPassenger(),
            "crouching",
            player.isCrouching(),
            "sprinting",
            player.isSprinting()),
        "inventory",
        inventory,
        "entities",
        entities,
        "blocks",
        blocks,
        "context",
        context);
  }

  private Vec3 point(JsonObject data) {
    double x = number(data, "x", Double.NaN),
        y = number(data, "y", Double.NaN),
        z = number(data, "z", Double.NaN);
    if (!Double.isFinite(x + y + z)
        || Math.abs(x) > 30000000
        || Math.abs(z) > 30000000
        || Math.abs(y) > 2048) throw new BridgeException("invalid_request", "Invalid position");
    return new Vec3(x, y, z);
  }

  private BlockPos block(JsonObject data) {
    Vec3 p = point(data);
    if (p.x != Math.rint(p.x) || p.y != Math.rint(p.y) || p.z != Math.rint(p.z))
      throw new BridgeException("invalid_request", "Block coordinates must be integers");
    return BlockPos.containing(p);
  }

  private void area(Vec3 p) {
    if (!policy.areas.isEmpty()) policy.checkPoint(vector(p), dimension());
  }

  private void loaded(BlockPos p) {
    if (!player.level().hasChunkAt(p))
      throw new BridgeException("chunk_unavailable", "Target chunk is not loaded");
    area(Vec3.atCenterOf(p));
  }

  private Entity entity(JsonObject data) {
    Entity e = player.level().getEntity(UUID.fromString(string(data, "entity_id")));
    if (e == null
        || e == player
        || !e.isAlive()
        || e.isInvisibleTo(player)
        || !player.hasLineOfSight(e))
      throw new BridgeException("target_lost", "Target no longer visible");
    area(e.position());
    return e;
  }

  private BlockHitResult hit(BlockPos p) {
    if (!player.level().hasChunkAt(p)) return null;
    var boxes =
        player
            .level()
            .getBlockState(p)
            .getShape(player.level(), p, CollisionContext.of(player))
            .toAabbs();
    for (AABB box : boxes) {
      Vec3 target = box.getCenter().add(p.getX(), p.getY(), p.getZ());
      BlockHitResult h =
          player
              .level()
              .clip(
                  new ClipContext(
                      player.getEyePosition(),
                      target,
                      ClipContext.Block.OUTLINE,
                      ClipContext.Fluid.NONE,
                      player));
      if (h.getType() == HitResult.Type.BLOCK && h.getBlockPos().equals(p)) return h;
    }
    return null;
  }

  private BlockHitResult reach(BlockPos p) {
    loaded(p);
    if (!player.isWithinBlockInteractionRange(p, 0))
      throw new BridgeException("out_of_reach", "Walk closer to the block first");
    BlockHitResult h = hit(p);
    if (h == null) throw new BridgeException("target_lost", "Block is occluded");
    if (!player.level().mayInteract(player, p))
      throw new BridgeException("permission_denied", "World protection rejects interaction");
    return h;
  }

  private void look(Vec3 target) {
    Vec3 d = target.subtract(player.getEyePosition());
    double h = Math.hypot(d.x, d.z);
    player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
    player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, h)));
    player.setYHeadRot(player.getYRot());
  }

  static boolean safeCell(ServerLevel level, Player actor, BlockPos p) {
    if (!level.hasChunkAt(p) || !level.hasChunkAt(p.above()) || !level.hasChunkAt(p.below()))
      return false;
    for (BlockPos n : List.of(p, p.above(), p.below())) {
      BlockState s = level.getBlockState(n);
      if (!s.getFluidState().isEmpty()
          || s.is(Blocks.FIRE)
          || s.is(Blocks.SOUL_FIRE)
          || s.is(Blocks.MAGMA_BLOCK)
          || s.is(Blocks.CACTUS)
          || s.is(Blocks.POWDER_SNOW)
          || s.is(Blocks.SWEET_BERRY_BUSH)
          || s.is(Blocks.CAMPFIRE)
          || s.is(Blocks.SOUL_CAMPFIRE)
          || s.is(Blocks.WITHER_ROSE)
          || s.is(Blocks.POINTED_DRIPSTONE)) return false;
    }
    AABB box =
        actor
            .getBoundingBox()
            .move(new Vec3(p.getX() + .5, p.getY(), p.getZ() + .5).subtract(actor.position()));
    return level.noCollision(actor, box)
        && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP);
  }

  private Pathfinder.Terrain terrain(Pathfinder.Cell cell) {
    BlockPos p = new BlockPos(cell.x(), cell.y(), cell.z());
    if (!player.level().hasChunkAt(p) || !player.level().hasChunkAt(p.above()))
      return Pathfinder.Terrain.UNKNOWN;
    try {
      area(Vec3.atCenterOf(p));
    } catch (BridgeException e) {
      return Pathfinder.Terrain.BLOCKED;
    }
    return safeCell(player.level(), player, p)
        ? Pathfinder.Terrain.WALKABLE
        : Pathfinder.Terrain.BLOCKED;
  }

  private List<Pathfinder.Cell> path(Vec3 target) {
    BlockPos start = player.blockPosition(), end = BlockPos.containing(target);
    return new Pathfinder()
        .plan(
            new Pathfinder.Grid() {
              public Pathfinder.Terrain terrain(Pathfinder.Cell c) {
                return CompanionWorld.this.terrain(c);
              }

              public Pathfinder.Terrain transition(Pathfinder.Cell a, Pathfinder.Cell b) {
                if (a.y() == b.y()) return Pathfinder.Terrain.WALKABLE;
                BlockPos above = new BlockPos(a.x(), a.y() + 2, a.z());
                if (b.y() < a.y()) above = new BlockPos(b.x(), a.y() + 1, b.z());
                if (!player.level().hasChunkAt(above)) return Pathfinder.Terrain.UNKNOWN;
                return player
                        .level()
                        .getBlockState(above)
                        .getCollisionShape(player.level(), above)
                        .isEmpty()
                    ? Pathfinder.Terrain.WALKABLE
                    : Pathfinder.Terrain.BLOCKED;
              }
            },
            new Pathfinder.Cell(start.getX(), start.getY(), start.getZ()),
            new Pathfinder.Cell(end.getX(), end.getY(), end.getZ()),
            4096)
        .cells();
  }

  private Step move(Task task, Vec3 target, double tolerance) {
    area(player.position());
    area(target);
    ServerPlayer human = owner.get();
    if (human != null
        && human.level() == player.level()
        && human.position().distanceTo(target) > 64
        && human.position().distanceTo(target) >= human.distanceTo(player))
      throw new BridgeException(
          "companion_range", "Stay within 64 blocks of owner; move towards owner to return");
    loaded(BlockPos.containing(target));
    double remaining = player.position().distanceTo(target);
    if (remaining <= tolerance) {
      player.stopInputs();
      return Step.success(
          object(
              "position",
              vector(player.position()),
              "distance",
              remaining,
              "source",
              "integrated_server"));
    }
    if (player.isPassenger()
        || player.isUsingItem()
        || player.containerMenu != player.inventoryMenu)
      throw new BridgeException("busy", "Stop item use and close menus before walking");
    var feet = player.blockPosition();
    var start = new Pathfinder.Cell(feet.getX(), feet.getY(), feet.getZ());
    Route cached = routes.get(task.id);
    if (cached == null
        || tick() - cached.tick() >= 10
        || cached.target().distanceTo(target) > .5
        || !cached.cells().contains(start)) {
      cached = new Route(tick(), target, path(target));
      routes.put(task.id, cached);
    }
    List<Pathfinder.Cell> route = cached.cells();
    Vec3 waypoint = target;
    int next = route.indexOf(start) + 1;
    if (next < route.size()) {
      var c = route.get(next);
      if (terrain(c) != Pathfinder.Terrain.WALKABLE) {
        routes.remove(task.id);
        player.stopInputs();
        return Step.waiting(object("stage", "replanning"));
      }
      waypoint = new Vec3(c.x() + .5, c.y(), c.z() + .5);
      if (player.position().distanceTo(waypoint) < .35 && next + 1 < route.size()) {
        c = route.get(next + 1);
        waypoint = new Vec3(c.x() + .5, c.y(), c.z() + .5);
      }
    }
    Vec3 delta = waypoint.subtract(player.position());
    float desired = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
    float turn = (float) MovementCommands.wrapYaw(desired - player.getYRot());
    player.setYRot(player.getYRot() + Math.max(-25, Math.min(25, turn)));
    player.setYHeadRot(player.getYRot());
    player.setXRot(0);
    if (!task.context.has("walking")) {
      ActionBudget.require(task, 1);
      task.actions++;
      task.context.addProperty("walking", true);
    }
    player.movement(
        Math.abs(turn) < 35,
        waypoint.y > player.getY() + .45 && player.onGround(),
        bool(task.parameters, "sprint", false),
        bool(task.parameters, "crouch", false));
    if (!task.context.has("walk_progress")
        || remaining < number(task.context, "walk_progress", remaining) - .1) {
      task.context.addProperty("walk_progress", remaining);
      task.context.addProperty("walk_tick", tick());
    }
    if (tick() - number(task.context, "walk_tick", tick()) > 80)
      throw new BridgeException("path_unreachable", "Movement made no progress for four seconds");
    return Step.waiting(
        object(
            "distance",
            remaining,
            "position",
            vector(player.position()),
            "source",
            "integrated_server"));
  }

  public void validate(String action, JsonObject p) {
    switch (action) {
      case "move.to" -> {
        point(child(p, "target"));
        MovementCommands.validateTravel(p);
      }
      case "move.relative" -> MovementCommands.relative(p, 0);
      case "move.follow" -> {
        UUID.fromString(string(p, "entity_id"));
        FollowSequence.validate(p);
      }
      case "move.jump" -> JumpSequence.count(p);
      case "move.crouch" -> MovementCommands.crouchTicks(p);
      case "look.at" -> {
        if (p.has("entity_id")) UUID.fromString(string(p, "entity_id"));
        else point(child(p, "target"));
      }
      case "look.turn" -> MovementCommands.turn(p, 0, 0);
      case "combat.attack" -> MeleeSequence.validate(p);
      case "block.break", "block.place" -> {
        block(child(p, "position"));
        if (action.equals("block.place")) Identifier.parse(string(p, "block"));
        else {
          bool(p, "require_tool", true);
          bool(p, "auto_tool", true);
        }
      }
      case "inventory.select" -> integer(p, "slot", 0, 0, 8);
      case "inventory.select_item" -> {
        Identifier.parse(string(p, "item"));
        integer(p, "hotbar_slot", player.getInventory().getSelectedSlot(), 0, 8);
      }
      case "item.use" -> {
        hand(p);
        integer(p, "duration_ticks", 40, 1, 1200);
        if (p.has("target")) point(child(p, "target"));
      }
      case "item.drop" -> {
        ItemDropSequence.count(p);
        if (!string(p, "hand", "main").equals("main"))
          throw new BridgeException("invalid_request", "Drop supports main hand");
      }
      case "item.pickup" -> {
        UUID.fromString(string(p, "entity_id"));
        if (p.has("count"))
          throw new BridgeException("invalid_request", "Pickup collects the ground stack");
      }
      case "container.open", "world.interact" -> {
        if (p.has("position") == p.has("entity_id"))
          throw new BridgeException("invalid_request", "Choose position or entity_id");
        if (p.has("position")) block(child(p, "position"));
        else UUID.fromString(string(p, "entity_id"));
        hand(p);
      }
      case "inventory.swap", "inventory.equip", "inventory.transfer" -> {
        integer(p, "from_slot", 0, 0, player.containerMenu.slots.size() - 1);
        if (!p.has("from_slot")) throw new BridgeException("invalid_request", "from_slot required");
        if (action.equals("inventory.equip") && p.has("equipment_slot")) {
          if (!Set.of("head", "chest", "legs", "feet", "offhand")
              .contains(string(p, "equipment_slot")))
            throw new BridgeException("invalid_request", "Invalid equipment slot");
          if (p.has("to_slot") || p.has("count"))
            throw new BridgeException("invalid_request", "Named equip exchanges whole stacks");
        } else {
          if (!p.has("to_slot")) throw new BridgeException("invalid_request", "to_slot required");
          integer(p, "to_slot", 0, 0, player.containerMenu.slots.size() - 1);
        }
        if (p.has("count")) integer(p, "count", 1, 1, 64);
        if (action.equals("inventory.swap") && p.has("count"))
          throw new BridgeException("invalid_request", "Swap exchanges whole stacks");
      }
      case "craft.execute" -> {
        Identifier.parse(string(p, "item"));
        integer(p, "count", 1, 1, 4096);
        CraftSequence.validate(p);
      }
      case "build.execute" -> {
        for (var b : buildPlan(p).blocks()) area(new Vec3(b.x() + .5, b.y() + .5, b.z() + .5));
      }
      default -> {}
    }
  }

  private static InteractionHand hand(JsonObject p) {
    String hand = string(p, "hand", "main");
    if (!Set.of("main", "off").contains(hand))
      throw new BridgeException("invalid_request", "hand must be main/off");
    return hand.equals("main") ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
  }

  public Step step(Task task) {
    current = task;
    if (!reflex.isEmpty() && task != defenceTask && task != careTask)
      return Step.waiting(object("stage", "survival_reflex", "reflex", reflex));
    if (!player.isAlive()) throw new BridgeException("actor_dead", "Companion is dead");
    area(player.position());
    return switch (task.action) {
      case "move.to" ->
          move(
              task,
              point(child(task.parameters, "target")),
              number(task.parameters, "tolerance", .7));
      case "move.relative" -> {
        if (!task.context.has("target")) {
          var offset = MovementCommands.relative(task.parameters, player.getYRot());
          task.context.add("target", vector(player.position().add(offset.x(), 0, offset.z())));
        }
        yield move(
            task, point(child(task.context, "target")), number(task.parameters, "tolerance", .7));
      }
      case "move.follow" -> {
        Entity target = entity(task.parameters);
        var seq =
            follows.computeIfAbsent(
                task.id,
                id ->
                    new FollowSequence(
                        integer(task.parameters, "ticks", 200, 1, 1200),
                        number(task.parameters, "distance", 3)));
        var u = seq.observe(new FollowSequence.Sample(tick(), player.distanceTo(target)));
        task.confirmed.addProperty(
            "follow_ticks", u.progress().get("confirmed_follow_ticks").getAsLong());
        if (u.done()) {
          player.stopInputs();
          yield Step.success(u.progress());
        }
        if (player.distanceTo(target) > number(task.parameters, "distance", 3))
          move(task, target.position(), number(task.parameters, "distance", 3));
        else player.stopInputs();
        yield Step.waiting(u.progress());
      }
      case "move.jump" -> jump(task);
      case "move.crouch" -> crouch(task);
      case "look.at" -> {
        look(
            task.parameters.has("entity_id")
                ? entity(task.parameters).getBoundingBox().getCenter()
                : point(child(task.parameters, "target")));
        task.actions++;
        yield Step.success(
            object(
                "yaw", player.getYRot(), "pitch", player.getXRot(), "source", "integrated_server"));
      }
      case "look.turn" -> {
        var turn = MovementCommands.turn(task.parameters, player.getYRot(), player.getXRot());
        player.setYRot((float) turn.yaw());
        player.setXRot((float) turn.pitch());
        player.setYHeadRot(player.getYRot());
        task.actions++;
        yield Step.success(
            object(
                "yaw", player.getYRot(), "pitch", player.getXRot(), "source", "integrated_server"));
      }
      case "inventory.select" -> select(task, integer(task.parameters, "slot", 0, 0, 8));
      case "inventory.select_item" -> selectItem(task, string(task.parameters, "item"));
      case "inventory.swap", "inventory.equip", "inventory.transfer" -> inventory(task);
      case "item.use" -> use(task);
      case "item.drop" -> drop(task);
      case "item.pickup" -> pickup(task);
      case "combat.attack" -> attack(task);
      case "block.break" -> mine(task);
      case "block.place" ->
          place(
              task,
              block(child(task.parameters, "position")),
              string(task.parameters, "block"),
              task.parameters.has("properties") ? child(task.parameters, "properties") : object());
      case "world.interact", "container.open" -> interact(task);
      case "container.close" -> {
        player.closeContainer();
        task.actions++;
        yield Step.success(
            object("menu_id", player.containerMenu.containerId, "source", "integrated_server"));
      }
      case "craft.execute" -> craft(task);
      case "build.execute" -> build(task);
      default -> throw new BridgeException("unsupported_capability", task.action);
    };
  }

  private Step jump(Task t) {
    var seq = jumps.computeIfAbsent(t.id, id -> new JumpSequence(JumpSequence.count(t.parameters)));
    var u =
        seq.observe(
            tick(),
            new JumpSequence.Sample(tick(), player.onGround(), player.getY()),
            !player.isUsingItem() && !player.isPassenger());
    player.movement(false, u.pressJump(), false, false);
    if (u.pressJump()) {
      ActionBudget.require(t, 1);
      t.actions++;
    }
    t.confirmed.addProperty("jumps", seq.completed());
    return u.error() != null
        ? Step.failure(u.error(), "Jump did not take off", u.progress())
        : u.done() ? Step.success(u.progress()) : Step.waiting(u.progress());
  }

  private Step crouch(Task t) {
    if (!t.context.has("crouch")) {
      ActionBudget.require(t, 1);
      t.actions++;
      t.context.addProperty("crouch", tick());
      player.movement(false, false, false, true);
    }
    long elapsed = tick() - t.context.get("crouch").getAsLong();
    t.confirmed.addProperty("crouch_ticks", elapsed);
    if (elapsed >= MovementCommands.crouchTicks(t.parameters)) {
      player.stopInputs();
      return Step.success(
          object(
              "confirmed_crouch_ticks", elapsed, "released", true, "source", "integrated_server"));
    }
    return Step.waiting(object("confirmed_crouch_ticks", elapsed, "source", "integrated_server"));
  }

  private Step select(Task t, int slot) {
    ActionBudget.require(t, 1);
    player.getInventory().setSelectedSlot(slot);
    t.actions++;
    t.confirmed.addProperty("selected_slot", slot);
    return Step.success(
        object(
            "slot",
            slot,
            "main_hand",
            stack(player.getMainHandItem()),
            "source",
            "integrated_server"));
  }

  private void menuReady() {
    if (player.isUsingItem())
      throw new BridgeException("item_busy", "Stop item use before inventory input");
    if (!player.containerMenu.getCarried().isEmpty())
      throw new BridgeException("cursor_occupied", "Cursor must be empty");
    if (!player.containerMenu.stillValid(player))
      throw new BridgeException("interface_closed", "Menu is not valid");
  }

  private Slot inventorySlot(int slot) {
    return player.containerMenu.slots.stream()
        .filter(s -> s.container == player.getInventory() && s.getContainerSlot() == slot)
        .findFirst()
        .orElseThrow(
            () -> new BridgeException("interface_open", "Required inventory slot unavailable"));
  }

  private Step selectItem(Task t, String item) {
    menuReady();
    int found = -1;
    for (int i = 0; i < 36; i++)
      if (itemId(player.getInventory().getItem(i)).equals(item)) {
        found = i;
        break;
      }
    if (found < 0)
      throw new BridgeException("missing_materials", "Item is not in companion inventory");
    return selectInventorySlot(t, found);
  }

  private Step selectInventorySlot(Task t, int found) {
    menuReady();
    int target =
        integer(t.parameters, "hotbar_slot", player.getInventory().getSelectedSlot(), 0, 8);
    if (found < 9) return select(t, found);
    ActionBudget.require(t, 2);
    ItemStack before = player.getInventory().getItem(found).copy();
    player.containerMenu.clicked(inventorySlot(found).index, target, ContainerInput.SWAP, player);
    t.actions++;
    if (!ItemStack.matches(before, player.getInventory().getItem(target)))
      throw new BridgeException(
          "inventory_unconfirmed", "Swap did not put the expected item in hotbar");
    t.confirmed.addProperty("inventory_swaps", 1);
    return select(t, target);
  }

  private Step inventory(Task t) {
    menuReady();
    JsonObject p = t.parameters;
    if (p.has("menu_id") && integer(p, "menu_id", 0, 0, 10000) != player.containerMenu.containerId
        || p.has("menu_revision")
            && integer(p, "menu_revision", 0, 0, Integer.MAX_VALUE)
                != player.containerMenu.getStateId())
      throw new BridgeException("stale_revision", "Menu changed");
    Slot
        from =
            player.containerMenu.slots.get(
                integer(p, "from_slot", 0, 0, player.containerMenu.slots.size() - 1)),
        to;
    if (t.action.equals("inventory.equip") && p.has("equipment_slot")) {
      int n =
          switch (string(p, "equipment_slot")) {
            case "head" -> 39;
            case "chest" -> 38;
            case "legs" -> 37;
            case "feet" -> 36;
            default -> 40;
          };
      to = inventorySlot(n);
    } else
      to =
          player.containerMenu.slots.get(
              integer(p, "to_slot", 0, 0, player.containerMenu.slots.size() - 1));
    if (from == to
        || !from.hasItem()
        || !from.mayPickup(player)
        || !to.mayPlace(from.getItem())
        || role(from).equals("craft_output")
        || role(to).equals("craft_output"))
      throw new BridgeException("invalid_request", "Slots cannot accept this exchange");
    ItemStack a = from.getItem().copy(), b = to.getItem().copy();
    if (isCare(t, "equip")
        && (!b.isEmpty()
            || !ItemStack.matches(a, equipmentExpected.getOrDefault(t.id, ItemStack.EMPTY))))
      throw new BridgeException(
          "equipment_changed", "Automatic equipment source or empty target changed");
    boolean swap = !t.action.equals("inventory.transfer");
    if (swap) {
      if (!b.isEmpty() && (!to.mayPickup(player) || !from.mayPlace(b))
          || a.getCount() > to.getMaxStackSize(a)
          || !b.isEmpty() && b.getCount() > from.getMaxStackSize(b))
        throw new BridgeException("invalid_request", "Exchange exceeds slot restrictions");
      ActionBudget.require(t, 3);
      player.containerMenu.clicked(from.index, 0, ContainerInput.PICKUP, player);
      player.containerMenu.clicked(to.index, 0, ContainerInput.PICKUP, player);
      player.containerMenu.clicked(from.index, 0, ContainerInput.PICKUP, player);
      t.actions += 3;
      if (!ItemStack.matches(from.getItem(), b) || !ItemStack.matches(to.getItem(), a))
        throw new BridgeException(
            "inventory_unconfirmed", "Exchange did not match expected stacks");
      t.confirmed.addProperty(t.action.equals("inventory.equip") ? "equips" : "inventory_swaps", 1);
    } else {
      int count = integer(p, "count", a.getCount(), 1, 64);
      if (count > a.getCount()
          || !b.isEmpty() && !ItemStack.isSameItemSameComponents(a, b)
          || b.getCount() + count > to.getMaxStackSize(a))
        throw new BridgeException("invalid_request", "Transfer does not fit");
      ActionBudget.require(t, count + 2);
      player.containerMenu.clicked(from.index, 0, ContainerInput.PICKUP, player);
      for (int i = 0; i < count; i++)
        player.containerMenu.clicked(to.index, 1, ContainerInput.PICKUP, player);
      player.containerMenu.clicked(from.index, 0, ContainerInput.PICKUP, player);
      t.actions += count + 2;
      if (!ItemStack.matches(from.getItem(), a.copyWithCount(a.getCount() - count))
          || !ItemStack.matches(to.getItem(), a.copyWithCount(b.getCount() + count)))
        throw new BridgeException("inventory_unconfirmed", "Transfer not confirmed");
      t.confirmed.addProperty("transfer_count", count);
    }
    if (!player.containerMenu.getCarried().isEmpty())
      throw new BridgeException("inventory_unconfirmed", "Exchange left cursor occupied");
    player.containerMenu.broadcastChanges();
    return Step.success(
        object(
            "from",
            stack(from.getItem()),
            "to",
            stack(to.getItem()),
            "source",
            "integrated_server"));
  }

  private Step use(Task t) {
    InteractionHand hand = hand(t.parameters);
    if (!t.context.has("use_at")) {
      menuReady();
      ItemStack held = player.getItemInHand(hand);
      if (held.isEmpty()) throw new BridgeException("missing_materials", "Hand is empty");
      if (!held.has(DataComponents.CONSUMABLE))
        throw new BridgeException(
            "unsupported_capability",
            "Companion item.use supports consumables; use world.interact for targeted tools");
      if (t.parameters.has("target")) look(point(child(t.parameters, "target")));
      t.context.add("before", stack(held));
      t.context.addProperty("food_before", player.getFoodData().getFoodLevel());
      ActionBudget.require(t, 1);
      var result = player.gameMode.useItem(player, player.level(), held, hand);
      t.actions++;
      player.swing(hand);
      t.context.addProperty("use_at", tick());
      if (!result.consumesAction() && !player.isUsingItem())
        throw new BridgeException("use_unconfirmed", "Item use rejected");
    }
    boolean changed =
        !child(t.context, "before").equals(stack(player.getItemInHand(hand)))
            || number(t.context, "food_before", 0) != player.getFoodData().getFoodLevel();
    long elapsed = tick() - t.context.get("use_at").getAsLong();
    if (changed) {
      player.stopUsingItem();
      t.confirmed.addProperty("item_uses", 1);
      return Step.success(
          object(
              "before",
              t.context.get("before"),
              "after",
              stack(player.getItemInHand(hand)),
              "food",
              player.getFoodData().getFoodLevel(),
              "source",
              "integrated_server"));
    }
    if (elapsed >= integer(t.parameters, "duration_ticks", 40, 1, 1200)) {
      player.stopUsingItem();
      return Step.failure(
          "use_unconfirmed",
          "No inventory or food change observed; timed hold released",
          object("held_ticks", elapsed, "source", "integrated_server"));
    }
    return Step.waiting(object("held_ticks", elapsed));
  }

  private Step drop(Task t) {
    menuReady();
    int count = ItemDropSequence.count(t.parameters);
    ItemStack before = player.getMainHandItem().copy();
    if (before.isEmpty() || before.getCount() < count)
      throw new BridgeException("missing_materials", "Not enough held items");
    if (t.parameters.has("expected_item")
        && !itemId(before).equals(string(t.parameters, "expected_item")))
      throw new BridgeException("target_changed", "Held item changed");
    ActionBudget.require(t, count);
    int dropped = 0;
    for (int i = 0; i < count; i++) {
      int n = player.getMainHandItem().getCount();
      player.drop(false);
      t.actions++;
      if (player.getMainHandItem().getCount() == n - 1) dropped++;
      else break;
    }
    t.confirmed.addProperty("drop_count", dropped);
    JsonObject result =
        object(
            "dropped",
            dropped,
            "before",
            stack(before),
            "after",
            stack(player.getMainHandItem()),
            "source",
            "integrated_server");
    return dropped == count
        ? Step.success(result)
        : Step.failure("drop_unconfirmed", "Drop was rejected", result);
  }

  private Map<String, Integer> counts() {
    Map<String, Integer> out = new HashMap<>();
    for (int i = 0; i < 36; i++) {
      ItemStack s = player.getInventory().getItem(i);
      if (!s.isEmpty()) out.merge(itemId(s), s.getCount(), Integer::sum);
    }
    return out;
  }

  void pickupEvent(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post event) {
    if (event.getPlayer() != player) return;
    var item = event.getItemEntity();
    var original = event.getOriginalStack();
    int remaining = event.getCurrentStack().getCount();
    pickupAttempts.remove(item.getUUID().toString());
    pickups.record(
        actorId(),
        dimension(),
        item.getUUID().toString(),
        itemId(original),
        original.getCount() - remaining,
        remaining,
        tick());
  }

  private int inventoryCount(String item) {
    int result = 0;
    for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
      ItemStack s = player.getInventory().getItem(i);
      if (itemId(s).equals(item)) result += s.getCount();
    }
    return result;
  }

  void pickupAttempt(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre event) {
    if (event.getPlayer() != player) return;
    var item = event.getItemEntity();
    String id = item.getUUID().toString();
    if (pickups.watching(id))
      pickupAttempts.put(
          id,
          new PickupAttempt(
              itemId(item.getItem()),
              item.getItem().getCount(),
              inventoryCount(itemId(item.getItem())),
              tick()));
  }

  void pickupAfterTick(Player actor) {
    if (actor != player) return;
    for (var entry : pickupAttempts.entrySet()) {
      Entity entity = player.level().getEntity(UUID.fromString(entry.getKey()));
      var before = entry.getValue();
      if (entity instanceof ItemEntity item && pickups.watching(entry.getKey())) {
        int remaining = item.getItem().getCount();
        int collected =
            before.collected(
                itemId(item.getItem()),
                remaining,
                inventoryCount(before.item()),
                tick(),
                !item.isRemoved());
        if (collected > 0)
          pickups.record(
              actorId(),
              dimension(),
              entry.getKey(),
              before.item(),
              collected,
              remaining,
              tick(),
              "integrated_server_pickup_state");
      }
    }
    pickupAttempts.clear();
  }

  private Step pickup(Task t) {
    String id = string(t.parameters, "entity_id");
    if (!t.context.has("pickup_at")) {
      Entity target = entity(t.parameters);
      if (!(target instanceof ItemEntity))
        throw new BridgeException("invalid_request", "Target must be a ground item");
      pickups.begin(id);
      t.context.addProperty("pickup_at", tick());
    }
    var receipt = pickups.peek(id, t.context.get("pickup_at").getAsLong());
    if (receipt != null) {
      t.confirmed.addProperty("pickup_count", receipt.collected());
      player.stopInputs();
      return Step.success(
          object(
              "entity_id",
              id,
              "item",
              receipt.item(),
              "picked_up",
              receipt.collected(),
              "remaining",
              receipt.remaining(),
              "source",
              receipt.source()));
    }
    Entity target = entity(t.parameters);
    Step movement = move(t, target.position(), .6);
    return movement.done() ? Step.waiting(object("stage", "waiting_for_vanilla_pickup")) : movement;
  }

  void damage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
    ServerPlayer human = owner.get();
    if ((event.getEntity() == player || event.getEntity() == human)
        && event.getSource().getEntity() instanceof Mob attacker
        && safeThreat(attacker, human)) {
      threats.record(
          attacker.getUUID().toString(),
          event.getEntity() == human,
          event.getHealthDamage(),
          tick());
    }
    if (event.getSource().getEntity() != player) return;
    Entity direct = event.getSource().getDirectEntity();
    if (direct == null) return;
    combat.recordDamage(
        actorId(),
        dimension(),
        event.getEntity().getUUID().toString(),
        direct.getUUID().toString(),
        false,
        event.getHealthDamage(),
        event.getEntity().getHealth(),
        tick());
  }

  private Step attack(Task t) {
    var existing = combat.peek(t.id);
    if (existing != null && existing.defeated()) {
      collectCombat(t);
      return Step.success(
          object(
              "outcome",
              "defeated",
              "hits",
              existing.hits(),
              "damage",
              existing.damage(),
              "source",
              "integrated_server_damage"));
    }
    Entity target = entity(t.parameters);
    if (!(target instanceof LivingEntity living) || target instanceof Player)
      throw new BridgeException("permission_denied", "Companion never attacks players");
    if (player.getHealth() <= number(t.parameters, "stop_health", 4))
      throw new BridgeException("low_health", "Melee stopped at low health");
    if (isDefence(t)) {
      if (!defendingEnabled)
        throw new BridgeException("defence_disabled", "Immediate defence is disabled");
      if (!(target instanceof Mob mob)
          || !safeThreat(mob, owner.get())
          || threats
              .select(tick(), List.of(threatCandidate(mob)), mob.getUUID().toString())
              .isEmpty())
        throw new BridgeException("threat_lost", "Mob is no longer a nearby threat");
      if (player.isInLava() || player.isInWater() && player.getAirSupply() < 100)
        throw new BridgeException("survival_priority", "Escape dangerous liquid before fighting");
      if (player.containerMenu != player.inventoryMenu) {
        policy.check("container.close", object(), dimension());
        ActionBudget.require(t, 1);
        player.closeContainer();
        t.actions++;
        return Step.waiting(object("stage", "closing_menu_for_defence"));
      }
      if (!t.context.has("weapon_prepared")) {
        t.context.addProperty("weapon_prepared", true);
        int best = player.getInventory().getSelectedSlot();
        double score = weaponScore(player.getInventory().getItem(best));
        for (int i = 0; i < 36; i++) {
          double candidate = weaponScore(player.getInventory().getItem(i));
          if (candidate > score + .01) {
            best = i;
            score = candidate;
          }
        }
        if (best != player.getInventory().getSelectedSlot()) {
          try {
            policy.check("inventory.select_item", object(), dimension());
          } catch (BridgeException denied) {
            best = player.getInventory().getSelectedSlot();
          }
          if (best != player.getInventory().getSelectedSlot()) {
            selectInventorySlot(t, best);
            return Step.waiting(object("stage", "weapon_prepared"));
          }
        }
      }
    }
    var seq =
        melee.computeIfAbsent(
            t.id,
            id -> {
              combat.begin(id, target.getUUID().toString(), "melee", tick());
              return new MeleeSequence(MeleeSequence.requestedHits(t.parameters));
            });
    var receipt = combat.peek(t.id);
    var update = seq.observe(tick(), receipt.hits(), receipt.defeated());
    collectCombat(t);
    if (update.error() != null)
      return Step.failure(update.error(), "Melee receipt not confirmed", object());
    if (update.done())
      return Step.success(
          object(
              "outcome",
              update.outcome(),
              "hits",
              receipt.hits(),
              "damage",
              receipt.damage(),
              "source",
              "integrated_server_damage"));
    if (!player.isWithinEntityInteractionRange(target, 0)) {
      if (!bool(t.parameters, "chase", false))
        throw new BridgeException("out_of_reach", "Walk closer or enable bounded chase");
      double max = number(t.parameters, "max_chase_distance", 16);
      if (!t.context.has("chase_start")) t.context.add("chase_start", vector(player.position()));
      Vec3 start = point(child(t.context, "chase_start"));
      if (!MeleeSequence.withinChase(
          start.distanceTo(player.position()), start.distanceTo(target.position()), max))
        throw new BridgeException("chase_limit", "Target escaped local chase");
      if (isDefence(t))
        policy.check("move.to", object("target", vector(target.position())), dimension());
      Step chase = move(t, target.position(), 2);
      return chase.done() ? Step.waiting(object("stage", "approaching_target")) : chase;
    }
    player.stopInputs();
    if (update.ready() && player.getAttackStrengthScale(0) >= .95 && !player.isUsingItem()) {
      look(target.getBoundingBox().getCenter());
      ActionBudget.require(t, 1);
      seq.sent(tick(), receipt.hits());
      combat.expectMelee(t.id, tick());
      player.attack(target);
      player.swing(InteractionHand.MAIN_HAND);
      t.actions++;
    }
    return Step.waiting(object("hits", receipt.hits(), "health", "unavailable"));
  }

  void blockBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
    if (event.getPlayer() != player) return;
    BlockPos p = event.getPos().immutable();
    String task =
        mining.attempt(
            actorId(),
            dimension(),
            pos(p),
            blockState(event.getState()),
            stack(player.getMainHandItem()),
            player.getInventory().getSelectedSlot(),
            tick());
    if (task != null)
      player
          .level()
          .getServer()
          .schedule(
              new net.minecraft.server.TickTask(
                  player.level().getServer().getTickCount(),
                  () ->
                      mining.complete(
                          task,
                          actorId(),
                          dimension(),
                          blockState(player.level().getBlockState(p)),
                          stack(player.getMainHandItem()),
                          player.getInventory().getSelectedSlot(),
                          tick(),
                          event.isCanceled())));
  }

  private Step mine(Task t) {
    BlockPos p = block(child(t.parameters, "position"));
    JsonObject receipt = mining.peek(t.id);
    if (receipt != null) {
      t.confirmed.addProperty("mined_blocks", 1);
      t.confirmed.add("mining", receipt);
      confirmBlock(t, p, child(receipt, "after"));
      return Step.success(receipt);
    }
    if (mining.error(t.id) != null)
      throw new BridgeException(mining.error(t.id), "Block break rejected");
    if (mining.interrupted(t.id))
      throw new BridgeException("mining_interrupted", "Mining interrupted");
    BlockHitResult hit = reach(p);
    BlockState state = player.level().getBlockState(p);
    if (state.isAir())
      return Step.success(object("outcome", "already_empty", "source", "integrated_server"));
    menuReady();
    if (player.containerMenu != player.inventoryMenu)
      throw new BridgeException("interface_open", "Close menu before mining");
    if (state.getDestroySpeed(player.level(), p) < 0)
      throw new BridgeException("permission_denied", "Unbreakable block");
    if (!t.context.has("mine_at")) {
      boolean required =
          bool(t.parameters, "require_tool", true) && state.requiresCorrectToolForDrops();
      if (bool(t.parameters, "auto_tool", true)) {
        int best = player.getInventory().getSelectedSlot();
        float speed = player.getMainHandItem().getDestroySpeed(state);
        for (int i = 0; i < 36; i++) {
          ItemStack tool = player.getInventory().getItem(i);
          if (required && !tool.isCorrectToolForDrops(state)) continue;
          if (!tool.canDestroyBlock(state, player.level(), p, player)) continue;
          float candidate = tool.getDestroySpeed(state);
          if (candidate > speed) {
            speed = candidate;
            best = i;
          }
        }
        if (best != player.getInventory().getSelectedSlot()) {
          selectItem(t, itemId(player.getInventory().getItem(best)));
          return Step.waiting(object("stage", "tool_selected"));
        }
      }
      if (required && !player.hasCorrectToolForDrops(state))
        throw new BridgeException("missing_tool", "Correct harvesting tool required");
      if (!player.getMainHandItem().canDestroyBlock(state, player.level(), p, player))
        throw new BridgeException("permission_denied", "Held item cannot break block");
      ActionBudget.require(t, 2);
      mining.begin(
          t.id,
          pos(p),
          blockState(state),
          blockState(state.getFluidState().createLegacyBlock()),
          tick());
      mining.arm(t.id, tick());
      look(hit.getLocation());
      breaking = p;
      player.gameMode.handleBlockBreakAction(
          p,
          ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
          hit.getDirection(),
          player.level().getMaxY(),
          0);
      t.actions++;
      t.context.addProperty("mine_at", tick());
      t.context.add("mine_original", blockState(state));
      return Step.waiting(object("stage", "mining"));
    }
    if (!blockState(state).equals(child(t.context, "mine_original")) && !mining.pending(t.id))
      throw new BridgeException("target_changed", "Mining block changed without receipt");
    if (t.context.has("mine_stop")) {
      if (tick() - number(t.context, "mine_stop", tick()) > 20)
        throw new BridgeException(
            "mining_unconfirmed", "Break event did not confirm; stop is not repeated");
      return Step.waiting(object("stage", "confirming_break"));
    }
    look(hit.getLocation());
    player.swing(InteractionHand.MAIN_HAND);
    double progress =
        state.getDestroyProgress(player, player.level(), p)
            * (tick() - number(t.context, "mine_at", tick()) + 1);
    if (progress >= 1) {
      ActionBudget.require(t, 1);
      mining.arm(t.id, tick());
      player.gameMode.handleBlockBreakAction(
          p,
          ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
          hit.getDirection(),
          player.level().getMaxY(),
          0);
      t.actions++;
      t.context.addProperty("mine_stop", tick());
      breaking = null;
    }
    return Step.waiting(object("stage", "mining", "progress", Math.min(progress, 1)));
  }

  void clickBlock(
      net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
    if (event.getEntity() != player) return;
    placement.clicked(
        actorId(),
        dimension(),
        event.getHand() == InteractionHand.MAIN_HAND ? "main" : "off",
        pos(event.getPos()),
        event.getFace() == null ? "" : event.getFace().getName(),
        stack(player.getMainHandItem()),
        player.getInventory().getSelectedSlot(),
        tick());
  }

  void blockPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
    if (event.getEntity() != player) return;
    var snapshots =
        event instanceof net.neoforged.neoforge.event.level.BlockEvent.EntityMultiPlaceEvent multi
            ? multi.getReplacedBlockSnapshots()
            : List.of(event.getBlockSnapshot());
    JsonArray cells = new JsonArray();
    for (var s : snapshots)
      cells.add(
          object(
              "position",
              pos(s.getPos()),
              "before",
              blockState(s.getState()),
              "after",
              blockState(player.level().getBlockState(s.getPos()))));
    String task = placement.attempt(actorId(), dimension(), cells, tick());
    if (task == null) return;
    try {
      for (var s : snapshots) {
        loaded(s.getPos());
        if (!s.getState().canBeReplaced())
          throw new BridgeException("build_conflict", "Occupied secondary cell");
      }
    } catch (BridgeException e) {
      event.setCanceled(true);
      placement.reject(task, e.code);
    }
    placeEvents.put(task, cells);
    canceledPlaces.put(task, event.isCanceled());
  }

  private boolean matches(BlockState state, String type, JsonObject wanted) {
    if (!blockId(state).equals(type)) return false;
    JsonObject actual = properties(state);
    for (var e : wanted.entrySet())
      if (!actual.has(e.getKey()) || !actual.get(e.getKey()).equals(e.getValue())) return false;
    return true;
  }

  private BlockHitResult placementHit(BlockPos p, JsonObject wanted, Vec3 eye) {
    for (Direction face : Direction.values()) {
      if (wanted.has("axis") && !face.getAxis().getName().equals(string(wanted, "axis"))) continue;
      BlockPos support = p.relative(face.getOpposite());
      try {
        loaded(support);
      } catch (BridgeException denied) {
        continue;
      }
      if (player.level().getBlockState(support).canBeReplaced()) continue;
      Vec3 aim =
          Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(.499));
      if (eye.distanceTo(aim) > player.blockInteractionRange()) continue;
      var h =
          player
              .level()
              .clip(
                  new ClipContext(
                      eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
      if (h.getType() == HitResult.Type.BLOCK
          && h.getBlockPos().equals(support)
          && h.getDirection() == face) return h;
    }
    return null;
  }

  private Step place(Task t, BlockPos p, String type, JsonObject wanted) {
    loaded(p);
    BlockState original = player.level().getBlockState(p);
    if (matches(original, type, wanted))
      return Step.success(
          object("outcome", "already_present", "position", pos(p), "source", "integrated_server"));
    if (!original.canBeReplaced())
      throw new BridgeException("build_conflict", "Explicit break required for occupied block");
    if (player.getBoundingBox().intersects(new AABB(p)))
      throw new BridgeException("placement_obstructed", "Walk out of target block");
    menuReady();
    if (player.containerMenu != player.inventoryMenu)
      throw new BridgeException("interface_open", "Close menu before placement");
    ItemStack material = player.getMainHandItem();
    if (!(material.getItem() instanceof BlockItem item)
        || !BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString().equals(type)) {
      selectItem(t, type);
      return Step.waiting(object("stage", "material_selected"));
    }
    BlockHitResult selected = placementHit(p, wanted, player.getEyePosition());
    if (selected == null)
      throw new BridgeException("out_of_reach", "No reachable visible support face");
    player.stopInputs();
    look(selected.getLocation());
    if (wanted.has("facing")) {
      String facing = string(wanted, "facing");
      float yaw =
          switch (facing) {
            case "south" -> 0;
            case "west" -> 90;
            case "north" -> 180;
            case "east" -> -90;
            default -> throw new BridgeException("invalid_request", "Unsupported facing");
          };
      if (!(item.getBlock() instanceof DoorBlock) && !(item.getBlock() instanceof BedBlock))
        yaw += 180;
      player.setYRot(yaw);
      player.setYHeadRot(yaw);
    }
    BlockPlaceContext context =
        new BlockPlaceContext(player, InteractionHand.MAIN_HAND, material, selected);
    BlockState predicted = item.getBlock().getStateForPlacement(context);
    if (predicted == null
        || !predicted.canSurvive(player.level(), p)
        || !player.level().isUnobstructed(predicted, p, CollisionContext.placementContext(player))
        || !matches(predicted, type, wanted))
      throw new BridgeException(
          "placement_obstructed", "Placement or requested properties unsupported here");
    ActionBudget.require(t, 1);
    ItemStack before = material.copy();
    String receiptKey = t.id + ":" + tick();
    placement.begin(
        receiptKey,
        pos(p),
        blockState(original),
        type,
        wanted,
        pos(selected.getBlockPos()),
        selected.getDirection().getName(),
        stack(before),
        player.getInventory().getSelectedSlot(),
        tick());
    materials.put(receiptKey, before);
    // Event attribution uses the unique pending click, not a guessed task success.
    player.gameMode.useItemOn(
        player, player.level(), material, InteractionHand.MAIN_HAND, selected);
    player.swing(InteractionHand.MAIN_HAND);
    t.actions++;
    JsonArray attempted = placeEvents.remove(receiptKey);
    if (attempted != null) {
      JsonArray actual = new JsonArray();
      for (JsonElement e : attempted) {
        JsonObject cell = e.getAsJsonObject();
        actual.add(
            object(
                "position",
                cell.get("position"),
                "before",
                cell.get("before"),
                "after",
                blockState(player.level().getBlockState(block(child(cell, "position"))))));
      }
      placement.complete(
          receiptKey,
          actorId(),
          dimension(),
          actual,
          stack(player.getMainHandItem()),
          player.getInventory().getSelectedSlot(),
          tick(),
          Boolean.TRUE.equals(canceledPlaces.remove(receiptKey)),
          ItemStack.matches(before.copyWithCount(before.getCount() - 1), player.getMainHandItem()));
    }
    String placementError = placement.error(receiptKey);
    JsonObject receipt = placement.finish(receiptKey);
    materials.remove(receiptKey);
    if (receipt == null)
      throw new BridgeException(
          placementError == null ? "placement_unconfirmed" : placementError,
          "Vanilla placement did not confirm a block event and consumed item");
    t.confirmed.add("placement", receipt);
    int changed = 0;
    for (JsonElement e : receipt.getAsJsonArray("cells")) {
      JsonObject cell = e.getAsJsonObject();
      if (!cell.get("before").equals(cell.get("after"))) {
        changed++;
        confirmBlock(t, block(child(cell, "position")), child(cell, "after"));
      }
    }
    t.confirmed.addProperty(
        "placed_blocks", integer(t.confirmed, "placed_blocks", 0, 0, Integer.MAX_VALUE) + changed);
    return matches(player.level().getBlockState(p), type, wanted)
        ? Step.success(receipt)
        : Step.failure("placement_mismatch", "Actual properties differ", receipt);
  }

  private void confirmBlock(Task task, BlockPos p, JsonObject state) {
    JsonArray updates =
        task.confirmed.has("block_updates")
            ? task.confirmed.getAsJsonArray("block_updates")
            : new JsonArray();
    JsonObject update = pos(p);
    update.addProperty("dimension", dimension());
    update.addProperty("type", string(state, "type"));
    update.add("properties", child(state, "properties"));
    update.addProperty("tick", tick());
    updates.add(update);
    task.confirmed.add("block_updates", updates);
  }

  private JsonObject targetState(Entity target) {
    JsonObject state = object("entity_id", target.getUUID().toString());
    if (target instanceof net.minecraft.world.entity.animal.Animal animal) {
      state.addProperty("baby", animal.isBaby());
      state.addProperty("in_love", animal.isInLove());
    }
    if (target instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep) {
      state.addProperty("sheared", sheep.isSheared());
      state.addProperty("color", sheep.getColor().getName());
    }
    return state;
  }

  private Step interact(Task t) {
    menuReady();
    InteractionHand hand = hand(t.parameters);
    ActionBudget.require(t, 1);
    int beforeMenu = player.containerMenu.containerId;
    ItemStack held = player.getItemInHand(hand).copy();
    JsonObject before;
    BlockPos p = null;
    Entity target = null;
    if (t.parameters.has("position")) {
      p = block(child(t.parameters, "position"));
      var hit = reach(p);
      before = blockState(player.level().getBlockState(p));
      look(hit.getLocation());
      player.gameMode.useItemOn(player, player.level(), player.getItemInHand(hand), hand, hit);
    } else {
      target = entity(t.parameters);
      if (!player.isWithinEntityInteractionRange(target, 0))
        throw new BridgeException("out_of_reach", "Walk closer to entity");
      before = targetState(target);
      look(target.getBoundingBox().getCenter());
      player.interactOn(
          target, hand, target.getBoundingBox().getCenter().subtract(target.position()));
    }
    t.actions++;
    player.swing(hand);
    JsonObject after =
        p != null ? blockState(player.level().getBlockState(p)) : targetState(target);
    boolean opened = player.containerMenu.containerId != beforeMenu,
        changed = !before.equals(after) || !ItemStack.matches(held, player.getItemInHand(hand));
    JsonObject result =
        object(
            "outcome",
            opened ? "menu_opened" : changed ? "target_changed" : "unconfirmed",
            "menu_id",
            player.containerMenu.containerId,
            "before",
            before,
            "after",
            after,
            "held_before",
            stack(held),
            "held_after",
            stack(player.getItemInHand(hand)),
            "source",
            "integrated_server_interaction");
    if (opened || changed) t.confirmed.addProperty("interactions", 1);
    if (!opened && (t.action.equals("container.open") || !changed))
      return Step.failure(
          "interaction_unconfirmed", "No matching new menu or observable effect", result);
    return Step.success(result);
  }

  private RecipeCatalog catalog() {
    if (recipes == null) recipes = RecipeCatalog.load(player.level().getServer());
    return recipes;
  }

  private record CraftPreparation(CraftPlanner.Plan plan, BlockPos station) {}

  private CraftPreparation prepareCraft(JsonObject data) {
    Identifier.parse(string(data, "item"));
    CraftSequence.validate(data);
    String item = string(data, "item");
    int count = integer(data, "count", 1, 1, 4096);
    Set<String> stations =
        player.containerMenu instanceof CraftingMenu
            ? Set.of("inventory", "crafting_table")
            : Set.of("inventory");
    var plan = catalog().planner().plan(item, count, counts(), stations);
    BlockPos table = null;
    if (plan.missingStations().contains("crafting_table")
        && player.containerMenu == player.inventoryMenu) {
      if (data.has("station_position")) {
        table = block(child(data, "station_position"));
        reach(table);
        if (!player.level().getBlockState(table).is(Blocks.CRAFTING_TABLE))
          throw new BridgeException("invalid_target", "station_position must be a crafting table");
      } else if (bool(data, "auto_station", true)) {
        double best = Double.MAX_VALUE;
        for (BlockPos p :
            BlockPos.betweenClosed(
                player.blockPosition().offset(-4, -4, -4),
                player.blockPosition().offset(4, 4, 4))) {
          if (!player.level().hasChunkAt(p)
              || !player.level().getBlockState(p).is(Blocks.CRAFTING_TABLE)
              || !player.isWithinBlockInteractionRange(p, 0)
              || hit(p) == null) continue;
          try {
            area(Vec3.atCenterOf(p));
          } catch (BridgeException denied) {
            continue;
          }
          double distance = player.position().distanceToSqr(Vec3.atCenterOf(p));
          if (distance < best) {
            best = distance;
            table = p.immutable();
          }
        }
      }
      if (table != null)
        plan =
            catalog().planner().plan(item, count, counts(), Set.of("inventory", "crafting_table"));
    }
    return new CraftPreparation(plan, table);
  }

  private Step craft(Task t) {
    menuReady();
    CraftPreparation prepared = prepareCraft(t.parameters);
    var plan = prepared.plan();
    if (!plan.executable())
      return Step.failure(
          plan.missing().isEmpty() ? "missing_station" : "missing_materials",
          "Craft cannot be completed from companion inventory and reachable stations",
          GSON.toJsonTree(plan).getAsJsonObject());
    if (plan.stages().isEmpty())
      return Step.success(
          object(
              "item",
              string(t.parameters, "item"),
              "inventory",
              counts(),
              "source",
              "integrated_server"));
    if (prepared.station() != null && !(player.containerMenu instanceof CraftingMenu)) {
      ActionBudget.require(t, 1);
      var hit = reach(prepared.station());
      player.gameMode.useItemOn(
          player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
      t.actions++;
      if (!(player.containerMenu instanceof CraftingMenu))
        throw new BridgeException("craft_unconfirmed", "Crafting table failed to open");
      t.confirmed.add(
          "craft_station",
          object(
              "position",
              pos(prepared.station()),
              "menu_id",
              player.containerMenu.containerId,
              "source",
              "integrated_server"));
      return Step.waiting(object("stage", "crafting_table_open"));
    }
    if (!(player.containerMenu instanceof AbstractCraftingMenu menu))
      throw new BridgeException("interface_open", "Close unrelated menu before crafting");
    var stage = plan.stages().getFirst();
    var recipe = catalog().entries.get(stage.recipe());
    if (recipe.width() > menu.getGridWidth() || recipe.height() > menu.getGridHeight())
      throw new BridgeException("missing_station", "Crafting grid too small");
    for (Slot grid : menu.getInputGridSlots())
      if (grid.hasItem())
        throw new BridgeException(
            "craft_grid_occupied", "Craft grid must be empty before a new craft");
    int ingredient = 0;
    List<Slot> sources = new ArrayList<>(), targets = new ArrayList<>();
    Map<Integer, Integer> usage = new HashMap<>();
    for (int cell = 0; cell < recipe.grid().size(); cell++) {
      if (recipe.grid().get(cell).isEmpty()) continue;
      String wanted = stage.ingredients().get(ingredient++);
      Slot source = null;
      for (Slot slot : menu.slots)
        if (slot.container == player.getInventory()
            && slot.getContainerSlot() < 36
            && itemId(slot.getItem()).equals(wanted)
            && slot.getItem().getCount() > usage.getOrDefault(slot.index, 0)) {
          source = slot;
          break;
        }
      if (source == null)
        throw new BridgeException("missing_materials", "Craft ingredient changed");
      usage.merge(source.index, 1, Integer::sum);
      sources.add(source);
      targets.add(
          menu.getInputGridSlots()
              .get((cell / recipe.width()) * menu.getGridWidth() + cell % recipe.width()));
    }
    ActionBudget.require(t, sources.size() * 3 + 1);
    int baseline = counts().getOrDefault(stage.item(), 0);
    try {
      for (int i = 0; i < sources.size(); i++) {
        Slot source = sources.get(i), target = targets.get(i);
        String type = itemId(source.getItem());
        menu.clicked(source.index, 0, ContainerInput.PICKUP, player);
        menu.clicked(target.index, 1, ContainerInput.PICKUP, player);
        menu.clicked(source.index, 0, ContainerInput.PICKUP, player);
        t.actions += 3;
        if (!menu.getCarried().isEmpty()
            || target.getItem().getCount() != 1
            || !itemId(target.getItem()).equals(type))
          throw new BridgeException("craft_unconfirmed", "Ingredient menu input failed");
      }
      if (!itemId(menu.getResultSlot().getItem()).equals(stage.item()))
        throw new BridgeException("craft_unconfirmed", "Result differs from planned recipe");
      if (player.getInventory().getFreeSlot() < 0
          && player.getInventory().getSlotWithRemainingSpace(menu.getResultSlot().getItem()) < 0)
        throw new BridgeException("inventory_full", "No space for output");
      menu.clicked(menu.getResultSlot().index, 0, ContainerInput.QUICK_MOVE, player);
      t.actions++;
      if (counts().getOrDefault(stage.item(), 0) < baseline + recipe.recipe().count())
        throw new BridgeException("craft_unconfirmed", "Crafted output was not received");
      t.confirmed.addProperty(
          "crafts_confirmed",
          integer(t.confirmed, "crafts_confirmed", 0, 0, Integer.MAX_VALUE) + 1);
      return Step.waiting(
          object(
              "item",
              stage.item(),
              "output",
              recipe.recipe().count(),
              "source",
              "integrated_server_menu"));
    } finally {
      player.closeContainer();
    }
  }

  private BuildPlanner.Plan buildPlan(JsonObject p) {
    return new BuildPlanner()
        .plan(
            p,
            type -> {
              Identifier id = Identifier.parse(type);
              if (!id.getNamespace().equals("minecraft")) return BuildPlanner.Kind.SINGLE;
              var block = BuiltInRegistries.BLOCK.getValue(id);
              return block instanceof DoorBlock
                  ? BuildPlanner.Kind.DOOR
                  : block instanceof BedBlock ? BuildPlanner.Kind.BED : BuildPlanner.Kind.SINGLE;
            });
  }

  private Step build(Task t) {
    var plan = builds.computeIfAbsent(t.id, id -> buildPlan(t.parameters));
    for (var b : plan.blocks()) loaded(new BlockPos(b.x(), b.y(), b.z()));
    int index = integer(t.context, "build_index", 0, 0, 4096);
    if (index >= plan.placements().size())
      return Step.success(
          object(
              "blocks",
              plan.blocks().size(),
              "placed",
              integer(t.confirmed, "placed_blocks", 0, 0, Integer.MAX_VALUE),
              "source",
              "integrated_server"));
    var group = plan.placements().get(index);
    boolean ready =
        group.cells().stream()
            .allMatch(
                b ->
                    matches(
                        player.level().getBlockState(new BlockPos(b.x(), b.y(), b.z())),
                        b.type(),
                        b.properties()));
    if (ready) {
      t.context.addProperty("build_index", index + 1);
      t.confirmed.addProperty(
          "matched_blocks",
          integer(t.confirmed, "matched_blocks", 0, 0, Integer.MAX_VALUE) + group.cells().size());
      return Step.waiting(object("stage", "matched", "index", index));
    }
    for (var b : group.cells()) {
      BlockPos p = new BlockPos(b.x(), b.y(), b.z());
      loaded(p);
      if (!player.level().getBlockState(p).canBeReplaced())
        throw new BridgeException(
            "build_conflict", "Occupied blueprint cell; explicit break required");
    }
    var primary = group.primary();
    BlockPos p = new BlockPos(primary.x(), primary.y(), primary.z());
    if (player.getBoundingBox().intersects(new AABB(p))
        || placementHit(p, primary.properties(), player.getEyePosition()) == null) {
      Vec3 stand = null;
      for (int dx = -2; dx <= 2 && stand == null; dx++)
        for (int dz = -2; dz <= 2 && stand == null; dz++) {
          BlockPos at = new BlockPos(p.getX() + dx, player.blockPosition().getY(), p.getZ() + dz);
          if (!safeCell(player.level(), player, at)
              || placementHit(
                      p,
                      primary.properties(),
                      new Vec3(at.getX() + .5, at.getY() + player.getEyeHeight(), at.getZ() + .5))
                  == null
              || plan.blocks().stream()
                  .anyMatch(
                      b ->
                          b.x() == at.getX()
                              && b.z() == at.getZ()
                              && Math.abs(b.y() - at.getY()) < 2)) continue;
          try {
            Vec3 target = new Vec3(at.getX() + .5, at.getY(), at.getZ() + .5);
            path(target);
            stand = target;
          } catch (BridgeException ignored) {
          }
        }
      if (stand == null)
        throw new BridgeException("path_unreachable", "No safe stand outside blueprint");
      Step approach = move(t, stand, .15);
      return approach.done() ? Step.waiting(object("stage", "approaching_support")) : approach;
    }
    Step result = place(t, p, primary.type(), primary.properties());
    if (result.done()) {
      if (result.error() != null) return result;
      if (group.cells().stream()
          .anyMatch(
              b ->
                  !matches(
                      player.level().getBlockState(new BlockPos(b.x(), b.y(), b.z())),
                      b.type(),
                      b.properties())))
        throw new BridgeException("placement_mismatch", "Secondary blueprint block not confirmed");
      t.context.addProperty("build_index", index + 1);
    }
    return Step.waiting(
        object("stage", "building", "index", index, "total", plan.placements().size()));
  }

  public JsonObject query(String operation, JsonObject data) {
    return switch (operation) {
      case "inventory.inspect" -> {
        JsonObject observed = snapshot(1, false);
        yield object("inventory", observed.get("inventory"), "menu", observed.get("context"));
      }
      case "route.plan" ->
          object(
              "path",
              path(point(child(data, "target"))),
              "modes",
              movementModes(),
              "unknown_cells",
              "excluded");
      case "craft.plan" -> {
        var prepared = prepareCraft(data);
        JsonObject result = GSON.toJsonTree(prepared.plan()).getAsJsonObject();
        if (prepared.station() != null)
          result.add(
              "station",
              object(
                  "position",
                  pos(prepared.station()),
                  "type",
                  "minecraft:crafting_table",
                  "source",
                  "companion_observation"));
        yield result;
      }
      case "build.plan" -> {
        var plan = buildPlan(data);
        for (var b : plan.blocks()) loaded(new BlockPos(b.x(), b.y(), b.z()));
        Map<String, Integer> needed = new HashMap<>(), missing = new HashMap<>();
        JsonArray conflicts = new JsonArray();
        for (var group : plan.placements()) {
          boolean ready =
              group.cells().stream()
                  .allMatch(
                      b ->
                          matches(
                              player.level().getBlockState(new BlockPos(b.x(), b.y(), b.z())),
                              b.type(),
                              b.properties()));
          if (!ready) {
            needed.merge(group.primary().type(), 1, Integer::sum);
            for (var b : group.cells()) {
              BlockPos p = new BlockPos(b.x(), b.y(), b.z());
              loaded(p);
              if (!player.level().getBlockState(p).canBeReplaced()) conflicts.add(pos(p));
            }
          }
        }
        Map<String, Integer> have = counts();
        needed.forEach(
            (k, v) -> {
              if (v > have.getOrDefault(k, 0)) missing.put(k, v - have.getOrDefault(k, 0));
            });
        yield object(
            "blocks",
            plan.blocks(),
            "materials",
            needed,
            "missing",
            missing,
            "conflicts",
            conflicts,
            "executable",
            missing.isEmpty() && conflicts.isEmpty(),
            "replacement",
            "explicit_break_required");
      }
      default -> throw new BridgeException("unsupported_capability", operation);
    };
  }

  public boolean awaitingConfirmation(Task t) {
    return switch (t.action) {
      case "move.to", "move.relative", "move.follow", "item.pickup" -> t.context.has("walking");
      case "build.execute" -> buildAwaitingConfirmation(t);
      case "craft.execute" ->
          counts().getOrDefault(string(t.parameters, "item"), 0)
              >= integer(t.parameters, "count", 1, 1, 4096);
      case "move.jump" -> jumps.containsKey(t.id);
      case "move.crouch" -> t.context.has("crouch");
      case "item.use" -> t.context.has("use_at");
      case "block.break" -> mining.pending(t.id) || t.context.has("mine_stop");
      case "combat.attack" -> melee.containsKey(t.id);
      default -> false;
    };
  }

  private boolean buildAwaitingConfirmation(Task t) {
    if (t.context.has("walking")) return true;
    var plan = builds.get(t.id);
    if (plan == null) return false;
    int index = integer(t.context, "build_index", 0, 0, 4096);
    if (index >= plan.placements().size()) return true;
    return plan.placements().get(index).cells().stream()
        .allMatch(
            b -> {
              BlockPos p = new BlockPos(b.x(), b.y(), b.z());
              return player.level().hasChunkAt(p)
                  && matches(player.level().getBlockState(p), b.type(), b.properties());
            });
  }

  private void collectCombat(Task t) {
    var receipt = combat.peek(t.id);
    if (receipt != null) {
      t.confirmed.addProperty("hits", receipt.hits());
      t.confirmed.addProperty("damage", receipt.damage());
      t.confirmed.addProperty("defeated", receipt.defeated());
    }
  }

  public void collectConfirmed(Task t) {
    if (t.action.equals("item.pickup") && t.context.has("pickup_at")) {
      var pickup =
          pickups.peek(string(t.parameters, "entity_id"), t.context.get("pickup_at").getAsLong());
      if (pickup != null) t.confirmed.addProperty("pickup_count", pickup.collected());
    }
    if (t.action.equals("combat.attack")) collectCombat(t);
    JsonObject receipt = mining.peek(t.id);
    if (receipt != null) {
      if (!t.confirmed.has("mining"))
        confirmBlock(t, block(child(receipt, "position")), child(receipt, "after"));
      t.confirmed.add("mining", receipt);
      t.confirmed.addProperty("mined_blocks", 1);
    }
    if (jumps.containsKey(t.id)) t.confirmed.addProperty("jumps", jumps.get(t.id).completed());
  }

  public void taskFinished(Task t) {
    if (isCare(t, "equip")) {
      equipmentRetry = tick() + (t.state == Task.State.succeeded ? 5 : 100);
      equipmentExpected.remove(t.id);
    } else if (isCare(t, "regroup")) {
      regroupRetry = tick() + (t.state == Task.State.succeeded ? 40 : 200);
    }
    if (careTask == t) {
      careTask = null;
      reflex = "";
    }
    if (isDefence(t)) {
      lastDefence = defenceSummary(t);
      lastDefenceTick = tick();
      threats.cooldown(string(t.parameters, "entity_id"), tick());
      if (defenceTask == t) {
        defenceTask = null;
        reflex = "";
      }
    }
    routes.remove(t.id);
    builds.remove(t.id);
    lastTask =
        object("action", t.action, "state", t.state.name(), "error", t.error, "tick", tick());
    if (t.action.equals("item.pickup")) pickups.discard(string(t.parameters, "entity_id"));
    jumps.remove(t.id);
    follows.remove(t.id);
    melee.remove(t.id);
    mining.end(t.id);
    combat.end(t.id);
    if (current == t) current = null;
  }

  public void releaseControls() {
    player.stopInputs();
    player.stopUsingItem();
    routes.clear();
    if (breaking != null) {
      player.gameMode.handleBlockBreakAction(
          breaking,
          ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
          Direction.UP,
          player.level().getMaxY(),
          0);
      breaking = null;
    }
    mining.suspend();
    placement.suspend();
    follows.values().forEach(FollowSequence::released);
    melee.values().forEach(MeleeSequence::released);
    if (current != null) current.context.remove("walking");
    stopReflex();
  }

  String reflex() {
    return reflex;
  }

  void stopReflex() {
    if (!reflex.isEmpty() || reflexSlot >= 0) {
      player.stopInputs();
      player.stopUsingItem();
      if (reflexSlot >= 0) player.getInventory().setSelectedSlot(reflexSlot);
      reflex = "";
      reflexSlot = -1;
      if (retreat != null) routes.remove(retreat.id);
      retreat = null;
      retreatTarget = null;
      defenceTask = null;
      careTask = null;
    }
  }

  boolean survivalReflex(BridgeEngine engine, boolean defend, boolean selfDirected) {
    if (defenceTask != null) return true;
    if (reflex.equals("eat")) {
      player.stopInputs();
      if (!player.isUsingItem() || tick() >= reflexEnd) {
        stopReflex();
        reflexRetry = tick() + 40;
        return false;
      }
      return true;
    }
    if (reflex.equals("retreat")) {
      try {
        if (tick() >= reflexEnd || move(retreat, retreatTarget, 1.2).done()) {
          stopReflex();
          reflexRetry = tick() + 100;
          return false;
        }
        return true;
      } catch (BridgeException unreachable) {
        stopReflex();
        reflexRetry = tick() + 100;
        return false;
      }
    }
    if (!reflex.isEmpty() && careTask == null) {
      player.stopInputs();
      if (tick() >= reflexEnd) {
        stopReflex();
        return false;
      }
      player.movement(false, true, false, false);
      return true;
    }
    if (tick() < reflexRetry) return careTask != null;
    if (player.isInWater() && player.getAirSupply() < 100 || player.isInLava()) {
      try {
        policy.check("move.jump", object(), dimension());
        area(player.position());
      } catch (BridgeException denied) {
        reflexRetry = tick() + 100;
        return false;
      }
      engine.actorInterrupt("Компаньон всплывает из опасной жидкости.");
      reflex = "surface";
      reflexEnd = tick() + 40;
      player.movement(false, true, false, false);
      return true;
    }
    ServerPlayer human = owner.get();
    LivingEntity threat = player.getLastHurtByMob();
    Optional<CompanionThreats.Threat> selected = selectedThreat(null);
    if ((threat == null || !threat.isAlive()) && selected.isPresent())
      threat = (LivingEntity) player.level().getEntity(UUID.fromString(selected.get().id()));
    if (player.getHealth() <= 6
        && threat != null
        && !(threat instanceof Player)
        && threat.isAlive()
        && player.distanceTo(threat) < 8
        && human != null
        && human.level() == player.level()
        && !player.isInWater()
        && !player.isInLava()) {
      try {
        policy.check("move.to", object("target", vector(human.position())), dimension());
        area(player.position());
        Vec3 target = retreatPosition(threat, human);
        if (target != null) {
          engine.actorInterrupt("Низкое здоровье: компаньон отходит от угрозы к владельцу.");
          reflex = "retreat";
          retreatTarget = target;
          retreat = new Task("move.to", object("sprint", true), 0, tick() + 160, 1);
          reflexEnd = tick() + 160;
          return true;
        }
      } catch (BridgeException denied) {
        reflexRetry = tick() + 100;
      }
    }
    if (defend && player.getHealth() > 6 && selected.isPresent()) {
      var danger = selected.get();
      Entity target = player.level().getEntity(UUID.fromString(danger.id()));
      try {
        defenceTask =
            engine.ownerReaction(
                "combat.attack",
                object(
                    "entity_id",
                    danger.id(),
                    "chase",
                    true,
                    "max_chase_distance",
                    12,
                    "stop_health",
                    6),
                200,
                16,
                danger.reason().startsWith("owner_")
                    ? "Моб атакует владельца: компаньон помогает."
                    : "Компаньон защищается от моба.");
        defenceTask.context.addProperty("threat_reason", danger.reason());
        defenceTask.context.addProperty(
            "threat_type", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
        defenceTask.context.addProperty("threat_priority", danger.priority());
        reflex = danger.reason().startsWith("owner_") ? "defend_owner" : "defend_self";
        return true;
      } catch (BridgeException denied) {
        threats.cooldown(danger.id(), tick());
      }
    }
    if (player.getFoodData().getFoodLevel() <= 14
        && !player.isUsingItem()
        && player.containerMenu == player.inventoryMenu
        && player.containerMenu.getCarried().isEmpty()) {
      int food = -1;
      for (int i = 0; i < 36; i++) {
        var s = player.getInventory().getItem(i);
        var nutrition = s.get(DataComponents.FOOD);
        if (nutrition != null
            && nutrition.nutrition() > 0
            && !s.is(Items.ROTTEN_FLESH)
            && !s.is(Items.SPIDER_EYE)
            && !s.is(Items.PUFFERFISH)
            && !s.is(Items.POISONOUS_POTATO)) {
          food = i;
          break;
        }
      }
      if (food >= 0) {
        try {
          policy.check("item.use", object(), dimension());
          policy.check("inventory.select_item", object(), dimension());
          area(player.position());
        } catch (BridgeException e) {
          reflexRetry = tick() + 100;
          return false;
        }
        engine.actorInterrupt("Компаньон ест из собственного инвентаря.");
        reflexSlot = player.getInventory().getSelectedSlot();
        if (food < 9) player.getInventory().setSelectedSlot(food);
        else {
          player.inventoryMenu.clicked(
              inventorySlot(food).index, reflexSlot, ContainerInput.SWAP, player);
        }
        var result =
            player.gameMode.useItem(
                player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (result.consumesAction() && player.isUsingItem()) {
          reflex = "eat";
          reflexEnd = tick() + 80;
          return true;
        }
        stopReflex();
        reflexRetry = tick() + 100;
      }
    }
    if (careTask != null) return true;
    if (selfDirected
        && player.containerMenu == player.inventoryMenu
        && player.containerMenu.getCarried().isEmpty()
        && !player.isUsingItem()) {
      if (human != null
          && tick() >= regroupRetry
          && human.level() == player.level()
          && player.distanceTo(human) > 24
          && CompanionCare.regroup(
              player.distanceTo(human),
              human.level() == player.level(),
              !human.isInvisibleTo(player) && player.hasLineOfSight(human))) {
        try {
          area(player.position());
          area(human.position());
          path(human.position());
          careTask =
              engine.ownerReaction(
                  "regroup",
                  "move.follow",
                  object(
                      "entity_id",
                      human.getUUID().toString(),
                      "ticks",
                      20,
                      "distance",
                      6,
                      "sprint",
                      true),
                  600,
                  32,
                  "Компаньон возвращается ближе к владельцу.");
          reflex = "regroup";
          return true;
        } catch (BridgeException denied) {
          regroupRetry = tick() + 200;
        }
      }
      if (tick() >= equipmentRetry && automaticArmor(engine)) return true;
    }
    return false;
  }

  private static boolean isCare(Task task, String kind) {
    return task.context.has("reaction") && task.context.get("reaction").getAsString().equals(kind);
  }

  private boolean automaticArmor(BridgeEngine engine) {
    List<CompanionCare.Armor> candidates = new ArrayList<>();
    Set<String> occupied = new HashSet<>();
    for (int i = 36; i < 40; i++)
      if (!player.getInventory().getItem(i).isEmpty()) occupied.add(equipment(i));
    for (int i = 0; i < 36; i++) {
      ItemStack stack = player.getInventory().getItem(i);
      var equippable = stack.get(DataComponents.EQUIPPABLE);
      if (stack.isEmpty() || equippable == null) continue;
      EquipmentSlot slot = equippable.slot();
      int inventory =
          switch (slot) {
            case HEAD -> 39;
            case CHEST -> 38;
            case LEGS -> 37;
            case FEET -> 36;
            default -> -1;
          };
      if (inventory < 0 || !inventorySlot(inventory).mayPlace(stack)) continue;
      var modifiers =
          stack.getOrDefault(
              DataComponents.ATTRIBUTE_MODIFIERS,
              net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
      candidates.add(
          new CompanionCare.Armor(
              i,
              equipment(inventory),
              modifiers.compute(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR, 0, slot),
              modifiers.compute(
                  net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS, 0, slot),
              stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : -1,
              net.minecraft.world.item.enchantment.EnchantmentHelper.has(
                  stack,
                  net.minecraft.world.item.enchantment.EnchantmentEffectComponents
                      .PREVENT_ARMOR_CHANGE)));
    }
    var best = CompanionCare.armor(candidates, occupied);
    if (best.isEmpty()) {
      equipmentRetry = tick() + 20;
      return false;
    }
    var armor = best.get();
    try {
      careTask =
          engine.ownerReaction(
              "equip",
              "inventory.equip",
              object(
                  "from_slot",
                  inventorySlot(armor.slot()).index,
                  "equipment_slot",
                  armor.equipment(),
                  "menu_id",
                  player.containerMenu.containerId,
                  "menu_revision",
                  player.containerMenu.getStateId()),
              40,
              3,
              "Компаньон надевает броню в пустую ячейку.");
      equipmentExpected.put(careTask.id, player.getInventory().getItem(armor.slot()).copy());
      reflex = "equip";
      return true;
    } catch (BridgeException denied) {
      equipmentRetry = tick() + 100;
      return false;
    }
  }

  private static boolean isDefence(Task task) {
    return task.context.has("reaction")
        && task.context.get("reaction").getAsString().equals("defence");
  }

  void prepareDefence(BridgeEngine engine) {
    if (defenceTask == null || defenceTask.terminal()) return;
    var receipt = combat.peek(defenceTask.id);
    if (receipt != null && receipt.defeated()) return;
    String active = string(defenceTask.parameters, "entity_id");
    selectedThreat(active)
        .ifPresent(
            next -> {
              if (next.id().equals(active)) {
                defenceTask.context.addProperty("threat_reason", next.reason());
                defenceTask.context.addProperty("threat_priority", next.priority());
                reflex = next.reason().startsWith("owner_") ? "defend_owner" : "defend_self";
              } else if (next.priority()
                  < integer(defenceTask.context, "threat_priority", 4, 0, 4)) {
                engine.actorInterrupt("Новая угроза владельцу: компаньон меняет цель защиты.");
              }
            });
  }

  void setDefending(boolean enabled) {
    defendingEnabled = enabled;
  }

  private boolean safeThreat(Mob mob, ServerPlayer human) {
    return mob.isAlive()
        && mob.level() == player.level()
        && !mob.isAlliedTo(player)
        && !player.isAlliedTo(mob)
        && (human == null || !human.isAlliedTo(mob))
        && !(mob instanceof TamableAnimal pet
            && (pet.isOwnedBy(player) || human != null && pet.isOwnedBy(human)));
  }

  private CompanionThreats.Candidate threatCandidate(Mob mob) {
    ServerPlayer human = owner.get();
    return new CompanionThreats.Candidate(
        mob.getUUID().toString(),
        false,
        !safeThreat(mob, human),
        mob.isAlive(),
        !mob.isInvisibleTo(player) && player.hasLineOfSight(mob),
        mob.distanceTo(player),
        human != null && human.level() == player.level()
            ? mob.distanceTo(human)
            : Double.POSITIVE_INFINITY,
        human != null && mob.getTarget() == human,
        mob.getTarget() == player);
  }

  private Optional<CompanionThreats.Threat> selectedThreat(String preferred) {
    return threats.select(
        tick(),
        player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(12)).stream()
            .map(this::threatCandidate)
            .toList(),
        preferred);
  }

  private static double weaponScore(ItemStack stack) {
    var modifiers =
        stack.getOrDefault(
            DataComponents.ATTRIBUTE_MODIFIERS,
            net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
    double damage =
        modifiers.compute(
            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE,
            1,
            EquipmentSlot.MAINHAND);
    double speed =
        modifiers.compute(
            net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED,
            4,
            EquipmentSlot.MAINHAND);
    return Double.isFinite(damage) && Double.isFinite(speed)
        ? Math.max(0, damage) * Math.max(.1, speed)
        : 0;
  }

  private Vec3 retreatPosition(LivingEntity threat, ServerPlayer human) {
    List<Vec3> choices = new ArrayList<>();
    for (int dx = -2; dx <= 2; dx += 2)
      for (int dz = -2; dz <= 2; dz += 2) {
        BlockPos p = human.blockPosition().offset(dx, 0, dz);
        choices.add(new Vec3(p.getX() + .5, p.getY(), p.getZ() + .5));
      }
    for (int dx = -6; dx <= 6; dx += 6)
      for (int dz = -6; dz <= 6; dz += 6) {
        BlockPos p = player.blockPosition().offset(dx, 0, dz);
        choices.add(new Vec3(p.getX() + .5, p.getY(), p.getZ() + .5));
      }
    choices.sort(Comparator.comparingDouble((Vec3 p) -> p.distanceTo(human.position())));
    for (Vec3 candidate : choices) {
      if (candidate.distanceTo(threat.position()) < player.distanceTo(threat) + 2
          || candidate.distanceTo(human.position()) > 64
          || !safeCell(player.level(), player, BlockPos.containing(candidate))) continue;
      try {
        path(candidate);
        return candidate;
      } catch (BridgeException ignored) {
      }
    }
    return null;
  }

  private JsonObject defenceSummary(Task task) {
    return object(
        "task_id",
        task.id,
        "entity_id",
        string(task.parameters, "entity_id"),
        "type",
        string(task.context, "threat_type", "unknown"),
        "reason",
        string(task.context, "threat_reason", "unknown"),
        "state",
        task.state.name(),
        "hits",
        integer(task.confirmed, "hits", 0, 0, Integer.MAX_VALUE),
        "damage",
        number(task.confirmed, "damage", 0),
        "defeated",
        bool(task.confirmed, "defeated", false));
  }

  JsonObject defenceStatus() {
    if (defenceTask != null) return defenceSummary(defenceTask);
    return lastDefence != null && tick() - lastDefenceTick <= 200
        ? lastDefence.deepCopy()
        : object();
  }
}
