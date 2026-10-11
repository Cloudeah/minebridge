package dev.minebridge.mod;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import java.util.Set;
import net.minecraft.network.PacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.*;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

/** A survival ServerPlayer. Vanilla ticks provide physics, food, equipment, pickups and death. */
final class CompanionPlayer extends ServerPlayer {
  CompanionPlayer(ServerLevel level, GameProfile profile) {
    super(level.getServer(), level, profile, ClientInformation.createDefault());
    connection = new LocalConnection(this);
    gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
    setInvulnerable(false);
    getAbilities().invulnerable = false;
  }

  @Override
  public boolean isFakePlayer() {
    return true;
  }

  @Override
  public void attack(Entity target) {
    if (!(target instanceof Player)) super.attack(target);
  }

  @Override
  public void tick() {
    double x = getX(), y = getY(), z = getZ();
    super.tick();
    doTick();
    if (!isPassenger()) checkMovementStatistics(getX() - x, getY() - y, getZ() - z);
    level().getChunkSource().move(this);
    if (isChangingDimension()) hasChangedDimension();
  }

  void stopInputs() {
    xxa = 0;
    zza = 0;
    yya = 0;
    setJumping(false);
    setSprinting(false);
    setShiftKeyDown(false);
  }

  void movement(boolean forward, boolean jump, boolean sprint, boolean crouch) {
    xxa = 0;
    zza = forward ? 1 : 0;
    setJumping(jump);
    setSprinting(sprint && getFoodData().getFoodLevel() > 6);
    setShiftKeyDown(crouch);
  }

  private static final class LocalConnection extends ServerGamePacketListenerImpl {
    LocalConnection(CompanionPlayer player) {
      super(
          player.level().getServer(),
          new Sink(),
          player,
          CommonListenerCookie.createInitial(player.getGameProfile(), false));
    }

    @Override
    public void tick() {}

    @Override
    public boolean hasClientLoaded() {
      return true;
    }

    @Override
    public void tickClientLoadTimeout() {}

    @Override
    public void send(Packet<?> packet) {}

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {}

    @Override
    public void disconnect(Component reason) {}

    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch) {
      player.absSnapTo(x, y, z, yaw, pitch);
      player.hasChangedDimension();
    }

    @Override
    public void teleport(PositionMoveRotation destination, Set<Relative> relatives) {
      player.teleportSetPosition(destination, relatives);
      player.hasChangedDimension();
    }
  }

  private static final class Sink extends net.minecraft.network.Connection {
    Sink() {
      super(PacketFlow.SERVERBOUND);
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {}
  }
}
