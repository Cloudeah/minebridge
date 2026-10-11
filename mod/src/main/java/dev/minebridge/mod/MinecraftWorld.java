package dev.minebridge.mod;

import com.google.gson.*;
import dev.minebridge.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static dev.minebridge.core.Json.*;

final class MinecraftWorld implements WorldAccess {
    final Minecraft mc;
    final OwnerPolicy policy;
    private final String worldId;
    private final String actor;
    private final String initialDimension;
    private long lastTick;
    private final CompletableFuture<RecipeCatalog> catalogFuture;
    private final Map<String,CompletableFuture<JsonObject>> confirmations=new HashMap<>();
    private final Map<String,List<Pathfinder.Cell>> routes=new HashMap<>();
    private final Map<String,JumpSequence> jumps=new HashMap<>();
    private final Map<String,CrouchSequence> crouches=new HashMap<>();
    private final Map<String,ItemDropSequence> drops=new HashMap<>();
    private final PickupLedger pickups;
    // Accessed only by server-side pickup/player-tick callbacks.
    private final Map<String,PickupAttempt> pickupAttempts=new HashMap<>();
    private final Map<String,CraftPlanner.Plan> crafts=new HashMap<>();
    private final Map<String,BuildPlanner.Plan> builds=new HashMap<>();
    private final Map<String,CompletableFuture<Integer>> buildChecks=new HashMap<>();
    private final Set<net.minecraft.client.KeyMapping> held=new HashSet<>();
    MinecraftWorld(Minecraft mc,OwnerPolicy policy) throws java.io.IOException {
        this.mc=mc;this.policy=policy;actor=mc.player.getUUID().toString();initialDimension=mc.level.dimension().identifier().toString();
        pickups=new PickupLedger(actor,initialDimension);
        if(mc.hasSingleplayerServer()) {
            Path marker=mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).resolve("minebridge-world-id.txt");
            if(!Files.exists(marker))Files.writeString(marker,id(),StandardOpenOption.CREATE_NEW);
            worldId=Files.readString(marker).trim();
            catalogFuture=mc.getSingleplayerServer().submit(()->RecipeCatalog.load(mc.getSingleplayerServer()));
        } else {
            // No action authority is inferred from having a client mod on an external server.
            worldId="remote-session-"+id();catalogFuture=CompletableFuture.completedFuture(new RecipeCatalog());
        }
    }
    @Override public String actorId(){return actor;}
    @Override public String worldId(){return worldId;}
    @Override public String dimension(){return mc.level==null?initialDimension:mc.level.dimension().identifier().toString();}
    @Override public long tick(){if(mc.level!=null)lastTick=mc.level.getGameTime();return lastTick;}
    @Override public boolean visionAvailable(){return mc.hasSingleplayerServer() && mc.getMainRenderTarget().getColorTexture() instanceof com.mojang.blaze3d.opengl.GlTexture && mc.getMainRenderTarget().getDepthTexture() instanceof com.mojang.blaze3d.opengl.GlTexture;}
    @Override public List<String> movementModes(){return mc.hasSingleplayerServer()?List.of("walk","sprint","jump","crouch"):List.of();}
    @Override public void taskFinished(Task t){confirmations.remove(t.id);routes.remove(t.id);jumps.remove(t.id);crouches.remove(t.id);drops.remove(t.id);crafts.remove(t.id);builds.remove(t.id);buildChecks.remove(t.id);if(t.action.equals("item.pickup"))pickups.discard(string(t.parameters,"entity_id"));}
    @Override public boolean awaitingConfirmation(Task t){
        return switch(t.action){
            case "item.use"->t.context.has("use_sent");
            case "item.drop"->drops.containsKey(t.id) && drops.get(t.id).awaitingConfirmation();
            case "item.pickup"->t.context.has("pickup_started");
            case "inventory.select"->t.context.has("selection_sent");
            case "container.close"->t.context.has("sent");
            default->false;
        };
    }
    // NeoForge dispatches Post on the server thread, before an empty stack is discarded.
    void recordPickup(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Post event){
        var player=event.getPlayer();var entity=event.getItemEntity();var original=event.getOriginalStack();
        int remaining=event.getCurrentStack().getCount();
        pickupAttempts.remove(entity.getUUID().toString());
        pickups.record(player.getUUID().toString(),player.level().dimension().identifier().toString(),
                entity.getUUID().toString(),itemId(original),original.getCount()-remaining,remaining,player.level().getGameTime());
    }
    private int inventoryCount(net.minecraft.world.entity.player.Player player,String item){
        int count=0;for(int i=0;i<player.getInventory().getContainerSize();i++){
            ItemStack stack=player.getInventory().getItem(i);if(itemId(stack).equals(item))count+=stack.getCount();
        }return count;
    }
    void recordPickupAttempt(net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent.Pre event){
        var player=event.getPlayer();var entity=event.getItemEntity();String target=entity.getUUID().toString();
        if(!actor.equals(player.getUUID().toString()) || !initialDimension.equals(player.level().dimension().identifier().toString()) || !pickups.watching(target))return;
        String item=itemId(entity.getItem());pickupAttempts.put(target,new PickupAttempt(item,entity.getItem().getCount(),inventoryCount(player,item),player.level().getGameTime()));
    }
    void confirmPickupAttempts(net.minecraft.world.entity.player.Player player){
        if(player.level().isClientSide() || !actor.equals(player.getUUID().toString()) || !initialDimension.equals(player.level().dimension().identifier().toString()))return;
        var level=(net.minecraft.server.level.ServerLevel)player.level();
        for(var pending:pickupAttempts.entrySet()){
            Entity entity=level.getEntity(UUID.fromString(pending.getKey()));var before=pending.getValue();
            if(entity instanceof ItemEntity item && pickups.watching(pending.getKey())){
                int remaining=item.getItem().getCount();
                int collected=before.collected(itemId(item.getItem()),remaining,inventoryCount(player,before.item()),level.getGameTime(),!item.isRemoved());
                if(collected>0)pickups.record(actor,initialDimension,pending.getKey(),before.item(),collected,remaining,level.getGameTime(),"integrated_server_pickup_state");
            }
        }
        // Inventory.add can partially insert a stack yet return false once it cannot add
        // more, so vanilla skips Post. The same-tick two-sided delta confirms that case.
        pickupAttempts.clear();
    }
    @Override public Set<String> capabilities() {
        if(!mc.hasSingleplayerServer())return Set.of("inventory.inspect");
        return Set.of("move.to","move.follow","move.jump","move.relative","move.crouch","route.plan","look.at","look.turn","combat.attack","item.use","item.drop","item.pickup","world.interact","block.break","block.place",
                "inventory.inspect","inventory.select","inventory.transfer","inventory.equip","container.open","container.close","craft.plan","craft.execute","build.plan","build.execute");
    }
    @Override public void validate(String action,JsonObject p) {
        switch(action) {
            case "move.to"->{point(child(p,"target"));MovementCommands.validateTravel(p);}
            case "look.at"->{if(p.has("entity_id"))UUID.fromString(string(p,"entity_id"));else point(child(p,"target"));}
            case "move.relative"->MovementCommands.relative(p,0);
            case "move.crouch"->MovementCommands.crouchTicks(p);
            case "look.turn"->MovementCommands.turn(p,0,0);
            case "move.follow", "combat.attack"->UUID.fromString(string(p,"entity_id"));
            case "move.jump"->JumpSequence.count(p);
            case "block.break", "block.place"->{block(child(p,"position"));if(action.equals("block.place"))Identifier.parse(string(p,"block"));}
            case "container.open", "world.interact"->{if(p.has("entity_id"))UUID.fromString(string(p,"entity_id"));else block(child(p,"position"));}
            case "inventory.select"->integer(p,"slot",0,0,8);
            case "inventory.transfer", "inventory.equip"->{integer(p,"from_slot",0,0,mc.player.containerMenu.slots.size()-1);integer(p,"to_slot",0,0,mc.player.containerMenu.slots.size()-1);if(p.has("count"))integer(p,"count",1,1,64);}
            case "craft.execute"->{Identifier.parse(string(p,"item"));integer(p,"count",1,1,4096);}
            case "build.execute"->{var plan=new BuildPlanner().plan(p);for(var b:plan.blocks())checkArea(new Vec3(b.x()+0.5,b.y()+0.5,b.z()+0.5));}
            case "item.use"->{String hand=string(p,"hand","main");if(!hand.equals("main")&&!hand.equals("off"))throw new BridgeException("invalid_request","hand must be main/off");integer(p,"duration_ticks",40,1,1200);if(p.has("target"))point(child(p,"target"));}
            case "item.drop"->{ItemDropSequence.count(p);if(!string(p,"hand","main").equals("main"))throw new BridgeException("invalid_request","item.drop supports only the selected main hand");if(p.has("expected_item"))Identifier.parse(string(p,"expected_item"));}
            case "item.pickup"->{UUID.fromString(string(p,"entity_id"));if(p.has("count"))throw new BridgeException("invalid_request","item.pickup collects the whole ground stack; count is not supported");}
            default->{}
        }
    }
    static JsonObject vector(Vec3 p){return object("x",p.x,"y",p.y,"z",p.z);}
    static JsonObject position(BlockPos p){return object("x",p.getX(),"y",p.getY(),"z",p.getZ());}
    static String itemId(ItemStack s){return s.isEmpty()?"minecraft:air":BuiltInRegistries.ITEM.getKey(s.getItem()).toString();}
    static String blockId(BlockState s){return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();}
    static JsonObject stack(ItemStack s){return object("type",itemId(s),"count",s.getCount(),"damage",s.getDamageValue(),"max_stack_size",s.getMaxStackSize(),"name",s.getHoverName().getString(),"components","Only public name, damage and stack limit are exposed; other components are unavailable");}
    static JsonObject heldStack(ItemStack s){return object("type",itemId(s),"count",s.getCount(),"damage",s.getDamageValue());}
    private static <T extends Comparable<T>> String propertyValue(Property<T> p,BlockState state){return p.getName(state.getValue(p));}
    static JsonObject properties(BlockState state){JsonObject o=object();for(Property<?> p:state.getProperties())o.addProperty(p.getName(),propertyValue(p,state));return o;}
    @Override public JsonObject snapshot(int radius,boolean telemetry) {
        JsonArray inventory=new JsonArray();for(int i=0;i<mc.player.getInventory().getContainerSize();i++){JsonObject s=stack(mc.player.getInventory().getItem(i));s.addProperty("slot",i);inventory.add(s);}
        JsonArray menu=new JsonArray();for(Slot slot:mc.player.containerMenu.slots){JsonObject s=stack(slot.getItem());s.addProperty("slot",slot.index);s.addProperty("player_inventory",slot.container==mc.player.getInventory());menu.add(s);}
        JsonArray entities=new JsonArray();
        for(Entity e:mc.level.getEntities(mc.player,mc.player.getBoundingBox().inflate(radius),e->!e.isInvisibleTo(mc.player))) {
            boolean visible=visible(e);if(!telemetry && !visible)continue;
            JsonObject ent=object("entity_id",e.getUUID().toString(),"type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),"position",vector(e.position()),"velocity",vector(e.getDeltaMovement()),"visible",visible,"source","client_engine","health","unavailable");
            if(e instanceof ItemEntity item)ent.add("item",heldStack(item.getItem()));
            entities.add(ent);
        }
        JsonArray blocks=new JsonArray();BlockPos center=mc.player.blockPosition();int count=0;
        for(BlockPos p:BlockPos.betweenClosed(center.offset(-radius,-4,-radius),center.offset(radius,4,radius))) {
            if(++count>6000)break;
            if(!mc.level.hasChunkAt(p))continue;
            BlockState state=mc.level.getBlockState(p);
            if(!telemetry && (state.isAir() || !blockVisible(p)))continue;
            JsonObject b=position(p);b.addProperty("dimension",dimension());b.addProperty("type",blockId(state));b.add("properties",properties(state));
            b.addProperty("solid",!state.getCollisionShape(mc.level,p).isEmpty());b.addProperty("fluid",!state.getFluidState().isEmpty());b.addProperty("source",telemetry?"owner_telemetry":"ray_observation");blocks.add(b);
        }
        return object("actor",object("position",vector(mc.player.position()),"velocity",vector(mc.player.getDeltaMovement()),"yaw",mc.player.getYRot(),"pitch",mc.player.getXRot(),"health",mc.player.getHealth(),"food",mc.player.getFoodData().getFoodLevel(),"armor",mc.player.getArmorValue(),"effects",mc.player.getActiveEffects().stream().map(e->e.toString()).toList(),"selected_slot",mc.player.getInventory().getSelectedSlot(),"main_hand",heldStack(mc.player.getMainHandItem()),"off_hand",heldStack(mc.player.getOffhandItem()),"using_item",mc.player.isUsingItem(),"on_ground",mc.player.onGround(),"flying",mc.player.getAbilities().flying,"in_water",mc.player.isInWater(),"in_lava",mc.player.isInLava(),"riding",mc.player.isPassenger(),"crouching",mc.player.isCrouching(),"sprinting",mc.player.isSprinting()),
                "inventory",inventory,"entities",entities,"blocks",blocks,"context",object("raining",mc.level.isRaining(),"day_time",mc.level.getDefaultClockTime(),"menu_id",mc.player.containerMenu.containerId,"menu_revision",mc.player.containerMenu.getStateId(),"menu_slots",menu,"cursor",stack(mc.player.containerMenu.getCarried()),"source","client_engine","coordinate_system","world_xyz_blocks","telemetry",telemetry));
    }
    boolean visible(Entity entity){return mc.level.clip(new ClipContext(mc.player.getEyePosition(),entity.getBoundingBox().getCenter(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player)).getType()==HitResult.Type.MISS;}
    boolean blockVisible(BlockPos p){BlockHitResult hit=mc.level.clip(new ClipContext(mc.player.getEyePosition(),Vec3.atCenterOf(p),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));return hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(p);}
    private Vec3 point(JsonObject p){double x=number(p,"x",Double.NaN),y=number(p,"y",Double.NaN),z=number(p,"z",Double.NaN);if(!Double.isFinite(x+y+z)||Math.abs(x)>30000000||Math.abs(z)>30000000||Math.abs(y)>2048)throw new BridgeException("invalid_request","Invalid world position");return new Vec3(x,y,z);}
    private BlockPos block(JsonObject p){Vec3 v=point(p);if(v.x!=Math.rint(v.x)||v.y!=Math.rint(v.y)||v.z!=Math.rint(v.z))throw new BridgeException("invalid_request","Block coordinates must be integers");return BlockPos.containing(v);}
    private Entity entity(JsonObject p){String target=string(p,"entity_id");for(Entity e:mc.level.entitiesForRendering())if(e.getUUID().toString().equals(target) && visible(e) && !e.isInvisibleTo(mc.player))return e;throw new BridgeException("target_lost","Target is no longer observed",true);}
    private void hold(net.minecraft.client.KeyMapping k,boolean down){
        // Toggle mappings invert setDown(true) and ignore setDown(false). Force the requested
        // state briefly in hold mode; restore the owner's preference without saving options.
        var toggle=k==mc.options.keyShift?mc.options.toggleCrouch():k==mc.options.keySprint?mc.options.toggleSprint():null;
        if(toggle!=null && toggle.get()) {toggle.set(false);try{k.setDown(down);}finally{toggle.set(true);}}
        else k.setDown(down);
        // A screen can remember a toggle key and restore it when closed. Cancelling an AI
        // hold must also clear that remembered state, or F9 in F7 would re-enable crouching.
        if(!down && k instanceof net.minecraft.client.ToggleKeyMapping mapping)mapping.shouldRestoreStateOnScreenClosed();
        if(down)held.add(k);else held.remove(k);
    }
    @Override public void releaseControls(){for(var k:Set.copyOf(held))hold(k,false);held.clear();if(mc.gameMode!=null)mc.gameMode.stopDestroyBlock();if(mc.gameMode!=null && mc.player!=null && mc.player.isUsingItem())mc.gameMode.releaseUsingItem(mc.player);routes.clear();crouches.values().forEach(CrouchSequence::released);}
    void beforeClientTick(){
        // Network packets are applied before Pre. Release before Minecraft handles keys
        // when the first use finishes, preventing its held-key automatic repeat.
        if(held.contains(mc.options.keyUse) && (mc.player==null || !mc.player.isUsingItem() || mc.screen!=null || mc.isPaused()))hold(mc.options.keyUse,false);
    }
    private void look(Vec3 target){Vec3 d=target.subtract(mc.player.getEyePosition());mc.player.setYRot((float)Math.toDegrees(Math.atan2(-d.x,d.z)));mc.player.setXRot((float)-Math.toDegrees(Math.atan2(d.y,Math.sqrt(d.x*d.x+d.z*d.z))));}
    private void checkArea(Vec3 p){if(!policy.areas.isEmpty())policy.checkPoint(vector(p),dimension());}
    private void reach(BlockPos pos){if(!mc.level.hasChunkAt(pos))throw new BridgeException("chunk_unavailable","Target chunk is not loaded",true);checkArea(Vec3.atCenterOf(pos));if(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos))>mc.player.blockInteractionRange())throw new BridgeException("out_of_reach","Target block is out of reach",true);}
    private JsonObject serverView(Task task,BlockPos pos,String entity) {
        CompletableFuture<JsonObject> pending=confirmations.get(task.id);
        if(pending==null) {
            var server=mc.getSingleplayerServer();if(server==null)throw new BridgeException("permission_denied","Actions require an integrated server");
            var dim=mc.level.dimension();UUID player=mc.player.getUUID();
            String usedItem=string(task.context,"use_item","minecraft:air");
            pending=server.submit(()->{
                var level=server.getLevel(dim);var actor=server.getPlayerList().getPlayer(player);
                if(level==null || actor==null)throw new BridgeException("disconnected","Actor unavailable");
                JsonObject view=object("source","integrated_server","tick",level.getGameTime(),"health",actor.getHealth(),"menu_id",actor.containerMenu.containerId,"cursor",stack(actor.containerMenu.getCarried()));
                view.add("position",vector(actor.position()));view.addProperty("on_ground",actor.onGround());
                view.addProperty("crouching",actor.isCrouching());view.addProperty("sprinting",actor.isSprinting());
                view.addProperty("yaw",actor.getYRot());view.addProperty("pitch",actor.getXRot());
                view.addProperty("selected_slot",actor.getInventory().getSelectedSlot());
                view.add("main_hand",heldStack(actor.getMainHandItem()));view.add("off_hand",heldStack(actor.getOffhandItem()));
                view.addProperty("food",actor.getFoodData().getFoodLevel());view.addProperty("using_item",actor.isUsingItem());
                view.addProperty("use_hand",actor.isUsingItem()?(actor.getUsedItemHand()==InteractionHand.MAIN_HAND?"main":"off"):"none");
                view.addProperty("item_uses",actor.getStats().getValue(Stats.ITEM_USED,BuiltInRegistries.ITEM.getValue(Identifier.parse(usedItem))));
                JsonArray slots=new JsonArray();for(Slot s:actor.containerMenu.slots){JsonObject item=stack(s.getItem());item.addProperty("slot",s.index);slots.add(item);}view.add("slots",slots);
                Map<String,Integer> counts=new HashMap<>();for(int i=0;i<actor.getInventory().getContainerSize();i++){ItemStack s=actor.getInventory().getItem(i);if(!s.isEmpty())counts.merge(itemId(s),s.getCount(),Integer::sum);}view.add("inventory_counts",GSON.toJsonTree(counts));
                if(pos!=null){BlockState state=level.getBlockState(pos);view.add("block",object("type",blockId(state),"properties",properties(state)));}
                if(entity!=null){Entity e=level.getEntity(UUID.fromString(entity));view.add("entity",e==null?JsonNull.INSTANCE:object("alive",e.isAlive(),"health",e instanceof LivingEntity l?l.getHealth():-1,"position",vector(e.position())));}
                return view;
            });confirmations.put(task.id,pending);return null;
        }
        if(!pending.isDone())return null;
        confirmations.remove(task.id);return pending.join();
    }
    @Override public Step step(Task t) {
        if(!mc.hasSingleplayerServer() || mc.player.isSpectator())throw new BridgeException("permission_denied","Singleplayer controllable actor required");
        if(mc.player.isDeadOrDying())return Step.failure("actor_dead","Actor is dead",object());
        if(mc.player.getHealth()<=number(t.parameters,"stop_health",4)){releaseControls();return Step.failure("critical_health","Actor health reached stop threshold",object("health",mc.player.getHealth()));}
        if(bool(t.context,"revalidate",false)){t.context.remove("revalidate");routes.remove(t.id);confirmations.remove(t.id);if(drops.containsKey(t.id))drops.get(t.id).resumed(tick());if(t.action.equals("look.turn"))t.context.addProperty("turn_sent",tick());if(t.action.equals("build.execute")){t.context.addProperty("build_index",0);t.context.remove("placing");}}
        if(mc.screen instanceof ModelScreen){
            releaseControls();confirmations.remove(t.id);
            t.context.addProperty("owner_panel_waiting",true);
            return Step.waiting(object("stage","owner_panel_open"));
        }
        if(bool(t.context,"owner_panel_waiting",false)){
            t.context.remove("owner_panel_waiting");
            // The panel is an observation pause, not evidence of being stuck. Keep the
            // original targets and owner deadline, but restart short confirmation timers.
            t.context.remove("last_position");t.context.remove("last_moved");
            if(drops.containsKey(t.id))drops.get(t.id).resumed(tick());
            if(t.action.equals("look.turn"))t.context.addProperty("turn_sent",tick());
        }
        return switch(t.action) {
            case "move.to" -> move(t,point(child(t.parameters,"target")),number(t.parameters,"tolerance",0.7),false);
            case "move.follow" -> move(t,entity(t.parameters).position(),number(t.parameters,"distance",3),true);
            case "move.relative" -> relative(t);
            case "move.crouch" -> crouch(t);
            case "move.jump" -> jump(t);
            case "look.turn" -> turn(t);
            case "look.at" -> {Vec3 target=t.parameters.has("entity_id")?entity(t.parameters).getBoundingBox().getCenter():point(child(t.parameters,"target"));look(target);t.actions++;yield Step.success(object("yaw",mc.player.getYRot(),"pitch",mc.player.getXRot()));}
            case "combat.attack" -> attack(t);
            case "block.break" -> breakBlock(t,block(child(t.parameters,"position")));
            case "block.place" -> placeBlock(t,block(child(t.parameters,"position")),string(t.parameters,"block"),t.parameters.has("properties")?child(t.parameters,"properties"):object());
            case "world.interact", "container.open" -> interact(t);
            case "container.close" -> {if(!t.context.has("sent")){mc.player.closeContainer();t.context.addProperty("sent",true);t.actions++;}JsonObject v=serverView(t,null,null);yield v!=null && v.get("menu_id").getAsInt()==0?Step.success(v):Step.waiting(object("waiting","server_menu"));}
            case "item.use" -> use(t);
            case "item.drop" -> drop(t);
            case "item.pickup" -> pickup(t);
            case "inventory.select" -> {
                int slot=integer(t.parameters,"slot",0,0,8);
                if(!t.context.has("selection_sent")){mc.player.getInventory().setSelectedSlot(slot);t.actions++;t.context.addProperty("selection_sent",tick());}
                JsonObject view=serverView(t,null,null);
                if(view!=null&&view.get("selected_slot").getAsInt()==slot)yield Step.success(object("selected_slot",slot,"source","integrated_server"));
                if(tick()-number(t.context,"selection_sent",tick())>20)throw new BridgeException("selection_unconfirmed","Server did not confirm hotbar selection");
                yield Step.waiting(object("waiting","server_selection"));
            }
            case "inventory.transfer", "inventory.equip" -> transfer(t);
            case "craft.execute" -> craft(t);
            case "build.execute" -> build(t);
            default -> throw new BridgeException("unsupported_capability",t.action);
        };
    }
    private Pathfinder.Terrain terrain(Pathfinder.Cell c) {
        BlockPos p=new BlockPos(c.x(),c.y(),c.z());if(!mc.level.hasChunkAt(p))return Pathfinder.Terrain.UNKNOWN;
        if(!policy.areas.isEmpty() && policy.areas.stream().noneMatch(a->a.contains(dimension(),c.x()+0.5,c.y(),c.z()+0.5)))return Pathfinder.Terrain.BLOCKED;
        BlockState feet=mc.level.getBlockState(p),head=mc.level.getBlockState(p.above()),floor=mc.level.getBlockState(p.below());
        if(!feet.getFluidState().isEmpty()||!head.getFluidState().isEmpty()||!feet.getCollisionShape(mc.level,p).isEmpty()||!head.getCollisionShape(mc.level,p.above()).isEmpty()||!floor.isCollisionShapeFullBlock(mc.level,p.below()))return Pathfinder.Terrain.BLOCKED;
        String type=blockId(floor);if(type.contains("magma")||type.contains("campfire")||type.contains("cactus")||type.contains("fire"))return Pathfinder.Terrain.BLOCKED;
        return Pathfinder.Terrain.WALKABLE;
    }
    private static Pathfinder.Cell cell(Vec3 v){return new Pathfinder.Cell((int)Math.floor(v.x),(int)Math.floor(v.y),(int)Math.floor(v.z));}
    private List<Pathfinder.Cell> path(Vec3 target){return new Pathfinder().plan(this::terrain,cell(mc.player.position()),cell(target),8192).cells();}
    private Step move(Task t,Vec3 target,double tolerance,boolean follow) {
        if(tolerance<0.3||tolerance>16)throw new BridgeException("invalid_request","Movement tolerance must be 0.3..16");
        if(bool(t.parameters,"sprint",false) && bool(t.parameters,"crouch",false))throw new BridgeException("invalid_request","Sprint and crouch cannot be combined");
        checkArea(target);
        double distance=mc.player.position().distanceTo(target);
        if(distance<=tolerance){
            hold(mc.options.keyUp,false);hold(mc.options.keyJump,false);hold(mc.options.keySprint,false);hold(mc.options.keyShift,false);
            if(follow)return Step.waiting(object("stage","following","distance",distance));
            JsonObject view=serverView(t,null,null);
            if(view==null)return Step.waiting(object("stage","confirming_position","position",vector(mc.player.position()),"remaining_distance",distance));
            double actual=point(child(view,"position")).distanceTo(target);
            if(actual<=tolerance){t.confirmed.add("position",view.get("position"));return Step.success(object("position",view.get("position"),"target",vector(target),"remaining_distance",actual,"source","integrated_server"));}
            return Step.waiting(object("stage","confirming_position","position",view.get("position"),"remaining_distance",actual));
        }
        if(mc.screen!=null && mc.player.containerMenu.containerId!=0)throw new BridgeException("interface_open","Close container before moving");
        List<Pathfinder.Cell> route=routes.get(t.id);
        if(route==null || (follow && tick()%20==0)) {route=path(target);routes.put(t.id,route);t.context.addProperty("waypoint",0);}
        int idx=integer(t.context,"waypoint",0,0,8192);
        while(idx<route.size()-1 && mc.player.position().distanceTo(new Vec3(route.get(idx).x()+0.5,route.get(idx).y(),route.get(idx).z()+0.5))<0.65)idx++;
        t.context.addProperty("waypoint",idx);Pathfinder.Cell next=route.get(Math.min(idx,route.size()-1));
        if(terrain(next)!=Pathfinder.Terrain.WALKABLE){routes.remove(t.id);releaseControls();return Step.waiting(object("stage","replanning"));}
        // A tight tolerance can exclude the goal cell's centre. Finish at the actual
        // requested point instead of repeatedly steering back to the voxel centre.
        boolean finalCell=idx>=route.size()-1 && next.equals(cell(target));
        Vec3 waypoint=finalCell?target:new Vec3(next.x()+0.5,next.y(),next.z()+0.5);
        look(waypoint.add(0,mc.player.getEyeHeight(),0));
        hold(mc.options.keyUp,true);hold(mc.options.keyJump,next.y()>mc.player.getY()+0.1);hold(mc.options.keySprint,bool(t.parameters,"sprint",false));hold(mc.options.keyShift,bool(t.parameters,"crouch",false));t.actions++;
        if(!t.context.has("last_position")){t.context.add("last_position",vector(mc.player.position()));t.context.addProperty("last_moved",tick());}
        if(mc.player.position().distanceTo(point(child(t.context,"last_position")))>0.25){t.context.add("last_position",vector(mc.player.position()));t.context.addProperty("last_moved",tick());}
        if(tick()-number(t.context,"last_moved",tick())>40){int retries=integer(t.context,"replans",0,0,3);if(retries>=3)return Step.failure("path_unreachable","Actor is stuck",object("position",vector(mc.player.position()),"remaining_distance",distance));t.context.addProperty("replans",retries+1);t.context.addProperty("last_moved",tick());routes.remove(t.id);}
        return Step.waiting(object("stage","walking","position",vector(mc.player.position()),"remaining_distance",distance,"waypoint",idx,"route_length",route.size()));
    }
    private Step relative(Task t) {
        if(!t.context.has("relative_target")){
            var delta=MovementCommands.relative(t.parameters,mc.player.getYRot());
            Vec3 target=mc.player.position().add(delta.x(),0,delta.z());checkArea(target);
            t.context.add("relative_target",vector(target));
        }
        return move(t,point(child(t.context,"relative_target")),number(t.parameters,"tolerance",0.7),false);
    }
    private Step crouch(Task t) {
        hold(mc.options.keyUp,false);hold(mc.options.keyJump,false);hold(mc.options.keySprint,false);
        if(mc.screen!=null)return Step.failure("interface_open","Close interfaces before crouching",t.result);
        if(!mc.player.onGround() || mc.player.getAbilities().flying || mc.player.isPassenger() || mc.player.isInWater() || mc.player.isInLava() || mc.player.isFallFlying())
            return Step.failure("crouch_unavailable","Crouching requires a standing player outside water, lava, vehicles and flight",t.result);
        checkArea(mc.player.position());
        var sequence=crouches.computeIfAbsent(t.id,key->new CrouchSequence(MovementCommands.crouchTicks(t.parameters)));
        JsonObject view=serverView(t,null,null);
        var sample=view==null?null:new CrouchSequence.Sample((long)number(view,"tick",0),bool(view,"crouching",false));
        var update=sequence.observe(tick(),sample);hold(mc.options.keyShift,update.holdCrouch());
        t.confirmed.addProperty("crouch_ticks",update.progress().get("confirmed_crouch_ticks").getAsLong());
        if(update.error()!=null)return Step.failure(update.error(),"Server did not confirm the requested crouch state",update.progress());
        if(update.done()){t.actions++;return Step.success(update.progress());}
        return Step.waiting(update.progress());
    }
    private Step turn(Task t) {
        if(!t.context.has("turn_target")){
            var target=MovementCommands.turn(t.parameters,mc.player.getYRot(),mc.player.getXRot());
            t.context.add("turn_target",object("yaw",target.yaw(),"pitch",target.pitch()));
            t.context.addProperty("turn_sent",tick());
        }
        JsonObject target=child(t.context,"turn_target");
        mc.player.setYRot((float)number(target,"yaw",0));mc.player.setXRot((float)number(target,"pitch",0));
        JsonObject view=serverView(t,null,null);
        if(view!=null && Math.abs(MovementCommands.wrapYaw(number(view,"yaw",0)-number(target,"yaw",0)))<=0.1 && Math.abs(number(view,"pitch",0)-number(target,"pitch",0))<=0.1){
            t.actions++;t.confirmed.add("look",target.deepCopy());return Step.success(object("yaw",view.get("yaw"),"pitch",view.get("pitch"),"source","integrated_server"));
        }
        if(tick()-number(t.context,"turn_sent",tick())>20)return Step.failure("look_unconfirmed","Server did not confirm the requested camera direction",target);
        return Step.waiting(object("stage","confirming_look","target",target));
    }
    private Step jump(Task t) {
        // A pulse lasts one client tick. Holding jump through a server poll can start an extra jump.
        hold(mc.options.keyJump,false);
        hold(mc.options.keyUp,false);hold(mc.options.keySprint,false);
        if(mc.player.getAbilities().flying || mc.player.isPassenger() || mc.player.isInWater() || mc.player.isInLava() || mc.player.isFallFlying())
            return Step.failure("jump_unavailable","Jumping in place requires a standing player outside water, lava, vehicles and flight",t.result);
        checkArea(mc.player.position());
        JumpSequence sequence=jumps.computeIfAbsent(t.id,key->new JumpSequence(JumpSequence.count(t.parameters)));
        JsonObject view=serverView(t,null,null);
        JumpSequence.Sample sample=view==null?null:new JumpSequence.Sample((long)number(view,"tick",0),bool(view,"on_ground",false),number(child(view,"position"),"y",0));
        var update=sequence.observe(tick(),sample,mc.screen==null && mc.player.onGround());
        // The final landing can complete a task whose action budget equals its jump count.
        // Only one unconfirmed pulse is possible, so this never permits an extra jump.
        t.actions=sequence.completed();
        t.confirmed.addProperty("jumps",sequence.completed());
        if(view!=null)update.progress().add("position",view.get("position"));
        if(update.pressJump()) {hold(mc.options.keyJump,true);t.context.addProperty("jump_requests",integer(t.context,"jump_requests",0,0,30)+1);}
        update.progress().addProperty("jump_requests",integer(t.context,"jump_requests",0,0,30));
        if(update.error()!=null)return Step.failure(update.error(),"Server did not confirm takeoff; the jump was not counted",update.progress());
        return update.done()?Step.success(update.progress()):Step.waiting(update.progress());
    }
    private Step attack(Task t) {
        String id=string(t.parameters,"entity_id");JsonObject view=serverView(t,null,id);
        if(view!=null && !view.get("entity").isJsonNull() && !child(view,"entity").get("alive").getAsBoolean())return Step.success(object("outcome","defeated","server",view));
        Entity target=entity(t.parameters);checkArea(target.position());
        if(target instanceof net.minecraft.world.entity.player.Player)throw new BridgeException("permission_denied","Player combat is disabled");
        if(mc.player.distanceTo(target)>mc.player.entityInteractionRange()) {if(!bool(t.parameters,"chase",false))throw new BridgeException("out_of_reach","Target out of attack range",true);return move(t,target.position(),Math.max(0.7,mc.player.entityInteractionRange()-0.5),true);}
        hold(mc.options.keyUp,false);hold(mc.options.keyJump,false);look(target.getBoundingBox().getCenter());
        if(mc.player.getAttackStrengthScale(0)>=1 && tick()-number(t.context,"last_attack",-100)>5) {mc.gameMode.attack(mc.player,target);mc.player.swing(InteractionHand.MAIN_HAND);t.actions++;t.context.addProperty("last_attack",tick());t.confirmed.addProperty("attack_requests",t.actions);}
        return Step.waiting(object("stage","combat","target",id,"server",view));
    }
    private Step breakBlock(Task t,BlockPos p) {
        reach(p);JsonObject view=serverView(t,p,null);
        if(view!=null && (string(child(view,"block"),"type").equals("minecraft:air") || t.context.has("break_expected")&&string(child(view,"block"),"type").equals(string(t.context,"break_expected")))){t.confirmed.add("broken",position(p));return Step.success(object("position",position(p),"state",view.get("block")));}
        BlockState state=mc.level.getBlockState(p);
        if(state.isAir())return Step.waiting(object("waiting","server_confirmation"));
        if(t.context.has("break_original")&&!state.toString().equals(string(t.context,"break_original")))throw new BridgeException("target_changed","Block changed while mining; inspect actual state");
        if(state.getDestroySpeed(mc.level,p)<0)throw new BridgeException("permission_denied","Unbreakable block");
        if(bool(t.parameters,"require_tool",true) && state.requiresCorrectToolForDrops() && !mc.player.getMainHandItem().isCorrectToolForDrops(state))throw new BridgeException("missing_tool","Selected tool cannot harvest block");
        if(!blockVisible(p))throw new BridgeException("target_lost","Block is occluded",true);look(Vec3.atCenterOf(p));
        if(!t.context.has("breaking")){t.context.addProperty("break_original",state.toString());t.context.addProperty("break_expected",blockId(state.getFluidState().createLegacyBlock()));mc.gameMode.startDestroyBlock(p,Direction.UP);t.context.addProperty("breaking",true);}else mc.gameMode.continueDestroyBlock(p,Direction.UP);
        mc.player.swing(InteractionHand.MAIN_HAND);t.actions++;return Step.waiting(object("stage","mining","position",position(p)));
    }
    private boolean matches(BlockState actual,String type,JsonObject wanted){if(!blockId(actual).equals(type))return false;JsonObject props=properties(actual);for(var e:wanted.entrySet())if(!props.has(e.getKey())||!props.get(e.getKey()).getAsString().equals(e.getValue().getAsString()))return false;return true;}
    private boolean matches(JsonObject actual,String type,JsonObject wanted){if(!string(actual,"type").equals(type))return false;JsonObject props=child(actual,"properties");for(var e:wanted.entrySet())if(!props.has(e.getKey())||!props.get(e.getKey()).getAsString().equals(e.getValue().getAsString()))return false;return true;}
    private Step placeBlock(Task t,BlockPos p,String type,JsonObject wanted) {
        reach(p);JsonObject view=serverView(t,p,null);
        if(view!=null && matches(child(view,"block"),type,wanted)){t.confirmed.add("placed",position(p));return Step.success(object("position",position(p),"state",view.get("block")));}
        if(matches(mc.level.getBlockState(p),type,wanted))return Step.waiting(object("waiting","server_confirmation_of_existing_block"));
        if(t.context.has("placing")) {
            if(tick()-number(t.context,"placing",tick())>20)throw new BridgeException("placement_failed","Server did not confirm requested block properties");
            return Step.waiting(object("waiting","server_confirmation"));
        }
        if(!mc.level.getBlockState(p).canBeReplaced())throw new BridgeException("build_conflict","Existing block is protected");
        Item material=BuiltInRegistries.ITEM.getValue(Identifier.parse(type));if(!(material instanceof BlockItem))throw new BridgeException("unsupported_capability","Only block items can be placed");
        BlockState desired=((BlockItem)material).getBlock().defaultBlockState();
        if(!blockId(desired).equals(type))throw new BridgeException("unsupported_capability","This block needs a distinct placement item or mechanic");
        for(var property:wanted.entrySet()){Property<?> definition=desired.getBlock().getStateDefinition().getProperty(property.getKey());if(definition==null||definition.getValue(property.getValue().getAsString()).isEmpty())throw new BridgeException("invalid_request","Invalid block property: "+property.getKey());}
        selectMaterial(type,t);
        BlockHitResult hit=null;
        for(Direction face:Direction.values()) {
            if(wanted.has("axis") && !face.getAxis().getName().equals(wanted.get("axis").getAsString()))continue;
            BlockPos support=p.relative(face.getOpposite());BlockState s=mc.level.getBlockState(support);if(s.getCollisionShape(mc.level,support).isEmpty())continue;
            // Trace slightly through the surface: an endpoint exactly on a voxel boundary may be a MISS.
            Vec3 aim=Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.49));
            BlockHitResult ray=mc.level.clip(new ClipContext(mc.player.getEyePosition(),aim,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
            if(ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(support) && ray.getDirection()==face){hit=ray;break;}
        }
        if(hit==null)throw new BridgeException("missing_support","No visible support face available",true);
        look(hit.getLocation());
        if(wanted.has("facing")){String face=wanted.get("facing").getAsString();switch(face){case "north"->mc.player.setYRot(0);case "south"->mc.player.setYRot(180);case "east"->mc.player.setYRot(90);case "west"->mc.player.setYRot(-90);default->{}}}
        mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);t.context.addProperty("placing",tick());t.actions++;return Step.waiting(object("stage","placing","position",position(p)));
    }
    private void selectMaterial(String type,Task t){for(int i=0;i<9;i++)if(itemId(mc.player.getInventory().getItem(i)).equals(type)){mc.player.getInventory().setSelectedSlot(i);return;}throw new BridgeException("missing_materials","Required block must be in the hotbar: "+type);}
    private Step interact(Task t) {
        if(!t.context.has("sent")) {
            if(t.parameters.has("position")){BlockPos before=block(child(t.parameters,"position"));t.context.add("block_before",object("type",blockId(mc.level.getBlockState(before)),"properties",properties(mc.level.getBlockState(before))));}
            if(t.parameters.has("entity_id")){Entity e=entity(t.parameters);checkArea(e.position());if(mc.player.distanceTo(e)>mc.player.entityInteractionRange())throw new BridgeException("out_of_reach","Entity out of reach");look(e.getBoundingBox().getCenter());mc.gameMode.interact(mc.player,e,new EntityHitResult(e),InteractionHand.MAIN_HAND);}
            else {BlockPos p=block(child(t.parameters,"position"));reach(p);BlockHitResult ray=mc.level.clip(new ClipContext(mc.player.getEyePosition(),Vec3.atCenterOf(p),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));if(!ray.getBlockPos().equals(p))throw new BridgeException("target_lost","Occluded target");look(ray.getLocation());mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,ray);}
            t.actions++;t.context.addProperty("sent",tick());t.context.addProperty("before_menu",mc.player.containerMenu.containerId);
        }
        JsonObject view=serverView(t,t.parameters.has("position")?block(child(t.parameters,"position")):null,null);
        if(view!=null && view.get("menu_id").getAsInt()!=0)return Step.success(object("menu_id",view.get("menu_id"),"server",view));
        if(view!=null && view.has("block") && t.context.has("block_before") && !view.get("block").equals(t.context.get("block_before")))return Step.success(object("outcome","block_changed","before",t.context.get("block_before"),"after",view.get("block")));
        if(tick()-number(t.context,"sent",tick())>20)return Step.failure("interaction_unconfirmed","No supported interface opened; inspect world state for custom interactions",object("server",view));
        return Step.waiting(object("waiting","interface_confirmation"));
    }
    private Step use(Task t) {
        checkArea(mc.player.position());
        String handName=string(t.parameters,"hand","main");
        InteractionHand hand=handName.equals("off")?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
        int duration=integer(t.parameters,"duration_ticks",40,1,1200);
        if(!t.context.has("use_sent")){
            if(mc.screen!=null || mc.player.containerMenu.containerId!=0)return Step.failure("interface_open","Close interfaces before using an item",object());
            if(mc.player.isUsingItem())return Step.failure("busy","Actor is already using an item",object());
            t.context.addProperty("use_item",itemId(mc.player.getItemInHand(hand)));
            JsonObject before=serverView(t,null,null);
            if(before==null)return Step.waiting(object("stage","reading_held_item"));
            if(number(child(before,handName+"_hand"),"count",0)==0)return Step.failure("empty_hand","Selected hand is empty",object());
            if(bool(before,"using_item",false))return Step.failure("busy","Server actor is already using an item",object());
            if(!before.get(handName+"_hand").equals(heldStack(mc.player.getItemInHand(hand))))return Step.failure("target_changed","Held item changed before use",object());
            t.context.add("use_before",before);
            if(t.parameters.has("target"))look(point(child(t.parameters,"target")));
            mc.gameMode.useItem(mc.player,hand);t.context.addProperty("use_sent",tick());t.actions++;
            if(mc.player.isUsingItem())hold(mc.options.keyUse,true);
            return Step.waiting(object("stage","confirming_item_use"));
        }
        JsonObject view=serverView(t,null,null);
        long elapsed=tick()-(long)number(t.context,"use_sent",tick());
        if(view==null)return Step.waiting(object("stage","confirming_item_use"));
        JsonObject before=child(t.context,"use_before");
        if(hand==InteractionHand.MAIN_HAND && number(view,"selected_slot",-1)!=number(before,"selected_slot",-1))
            return Step.failure("target_changed","Selected hand changed during use",object());
        boolean active=ItemUseEvidence.using(view,handName);
        if(active){
            if(!t.context.has("use_first_tick"))t.context.addProperty("use_first_tick",number(view,"tick",tick()));
            t.context.addProperty("use_last_tick",number(view,"tick",tick()));
        }
        boolean changed=ItemUseEvidence.itemChanged(before,view,handName),recorded=ItemUseEvidence.recordedUse(before,view);
        boolean observed=t.context.has("use_first_tick");
        long heldTicks=observed?(long)(number(t.context,"use_last_tick",0)-number(t.context,"use_first_tick",0)+1):0;
        JsonObject evidence=object("item",string(t.context,"use_item"),"hand",handName,"held_item_changed",changed,"server_use_ticks",heldTicks,
                "recorded_uses",Math.max(0,(int)(number(view,"item_uses",0)-number(before,"item_uses",0))));
        JsonObject result=object("before",ItemUseEvidence.summary(before,handName),"after",ItemUseEvidence.summary(view,handName),
                "server_use_confirmed",observed||changed||recorded,"effect_observed",changed,"source","integrated_server");
        if(observed||changed||recorded)t.confirmed.add("item_use",evidence);
        if(active && elapsed>=duration && !t.context.has("use_released")){
            hold(mc.options.keyUse,false);mc.gameMode.releaseUsingItem(mc.player);t.context.addProperty("use_released",tick());
            return Step.waiting(result);
        }
        // Poll after release so the result includes its server effects (e.g. bow damage).
        if(!active && (observed||changed||recorded) && (!t.context.has("use_released") || tick()-number(t.context,"use_released",tick())>=4)){
            result.addProperty("outcome",changed?"held_item_changed":recorded?"item_use_recorded":"use_released");
            return Step.success(result);
        }
        if(!observed && !changed && !recorded && elapsed>20)return Step.failure("use_no_effect","Server observed no use or held-item change; the item may require a block/entity target or hunger",result);
        if(t.context.has("use_released") && tick()-number(t.context,"use_released",tick())>20)return Step.failure("use_unconfirmed","Server did not confirm item release",result);
        return Step.waiting(result);
    }
    private Step drop(Task t){
        if(mc.screen!=null || mc.player.containerMenu.containerId!=0)return Step.failure("interface_open","Close interfaces before dropping an item",t.result);
        if(mc.player.isUsingItem())return Step.failure("busy","Stop using the held item before dropping it",t.result);
        checkArea(mc.player.position());
        ItemDropSequence sequence=drops.computeIfAbsent(t.id,key->new ItemDropSequence(ItemDropSequence.count(t.parameters),string(t.parameters,"expected_item","")));
        JsonObject view=serverView(t,null,null);
        ItemDropSequence.Sample sample=view==null?null:new ItemDropSequence.Sample((long)number(view,"tick",0),
                (int)number(view,"selected_slot",-1),string(child(view,"main_hand"),"type"),(int)number(child(view,"main_hand"),"count",0));
        var update=sequence.observe(tick(),sample);
        t.confirmed.add("drop",object("item",update.progress().get("item"),"count",sequence.completed()));
        if(update.error()!=null)return Step.failure(update.error(),"Held-item count did not confirm the requested drop",update.progress());
        if(update.pressDrop()){
            if(!mc.player.drop(false))return Step.failure("target_changed","Client hand became empty before drop",update.progress());
            t.actions++;
        }
        return update.done()?Step.success(update.progress()):Step.waiting(update.progress());
    }
    private Step pickup(Task t){
        String target=string(t.parameters,"entity_id");
        if(!t.context.has("pickup_started")){
            Entity entity=entity(t.parameters);
            if(!(entity instanceof ItemEntity item))return Step.failure("invalid_target","Pickup requires an observed item entity",object());
            checkArea(entity.position());pickups.begin(target);
            t.context.addProperty("pickup_started",tick());t.context.add("pickup_item",heldStack(item.getItem()));
        }
        var receipt=pickups.peek(target,(long)number(t.context,"pickup_started",tick()));
        if(receipt!=null){
            releaseControls();
            JsonObject result=object("entity_id",target,"item",receipt.item(),"collected",receipt.collected(),"remaining",receipt.remaining(),"source",receipt.source());
            t.confirmed.add("pickup",object("item",receipt.item(),"count",receipt.collected()));
            return receipt.remaining()==0?Step.success(result):Step.failure("inventory_full","Only part of the ground stack fitted in the inventory",result);
        }
        if(t.actions>=t.maxActions){
            releaseControls();if(!t.context.has("pickup_budget_wait"))t.context.addProperty("pickup_budget_wait",tick());
            if(tick()-number(t.context,"pickup_budget_wait",tick())>20)return Step.failure("action_limit","Movement budget exhausted without a server pickup receipt",t.result);
            return Step.waiting(object("stage","confirming_pickup","entity_id",target));
        }
        if(mc.screen!=null || mc.player.containerMenu.containerId!=0)return Step.failure("interface_open","Close interfaces before picking up an item",t.result);
        Entity entity=entity(t.parameters);
        if(!(entity instanceof ItemEntity item) || !itemId(item.getItem()).equals(string(child(t.context,"pickup_item"),"type")))
            return Step.failure("target_changed","Observed ground item changed",t.result);
        checkArea(entity.position());
        Step movement=move(t,entity.position(),0.5,false);
        if(movement.error()!=null)return movement;
        if(movement.done() || mc.player.position().distanceTo(entity.position())<=0.5){
            if(!t.context.has("pickup_wait"))t.context.addProperty("pickup_wait",tick());
            if(tick()-number(t.context,"pickup_wait",tick())>40)return Step.failure("pickup_unconfirmed","No server pickup receipt; inventory may be full or item may not be available",object("entity_id",target));
            return Step.waiting(object("stage","waiting_for_pickup","entity_id",target));
        }
        t.context.remove("pickup_wait");return movement;
    }
    private Step transfer(Task t) {
        AbstractContainerMenu menu=mc.player.containerMenu;int from=integer(t.parameters,"from_slot",0,0,menu.slots.size()-1),to=integer(t.parameters,"to_slot",0,0,menu.slots.size()-1);
        if(from==to)throw new BridgeException("invalid_request","Source and destination are identical");
        if(t.parameters.has("menu_id") && integer(t.parameters,"menu_id",0,0,10000)!=menu.containerId)throw new BridgeException("stale_revision","Menu changed");
        if(t.parameters.has("menu_revision") && !t.context.has("transfer_sent") && integer(t.parameters,"menu_revision",0,0,Integer.MAX_VALUE)!=menu.getStateId())throw new BridgeException("stale_revision","Menu contents changed");
        if(!t.context.has("transfer_sent")) {
            if(!menu.getCarried().isEmpty())throw new BridgeException("cursor_occupied","Clear cursor before transferring");
            Slot source=menu.getSlot(from),target=menu.getSlot(to);ItemStack src=source.getItem(),dst=target.getItem();
            int count=integer(t.parameters,"count",src.getCount(),1,64);
            if(src.isEmpty() || src.getCount()<count)throw new BridgeException("missing_materials","Source contains too few items");
            if(!source.mayPickup(mc.player)||!target.mayPlace(src))throw new BridgeException("permission_denied","Menu disallows this transfer");
            if(!dst.isEmpty()&&!ItemStack.isSameItemSameComponents(src,dst))throw new BridgeException("inventory_full","Destination contains another item");
            if(target.getMaxStackSize(src)-dst.getCount()<count)throw new BridgeException("inventory_full","Destination has insufficient capacity");
            t.context.addProperty("source_before",src.getCount());t.context.addProperty("target_before",dst.getCount());t.context.addProperty("item",itemId(src));t.context.addProperty("count",count);t.context.addProperty("transfer_menu",menu.containerId);
            click(from,0,ContainerInput.PICKUP);for(int i=0;i<count;i++)click(to,1,ContainerInput.PICKUP);click(from,0,ContainerInput.PICKUP);
            t.actions+=count+2;t.context.addProperty("transfer_sent",tick());
        }
        JsonObject v=serverView(t,null,null);
        if(v==null)return Step.waiting(object("waiting","server_transfer"));
        if(v.get("menu_id").getAsInt()!=t.context.get("transfer_menu").getAsInt())return Step.failure("interface_closed","Menu changed during transfer",object("server",v));
        JsonArray slots=v.getAsJsonArray("slots");int source=slots.get(from).getAsJsonObject().get("count").getAsInt(),target=slots.get(to).getAsJsonObject().get("count").getAsInt(),count=t.context.get("count").getAsInt();
        int transferred=Math.max(0,t.context.get("source_before").getAsInt()-source);
        JsonObject result=object("transferred",transferred,"remaining",Math.max(0,count-transferred),"source_slot",slots.get(from),"target_slot",slots.get(to),"cursor",v.get("cursor"));
        if(source==t.context.get("source_before").getAsInt()-count && target==t.context.get("target_before").getAsInt()+count && child(v,"cursor").get("count").getAsInt()==0){t.confirmed.add("transfer",result);return Step.success(result);}
        if(tick()-number(t.context,"transfer_sent",tick())>20)return Step.failure("transfer_unconfirmed","Observed slot counts differ",result);
        return Step.waiting(result);
    }
    private void click(int slot,int button,ContainerInput action){mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,slot,button,action,mc.player);}
    private Map<String,Integer> counts(){Map<String,Integer> counts=new HashMap<>();for(int i=0;i<mc.player.getInventory().getContainerSize();i++){ItemStack s=mc.player.getInventory().getItem(i);if(!s.isEmpty())counts.merge(itemId(s),s.getCount(),Integer::sum);}return counts;}
    private RecipeCatalog catalog(){if(!catalogFuture.isDone())throw new BridgeException("busy","Recipe catalog is loading",true);return catalogFuture.join();}
    private Set<String> stations(){return mc.player.containerMenu instanceof CraftingMenu?Set.of("inventory","crafting_table"):Set.of("inventory");}
    private CraftPlanner.Plan craftPlan(JsonObject d){return catalog().planner().plan(string(d,"item"),integer(d,"count",1,1,4096),counts(),stations());}
    private Step craft(Task t) {
        CraftPlanner.Plan plan=crafts.computeIfAbsent(t.id,key->craftPlan(t.parameters));
        if(!plan.executable())return Step.failure("missing_materials","Missing ingredients or open crafting station",GSON.toJsonTree(plan).getAsJsonObject());
        if(!(mc.player.containerMenu instanceof AbstractCraftingMenu menu))throw new BridgeException("unsupported_capability","Open a vanilla crafting menu");
        if(!menu.getCarried().isEmpty())throw new BridgeException("cursor_occupied","Clear cursor before crafting");
        int stage=integer(t.context,"craft_stage",0,0,10000),iteration=integer(t.context,"craft_iteration",0,0,4096);
        if(stage>=plan.stages().size()){crafts.remove(t.id);return Step.success(object("item",string(t.parameters,"item"),"inventory",counts(),"stages",plan.stages()));}
        var step=plan.stages().get(stage);var recipe=catalog().entries.get(step.recipe());
        if(recipe.width()>menu.getGridWidth()||recipe.height()>menu.getGridHeight())throw new BridgeException("missing_station","A crafting table must be open");
        if(t.context.has("craft_sent")) {
            JsonObject v=serverView(t,null,null);if(v==null)return Step.waiting(object("waiting","server_craft"));
            JsonObject amounts=child(v,"inventory_counts");int actual=amounts.has(step.item())?amounts.get(step.item()).getAsInt():0;
            if(actual>=t.context.get("craft_before").getAsInt()+recipe.recipe().count()) {
                t.confirmed.addProperty("crafts_confirmed",integer(t.confirmed,"crafts_confirmed",0,0,100000)+1);
                t.context.remove("craft_sent");t.context.addProperty("craft_cell",0);iteration++;
                if(iteration>=step.crafts()){stage++;iteration=0;}t.context.addProperty("craft_stage",stage);t.context.addProperty("craft_iteration",iteration);
                return Step.waiting(object("stage",stage,"craft_iteration",iteration));
            }
            if(tick()-number(t.context,"craft_sent",tick())>20)throw new BridgeException("craft_unconfirmed","Server did not confirm crafting output");return Step.waiting(object("waiting","server_craft"));
        }
        int cell=integer(t.context,"craft_cell",0,0,9);
        if(cell==0 && !t.context.has("grid_cleared")) {
            for(Slot grid:menu.getInputGridSlots())if(grid.hasItem()){click(grid.index,0,ContainerInput.QUICK_MOVE);t.actions++;return Step.waiting(object("stage","clearing_grid"));}
            t.context.addProperty("grid_cleared",true);
        }
        while(cell<recipe.grid().size() && recipe.grid().get(cell).isEmpty())cell++;
        if(cell<recipe.grid().size()) {
            int ingredientIndex=0;for(int previous=0;previous<cell;previous++)if(!recipe.grid().get(previous).isEmpty())ingredientIndex++;
            String planned=step.ingredients().get(iteration*recipe.recipe().ingredients().size()+ingredientIndex);
            List<String> alternatives=List.of(planned);Slot source=null;
            for(Slot s:menu.slots)if(s.container==mc.player.getInventory() && alternatives.contains(itemId(s.getItem()))){source=s;break;}
            if(source==null)throw new BridgeException("missing_materials","Ingredient is unavailable: "+alternatives);
            int index=(cell/recipe.width())*menu.getGridWidth()+(cell%recipe.width());Slot target=menu.getInputGridSlots().get(index);
            if(target.hasItem())throw new BridgeException("stale_revision","Craft grid changed");
            click(source.index,0,ContainerInput.PICKUP);click(target.index,1,ContainerInput.PICKUP);click(source.index,0,ContainerInput.PICKUP);t.actions+=3;
            t.context.addProperty("craft_cell",cell+1);return Step.waiting(object("stage","filling_grid","cell",cell));
        }
        ItemStack result=menu.getResultSlot().getItem();
        if(!itemId(result).equals(step.item()))return Step.waiting(object("waiting","recipe_result"));
        boolean room=mc.player.getInventory().getFreeSlot()!=-1 || mc.player.getInventory().getSlotWithRemainingSpace(result)!=-1;
        if(!room)throw new BridgeException("inventory_full","No room for crafting output");
        t.context.addProperty("craft_before",counts().getOrDefault(step.item(),0));click(menu.getResultSlot().index,0,ContainerInput.QUICK_MOVE);t.actions++;t.context.addProperty("craft_sent",tick());t.context.remove("grid_cleared");
        return Step.waiting(object("stage","crafting","recipe",step.recipe()));
    }
    private Step build(Task t) {
        BuildPlanner.Plan plan=builds.computeIfAbsent(t.id,key->new BuildPlanner().plan(t.parameters));
        if(bool(t.parameters,"replace",false))throw new BridgeException("unsupported_capability","Automatic replacement is disabled; authorize block.break separately");
        int index=integer(t.context,"build_index",0,0,4096);
        if(index>=plan.blocks().size()){
            CompletableFuture<Integer> check=buildChecks.get(t.id);
            if(check==null){var dimension=mc.level.dimension();check=mc.getSingleplayerServer().submit(()->{var level=mc.getSingleplayerServer().getLevel(dimension);for(int i=0;i<plan.blocks().size();i++){var b=plan.blocks().get(i);if(!matches(level.getBlockState(new BlockPos(b.x(),b.y(),b.z())),b.type(),b.properties()))return i;}return -1;});buildChecks.put(t.id,check);return Step.waiting(object("stage","final_verification"));}
            if(!check.isDone())return Step.waiting(object("stage","final_verification"));
            buildChecks.remove(t.id);int wrong=check.join();
            if(wrong>=0){t.context.addProperty("build_index",wrong);return Step.waiting(object("stage","revalidating_changed_block","index",wrong));}
            return Step.success(object("confirmed_blocks",plan.blocks().size(),"source","integrated_server"));
        }
        var b=plan.blocks().get(index);BlockPos p=new BlockPos(b.x(),b.y(),b.z());checkArea(Vec3.atCenterOf(p));
        if(t.context.has("build_stand")) {
            Step approaching=move(t,point(child(t.context,"build_stand")),0.4,false);
            if(approaching.done()){t.context.remove("build_stand");routes.remove(t.id);hold(mc.options.keyUp,false);hold(mc.options.keyJump,false);}
            return approaching.error()!=null?approaching:Step.waiting(object("stage","finding_visible_build_face","movement",approaching.result()));
        }
        if(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(p))>mc.player.blockInteractionRange()) {
            Vec3 stand=null;double best=Double.MAX_VALUE;
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){Pathfinder.Cell c=new Pathfinder.Cell(b.x()+dx,b.y(),b.z()+dz);Vec3 v=new Vec3(c.x()+0.5,c.y(),c.z()+0.5);if((dx!=0||dz!=0)&&terrain(c)==Pathfinder.Terrain.WALKABLE && v.distanceTo(mc.player.position())<best){best=v.distanceTo(mc.player.position());stand=v;}}
            if(stand==null)throw new BridgeException("path_unreachable","No standing place near build cell");Step approach=move(t,stand,0.65,false);return approach.error()!=null?approach:Step.waiting(object("stage","approaching_build_cell","movement",approach.result()));
        }
        hold(mc.options.keyUp,false);hold(mc.options.keyJump,false);
        Step placed;
        try {placed=placeBlock(t,p,b.type(),b.properties());}
        catch(BridgeException e) {
            if(!e.code.equals("missing_support") || integer(t.context,"reposition_count",0,0,3)>=3)throw e;
            Vec3 standing=null;double best=Double.MAX_VALUE;
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
                if(dx==0&&dz==0)continue;Pathfinder.Cell candidate=new Pathfinder.Cell(p.getX()+dx,p.getY(),p.getZ()+dz);if(terrain(candidate)!=Pathfinder.Terrain.WALKABLE)continue;
                Vec3 at=new Vec3(candidate.x()+0.5,candidate.y(),candidate.z()+0.5),eye=at.add(0,mc.player.getEyeHeight(),0);
                if(at.distanceTo(mc.player.position())<0.5)continue;
                if(eye.distanceTo(Vec3.atCenterOf(p))>mc.player.blockInteractionRange())continue;
                boolean supportVisible=false;
                for(Direction face:Direction.values()) {
                    BlockPos support=p.relative(face.getOpposite());if(mc.level.getBlockState(support).getCollisionShape(mc.level,support).isEmpty())continue;
                    Vec3 aim=Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.49));
                    BlockHitResult ray=mc.level.clip(new ClipContext(eye,aim,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
                    if(ray.getType()==HitResult.Type.BLOCK&&ray.getBlockPos().equals(support)&&ray.getDirection()==face){supportVisible=true;break;}
                }
                if(supportVisible && at.distanceTo(mc.player.position())<best){try{path(at);}catch(BridgeException blocked){continue;}best=at.distanceTo(mc.player.position());standing=at;}
            }
            if(standing==null)throw e;
            t.context.add("build_stand",vector(standing));t.context.addProperty("reposition_count",integer(t.context,"reposition_count",0,0,3)+1);routes.remove(t.id);
            return Step.waiting(object("stage","repositioning_for_support","confirmed_blocks",index));
        }
        if(placed.done() && placed.error()==null){t.context.addProperty("build_index",index+1);t.context.remove("placing");t.context.remove("reposition_count");routes.remove(t.id);t.confirmed.addProperty("blocks",index+1);return Step.waiting(object("confirmed_blocks",index+1,"total",plan.blocks().size()));}
        return placed;
    }
    @Override public JsonObject query(String operation,JsonObject d) {
        return switch(operation) {
            case "inventory.inspect" -> object("inventory",snapshot(1,false).get("inventory"),"menu",snapshot(1,false).get("context"));
            case "route.plan" -> object("path",path(point(child(d,"target"))),"modes",List.of("walk","jump"),"unknown_cells","excluded");
            case "craft.plan" -> GSON.toJsonTree(craftPlan(d)).getAsJsonObject();
            case "build.plan" -> {
                var plan=new BuildPlanner().plan(d);JsonArray conflicts=new JsonArray();Map<String,Integer> needed=new HashMap<>();
                for(var b:plan.blocks()){BlockPos p=new BlockPos(b.x(),b.y(),b.z());checkArea(Vec3.atCenterOf(p));if(!mc.level.hasChunkAt(p))throw new BridgeException("chunk_unavailable","Blueprint crosses unloaded chunk");BlockState actual=mc.level.getBlockState(p);if(matches(actual,b.type(),b.properties()))continue;if(!actual.canBeReplaced())conflicts.add(position(p));needed.merge(b.type(),1,Integer::sum);}
                Map<String,Integer> missing=new HashMap<>(),counts=counts();needed.forEach((k,v)->{int m=v-counts.getOrDefault(k,0);if(m>0)missing.put(k,m);});
                yield object("blocks",plan.blocks(),"materials",needed,"missing",missing,"conflicts",conflicts,"replacement","explicit_break_required","supports","verified_during_execution");
            }
            default -> throw new BridgeException("unsupported_capability",operation);
        };
    }
}
