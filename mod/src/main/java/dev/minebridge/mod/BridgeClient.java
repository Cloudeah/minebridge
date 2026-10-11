package dev.minebridge.mod;

import dev.minebridge.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.*;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.io.IOException;

@Mod(value="minebridge",dist=Dist.CLIENT)
public final class BridgeClient {
    private static final Logger LOG=LoggerFactory.getLogger("minebridge");
    private Minecraft mc;
    private final KeyMapping.Category category=new KeyMapping.Category(Identifier.fromNamespaceAndPath("minebridge","control"));
    private final KeyMapping arm=new KeyMapping("key.minebridge.arm",GLFW.GLFW_KEY_F8,category);
    private final KeyMapping stop=new KeyMapping("key.minebridge.stop",GLFW.GLFW_KEY_F9,category);
    private final KeyMapping panelKey=new KeyMapping("key.minebridge.panel",GLFW.GLFW_KEY_F7,category);
    private ModelScreen panel;
    private BridgeEngine engine;
    private BridgeSocket socket;
    private volatile MinecraftWorld world;
    private ClientVision vision;
    private OwnerPolicy policy;
    private String identity;
    private boolean failed;
    private Path auditFile;
    private float expectedYaw,expectedPitch;
    private boolean haveLook;
    private boolean panelWasOpen;
    private int lookResetTicks;
    public BridgeClient(IEventBus bus) {
        bus.addListener(this::keys);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::beforeTick);
        NeoForge.EVENT_BUS.addListener(this::keyInput);
        NeoForge.EVENT_BUS.addListener(this::mouseInput);
        NeoForge.EVENT_BUS.addListener(this::extract);
        NeoForge.EVENT_BUS.addListener(this::render);
        NeoForge.EVENT_BUS.addListener(this::pickup);
        NeoForge.EVENT_BUS.addListener(this::pickupAttempt);
        NeoForge.EVENT_BUS.addListener(this::afterPlayerTick);
        NeoForge.EVENT_BUS.addListener(this::projectileTick);
        NeoForge.EVENT_BUS.addListener(this::arrowSpawn);
        NeoForge.EVENT_BUS.addListener(this::damage);
        NeoForge.EVENT_BUS.addListener(this::entityInteract);
        NeoForge.EVENT_BUS.addListener(this::entityInteractSpecific);
        NeoForge.EVENT_BUS.addListener(this::blockInteract);
    }
    private void pickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post event){
        MinecraftWorld activeWorld=world;
        if(activeWorld!=null)activeWorld.recordPickup(event);
    }
    private void beforeTick(ClientTickEvent.Pre event){if(world!=null)world.beforeClientTick();}
    private void pickupAttempt(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre event){
        MinecraftWorld activeWorld=world;if(activeWorld!=null)activeWorld.recordPickupAttempt(event);
    }
    private void afterPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event){
        MinecraftWorld activeWorld=world;if(activeWorld!=null)activeWorld.confirmPickupAttempts(event.getEntity());
    }
    private void entityInteract(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event){MinecraftWorld w=world;if(w!=null)w.recordInteraction(event,"entity:"+event.getTarget().getUUID());}
    private void entityInteractSpecific(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific event){MinecraftWorld w=world;if(w!=null)w.recordInteraction(event,"entity:"+event.getTarget().getUUID());}
    private void blockInteract(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event){MinecraftWorld w=world;if(w!=null){var p=event.getPos();w.recordInteraction(event,"block:"+p.getX()+","+p.getY()+","+p.getZ());}}
    private void projectileTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre event){
        MinecraftWorld activeWorld=world;if(activeWorld!=null)activeWorld.recordProjectile(event.getEntity());
    }
    private void arrowSpawn(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event){
        MinecraftWorld activeWorld=world;if(activeWorld!=null)activeWorld.recordArrowSpawn(event);
    }
    private void damage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event){
        MinecraftWorld activeWorld=world;if(activeWorld!=null)activeWorld.recordDamage(event);
    }
    private void keys(RegisterKeyMappingsEvent event){event.registerCategory(category);event.register(arm);event.register(stop);event.register(panelKey);}
    private void tick(ClientTickEvent.Post event) {
        if(mc==null)mc=Minecraft.getInstance();
        if(mc==null)return;
        if(mc.player==null || mc.level==null || mc.gameMode==null) { close();failed=false;return; }
        String current=mc.player.getUUID()+"/"+mc.level.dimension().identifier();
        if(identity!=null && !identity.equals(current)) {close();failed=false;}
        if(engine==null && !failed) open(current);
        if(engine==null) return;
        boolean stopRequested=false;
        while(stop.consumeClick())stopRequested=true;
        if(stopRequested){engine.ownerStop();message("AI Bridge: управление остановлено");}
        while(arm.consumeClick()) {
            if(stopRequested)continue;
            if(!mc.hasSingleplayerServer() && !policy.allowMultiplayer){message("AI Bridge: разрешена только одиночная игра");continue;}
            engine.ownerArm();message("AI Bridge: управление разрешено; F9 — остановка");
        }
        while(panelKey.consumeClick()){if(panel==null)panel=new ModelScreen(engine,arm,stop,panelKey);mc.setScreen(mc.screen==panel?null:panel);}
        if(mc.isPaused()) {engine.manualInput();return;}
        boolean panelOpen=mc.screen instanceof ModelScreen;
        // GLFW recentres the cursor when the owner panel changes mouse capture.
        // Discard those transition samples; actual key/button presses and F9 still interrupt.
        if(panelOpen!=panelWasOpen)lookResetTicks=4;
        panelWasOpen=panelOpen;
        if(haveLook && !panelOpen && lookResetTicks==0 && (Math.abs(mc.player.getYRot()-expectedYaw)>0.01 || Math.abs(mc.player.getXRot()-expectedPitch)>0.01))engine.manualInput();
        if(lookResetTicks>0)lookResetTicks--;
        engine.tick();
        expectedYaw=mc.player.getYRot();expectedPitch=mc.player.getXRot();haveLook=true;
    }
    private void open(String current) {
        try {
            Path dir=FMLPaths.CONFIGDIR.get().resolve("minebridge");
            policy=OwnerPolicy.load(dir.resolve("bridge-owner.json"));
            world=new MinecraftWorld(mc,policy);
            WorldMemory memory=new WorldMemory(dir.resolve("bridge-memory"),world.worldId());
            Path logs=mc.gameDirectory.toPath().resolve("logs");Files.createDirectories(logs);auditFile=logs.resolve("minebridge-events.jsonl");
            engine=new BridgeEngine(world,policy,memory,this::audit);
            vision=new ClientVision(mc,world,engine);
            socket=new BridgeSocket(policy.port,engine,s->LOG.warn("{}",s));socket.start();identity=current;
            message("AI Bridge: локальный WebSocket готов; F8 — разрешить управление");
        } catch(Exception e) {failed=true;LOG.error("AI Bridge startup failed",e);close();message("AI Bridge: ошибка запуска; см. журнал");}
    }
    private void audit(com.google.gson.JsonObject e) {
        try {
            // Rotate instead of allowing a long-running world to fill the disk.
            if(Files.exists(auditFile) && Files.size(auditFile)>8*1024*1024)Files.move(auditFile,auditFile.resolveSibling("minebridge-events.previous.jsonl"),StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(auditFile,e+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        } catch(IOException error){LOG.warn("AI Bridge audit persistence failed: {}",error.getClass().getSimpleName());}
    }
    private void keyInput(InputEvent.Key event){if(engine!=null && !(mc.screen instanceof ModelScreen) && event.getAction()==GLFW.GLFW_PRESS && event.getKey()!=arm.getKey().getValue() && event.getKey()!=stop.getKey().getValue() && event.getKey()!=panelKey.getKey().getValue())engine.manualInput();}
    private void mouseInput(InputEvent.MouseButton.Pre event){if(engine!=null && !(mc.screen instanceof ModelScreen) && event.getAction()==GLFW.GLFW_PRESS)engine.manualInput();}
    private void extract(ExtractLevelRenderStateEvent event){if(vision!=null)vision.extract(event);}
    private void render(RenderLevelStageEvent.AfterLevel event){if(vision!=null)vision.capture(event);}
    private void message(String text){if(mc.player!=null)mc.player.sendSystemMessage(Component.literal(text));}
    private void close(){if(mc!=null && mc.screen instanceof ModelScreen)mc.setScreen(null);panel=null;if(vision!=null)vision.close();if(engine!=null)engine.close();if(socket!=null)socket.close();engine=null;socket=null;world=null;vision=null;identity=null;haveLook=false;panelWasOpen=false;lookResetTicks=0;}
}
