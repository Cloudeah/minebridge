package dev.minebridge.mod;

import static dev.minebridge.core.Json.*;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.minebridge.core.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.*;

/** Owns all companion state on the integrated-server thread. UI only queues owner requests. */
final class CompanionManager {
  static final CompanionManager INSTANCE = new CompanionManager();
  private static final Logger LOG = LoggerFactory.getLogger("minebridge-companion");
  private volatile MinecraftServer server;
  private volatile JsonObject status = emptyStatus();
  private CompanionProfile profile;
  private CompoundTag saved;
  private Path file;
  private CompanionPlayer player;
  private CompanionWorld world;
  private BridgeEngine engine;
  private BridgeSocket socket;
  private long nextGoal, deadAt = -1;
  private String dimension = "", error = "";
  private boolean broken;

  private static JsonObject emptyStatus() {
    return object(
        "exists",
        false,
        "present",
        false,
        "armed",
        false,
        "controller_connected",
        false,
        "state",
        "idle",
        "step",
        0,
        "steps",
        8,
        "vision",
        false,
        "detail",
        "Создай компаньона через F6.",
        "autonomous",
        true,
        "health",
        0,
        "food",
        0);
  }

  JsonObject status() {
    return status.deepCopy();
  }

  OwnerControls controls(UUID owner) {
    return new OwnerControls() {
      public String actorMode() {
        return "companion";
      }

      public boolean visionAvailable() {
        return false;
      }

      public boolean armed() {
        return bool(status, "armed", false);
      }

      public JsonObject modelStatus() {
        return status();
      }

      public void ownerArm() {
        request(
            owner,
            m -> {
              m.requirePlayer();
              m.engine.ownerArm();
              m.nextGoal = 0;
            });
      }

      public void ownerStop() {
        request(
            owner,
            m -> {
              if (m.engine != null) m.engine.ownerStop();
            });
      }

      public void ownerModelStart(String goal, int steps, boolean vision) {
        request(
            owner,
            m -> {
              m.requirePlayer();
              m.engine.actorInterrupt("Новая цель владельца.");
              m.engine.ownerModelStart(goal, steps, vision);
              m.nextGoal = m.world.tick() + 100;
            });
      }
    };
  }

  @FunctionalInterface
  private interface Request {
    void accept(CompanionManager manager) throws Exception;
  }

  void request(UUID owner, Request action) {
    MinecraftServer target = server;
    if (target == null) return;
    target.execute(
        () -> {
          if (target != server || target.isDedicatedServer()) return;
          ServerPlayer human = target.getPlayerList().getPlayer(owner);
          if (human == null
              || !target.isSingleplayerOwner(new NameAndId(human.getGameProfile()))
              || profile != null && !profile.owner().equals(owner)) return;
          try {
            if (broken) throw new IllegalStateException(error);
            error = "";
            action.accept(this);
          } catch (Exception e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            LOG.warn("Companion request failed: {}", error);
          }
          publish();
        });
  }

  void create(UUID owner, String name) {
    request(owner, m -> m.createNow(owner, name));
  }

  void autonomous(UUID owner, boolean enabled) {
    request(
        owner,
        m -> {
          m.requireProfile();
          if (m.engine != null)
            m.engine.actorInterrupt("Режим самостоятельности изменён владельцем.");
          m.profile = m.profile.state(m.profile.dimension(), m.profile.present(), enabled);
          m.nextGoal = 0;
          m.save();
        });
  }

  void hide(UUID owner) {
    request(
        owner,
        m -> {
          m.requireProfile();
          if (m.engine != null) m.engine.ownerStop();
          if (m.player != null) m.player.closeContainer();
          m.profile =
              m.profile.state(
                  m.player == null
                      ? m.profile.dimension()
                      : m.player.level().dimension().identifier().toString(),
                  false,
                  m.profile.autonomous());
          m.save();
          m.removePlayer();
        });
  }

  void defending(UUID owner, boolean enabled) {
    request(
        owner,
        m -> {
          m.requireProfile();
          if (m.engine != null) m.engine.actorInterrupt("Режим защиты изменён владельцем.");
          m.profile = m.profile.defence(enabled);
          if (m.world != null) m.world.setDefending(enabled);
          m.nextGoal = 0;
          m.save();
        });
  }

  void show(UUID owner) {
    request(
        owner,
        m -> {
          m.requireProfile();
          m.profile = m.profile.state(m.profile.dimension(), true, m.profile.autonomous());
          m.spawn();
          m.save();
        });
  }

  void respawn(UUID owner) {
    request(owner, m -> m.respawnNow());
  }

  private void requireProfile() {
    if (profile == null) throw new IllegalStateException("Сначала создай компаньона через F6.");
  }

  private void requirePlayer() {
    requireProfile();
    if (player == null || !player.isAlive())
      throw new IllegalStateException("Компаньон отсутствует или погиб; открой F6.");
    if (engine == null)
      throw new IllegalStateException(
          "Сессия компаньона не запущена; проверь порт в bridge-owner.json и перезайди в мир.");
  }

  void started(ServerStartedEvent event) {
    if (event.getServer().isDedicatedServer()) return;
    server = event.getServer();
    file = server.getWorldPath(LevelResource.ROOT).resolve("minebridge-companion.dat");
    profile = null;
    saved = null;
    broken = false;
    error = "";
    deadAt = -1;
    nextGoal = 0;
    try {
      if (Files.exists(file)) {
        saved = NbtIo.readCompressed(file, NbtAccounter.create(8 * 1024 * 1024));
        profile =
            CompanionProfile.read(
                JsonParser.parseString(saved.getString("Profile").orElseThrow()).getAsJsonObject());
        if (saved.getCompound("Player").isEmpty())
          throw new IllegalStateException("Нет сохранения персонажа");
      }
    } catch (Exception e) {
      broken = true;
      error =
          "Не удалось прочитать minebridge-companion.dat; файл сохранён без изменений. "
              + e.getClass().getSimpleName();
      LOG.error("Companion save could not be loaded", e);
    }
    publish();
  }

  private ServerPlayer owner() {
    return profile == null ? null : server.getPlayerList().getPlayer(profile.owner());
  }

  private void createNow(UUID owner, String name) throws Exception {
    if (profile != null) throw new IllegalStateException("В этом мире уже есть компаньон.");
    ServerPlayer human = server.getPlayerList().getPlayer(owner);
    CompanionProfile candidate =
        CompanionProfile.create(owner, name, human.level().dimension().identifier().toString());
    CompanionPlayer actor =
        new CompanionPlayer(human.level(), new GameProfile(candidate.id(), name));
    Vec3 spawn = null;
    BlockPos center = human.blockPosition();
    for (int r = 2; r <= 5 && spawn == null; r++)
      for (int dx = -r; dx <= r && spawn == null; dx++)
        for (int dz = -r; dz <= r && spawn == null; dz++) {
          BlockPos pos = center.offset(dx, 0, dz);
          Vec3 point = new Vec3(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
          if (!human.level().hasChunkAt(pos) || !CompanionWorld.safeCell(human.level(), actor, pos))
            continue;
          if (!human
              .level()
              .getEntities(
                  actor,
                  actor.getBoundingBox().move(point.subtract(actor.position())),
                  e -> e.isAlive())
              .isEmpty()) continue;
          spawn = point;
        }
    if (spawn == null)
      throw new IllegalStateException(
          "Нет безопасного места рядом: освободи площадку на той же высоте.");
    actor.absSnapTo(spawn.x, spawn.y, spawn.z, human.getYRot(), 0);
    profile = candidate;
    player = actor;
    // Save identity and empty inventory before exposing the actor. Never mint starting gear.
    save();
    attach();
    openBridge();
  }

  private void spawn() throws Exception {
    if (player != null) return;
    requireProfile();
    ServerPlayer human = owner();
    if (human == null) return;
    var level =
        server.getLevel(
            ResourceKey.create(Registries.DIMENSION, Identifier.parse(profile.dimension())));
    if (level == null)
      throw new IllegalStateException("Измерение сохранённого компаньона недоступно.");
    CompanionPlayer actor =
        new CompanionPlayer(level, new GameProfile(profile.id(), profile.name()));
    if (saved == null)
      throw new IllegalStateException(
          "Сохранение персонажа отсутствует; создание дубликата остановлено.");
    actor.load(
        TagValueInput.create(
            ProblemReporter.DISCARDING,
            server.registryAccess(),
            saved.getCompound("Player").orElseThrow()));
    if (!actor.getUUID().equals(profile.id()))
      throw new IllegalStateException("UUID персонажа не совпадает с профилем.");
    actor.stopInputs();
    actor.stopUsingItem();
    actor.closeContainer();
    player = actor;
    attach();
    openBridge();
  }

  private void attach() {
    server
        .getPlayerList()
        .broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
    player.level().addNewPlayer(player);
  }

  private void openBridge() throws Exception {
    closeBridge();
    Path dir = FMLPaths.CONFIGDIR.get().resolve("minebridge");
    OwnerPolicy policy = OwnerPolicy.load(dir.resolve("bridge-owner.json"));
    if (policy.port > 65533)
      throw new IllegalStateException(
          "Для компаньона нужен свободный порт port+2; измени port в bridge-owner.json.");
    policy.port += 2;
    world = new CompanionWorld(player, policy, this::owner);
    world.setDefending(profile.defending());
    dimension = world.dimension();
    engine =
        new BridgeEngine(
            world,
            policy,
            new WorldMemory(
                dir.resolve("bridge-memory").resolve("companion-" + player.getUUID()),
                world.worldId()),
            this::audit);
    socket = new BridgeSocket(policy.port, engine, s -> LOG.warn("{}", s));
    socket.start();
    nextGoal = world.tick() + 20;
  }

  private void audit(JsonObject event) {
    try {
      Path log = FMLPaths.GAMEDIR.get().resolve("logs/minebridge-companion-events.jsonl");
      Files.createDirectories(log.getParent());
      if (Files.exists(log) && Files.size(log) > 8 * 1024 * 1024)
        Files.move(
            log,
            log.resolveSibling("minebridge-companion-events.previous.jsonl"),
            StandardCopyOption.REPLACE_EXISTING);
      Files.writeString(log, event + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception e) {
      LOG.warn("Companion audit save failed: {}", e.getClass().getSimpleName());
    }
  }

  void tick(ServerTickEvent.Post event) {
    if (event.getServer() != server || broken) return;
    try {
      if (player == null && profile != null && profile.present() && owner() != null) spawn();
      if (player != null && engine != null) {
        if (!player.level().dimension().identifier().toString().equals(dimension)) {
          save();
          openBridge();
          error = "Измерение изменилось: перезапусти контроллер и разреши F8.";
        }
        if (owner() == null) {
          engine.ownerStop();
          world.releaseControls();
        }
        world.prepareDefence(engine);
        engine.tick();
        if (!player.isAlive()) {
          if (deadAt < 0) {
            deadAt = world.tick();
            engine.ownerStop();
            save();
          }
        } else {
          deadAt = -1;
          JsonObject state = engine.modelStatus();
          boolean connected = bool(state, "controller_connected", false);
          if (engine.armed() && connected) {
            if (world.survivalReflex(engine, profile.defending())) {
              nextGoal = world.tick() + 40;
            } else if (profile.autonomous()
                && !engine.hasPendingTasks()
                && !Set.of("queued", "thinking", "executing").contains(string(state, "state"))
                && world.tick() >= nextGoal) {
              engine.ownerModelStart(CompanionProfile.GOAL, 8, false);
              nextGoal = world.tick() + 100;
            }
          } else world.stopReflex();
        }
        if (world.tick() % 100 == 0) save();
      }
    } catch (Exception e) {
      if (engine != null) engine.ownerStop();
      error = "Компаньон остановлен: " + e.getClass().getSimpleName() + ": " + e.getMessage();
      LOG.error("Companion tick failed", e);
    }
    publish();
  }

  private void respawnNow() throws Exception {
    if (player == null || player.isAlive())
      throw new IllegalStateException("Возрождение доступно после смерти компаньона.");
    CompanionPlayer previous = player;
    var dest = previous.findRespawnPositionAndUseSpawnBlock(true, TeleportTransition.DO_NOTHING);
    closeBridge();
    previous.level().removePlayerImmediately(previous, Entity.RemovalReason.DISCARDED);
    player = new CompanionPlayer(dest.newLevel(), new GameProfile(profile.id(), profile.name()));
    player.restoreFrom(previous, false);
    player.copyRespawnPosition(previous);
    player.absSnapTo(
        dest.position().x, dest.position().y, dest.position().z, dest.yRot(), dest.xRot());
    attach();
    openBridge();
    deadAt = -1;
    save();
    error = "Возрождён по правилам мира. Перезапусти контроллер и разреши F8.";
  }

  private void save() throws Exception {
    if (profile == null || broken) return;
    CompoundTag data;
    if (player != null) {
      profile =
          profile.state(
              player.level().dimension().identifier().toString(),
              profile.present(),
              profile.autonomous());
      TagValueOutput out =
          TagValueOutput.createWithContext(ProblemReporter.DISCARDING, server.registryAccess());
      player.saveWithoutId(out);
      data = new CompoundTag();
      data.put("Player", out.buildResult());
    } else if (saved != null) data = saved.copy();
    else throw new IllegalStateException("Нет персонажа для сохранения");
    data.putString("Profile", profile.json().toString());
    Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
    NbtIo.writeCompressed(data, temporary);
    if (Files.exists(file))
      Files.copy(
          file,
          file.resolveSibling(file.getFileName() + ".bak"),
          StandardCopyOption.REPLACE_EXISTING);
    try {
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
    saved = data;
  }

  private void publish() {
    JsonObject out = engine == null ? emptyStatus() : engine.modelStatus();
    out.addProperty("exists", profile != null);
    out.addProperty("present", player != null);
    out.addProperty("autonomous", profile == null || profile.autonomous());
    out.addProperty("defending", profile == null || profile.defending());
    if (profile != null) {
      out.addProperty("name", profile.name());
      out.addProperty("actor_id", profile.id().toString());
    }
    if (player != null) {
      out.addProperty("health", player.getHealth());
      out.addProperty("food", player.getFoodData().getFoodLevel());
      out.add("position", CompanionWorld.vector(player.position()));
      out.addProperty("dimension", player.level().dimension().identifier().toString());
      out.addProperty("reflex", world == null ? "" : world.reflex());
      if (world != null) out.add("defence", world.defenceStatus());
    }
    if (!error.isEmpty()) out.addProperty("detail", error);
    status = out;
  }

  private void closeBridge() {
    if (engine != null) engine.close();
    if (socket != null) socket.close();
    engine = null;
    socket = null;
    world = null;
  }

  private void removePlayer() {
    closeBridge();
    if (player != null) {
      player.stopInputs();
      player.stopUsingItem();
      player.closeContainer();
      player.level().removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
      server
          .getPlayerList()
          .broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
      player = null;
    }
  }

  void stopping(ServerStoppingEvent event) {
    if (event.getServer() != server) return;
    try {
      if (engine != null) engine.ownerStop();
      if (player != null) player.closeContainer();
      save();
    } catch (Exception e) {
      LOG.error("Companion final save failed", e);
    }
    removePlayer();
  }

  void stopped(ServerStoppedEvent event) {
    if (event.getServer() != server) return;
    server = null;
    profile = null;
    saved = null;
    status = emptyStatus();
  }

  CompanionWorld world() {
    return world;
  }

  void commands(RegisterCommandsEvent event) {
    event
        .getDispatcher()
        .register(
            Commands.literal("minebridge")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(
                    Commands.literal("companion")
                        .then(
                            Commands.literal("create")
                                .executes(
                                    c -> {
                                      create(
                                          c.getSource().getPlayerOrException().getUUID(),
                                          "BridgeBuddy");
                                      return 1;
                                    })
                                .then(
                                    Commands.argument("name", StringArgumentType.word())
                                        .executes(
                                            c -> {
                                              create(
                                                  c.getSource().getPlayerOrException().getUUID(),
                                                  StringArgumentType.getString(c, "name"));
                                              return 1;
                                            })))
                        .then(
                            Commands.literal("arm")
                                .executes(
                                    c -> {
                                      controls(c.getSource().getPlayerOrException().getUUID())
                                          .ownerArm();
                                      return 1;
                                    }))
                        .then(
                            Commands.literal("stop")
                                .executes(
                                    c -> {
                                      controls(c.getSource().getPlayerOrException().getUUID())
                                          .ownerStop();
                                      return 1;
                                    }))
                        .then(
                            Commands.literal("respawn")
                                .executes(
                                    c -> {
                                      respawn(c.getSource().getPlayerOrException().getUUID());
                                      return 1;
                                    }))
                        .then(
                            Commands.literal("hide")
                                .executes(
                                    c -> {
                                      hide(c.getSource().getPlayerOrException().getUUID());
                                      return 1;
                                    }))
                        .then(
                            Commands.literal("show")
                                .executes(
                                    c -> {
                                      show(c.getSource().getPlayerOrException().getUUID());
                                      return 1;
                                    }))
                        .then(
                            Commands.literal("autonomy")
                                .then(
                                    Commands.literal("on")
                                        .executes(
                                            c -> {
                                              autonomous(
                                                  c.getSource().getPlayerOrException().getUUID(),
                                                  true);
                                              return 1;
                                            }))
                                .then(
                                    Commands.literal("off")
                                        .executes(
                                            c -> {
                                              autonomous(
                                                  c.getSource().getPlayerOrException().getUUID(),
                                                  false);
                                              return 1;
                                            })))
                        .then(
                            Commands.literal("status")
                                .executes(
                                    c -> {
                                      c.getSource()
                                          .sendSuccess(
                                              () -> Component.literal(status.toString()), false);
                                      return 1;
                                    }))));
  }
}
