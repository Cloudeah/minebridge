package dev.minebridge.mod;

import com.google.gson.*;
import dev.minebridge.core.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
import static dev.minebridge.core.Json.*;

/** Dedicated servers explicitly expose only authorized structural observation. */
final class DedicatedBridge {
    private BridgeEngine engine;
    private BridgeSocket socket;
    private ServerPlayer actor;
    void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("minebridge")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.literal("observe").then(Commands.argument("player",EntityArgument.player()).executes(context->{
                    if(!context.getSource().getServer().isDedicatedServer())return 0;
                    close();actor=EntityArgument.getPlayer(context,"player");
                    try {
                        Path dir=FMLPaths.CONFIGDIR.get().resolve("minebridge");OwnerPolicy policy=OwnerPolicy.load(dir.resolve("bridge-owner.json"));
                        Path marker=actor.level().getServer().getWorldPath(LevelResource.ROOT).resolve("minebridge-world-id.txt");
                        if(!Files.exists(marker))Files.writeString(marker,id(),StandardOpenOption.CREATE_NEW);
                        WorldAccess world=new Observer(actor);engine=new BridgeEngine(world,policy,new WorldMemory(dir.resolve("bridge-memory"),world.worldId()),e->{});
                        socket=new BridgeSocket(policy.port,engine,s->{});socket.start();
                        context.getSource().sendSuccess(()->Component.literal("AI Bridge: structural observation active; credentials are in the owner file"),false);return 1;
                    } catch(Exception e){close();context.getSource().sendFailure(Component.literal("AI Bridge startup failed: "+e.getClass().getSimpleName()));return 0;}
                })))
                .then(Commands.literal("stop").executes(context->{close();return 1;})));
    }
    void tick(ServerTickEvent.Post event){if(engine!=null){if(actor.hasDisconnected()||actor.isRemoved())close();else engine.tick();}}
    void stopped(ServerStoppedEvent event){close();}
    private void close(){if(engine!=null)engine.close();if(socket!=null)socket.close();engine=null;socket=null;actor=null;}
    private static final class Observer implements WorldAccess {
        private final ServerPlayer player;
        private final String world;
        Observer(ServerPlayer player) throws java.io.IOException {this.player=player;world=Files.readString(player.level().getServer().getWorldPath(LevelResource.ROOT).resolve("minebridge-world-id.txt")).trim();}
        public String actorId(){return player.getUUID().toString();}public String worldId(){return world;}public String dimension(){return player.level().dimension().identifier().toString();}public long tick(){return player.level().getGameTime();}
        public Set<String> capabilities(){return Set.of("inventory.inspect");}
        public void releaseControls(){}public Step step(Task t){throw new BridgeException("unsupported_capability","Dedicated bridge is observation-only");}
        private JsonObject vector(Vec3 v){return object("x",v.x,"y",v.y,"z",v.z);}
        private JsonObject stack(net.minecraft.world.item.ItemStack s){return object("type",BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),"count",s.getCount());}
        public JsonObject snapshot(int radius,boolean telemetry) {
            JsonArray slots=new JsonArray();for(int i=0;i<player.getInventory().getContainerSize();i++){JsonObject s=stack(player.getInventory().getItem(i));s.addProperty("slot",i);slots.add(s);}
            JsonArray entities=new JsonArray();for(var e:player.level().getEntities(player,player.getBoundingBox().inflate(radius),e->!e.isInvisibleTo(player))) {
                boolean visible=player.hasLineOfSight(e);if(!visible&&!telemetry)continue;
                entities.add(object("entity_id",e.getUUID().toString(),"type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),"position",vector(e.position()),"visible",visible));
            }
            JsonArray blocks=new JsonArray();BlockPos center=player.blockPosition();int visited=0;
            for(BlockPos pos:BlockPos.betweenClosed(center.offset(-radius,-4,-radius),center.offset(radius,4,radius))) {
                if(++visited>6000)break;if(!player.level().hasChunkAt(pos))continue;var state=player.level().getBlockState(pos);
                if(!telemetry){if(state.isAir())continue;var ray=player.level().clip(new ClipContext(player.getEyePosition(),Vec3.atCenterOf(pos),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));if(ray.getType()!=HitResult.Type.BLOCK||!ray.getBlockPos().equals(pos))continue;}
                blocks.add(object("x",pos.getX(),"y",pos.getY(),"z",pos.getZ(),"type",BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),"dimension",dimension(),"source",telemetry?"owner_telemetry":"ray_observation"));
            }
            return object("actor",object("position",vector(player.position()),"health",player.getHealth(),"food",player.getFoodData().getFoodLevel()),"inventory",slots,"entities",entities,"blocks",blocks,"context",object("source","dedicated_server","actions_available",false));
        }
        public JsonObject query(String operation,JsonObject data){if(operation.equals("inventory.inspect"))return object("inventory",snapshot(1,false).get("inventory"));throw new BridgeException("unsupported_capability",operation);}
    }
}
