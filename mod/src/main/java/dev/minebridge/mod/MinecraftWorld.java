package dev.minebridge.mod;

import com.google.gson.*;
import dev.minebridge.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ChargedProjectiles;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
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
    private record SlotChange(int slot,ItemStack before,ItemStack after) {}
    private record InventoryChange(int menu,List<SlotChange> slots,List<InventorySwapPlan.Click> clicks) {
        boolean matches(AbstractContainerMenu current,boolean after) {
            if(current.containerId!=menu || !current.getCarried().isEmpty())return false;
            for(var change:slots)if(change.slot()>=current.slots.size() || !ItemStack.matches(current.getSlot(change.slot()).getItem(),after?change.after():change.before()))return false;
            return true;
        }
    }
    private final Map<String,InventoryChange> inventoryChanges=new HashMap<>();
    private final Map<String,CraftSequence> craftSequences=new HashMap<>();
    private final Map<String,BlockPos> craftStations=new HashMap<>();
    private final Map<String,List<Pathfinder.Cell>> routes=new HashMap<>();
    private final Map<String,JumpSequence> jumps=new HashMap<>();
    private final Map<String,CrouchSequence> crouches=new HashMap<>();
    private final Map<String,FollowSequence> follows=new HashMap<>();
    private final Map<String,CrossbowSequence> crossbows=new HashMap<>();
    private final Map<String,ItemDropSequence> drops=new HashMap<>();
    private final PickupLedger pickups;
    private final CombatLedger combat;
    private final InteractionLedger interactions;
    private final MiningLedger mining;
    private final PlacementLedger placement;
    private record PlacementMaterial(int selected,ItemStack stack,BlockPos support,BlockState supportState) {}
    private final Map<String,PlacementMaterial> placementMaterials=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,MeleeSequence> melees=new HashMap<>();
    private record MiningTool(int selected,ItemStack stack) {}
    private final Map<String,MiningTool> miningTools=new HashMap<>();
    // Only server-side join/tick callbacks access this bounded set.
    private final LinkedHashSet<String> freshArrows=new LinkedHashSet<>();
    private boolean bowDrawing;
    // Accessed only by server-side pickup/player-tick callbacks.
    private final Map<String,PickupAttempt> pickupAttempts=new HashMap<>();
    private final Map<String,CraftPlanner.Plan> crafts=new HashMap<>();
    private final Map<String,BuildPlanner.Plan> builds=new HashMap<>();
    private final Map<String,CompletableFuture<Integer>> buildChecks=new HashMap<>();
    private final Set<net.minecraft.client.KeyMapping> held=new HashSet<>();
    MinecraftWorld(Minecraft mc,OwnerPolicy policy) throws java.io.IOException {
        this.mc=mc;this.policy=policy;actor=mc.player.getUUID().toString();initialDimension=mc.level.dimension().identifier().toString();
        pickups=new PickupLedger(actor,initialDimension);
        combat=new CombatLedger(actor,initialDimension);
        interactions=new InteractionLedger(actor,initialDimension);mining=new MiningLedger(actor,initialDimension);placement=new PlacementLedger(actor,initialDimension);
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
    @Override public void taskFinished(Task t){melees.remove(t.id);mining.end(t.id);miningTools.remove(t.id);placement.end(t.id);placementMaterials.remove(t.id);confirmations.remove(t.id);inventoryChanges.remove(t.id);craftSequences.remove(t.id);craftStations.remove(t.id);routes.remove(t.id);jumps.remove(t.id);crouches.remove(t.id);follows.remove(t.id);crossbows.remove(t.id);drops.remove(t.id);crafts.remove(t.id);builds.remove(t.id);buildChecks.remove(t.id);combat.end(t.id);interactions.end(t.id);if(t.action.equals("item.pickup"))pickups.discard(string(t.parameters,"entity_id"));}
    @Override public void collectConfirmed(Task t) {
        updateCombatReceipt(t,combat.finish(t.id));
        updateInteractionReceipt(t,interactions.finish(t.id));updateMiningReceipt(t,mining.finish(t.id));
        updatePlacementReceipt(t,placement.finish(t.id));
    }
    private void updateInteractionReceipt(Task task,JsonObject receipt){if(receipt!=null){task.confirmed.addProperty("interactions",1);task.confirmed.add("interaction",receipt);}}
    private static String interactionKey(BlockPos pos){return "block:"+pos.getX()+","+pos.getY()+","+pos.getZ();}
    private JsonObject interactionSample(net.minecraft.server.level.ServerPlayer player,String target) {
        JsonObject state=object("key",target);var level=player.level();
        if(target.startsWith("block:")) {
            String[] coordinates=target.substring(6).split(",");BlockPos pos=new BlockPos(Integer.parseInt(coordinates[0]),Integer.parseInt(coordinates[1]),Integer.parseInt(coordinates[2]));
            if(!level.hasChunkAt(pos))return null;
            BlockState block=level.getBlockState(pos);state.addProperty("type",blockId(block));state.add("properties",properties(block));
        } else {
            Entity entity=level.getEntity(UUID.fromString(target.substring(7)));if(entity==null)return null;
            state.addProperty("type",BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());state.addProperty("alive",entity.isAlive());
            if(entity instanceof net.minecraft.world.entity.animal.Animal animal){state.addProperty("in_love",animal.isInLove());state.addProperty("love_cause",animal.getLoveCause()==null?"none":animal.getLoveCause().getUUID().toString());}
            if(entity instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep){state.addProperty("sheared",sheep.isSheared());state.addProperty("color",sheep.getColor().getName());}
        }
        return object("target",state,"main_hand",heldStack(player.getMainHandItem()),"off_hand",heldStack(player.getOffhandItem()),
                "selected_slot",player.getInventory().getSelectedSlot(),"menu_id",player.containerMenu.containerId);
    }
    void recordInteraction(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent event,String target) {
        if(event.getLevel().isClientSide() || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) || !actor.equals(player.getUUID().toString()))return;
        if(event instanceof net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock click && click.getFace()!=null) {
            String task=placement.clicked(actor,player.level().dimension().identifier().toString(),event.getHand()==InteractionHand.MAIN_HAND?"main":"off",position(click.getPos()),click.getFace().getName(),heldStack(player.getItemInHand(event.getHand())),player.getInventory().getSelectedSlot(),player.level().getGameTime());
            if(task!=null){var material=placementMaterials.get(task);if(material==null || !ItemStack.matches(material.stack(),player.getItemInHand(event.getHand())))placement.reject(task,"target_changed");}
        }
        JsonObject before=interactionSample(player,target);if(before==null)return;
        if(interactions.recordAttempt(actor,player.level().dimension().identifier().toString(),target,event.getHand()==InteractionHand.MAIN_HAND?"main":"off",player.level().getGameTime(),before)) {
            var server=player.level().getServer();
            // Queue rather than execute inline: vanilla must finish handling this click first.
            server.schedule(new net.minecraft.server.TickTask(server.getTickCount(),()->confirmInteractions(player)));
        }
    }
    void confirmInteractions(net.minecraft.world.entity.player.Player actor) {
        if(!(actor instanceof net.minecraft.server.level.ServerPlayer player))return;
        String id=player.getUUID().toString(),dimension=player.level().dimension().identifier().toString();
        for(var pending:interactions.pending(id,dimension)) {
            JsonObject after=interactionSample(player,pending.target());
            if(after!=null)interactions.recordResult(pending.task(),id,dimension,player.level().getGameTime(),after);
        }
    }
    private void updateCombatReceipt(Task t,CombatLedger.Snapshot receipt) {
        if(receipt==null || receipt.shots()==0 && receipt.hits()==0)return;
        t.confirmed.addProperty("shots",receipt.shots());t.confirmed.addProperty("hits",receipt.hits());t.confirmed.addProperty("damage",receipt.damage());
        t.confirmed.addProperty("target_defeated",receipt.defeated());
        t.confirmed.add("combat",object("target",string(t.parameters,"entity_id"),"projectile_id",receipt.projectileId(),"remaining_health",receipt.remainingHealth(),"tick",receipt.tick(),"source",receipt.hits()>0?"integrated_server_damage_event":"integrated_server_projectile_tick"));
    }
    void recordProjectile(Entity entity) {
        // ServerLevel increments tickCount before EntityTickEvent.Pre.
        if(entity.level().isClientSide() || entity.tickCount!=1 || !(entity instanceof AbstractArrow arrow) || arrow.getOwner()==null || !freshArrows.remove(entity.getUUID().toString()))return;
        combat.recordShot(arrow.getOwner().getUUID().toString(),entity.level().dimension().identifier().toString(),entity.getUUID().toString(),entity.level().getGameTime());
    }
    void recordArrowSpawn(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if(event.getLevel().isClientSide() || event.loadedFromDisk() || !(event.getEntity() instanceof AbstractArrow arrow) || arrow.getOwner()==null || !actor.equals(arrow.getOwner().getUUID().toString()))return;
        freshArrows.add(arrow.getUUID().toString());while(freshArrows.size()>128)freshArrows.remove(freshArrows.iterator().next());
    }
    void recordDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        LivingEntity target=event.getEntity();if(target.level().isClientSide())return;
        Entity attacker=event.getSource().getEntity(),direct=event.getSource().getDirectEntity();if(attacker==null || direct==null)return;
        combat.recordDamage(attacker.getUUID().toString(),target.level().dimension().identifier().toString(),target.getUUID().toString(),direct.getUUID().toString(),direct instanceof AbstractArrow,event.getHealthDamage(),target.getHealth(),target.level().getGameTime());
    }
    @Override public boolean awaitingConfirmation(Task t){
        return switch(t.action){
            case "move.follow"->follows.containsKey(t.id);
            case "item.use"->t.context.has("use_sent");
            case "combat.ranged"->t.context.has("bow_started") || crossbows.containsKey(t.id);
            case "combat.attack"->t.context.has("last_attack");
            case "item.drop"->drops.containsKey(t.id) && drops.get(t.id).awaitingConfirmation();
            case "item.pickup"->t.context.has("pickup_started");
            case "inventory.select"->t.context.has("selection_sent");
            case "inventory.select_item"->t.context.has("material_sent");
            case "inventory.transfer", "inventory.equip", "inventory.swap"->t.context.has("transfer_sent") || t.context.has("swap_sent");
            case "block.break"->t.context.has("breaking") || t.context.has("material_sent");
            case "block.place"->t.context.has("placing") || t.context.has("material_sent");
            case "world.interact", "container.open"->t.context.has("sent");
            case "craft.execute"->t.context.has("station_sent") || craftSequences.containsKey(t.id) && craftSequences.get(t.id).pending()!=null || crafts.containsKey(t.id) && integer(t.context,"craft_stage",0,0,10000)>=crafts.get(t.id).stages().size();
            case "build.execute"->buildAwaitingConfirmation(t);
            case "container.close"->t.context.has("sent");
            default->false;
        };
    }
    private boolean buildAwaitingConfirmation(Task task) {
        if(task.context.has("placing") || task.context.has("material_sent"))return true;
        var plan=builds.get(task.id);if(plan==null)return false;int index=integer(task.context,"build_index",0,0,4096);
        if(index>=plan.blocks().size())return true;
        var next=plan.blocks().get(index);BlockPos pos=new BlockPos(next.x(),next.y(),next.z());
        if(clientChunkLoaded(pos) && matches(mc.level.getBlockState(pos),next.type(),next.properties()))return true;
        if(task.confirmed.has("block_updates"))for(JsonElement element:task.confirmed.getAsJsonArray("block_updates")){
            JsonObject cell=element.getAsJsonObject();
            if(number(cell,"x",Double.NaN)==pos.getX() && number(cell,"y",Double.NaN)==pos.getY() && number(cell,"z",Double.NaN)==pos.getZ() && matches(cell,next.type(),next.properties()))return true;
        }
        return false;
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
        return Set.of("move.to","move.follow","move.jump","move.relative","move.crouch","route.plan","look.at","look.turn","combat.attack","combat.ranged","item.use","item.drop","item.pickup","world.interact","block.break","block.place",
                "inventory.inspect","inventory.select","inventory.select_item","inventory.transfer","inventory.equip","inventory.swap","container.open","container.close","craft.plan","craft.execute","build.plan","build.execute");
    }
    @Override public Set<String> actionFeatures(String action){if(!mc.hasSingleplayerServer())return Set.of();return switch(action){case "combat.attack"->Set.of("confirmed_melee_v1");case "craft.execute"->Set.of("craft_station_v1");case "block.place"->Set.of("confirmed_placement_v1");case "build.execute"->Set.of("confirmed_placement_v1","multiblock_blueprint_v1");default->Set.of();};}
    @Override public void validate(String action,JsonObject p) {
        switch(action) {
            case "move.to"->{point(child(p,"target"));MovementCommands.validateTravel(p);}
            case "look.at"->{if(p.has("entity_id"))UUID.fromString(string(p,"entity_id"));else point(child(p,"target"));}
            case "move.relative"->MovementCommands.relative(p,0);
            case "move.crouch"->MovementCommands.crouchTicks(p);
            case "look.turn"->MovementCommands.turn(p,0,0);
            case "move.follow"->{UUID.fromString(string(p,"entity_id"));FollowSequence.validate(p);}
            case "combat.attack"->MeleeSequence.validate(p);
            case "combat.ranged"->{
                UUID.fromString(string(p,"entity_id"));integer(p,"charge_ticks",20,20,60);integer(p,"wait_ticks",40,10,100);
                double range=number(p,"max_distance",24);if(range<1 || range>32)throw new BridgeException("invalid_request","max_distance must be 1..32");
                if(!string(p,"hand","main").equals("main"))throw new BridgeException("invalid_request","Bow combat supports the main hand only");
                if(p.has("shots") || p.has("count") || p.has("chase"))throw new BridgeException("invalid_request","combat.ranged fires one arrow without chasing");
                if(mc.player.getMainHandItem().is(Items.CROSSBOW) && p.has("charge_ticks"))throw new BridgeException("invalid_request","charge_ticks applies to bows; crossbows use vanilla loading time");
            }
            case "move.jump"->JumpSequence.count(p);
            case "block.break", "block.place"->{block(child(p,"position"));if(action.equals("block.place"))Identifier.parse(string(p,"block"));else {bool(p,"require_tool",true);bool(p,"auto_tool",true);}}
            case "container.open", "world.interact"->{if(p.has("entity_id")==p.has("position"))throw new BridgeException("invalid_request","Choose position or entity_id");if(p.has("entity_id"))UUID.fromString(string(p,"entity_id"));else block(child(p,"position"));if(!Set.of("main","off").contains(string(p,"hand","main")))throw new BridgeException("invalid_request","hand must be main/off");}
            case "inventory.select"->integer(p,"slot",0,0,8);
            case "inventory.select_item"->{Identifier.parse(string(p,"item"));if(p.has("hotbar_slot"))integer(p,"hotbar_slot",0,0,8);}
            case "inventory.transfer", "inventory.equip", "inventory.swap"->{
                if(!p.has("from_slot"))throw new BridgeException("invalid_request","from_slot is required");
                integer(p,"from_slot",0,0,mc.player.containerMenu.slots.size()-1);
                if(action.equals("inventory.equip") && p.has("equipment_slot")) {
                    if(!Set.of("head","chest","legs","feet","offhand").contains(string(p,"equipment_slot")))throw new BridgeException("invalid_request","Unsupported equipment_slot");
                    if(p.has("to_slot") || p.has("count"))throw new BridgeException("invalid_request","Named equipment exchanges a whole stack; omit to_slot/count");
                } else {if(!p.has("to_slot"))throw new BridgeException("invalid_request","to_slot is required");integer(p,"to_slot",0,0,mc.player.containerMenu.slots.size()-1);}
                if(action.equals("inventory.swap") && (p.has("count") || p.has("equipment_slot")))throw new BridgeException("invalid_request","swap exchanges whole stacks; omit count/equipment_slot");
                if(p.has("count"))integer(p,"count",1,1,64);
                if(p.has("menu_id"))integer(p,"menu_id",0,0,10000);
                if(p.has("menu_revision"))integer(p,"menu_revision",0,0,Integer.MAX_VALUE);
            }
            case "craft.execute"->{Identifier.parse(string(p,"item"));integer(p,"count",1,1,4096);CraftSequence.validate(p);}
            case "build.execute"->{var plan=buildPlan(p);for(var b:plan.blocks())checkArea(new Vec3(b.x()+0.5,b.y()+0.5,b.z()+0.5));}
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
    static JsonObject heldStack(ItemStack s){JsonObject result=object("type",itemId(s),"count",s.getCount(),"damage",s.getDamageValue());if(s.is(Items.CROSSBOW))result.add("crossbow",crossbowState(s));return result;}
    private static JsonObject crossbowState(ItemStack stack) {
        var projectiles=stack.getOrDefault(DataComponents.CHARGED_PROJECTILES,ChargedProjectiles.EMPTY).itemCopies();
        return object("charged",!projectiles.isEmpty(),"projectiles",projectiles.stream().map(s->object("type",itemId(s),"count",s.getCount())).toList());
    }
    private static <T extends Comparable<T>> String propertyValue(Property<T> p,BlockState state){return p.getName(state.getValue(p));}
    static JsonObject properties(BlockState state){JsonObject o=object();for(Property<?> p:state.getProperties())o.addProperty(p.getName(),propertyValue(p,state));return o;}
    private static String equipmentSlot(int inventorySlot){return switch(inventorySlot){case 39->"head";case 38->"chest";case 37->"legs";case 36->"feet";case 40->"offhand";default->"";};}
    private String slotRole(Slot slot) {
        if(slot.container==mc.player.getInventory()) {int index=slot.getContainerSlot();return index<9?"hotbar":index<36?"main":index==40?"offhand":index<40?"armor":"player_special";}
        if(mc.player.containerMenu instanceof AbstractCraftingMenu crafting) {if(slot==crafting.getResultSlot())return "craft_output";if(crafting.getInputGridSlots().contains(slot))return "craft_grid";}
        return "container";
    }
    @Override public JsonObject snapshot(int radius,boolean telemetry) {
        JsonArray inventory=new JsonArray();for(int i=0;i<mc.player.getInventory().getContainerSize();i++){JsonObject s=stack(mc.player.getInventory().getItem(i));s.addProperty("slot",i);inventory.add(s);}
        JsonArray menu=new JsonArray();for(Slot slot:mc.player.containerMenu.slots){JsonObject s=stack(slot.getItem());s.addProperty("slot",slot.index);s.addProperty("player_inventory",slot.container==mc.player.getInventory());s.addProperty("role",slotRole(slot));
            if(slot.container==mc.player.getInventory()){s.addProperty("inventory_slot",slot.getContainerSlot());String equipment=equipmentSlot(slot.getContainerSlot());if(!equipment.isEmpty())s.addProperty("equipment_slot",equipment);}menu.add(s);}
        JsonArray entities=new JsonArray();
        for(Entity e:mc.level.getEntities(mc.player,mc.player.getBoundingBox().inflate(radius),e->!e.isInvisibleTo(mc.player))) {
            boolean visible=visible(e);if(!telemetry && !visible)continue;
            JsonObject ent=object("entity_id",e.getUUID().toString(),"type",BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),"position",vector(e.position()),"velocity",vector(e.getDeltaMovement()),"visible",visible,"source","client_engine","health","unavailable");
            ent.addProperty("kind",e instanceof LivingEntity?"living":e instanceof ItemEntity?"item":e instanceof net.minecraft.world.entity.projectile.Projectile?"projectile":"other");
            if(e instanceof net.minecraft.world.entity.animal.Animal animal)ent.addProperty("baby",animal.isBaby());
            if(e instanceof net.minecraft.world.entity.animal.sheep.Sheep sheep){ent.addProperty("sheared",sheep.isSheared());ent.addProperty("color",sheep.getColor().getName());}
            if(e instanceof ItemEntity item)ent.add("item",heldStack(item.getItem()));
            entities.add(ent);
        }
        JsonArray blocks=new JsonArray();BlockPos center=mc.player.blockPosition();int count=0;
        for(BlockPos p:BlockPos.betweenClosed(center.offset(-radius,-4,-radius),center.offset(radius,4,radius))) {
            if(++count>6000)break;
            if(!clientChunkLoaded(p))continue;
            BlockState state=mc.level.getBlockState(p);
            if(!telemetry && (state.isAir() || !blockVisible(p)))continue;
            JsonObject b=position(p);b.addProperty("dimension",dimension());b.addProperty("type",blockId(state));b.add("properties",properties(state));
            b.addProperty("solid",!state.getCollisionShape(mc.level,p).isEmpty());b.addProperty("fluid",!state.getFluidState().isEmpty());b.addProperty("source",telemetry?"owner_telemetry":"ray_observation");blocks.add(b);
        }
        return object("actor",object("position",vector(mc.player.position()),"velocity",vector(mc.player.getDeltaMovement()),"yaw",mc.player.getYRot(),"pitch",mc.player.getXRot(),"health",mc.player.getHealth(),"food",mc.player.getFoodData().getFoodLevel(),"armor",mc.player.getArmorValue(),"effects",mc.player.getActiveEffects().stream().map(e->e.toString()).toList(),"selected_slot",mc.player.getInventory().getSelectedSlot(),"main_hand",heldStack(mc.player.getMainHandItem()),"off_hand",heldStack(mc.player.getOffhandItem()),"using_item",mc.player.isUsingItem(),"on_ground",mc.player.onGround(),"flying",mc.player.getAbilities().flying,"in_water",mc.player.isInWater(),"in_lava",mc.player.isInLava(),"riding",mc.player.isPassenger(),"crouching",mc.player.isCrouching(),"sprinting",mc.player.isSprinting()),
                "inventory",inventory,"entities",entities,"blocks",blocks,"context",object("raining",mc.level.isRaining(),"day_time",mc.level.getDefaultClockTime(),"menu_id",mc.player.containerMenu.containerId,"menu_revision",mc.player.containerMenu.getStateId(),"menu_slots",menu,"cursor",stack(mc.player.containerMenu.getCarried()),"source","client_engine","coordinate_system","world_xyz_blocks","telemetry",telemetry));
    }
    boolean visible(Entity entity){return mc.level.clip(new ClipContext(mc.player.getEyePosition(),entity.getBoundingBox().getCenter(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player)).getType()==HitResult.Type.MISS;}
    boolean blockVisible(BlockPos p){return interactionHit(p)!=null;}
    private BlockHitResult interactionHit(BlockPos pos) {
        Vec3 eye=mc.player.getEyePosition();
        var boxes=new ArrayList<>(mc.level.getBlockState(pos).getShape(mc.level,pos,net.minecraft.world.phys.shapes.CollisionContext.of(mc.player)).toAabbs());
        boxes.sort(Comparator.comparingDouble(box->eye.distanceToSqr(box.getCenter().add(pos.getX(),pos.getY(),pos.getZ()))));
        for(var box:boxes) {
            Vec3 point=box.getCenter().add(pos.getX(),pos.getY(),pos.getZ());
            BlockHitResult hit=mc.level.clip(new ClipContext(eye,point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
            if(hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(pos))return hit;
        }
        return null;
    }
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
    @Override public void releaseControls(){
        combat.suspendMelee();melees.values().forEach(MeleeSequence::released);
        interactions.suspend();mining.suspend();placement.suspend();
        craftSequences.values().forEach(CraftSequence::released);
        crossbows.values().forEach(CrossbowSequence::released);
        boolean cancelBow=bowDrawing || mc.player!=null && held.contains(mc.options.keyUse) && mc.player.getUseItem().is(Items.BOW) && mc.player.getUsedItemHand()==InteractionHand.MAIN_HAND;
        for(var k:Set.copyOf(held))hold(k,false);held.clear();if(mc.gameMode!=null)mc.gameMode.stopDestroyBlock();
        if(cancelBow && mc.player!=null) {
            // Vanilla RELEASE_USE_ITEM fires a drawn bow. Switching away stops use on
            // the server without firing; switch back preserves the owner's hotbar.
            int slot=mc.player.getInventory().getSelectedSlot();
            mc.player.connection.send(new ServerboundSetCarriedItemPacket((slot+1)%9));
            mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));mc.player.stopUsingItem();bowDrawing=false;
        } else if(mc.gameMode!=null && mc.player!=null && mc.player.isUsingItem())mc.gameMode.releaseUsingItem(mc.player);
        routes.clear();crouches.values().forEach(CrouchSequence::released);follows.values().forEach(FollowSequence::released);
    }
    void beforeClientTick(){
        // Network packets are applied before Pre. Release before Minecraft handles keys
        // when the first use finishes, preventing its held-key automatic repeat.
        if(held.contains(mc.options.keyUse) && (mc.player==null || !mc.player.isUsingItem() || mc.screen!=null || mc.isPaused()))hold(mc.options.keyUse,false);
    }
    private void look(Vec3 target){Vec3 d=target.subtract(mc.player.getEyePosition());mc.player.setYRot((float)Math.toDegrees(Math.atan2(-d.x,d.z)));mc.player.setXRot((float)-Math.toDegrees(Math.atan2(d.y,Math.sqrt(d.x*d.x+d.z*d.z))));}
    private void checkArea(Vec3 p){if(!policy.areas.isEmpty())policy.checkPoint(vector(p),dimension());}
    private boolean clientChunkLoaded(BlockPos pos){return mc.level.getChunkSource().getChunk(pos.getX()>>4,pos.getZ()>>4,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false)!=null;}
    private void reach(BlockPos pos){if(!clientChunkLoaded(pos))throw new BridgeException("chunk_unavailable","Target chunk is not loaded",true);checkArea(Vec3.atCenterOf(pos));if(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos))>mc.player.blockInteractionRange())throw new BridgeException("out_of_reach","Target block is out of reach",true);}
    private JsonObject serverView(Task task,BlockPos pos,String entity) {
        CompletableFuture<JsonObject> pending=confirmations.get(task.id);
        if(pending==null) {
            var server=mc.getSingleplayerServer();if(server==null)throw new BridgeException("permission_denied","Actions require an integrated server");
            var dim=mc.level.dimension();UUID player=mc.player.getUUID();
            String usedItem=string(task.context,"use_item","minecraft:air"),craftedItem=string(task.context,"craft_item","minecraft:air");
            InventoryChange inventoryChange=inventoryChanges.get(task.id);
            MiningTool miningTool=miningTools.get(task.id);
            PlacementMaterial placementMaterial=placementMaterials.get(task.id);
            pending=server.submit(()->{
                var level=server.getLevel(dim);var actor=server.getPlayerList().getPlayer(player);
                if(level==null || actor==null)throw new BridgeException("disconnected","Actor unavailable");
                JsonObject view=object("source","integrated_server","tick",level.getGameTime(),"health",actor.getHealth(),"menu_id",actor.containerMenu.containerId,"cursor",stack(actor.containerMenu.getCarried()));
                view.addProperty("menu_revision",actor.containerMenu.getStateId());
                if(miningTool!=null)view.addProperty("mining_tool_matches",actor.getInventory().getSelectedSlot()==miningTool.selected() && ItemStack.matches(actor.getMainHandItem(),miningTool.stack()));
                if(placementMaterial!=null){view.addProperty("placement_material_matches",actor.getInventory().getSelectedSlot()==placementMaterial.selected() && ItemStack.matches(actor.getMainHandItem(),placementMaterial.stack()));view.addProperty("placement_support_matches",level.hasChunkAt(placementMaterial.support()) && level.getBlockState(placementMaterial.support()).equals(placementMaterial.supportState()) && !level.getBlockState(placementMaterial.support()).canBeReplaced());}
                view.addProperty("creative",actor.isCreative());
                if(inventoryChange!=null){view.addProperty("inventory_before_matches",inventoryChange.matches(actor.containerMenu,false));view.addProperty("inventory_after_matches",inventoryChange.matches(actor.containerMenu,true));}
                view.add("position",vector(actor.position()));view.addProperty("on_ground",actor.onGround());
                view.addProperty("crouching",actor.isCrouching());view.addProperty("sprinting",actor.isSprinting());
                view.addProperty("yaw",actor.getYRot());view.addProperty("pitch",actor.getXRot());
                view.addProperty("selected_slot",actor.getInventory().getSelectedSlot());
                view.add("main_hand",heldStack(actor.getMainHandItem()));view.add("off_hand",heldStack(actor.getOffhandItem()));
                if(actor.getMainHandItem().is(Items.CROSSBOW)) {
                    view.addProperty("crossbow_projectile_count",EnchantmentHelper.processProjectileCount(level,actor.getMainHandItem(),actor,1));
                    view.addProperty("crossbow_charge_ticks",CrossbowItem.getChargeDuration(actor.getMainHandItem(),actor));
                    view.add("crossbow_ammo",heldStack(actor.getProjectile(actor.getMainHandItem())));
                }
                view.addProperty("food",actor.getFoodData().getFoodLevel());view.addProperty("using_item",actor.isUsingItem());
                view.addProperty("use_hand",actor.isUsingItem()?(actor.getUsedItemHand()==InteractionHand.MAIN_HAND?"main":"off"):"none");
                view.addProperty("use_ticks",actor.getTicksUsingItem());
                view.addProperty("item_uses",actor.getStats().getValue(Stats.ITEM_USED,BuiltInRegistries.ITEM.getValue(Identifier.parse(usedItem))));
                view.addProperty("items_crafted",actor.getStats().getValue(Stats.ITEM_CRAFTED,BuiltInRegistries.ITEM.getValue(Identifier.parse(craftedItem))));
                view.addProperty("crafting_table",actor.containerMenu instanceof CraftingMenu);
                if(actor.containerMenu instanceof AbstractCraftingMenu crafting)view.add("craft_output",heldStack(crafting.getResultSlot().getItem()));
                JsonArray slots=new JsonArray();for(Slot s:actor.containerMenu.slots){JsonObject item=stack(s.getItem());item.addProperty("slot",s.index);slots.add(item);}view.add("slots",slots);
                Map<String,Integer> counts=new HashMap<>();for(int i=0;i<36;i++){ItemStack s=actor.getInventory().getItem(i);if(!s.isEmpty())counts.merge(itemId(s),s.getCount(),Integer::sum);}view.add("inventory_counts",GSON.toJsonTree(counts));
                JsonArray inventorySlots=new JsonArray();for(int i=0;i<36;i++)inventorySlots.add(heldStack(actor.getInventory().getItem(i)));view.add("inventory_slots",inventorySlots);
                if(pos!=null){if(!level.hasChunkAt(pos))throw new BridgeException("chunk_unavailable","Confirmation chunk is no longer loaded",true);BlockState state=level.getBlockState(pos);view.add("block",blockSample(state));view.addProperty("block_empty",state.isAir());if(miningTool!=null)view.addProperty("mining_correct_tool",!state.requiresCorrectToolForDrops() || state.canHarvestBlock(level,pos,actor));
                    if(task.action.equals("craft.execute")){view.addProperty("block_in_range",actor.getEyePosition().distanceTo(Vec3.atCenterOf(pos))<=actor.blockInteractionRange());var ray=level.clip(new ClipContext(actor.getEyePosition(),Vec3.atCenterOf(pos),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,actor));view.addProperty("block_visible",ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(pos));}
                }
                if(entity!=null){Entity e=level.getEntity(UUID.fromString(entity));view.add("entity",e==null?JsonNull.INSTANCE:object("alive",e.isAlive(),"health",e instanceof LivingEntity l?l.getHealth():-1,"position",vector(e.position())));
                    if(task.action.equals("combat.attack") && e!=null){view.addProperty("melee_in_range",actor.isWithinEntityInteractionRange(e,0));view.addProperty("melee_visible",actor.hasLineOfSight(e));view.addProperty("attack_strength",actor.getAttackStrengthScale(0));}
                }
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
        if(bool(t.context,"revalidate",false)){t.context.remove("revalidate");routes.remove(t.id);confirmations.remove(t.id);if(drops.containsKey(t.id))drops.get(t.id).resumed(tick());if(t.action.equals("look.turn"))t.context.addProperty("turn_sent",tick());if(t.action.equals("build.execute"))buildChecks.remove(t.id);}
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
            if(!t.context.has("placing"))t.context.remove("placement_look_started");
        }
        return switch(t.action) {
            case "move.to" -> move(t,point(child(t.parameters,"target")),number(t.parameters,"tolerance",0.7),false);
            case "move.follow" -> follow(t);
            case "move.relative" -> relative(t);
            case "move.crouch" -> crouch(t);
            case "move.jump" -> jump(t);
            case "look.turn" -> turn(t);
            case "look.at" -> lookAt(t);
            case "combat.attack" -> attack(t);
            case "combat.ranged" -> ranged(t);
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
                if(view!=null&&view.get("selected_slot").getAsInt()==slot){t.confirmed.addProperty("selected_slot",slot);yield Step.success(object("selected_slot",slot,"source","integrated_server"));}
                if(tick()-number(t.context,"selection_sent",tick())>20)throw new BridgeException("selection_unconfirmed","Server did not confirm hotbar selection");
                yield Step.waiting(object("waiting","server_selection"));
            }
            case "inventory.select_item" -> selectItem(t,string(t.parameters,"item"),t.parameters.has("hotbar_slot")?integer(t.parameters,"hotbar_slot",0,0,8):-1);
            case "inventory.transfer" -> transfer(t);
            case "inventory.equip" -> t.parameters.has("equipment_slot")?swap(t):transfer(t);
            case "inventory.swap" -> swap(t);
            case "craft.execute" -> craft(t);
            case "build.execute" -> build(t);
            default -> throw new BridgeException("unsupported_capability",t.action);
        };
    }
    private Pathfinder.Terrain terrain(Pathfinder.Cell c) {
        BlockPos p=new BlockPos(c.x(),c.y(),c.z());if(!clientChunkLoaded(p))return Pathfinder.Terrain.UNKNOWN;
        if(!policy.areas.isEmpty() && policy.areas.stream().noneMatch(a->a.contains(dimension(),c.x()+0.5,c.y(),c.z()+0.5)))return Pathfinder.Terrain.BLOCKED;
        BlockState feet=mc.level.getBlockState(p),head=mc.level.getBlockState(p.above()),floor=mc.level.getBlockState(p.below());
        if(!feet.getFluidState().isEmpty()||!head.getFluidState().isEmpty()||!feet.getCollisionShape(mc.level,p).isEmpty()||!head.getCollisionShape(mc.level,p.above()).isEmpty()||!floor.isCollisionShapeFullBlock(mc.level,p.below()))return Pathfinder.Terrain.BLOCKED;
        String type=blockId(floor);if(type.contains("magma")||type.contains("campfire")||type.contains("cactus")||type.contains("fire"))return Pathfinder.Terrain.BLOCKED;
        return Pathfinder.Terrain.WALKABLE;
    }
    private static Pathfinder.Cell cell(Vec3 v){return new Pathfinder.Cell((int)Math.floor(v.x),(int)Math.floor(v.y),(int)Math.floor(v.z));}
    private Pathfinder.Terrain transition(Pathfinder.Cell from,Pathfinder.Cell to) {
        int height=Math.max(from.y(),to.y());
        Vec3 start=new Vec3(from.x()+0.5,height,from.z()+0.5);
        AABB clearance=mc.player.getDimensions(Pose.STANDING).makeBoundingBox(start).expandTowards(to.x()-from.x(),0,to.z()-from.z());
        for(BlockPos p:BlockPos.betweenClosed(BlockPos.containing(clearance.minX,clearance.minY,clearance.minZ),BlockPos.containing(clearance.maxX,clearance.maxY,clearance.maxZ)))
            if(!clientChunkLoaded(p))return Pathfinder.Terrain.UNKNOWN;
        return mc.level.getBlockCollisions(mc.player,clearance).iterator().hasNext()?Pathfinder.Terrain.BLOCKED:Pathfinder.Terrain.WALKABLE;
    }
    private List<Pathfinder.Cell> path(Vec3 target){
        Pathfinder.Grid grid=new Pathfinder.Grid(){
            @Override public Pathfinder.Terrain terrain(Pathfinder.Cell c){return MinecraftWorld.this.terrain(c);}
            @Override public Pathfinder.Terrain transition(Pathfinder.Cell a,Pathfinder.Cell b){return MinecraftWorld.this.transition(a,b);}
        };
        return new Pathfinder().plan(grid,cell(mc.player.position()),cell(target),8192).cells();
    }
    private Step follow(Task t) {
        Entity target=entity(t.parameters);checkArea(target.position());checkArea(mc.player.position());
        FollowSequence sequence=follows.computeIfAbsent(t.id,key->new FollowSequence(integer(t.parameters,"ticks",200,1,1200),number(t.parameters,"distance",3)));
        JsonObject view=serverView(t,null,string(t.parameters,"entity_id"));
        FollowSequence.Sample sample=null;
        if(view!=null) {
            if(view.get("entity").isJsonNull() || !bool(child(view,"entity"),"alive",false))return Step.failure("target_lost","Follow target is no longer alive or available",t.result);
            sample=new FollowSequence.Sample(view.get("tick").getAsLong(),point(child(view,"position")).distanceTo(point(child(child(view,"entity"),"position"))));
        }
        var update=sequence.observe(sample);JsonObject progress=update.progress();
        progress.addProperty("target",string(t.parameters,"entity_id"));
        t.confirmed.add("follow_ticks",progress.get("confirmed_follow_ticks"));
        if(view!=null){progress.add("position",view.get("position"));progress.add("target_position",child(view,"entity").get("position"));progress.addProperty("remaining_distance",sample.distance());}
        if(update.done()) {
            hold(mc.options.keyUp,false);hold(mc.options.keyJump,false);hold(mc.options.keySprint,false);hold(mc.options.keyShift,false);
            t.confirmed.add("position",view.get("position"));return Step.success(progress);
        }
        Step movement=move(t,target.position(),number(t.parameters,"distance",3),true);
        progress.add("movement",movement.result());return Step.waiting(progress);
    }
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
        if(route==null || follow && tick()-number(t.context,"route_planned",-100)>=10 && !cell(target).equals(route.getLast())) {route=path(target);routes.put(t.id,route);t.context.addProperty("waypoint",0);t.context.addProperty("route_planned",tick());}
        int idx=integer(t.context,"waypoint",0,0,8192);
        while(idx<route.size()-1 && mc.player.position().distanceTo(new Vec3(route.get(idx).x()+0.5,route.get(idx).y(),route.get(idx).z()+0.5))<0.65)idx++;
        t.context.addProperty("waypoint",idx);Pathfinder.Cell next=route.get(Math.min(idx,route.size()-1));
        if(terrain(next)!=Pathfinder.Terrain.WALKABLE || transition(cell(mc.player.position()),next)!=Pathfinder.Terrain.WALKABLE){routes.remove(t.id);releaseControls();return Step.waiting(object("stage","replanning"));}
        // A tight tolerance can exclude the goal cell's centre. Finish at the actual
        // requested point instead of repeatedly steering back to the voxel centre.
        boolean finalCell=idx>=route.size()-1 && next.equals(cell(target));
        Vec3 waypoint=finalCell?target:new Vec3(next.x()+0.5,next.y(),next.z()+0.5);
        ActionBudget.require(t,1);
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
    private Step lookAt(Task t) {
        if(!t.context.has("turn_target")) {
            Vec3 target=t.parameters.has("entity_id")?entity(t.parameters).getBoundingBox().getCenter():point(child(t.parameters,"target"));look(target);
            t.context.add("turn_target",object("yaw",mc.player.getYRot(),"pitch",mc.player.getXRot()));t.context.addProperty("turn_sent",tick());
        }
        return turn(t);
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
        String id=UUID.fromString(string(t.parameters,"entity_id")).toString();
        CombatLedger.Snapshot receipt=combat.peek(t.id);updateCombatReceipt(t,receipt);
        int hits=receipt==null?0:receipt.hits();
        MeleeSequence sequence=melees.computeIfAbsent(t.id,key->new MeleeSequence(MeleeSequence.requestedHits(t.parameters)));
        var update=sequence.observe(tick(),hits,receipt!=null && receipt.defeated());
        JsonObject progress=object("stage",update.outcome(),"target",id,"confirmed_hits",hits);
        if(t.parameters.has("hits"))progress.addProperty("requested_hits",MeleeSequence.requestedHits(t.parameters));
        if(receipt!=null && hits>0){progress.addProperty("remaining_health",receipt.remainingHealth());progress.addProperty("source","integrated_server_damage_event");}
        hold(mc.options.keyUp,false);hold(mc.options.keyJump,false);hold(mc.options.keySprint,false);hold(mc.options.keyShift,false);
        if(update.done()){progress.addProperty("outcome",update.outcome());return Step.success(progress);}
        if(update.error()!=null)return Step.failure(update.error(),"The previous melee request will not be repeated; inspect the target before a new task",progress);
        if(!update.ready())return Step.waiting(progress);
        if(t.actions>=t.maxActions)return Step.failure("action_limit","Attack/movement budget exhausted",progress);
        if(mc.screen!=null || mc.player.containerMenu!=mc.player.inventoryMenu)throw new BridgeException("interface_open","Close interfaces before combat");
        if(!mc.player.containerMenu.getCarried().isEmpty())throw new BridgeException("cursor_occupied","Clear cursor before combat");
        if(mc.player.isUsingItem())throw new BridgeException("busy","Stop item use before combat");
        JsonObject view=serverView(t,null,id);if(view==null)return Step.waiting(progress);
        if(view.get("entity").isJsonNull() || !bool(child(view,"entity"),"alive",false))return Step.failure("target_lost","Target died or vanished without a confirmed lethal hit from this task",progress);
        if(number(view,"health",0)<=number(t.parameters,"stop_health",4))return Step.failure("critical_health","Server actor reached stop health",progress);
        if(number(view,"menu_id",-1)!=0 || bool(view,"using_item",false) || number(child(view,"cursor"),"count",0)!=0)throw new BridgeException("busy","Server inventory or item use is not ready for combat");
        Entity target=entity(object("entity_id",id));checkArea(target.position());checkArea(mc.player.position());
        if(target instanceof net.minecraft.world.entity.player.Player)throw new BridgeException("permission_denied","Player combat is disabled");
        if(!(target instanceof LivingEntity) || !target.isAlive())throw new BridgeException("unsupported_capability","Combat requires a living target");
        if(!bool(view,"melee_visible",false))throw new BridgeException("target_lost","Server cannot see the target");
        if(bool(t.parameters,"chase",false)) {
            if(!t.context.has("chase_origin"))t.context.add("chase_origin",view.get("position").deepCopy());
            Vec3 origin=point(child(t.context,"chase_origin"));double limit=number(t.parameters,"max_chase_distance",16);
            if(!MeleeSequence.withinChase(origin.distanceTo(point(child(view,"position"))),origin.distanceTo(point(child(child(view,"entity"),"position"))),limit))return Step.failure("chase_limit","Actor or target left the allowed distance from the starting position",progress);
        }
        if(!mc.player.isWithinEntityInteractionRange(target,0) || !bool(view,"melee_in_range",false)) {
            if(!bool(t.parameters,"chase",false))throw new BridgeException("out_of_reach","Target out of attack range",true);
            Step movement=move(t,target.position(),Math.max(0.7,mc.player.entityInteractionRange()-0.5),true);
            if(movement.error()!=null)return movement;
            progress.addProperty("stage","chasing");progress.add("movement",movement.result());return Step.waiting(progress);
        }
        look(target.getBoundingBox().getCenter());
        if(mc.player.getAttackStrengthScale(0)<1 || number(view,"attack_strength",0)<1){progress.addProperty("stage","cooldown");return Step.waiting(progress);}
        if(!view.get("main_hand").equals(heldStack(mc.player.getMainHandItem())))return Step.waiting(object("stage","synchronizing_weapon","target",id));
        ActionBudget.require(t,1);long serverTick=view.get("tick").getAsLong();
        if(receipt==null)combat.begin(t.id,id,"melee",serverTick);
        if(!combat.expectMelee(t.id,serverTick))return Step.failure("combat_unconfirmed","No unique melee receipt can be opened",progress);
        sequence.sent(tick(),hits);mc.gameMode.attack(mc.player,target);mc.player.swing(InteractionHand.MAIN_HAND);t.actions++;
        t.context.addProperty("last_attack",tick());t.confirmed.addProperty("attack_requests",integer(t.confirmed,"attack_requests",0,0,100000)+1);
        progress.addProperty("stage","confirming_hit");return Step.waiting(progress);
    }
    private Step ranged(Task t) {
        String id=string(t.parameters,"entity_id");
        if(t.context.has("bow_released")) {
            CombatLedger.Snapshot receipt=combat.peek(t.id);updateCombatReceipt(t,receipt);
            if(receipt!=null && receipt.hits()>0)return Step.success(object("outcome",receipt.defeated()?"defeated":"hit","target",id,"projectile_id",receipt.projectileId(),"source","integrated_server_damage_event"));
            if(tick()-number(t.context,"bow_released",tick())>=integer(t.parameters,"wait_ticks",40,10,100))
                return Step.failure(receipt!=null&&receipt.shots()>0?"hit_unconfirmed":"shot_unconfirmed","No damage to the requested target was confirmed; this task will not shoot again",object("stage","waiting_for_hit","target",id));
            return Step.waiting(object("stage","waiting_for_hit","target",id));
        }
        if(mc.screen!=null)throw new BridgeException("interface_open","Close the interface before drawing a bow");
        Entity target=entity(t.parameters);checkArea(target.position());checkArea(mc.player.position());
        if(target instanceof net.minecraft.world.entity.player.Player)throw new BridgeException("permission_denied","Player combat is disabled");
        if(!(target instanceof LivingEntity) || !target.isAlive())throw new BridgeException("target_lost","A visible living target is required");
        if(mc.player.distanceTo(target)>number(t.parameters,"max_distance",24))throw new BridgeException("out_of_reach","Bow target is outside max_distance",true);
        if(!mc.player.getMainHandItem().is(Items.BOW) && !mc.player.getMainHandItem().is(Items.CROSSBOW))throw new BridgeException("missing_weapon","Select minecraft:bow or minecraft:crossbow in the main hand first");
        if(mc.player.isInWater() || mc.player.isInLava())throw new BridgeException("ranged_unavailable","Bow aiming from water or lava is unsupported");
        if(mc.player.getMainHandItem().is(Items.CROSSBOW))return crossbow(t,target);
        if(crossbows.containsKey(t.id))throw new BridgeException("draw_interrupted","The ranged weapon changed during loading");
        if(!t.context.has("bow_started")) {
            ActionBudget.require(t,2);
            if(mc.player.isUsingItem())throw new BridgeException("item_busy","Finish the current item use first");
            if(mc.player.getProjectile(mc.player.getMainHandItem()).isEmpty())throw new BridgeException("missing_ammo","No usable arrow is available");
        }
        JsonObject view=serverView(t,null,id);
        if(!t.context.has("bow_started") && view==null)return Step.waiting(object("waiting","server_bow_ready"));
        hold(mc.options.keyUp,false);hold(mc.options.keyJump,false);hold(mc.options.keySprint,false);aimBow(target,3.0);
        if(!t.context.has("bow_started")) {
            if(combat.peek(t.id)==null)combat.begin(t.id,id,"bow",view.get("tick").getAsLong());
            mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);t.actions++;t.context.addProperty("bow_started",tick());
            bowDrawing=true;hold(mc.options.keyUse,true);
            return Step.waiting(object("stage","drawing_bow","target",id));
        }
        if(!mc.player.isUsingItem() || !mc.player.getUseItem().is(Items.BOW))throw new BridgeException("draw_interrupted","Bow draw was interrupted; start a new task to shoot");
        if(view==null)return Step.waiting(object("waiting","server_bow_draw"));
        if(!bool(view,"using_item",false) || !string(view,"use_hand").equals("main")) {
            if(tick()-number(t.context,"bow_started",tick())>10)throw new BridgeException("draw_interrupted","Server did not retain the bow draw");
            return Step.waiting(object("waiting","server_bow_draw"));
        }
        int charged=integer(view,"use_ticks",0,0,12000),required=integer(t.parameters,"charge_ticks",20,20,60);
        if(charged<required)return Step.waiting(object("stage","drawing_bow","charge_ticks",charged,"required_ticks",required,"target",id));
        ActionBudget.require(t,1);if(!combat.expectShot(t.id,view.get("tick").getAsLong()))throw new BridgeException("shot_unconfirmed","No unique shot receipt can be opened");
        hold(mc.options.keyUse,false);mc.gameMode.releaseUsingItem(mc.player);bowDrawing=false;
        t.actions++;t.context.addProperty("bow_released",tick());
        return Step.waiting(object("stage","waiting_for_shot","target",id));
    }
    private Step crossbow(Task t,Entity target) {
        if(t.context.has("bow_started"))throw new BridgeException("draw_interrupted","The ranged weapon changed during drawing");
        if(t.parameters.has("charge_ticks"))throw new BridgeException("invalid_request","charge_ticks applies to bows; crossbows use vanilla loading time");
        CrossbowSequence sequence=crossbows.computeIfAbsent(t.id,key->new CrossbowSequence());
        JsonObject view=serverView(t,null,string(t.parameters,"entity_id"));
        CrossbowSequence.Sample sample=null;
        if(view!=null) {
            if(!string(child(view,"main_hand"),"type").equals("minecraft:crossbow"))throw new BridgeException("draw_interrupted","Server selected weapon changed");
            JsonObject state=child(child(view,"main_hand"),"crossbow");JsonArray projectiles=state.getAsJsonArray("projectiles");
            boolean plain=projectiles.size()==1 && string(projectiles.get(0).getAsJsonObject(),"type").equals("minecraft:arrow") && integer(projectiles.get(0).getAsJsonObject(),"count",0,0,64)==1;
            sample=new CrossbowSequence.Sample(view.get("tick").getAsLong(),bool(view,"using_item",false),bool(state,"charged",false),projectiles.size(),plain);
        }
        var decision=sequence.observe(sample,mc.player.isUsingItem(),CrossbowItem.isCharged(mc.player.getMainHandItem()));
        if(decision.confirmedLoads()>0)t.confirmed.addProperty("crossbow_loads",decision.confirmedLoads());
        if(decision.error()!=null)throw new BridgeException(decision.error(),"Crossbow loading cannot continue; start a new task after checking the weapon/ammunition");
        if(decision.command()==CrossbowSequence.Command.wait)return Step.waiting(object("stage",decision.stage(),"weapon","minecraft:crossbow","target",string(t.parameters,"entity_id")));
        if(decision.command()==CrossbowSequence.Command.draw) {
            ActionBudget.require(t,3);
            if(integer(view,"crossbow_projectile_count",1,0,64)!=1)throw new BridgeException("unsupported_ammo","Multishot crossbows are unsupported");
            String ammo=string(child(view,"crossbow_ammo"),"type");
            if(ammo.equals("minecraft:air"))throw new BridgeException("missing_ammo","No crossbow arrow is available");
            if(!ammo.equals("minecraft:arrow"))throw new BridgeException("unsupported_ammo","Crossbows support one ordinary arrow; fireworks and other ammunition are unsupported");
            if(integer(view,"crossbow_charge_ticks",25,0,12000)>100)throw new BridgeException("unsupported_capability","Crossbow charging time is unsupported");
        } else ActionBudget.require(t,1);
        hold(mc.options.keyUp,false);hold(mc.options.keyJump,false);hold(mc.options.keySprint,false);aimBow(target,3.15);
        long serverTick=view.get("tick").getAsLong();
        if(decision.command()==CrossbowSequence.Command.draw) {
            mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);hold(mc.options.keyUse,true);
        } else if(decision.command()==CrossbowSequence.Command.release) {
            hold(mc.options.keyUse,false);mc.gameMode.releaseUsingItem(mc.player);
        } else {
            JsonObject local=crossbowState(mc.player.getMainHandItem());JsonArray arrows=local.getAsJsonArray("projectiles");
            if(arrows.size()!=1 || !string(arrows.get(0).getAsJsonObject(),"type").equals("minecraft:arrow"))throw new BridgeException("unsupported_ammo","Client crossbow does not contain one ordinary arrow");
            if(combat.peek(t.id)==null)combat.begin(t.id,string(t.parameters,"entity_id"),"bow",serverTick);
            if(!combat.expectShot(t.id,serverTick))throw new BridgeException("shot_unconfirmed","No unique shot receipt can be opened");
            mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);t.context.addProperty("bow_released",tick());
        }
        t.actions++;sequence.sent(decision.command(),serverTick);
        return Step.waiting(object("stage",decision.command().name(),"weapon","minecraft:crossbow","target",string(t.parameters,"entity_id")));
    }
    private void aimBow(Entity target,double speed) {
        Vec3 eye=mc.player.getEyePosition(),point=target.getBoundingBox().getCenter();
        double flight=Math.max(1,eye.distanceTo(point)/speed);
        Vec3 velocity=target.getDeltaMovement();if(velocity.lengthSqr()<=1)point=point.add(velocity.scale(flight));
        Vec3 d=point.subtract(eye);double horizontal=Math.sqrt(d.x*d.x+d.z*d.z);
        if(horizontal<0.1){look(point);return;}
        // Low ballistic arc for a fully drawn vanilla bow. Actual hit receipts remain
        // authoritative: drag, spread and moving targets can still produce a miss.
        double gravity=0.05,speedSquared=speed*speed,discriminant=speedSquared*speedSquared-gravity*(gravity*horizontal*horizontal+2*d.y*speedSquared);
        if(discriminant<0)throw new BridgeException("out_of_reach","No supported bow trajectory");
        double rise=(speedSquared-Math.sqrt(discriminant))/gravity;
        look(eye.add(d.x,rise,d.z));
    }
    private static JsonObject blockSample(BlockState state){return object("type",blockId(state),"properties",properties(state));}
    private void updateMiningReceipt(Task task,JsonObject receipt) {
        if(receipt==null)return;
        task.confirmed.addProperty("mined_blocks",1);task.confirmed.add("mining",receipt);task.confirmed.add("broken",receipt.get("position"));
        BlockPos pos=block(child(receipt,"position"));confirmBlock(task,pos,object("block",receipt.get("after"),"tick",receipt.get("tick")));
    }
    void recordBlockBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
        if(!(event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player) || !actor.equals(player.getUUID().toString()))return;
        var level=player.level();var pos=event.getPos().immutable();String dim=level.dimension().identifier().toString();
        String task=mining.attempt(actor,dim,position(pos),blockSample(event.getState()),heldStack(player.getMainHandItem()),player.getInventory().getSelectedSlot(),level.getGameTime());
        if(task==null)return;
        var server=level.getServer();
        // The event precedes removal. Check its final cancellation and actual block after vanilla returns.
        server.schedule(new net.minecraft.server.TickTask(server.getTickCount(),()->{
            if(!level.hasChunkAt(pos) || player.level()!=level)return;
            mining.complete(task,actor,dim,blockSample(level.getBlockState(pos)),heldStack(player.getMainHandItem()),player.getInventory().getSelectedSlot(),level.getGameTime(),event.isCanceled());
        }));
    }
    private Step breakBlock(Task t,BlockPos p) {
        JsonObject receipt=mining.peek(t.id);updateMiningReceipt(t,receipt);
        if(receipt!=null)return Step.success(receipt);
        if(mining.interrupted(t.id))return Step.failure("mining_interrupted","Mining was stopped; inspect the block and start a new task",object("position",position(p)));
        if(mining.error(t.id)!=null)return Step.failure(mining.error(t.id),"Server did not complete this player's block break; it will not be repeated",object("position",position(p)));
        reach(p);
        if(mc.screen!=null || mc.player.containerMenu!=mc.player.inventoryMenu)throw new BridgeException("interface_open","Close interfaces before mining");
        if(!mc.player.containerMenu.getCarried().isEmpty())throw new BridgeException("cursor_occupied","Clear cursor before mining");
        if(mc.player.isUsingItem())throw new BridgeException("busy","Stop using the held item before mining");
        JsonObject view=serverView(t,p,null);if(view==null)return Step.waiting(object("stage","checking_mining"));
        if(number(view,"menu_id",-1)!=0 || bool(view,"using_item",false) || number(child(view,"cursor"),"count",0)!=0)throw new BridgeException("busy","Server inventory or item use is not ready for mining");
        BlockState state=mc.level.getBlockState(p);JsonObject actual=child(view,"block");
        if(!t.context.has("mining_original")) {
            if(bool(view,"block_empty",false))return Step.success(object("outcome","already_empty","position",position(p),"state",actual,"source","integrated_server"));
            if(!actual.equals(blockSample(state)))return Step.waiting(object("stage","synchronizing_mining_target"));
            if(state.getDestroySpeed(mc.level,p)<0)throw new BridgeException("permission_denied","Unbreakable block");
            if(interactionHit(p)==null)throw new BridgeException("target_lost","Block is occluded",true);
            boolean required=bool(t.parameters,"require_tool",true) && state.requiresCorrectToolForDrops();
            List<MiningTools.Candidate> candidates=new ArrayList<>();int selected=mc.player.getInventory().getSelectedSlot();
            for(int i=0;i<36;i++) {
                ItemStack stack=mc.player.getInventory().getItem(i);
                if(i!=selected && (stack.isEmpty() || !stack.has(DataComponents.TOOL)))continue;
                if(!stack.canDestroyBlock(state,mc.level,p,mc.player))continue;
                candidates.add(new MiningTools.Candidate(i,itemId(stack),stack.getCount(),stack.getDestroySpeed(state),stack.isCorrectToolForDrops(state),stack.isDamageableItem()?stack.getMaxDamage()-stack.getDamageValue():-1));
            }
            var chosen=MiningTools.choose(candidates,selected,required,bool(t.parameters,"auto_tool",true));
            int target=chosen.slot()<9?chosen.slot():selected;
            ActionBudget.require(t,(chosen.slot()==selected?0:1)+1);
            miningTools.put(t.id,new MiningTool(target,mc.player.getInventory().getItem(chosen.slot()).copy()));
            t.context.add("mining_original",actual.deepCopy());t.context.addProperty("mining_source",chosen.slot());t.context.addProperty("mining_slot",target);t.context.addProperty("mining_item",chosen.item());
            if(chosen.slot()==selected)t.context.addProperty("mining_ready",true);
        }
        boolean changed=!actual.equals(child(t.context,"mining_original")),clientChanged=!blockSample(state).equals(child(t.context,"mining_original"));
        if(mining.pending(t.id) || (changed || clientChanged) && t.context.has("breaking")) {
            if(tick()-number(t.context,"break_last_sent",tick())>20)return Step.failure(changed?"target_changed":"mining_unconfirmed","No completed block-break event for this actor and cell",object("position",position(p),"state",actual));
            return Step.waiting(object("stage","confirming_mining","position",position(p)));
        }
        if(changed || clientChanged)throw new BridgeException("target_changed","Block changed before or during mining");
        if(!t.context.has("mining_ready")) {
            Step prepared=selectItem(t,string(t.context,"mining_item"),integer(t.context,"mining_slot",0,0,8),view,integer(t.context,"mining_source",0,0,35));
            if(!prepared.done() || prepared.error()!=null)return prepared;
            t.context.addProperty("mining_ready",true);return Step.waiting(object("stage","checking_selected_tool"));
        }
        if(!view.has("mining_tool_matches"))return Step.waiting(object("stage","checking_selected_tool"));
        if(!bool(view,"mining_tool_matches",false))throw new BridgeException("target_changed","Selected mining tool or its components changed");
        if(bool(t.parameters,"require_tool",true) && !bool(view,"mining_correct_tool",false))throw new BridgeException("missing_tool","Server tool cannot harvest this block");
        BlockHitResult hit=interactionHit(p);if(hit==null)throw new BridgeException("target_lost","Block is occluded",true);look(hit.getLocation());
        if(t.actions>=t.maxActions) {
            if(tick()-number(t.context,"break_last_sent",-100)>20)throw new BridgeException("action_limit","Mining budget exhausted without a completed break event");
            return Step.waiting(object("stage","final_mining_confirmation"));
        }
        ActionBudget.require(t,1);long serverTick=(long)number(view,"tick",tick());
        if(!t.context.has("breaking")) {
            mining.begin(t.id,position(p),actual,blockSample(state.getFluidState().createLegacyBlock()),serverTick);
            t.context.addProperty("breaking",true);
        }
        if(!mining.arm(t.id,serverTick))return Step.waiting(object("stage","confirming_mining"));
        if(!t.context.has("break_last_sent"))mc.gameMode.startDestroyBlock(p,hit.getDirection());else mc.gameMode.continueDestroyBlock(p,hit.getDirection());
        t.context.addProperty("break_last_sent",tick());mc.player.swing(InteractionHand.MAIN_HAND);t.actions++;
        return Step.waiting(object("stage","mining","position",position(p),"tool",string(t.context,"mining_item")));
    }
    private boolean matches(BlockState actual,String type,JsonObject wanted){if(!blockId(actual).equals(type))return false;JsonObject props=properties(actual);for(var e:wanted.entrySet())if(!props.has(e.getKey())||!props.get(e.getKey()).getAsString().equals(e.getValue().getAsString()))return false;return true;}
    private boolean matches(JsonObject actual,String type,JsonObject wanted){if(!string(actual,"type").equals(type))return false;JsonObject props=child(actual,"properties");for(var e:wanted.entrySet())if(!props.has(e.getKey())||!props.get(e.getKey()).getAsString().equals(e.getValue().getAsString()))return false;return true;}
    private void updatePlacementReceipt(Task task,JsonObject receipt) {
        if(receipt==null || string(task.context,"placement_receipt","").equals(string(receipt,"receipt_id")))return;
        task.context.addProperty("placement_receipt",string(receipt,"receipt_id"));task.confirmed.add("placement",receipt);
        int changed=0;JsonArray placed=task.context.has("placed_cells")?task.context.getAsJsonArray("placed_cells"):new JsonArray();
        for(JsonElement element:receipt.getAsJsonArray("cells")) {
            JsonObject cell=element.getAsJsonObject();if(cell.get("before").equals(cell.get("after")))continue;
            changed++;confirmBlock(task,block(child(cell,"position")),object("block",cell.get("after"),"tick",receipt.get("tick")));
            if(task.action.equals("build.execute")){
                for(int i=placed.size()-1;i>=0;i--)if(child(placed.get(i).getAsJsonObject(),"position").equals(child(cell,"position")))placed.remove(i);
                placed.add(object("position",cell.get("position").deepCopy(),"after",cell.get("after").deepCopy()));
            }
        }
        if(task.action.equals("build.execute"))task.context.add("placed_cells",placed);
        task.confirmed.addProperty("placed_blocks",integer(task.confirmed,"placed_blocks",0,0,Integer.MAX_VALUE)+changed);
        task.confirmed.add("placed",receipt.get("position"));
    }
    void recordBlockPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if(!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) || !actor.equals(player.getUUID().toString()))return;
        var level=player.level();String dim=level.dimension().identifier().toString();
        var snapshots=event instanceof net.neoforged.neoforge.event.level.BlockEvent.EntityMultiPlaceEvent multi?multi.getReplacedBlockSnapshots():List.of(event.getBlockSnapshot());
        JsonArray cells=new JsonArray();
        for(var snapshot:snapshots)cells.add(object("position",position(snapshot.getPos()),"before",blockSample(snapshot.getState()),"after",blockSample(level.getBlockState(snapshot.getPos()))));
        // Oversized effects are rejected after attribution, before vanilla commits them.
        if(cells.size()>64){for(JsonElement cell:cells){JsonArray one=new JsonArray();one.add(cell);String task=placement.attempt(actor,dim,one,level.getGameTime());if(task!=null){event.setCanceled(true);placement.reject(task,"unsupported_capability");return;}}return;}
        String task=placement.attempt(actor,dim,cells,level.getGameTime());if(task==null)return;
        var material=placementMaterials.get(task);
        if(material==null || player.getInventory().getSelectedSlot()!=material.selected() || !ItemStack.matches(material.stack(),player.getMainHandItem())){event.setCanceled(true);placement.reject(task,"target_changed");return;}
        try {
            for(var snapshot:snapshots){checkArea(Vec3.atCenterOf(snapshot.getPos()));if(!snapshot.getState().canBeReplaced()){event.setCanceled(true);placement.reject(task,"build_conflict");return;}}
        } catch(BridgeException e){event.setCanceled(true);placement.reject(task,e.code);return;}
        ItemStack expectedAfter=material.stack().copyWithCount(material.stack().getCount()-(player.isCreative()?0:1));
        var server=level.getServer();JsonArray before=cells.deepCopy();
        server.schedule(new net.minecraft.server.TickTask(server.getTickCount(),()->{
            if(player.level()!=level)return;JsonArray actual=new JsonArray();
            for(JsonElement element:before){JsonObject cell=element.getAsJsonObject();BlockPos pos=block(child(cell,"position"));if(!level.hasChunkAt(pos))return;actual.add(object("position",cell.get("position"),"before",cell.get("before"),"after",blockSample(level.getBlockState(pos))));}
            placement.complete(task,actor,dim,actual,heldStack(player.getMainHandItem()),player.getInventory().getSelectedSlot(),level.getGameTime(),event.isCanceled(),ItemStack.matches(expectedAfter,player.getMainHandItem()));
        }));
    }
    private Step placeBlock(Task t,BlockPos p,String type,JsonObject wanted) {
        checkArea(Vec3.atCenterOf(p));
        JsonObject receipt=placement.peek(t.id);updatePlacementReceipt(t,receipt);
        if(placement.error(t.id)!=null)return Step.failure(placement.error(t.id),"Requested placement could not be verified; inspect the reported actual changes",receipt==null?object():receipt);
        if(receipt!=null){placement.end(t.id);placementMaterials.remove(t.id);t.context.remove("material_sent");return Step.success(receipt);}
        if(t.context.has("placing")) {
            if(placement.interrupted(t.id))throw new BridgeException("placement_interrupted","Inspect actual blocks before a new task; the click is not repeated");
            if(tick()-number(t.context,"placing",tick())>20)throw new BridgeException("placement_unconfirmed","No matching server placement receipt; the click is not repeated");
            return Step.waiting(object("waiting","server_placement_event"));
        }
        if(!clientChunkLoaded(p))throw new BridgeException("chunk_unavailable","Target chunk is not loaded",true);
        JsonObject view=serverView(t,p,null);
        if(view!=null && matches(child(view,"block"),type,wanted)){
            confirmBlock(t,p,view);t.context.remove("material_sent");placementMaterials.remove(t.id);
            JsonArray matched=t.context.has("matched_cells")?t.context.getAsJsonArray("matched_cells"):new JsonArray();JsonObject position=position(p);
            boolean placedHere=false;
            if(t.context.has("placed_cells"))for(JsonElement element:t.context.getAsJsonArray("placed_cells")){JsonObject cell=element.getAsJsonObject();if(child(cell,"position").equals(position) && child(cell,"after").equals(child(view,"block"))){placedHere=true;break;}}
            if(!placedHere && !matched.contains(position)){matched.add(position);t.context.add("matched_cells",matched);t.confirmed.addProperty("matched_blocks",matched.size());}
            return Step.success(object("position",position,"state",view.get("block"),"outcome","already_present","source","integrated_server"));
        }
        if(matches(mc.level.getBlockState(p),type,wanted))return Step.waiting(object("waiting","server_confirmation_of_existing_block"));
        reach(p);
        if(mc.screen!=null || mc.player.containerMenu.containerId!=0)throw new BridgeException("interface_open","Close interfaces before placing blocks");
        if(!mc.player.containerMenu.getCarried().isEmpty())throw new BridgeException("cursor_occupied","Clear cursor before placing blocks");
        if(mc.player.isUsingItem())throw new BridgeException("item_busy","Finish item use before placing blocks");
        if(!mc.level.getBlockState(p).canBeReplaced())throw new BridgeException("build_conflict","Existing block is protected");
        Item material=BuiltInRegistries.ITEM.getValue(Identifier.parse(type));if(!(material instanceof BlockItem))throw new BridgeException("unsupported_capability","Only block items can be placed");
        BlockState desired=((BlockItem)material).getBlock().defaultBlockState();
        if(!blockId(desired).equals(type))throw new BridgeException("unsupported_capability","This block needs a distinct placement item or mechanic");
        for(var property:wanted.entrySet()){Property<?> definition=desired.getBlock().getStateDefinition().getProperty(property.getKey());if(definition==null||definition.getValue(property.getValue().getAsString()).isEmpty())throw new BridgeException("invalid_request","Invalid block property: "+property.getKey());}
        Step prepared=selectItem(t,type,-1,view);
        if(!prepared.done() || prepared.error()!=null)return prepared;
        BlockHitResult hit=null;
        for(Direction face:Direction.values()) {
            if(wanted.has("axis") && !face.getAxis().getName().equals(wanted.get("axis").getAsString()))continue;
            BlockPos support=p.relative(face.getOpposite());BlockState s=mc.level.getBlockState(support);if(s.canBeReplaced() || s.getCollisionShape(mc.level,support).isEmpty())continue;
            // Trace slightly through the surface: an endpoint exactly on a voxel boundary may be a MISS.
            Vec3 aim=Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.49));
            BlockHitResult ray=mc.level.clip(new ClipContext(mc.player.getEyePosition(),aim,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
            if(ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(support) && ray.getDirection()==face){hit=ray;break;}
        }
        if(hit==null)throw new BridgeException("missing_support","No visible support face available",true);
        checkArea(mc.player.position());checkArea(Vec3.atCenterOf(hit.getBlockPos()));
        var materialSnapshot=placementMaterials.get(t.id);
        if(materialSnapshot==null){placementMaterials.put(t.id,new PlacementMaterial(mc.player.getInventory().getSelectedSlot(),mc.player.getMainHandItem().copy(),hit.getBlockPos().immutable(),mc.level.getBlockState(hit.getBlockPos())));return Step.waiting(object("stage","server_placement_ready"));}
        if(view==null || !view.has("placement_material_matches"))return Step.waiting(object("stage","server_placement_ready"));
        if(!bool(view,"placement_material_matches",false) || !ItemStack.matches(materialSnapshot.stack(),mc.player.getMainHandItem()))throw new BridgeException("target_changed","Placement material or components changed");
        if(!hit.getBlockPos().equals(materialSnapshot.support()) || !bool(view,"placement_support_matches",false))throw new BridgeException("target_changed","Placement support changed");
        if(number(view,"menu_id",-1)!=0 || integer(child(view,"cursor"),"count",0,0,64)!=0 || bool(view,"using_item",false))throw new BridgeException("item_busy","Server actor is not ready to place blocks");
        if(!child(view,"block").equals(blockSample(mc.level.getBlockState(p))))throw new BridgeException("target_changed","Placement cell changed between client and server");
        look(hit.getLocation());
        if(wanted.has("facing")){String face=wanted.get("facing").getAsString();boolean direct=placementKind(type)!=BuildPlanner.Kind.SINGLE;switch(face){case "north"->mc.player.setYRot(direct?180:0);case "south"->mc.player.setYRot(direct?0:180);case "east"->mc.player.setYRot(direct?-90:90);case "west"->mc.player.setYRot(direct?90:-90);default->{}}}
        if(!t.context.has("placement_look_started")){t.context.addProperty("placement_look_started",tick());return Step.waiting(object("stage","server_placement_orientation"));}
        if(Math.abs(MovementCommands.wrapYaw(number(view,"yaw",0)-mc.player.getYRot()))>0.1 || Math.abs(number(view,"pitch",0)-mc.player.getXRot())>0.1){
            if(tick()-number(t.context,"placement_look_started",tick())>20)throw new BridgeException("look_unconfirmed","Server did not confirm placement orientation before click");
            return Step.waiting(object("stage","server_placement_orientation"));
        }
        var placementContext=new net.minecraft.world.item.context.BlockPlaceContext(mc.player,InteractionHand.MAIN_HAND,mc.player.getMainHandItem(),hit);
        BlockState preview=((BlockItem)material).getBlock().getStateForPlacement(placementContext);
        if(preview==null || !preview.canSurvive(mc.level,p))throw new BridgeException("missing_support","Block cannot be placed with the current support or adjacent cells",true);
        if(!mc.level.isUnobstructed(preview,p,CollisionContext.placementContext(mc.player)))throw new BridgeException("placement_obstructed","An entity obstructs this placement; move it before a new task",true);
        ActionBudget.require(t,1);
        placement.begin(t.id,position(p),child(view,"block"),type,wanted,position(hit.getBlockPos()),hit.getDirection().getName(),heldStack(materialSnapshot.stack()),materialSnapshot.selected(),(long)number(view,"tick",tick()));
        mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);t.context.addProperty("placing",tick());t.actions++;return Step.waiting(object("stage","placing","position",position(p)));
    }
    private void confirmBlock(Task t,BlockPos p,JsonObject view) {
        JsonArray updates=t.confirmed.has("block_updates")?t.confirmed.getAsJsonArray("block_updates"):new JsonArray();
        JsonObject actual=child(view,"block").deepCopy();actual.addProperty("dimension",dimension());
        actual.addProperty("x",p.getX());actual.addProperty("y",p.getY());actual.addProperty("z",p.getZ());actual.addProperty("tick",view.get("tick").getAsLong());
        // A blueprint may revisit a cell; keep only its last verified state.
        for(int i=updates.size()-1;i>=0;i--){JsonObject old=updates.get(i).getAsJsonObject();if(old.get("x").equals(actual.get("x"))&&old.get("y").equals(actual.get("y"))&&old.get("z").equals(actual.get("z")))updates.remove(i);}
        updates.add(actual);t.confirmed.add("block_updates",updates);
    }
    private Step selectItem(Task t,String type,int requestedSlot) {return selectItem(t,type,requestedSlot,null);}
    private Step selectItem(Task t,String type,int requestedSlot,JsonObject observed) {return selectItem(t,type,requestedSlot,observed,-1);}
    private Step selectItem(Task t,String type,int requestedSlot,JsonObject observed,int exactSource) {
        if(!t.context.has("material_sent")) {
            int source=-1,target=requestedSlot;
            if(exactSource>=0){source=exactSource;target=source<9?source:requestedSlot;if(!itemId(mc.player.getInventory().getItem(source)).equals(type))throw new BridgeException("target_changed","Mining source item changed");}
            for(int i=0;source<0 && i<9;i++)if(itemId(mc.player.getInventory().getItem(i)).equals(type) && (requestedSlot<0 || i==requestedSlot)){source=i;target=i;break;}
            if(source<0)for(int i=0;i<36;i++)if(itemId(mc.player.getInventory().getItem(i)).equals(type)){source=i;break;}
            if(source<0)throw new BridgeException("missing_materials","Item is unavailable in the main inventory: "+type);
            if(target<0){for(int i=0;i<9;i++)if(mc.player.getInventory().getItem(i).isEmpty()){target=i;break;}if(target<0)target=mc.player.getInventory().getSelectedSlot();}
            boolean swap=source!=target;boolean select=mc.player.getInventory().getSelectedSlot()!=target;
            if(swap || select)ActionBudget.require(t,(swap?1:0)+(select?1:0));
            if(swap && mc.player.containerMenu!=mc.player.inventoryMenu)throw new BridgeException("interface_open","Close the container before preparing a hotbar item");
            if(swap && !mc.player.containerMenu.getCarried().isEmpty())throw new BridgeException("cursor_occupied","Clear cursor before preparing an item");
            t.context.addProperty("material_slot",target);t.context.addProperty("material_source",source);t.context.addProperty("material_menu",mc.player.containerMenu.containerId);
            t.context.add("material_expected",heldStack(mc.player.getInventory().getItem(source)));t.context.add("material_displaced",heldStack(mc.player.getInventory().getItem(target)));
            if(swap){Slot from=null;for(Slot slot:mc.player.containerMenu.slots)if(slot.container==mc.player.getInventory() && slot.getContainerSlot()==source){from=slot;break;}
                if(from==null || !from.mayPickup(mc.player))throw new BridgeException("permission_denied","Inventory slot cannot be moved");
                click(from.index,target,ContainerInput.SWAP);t.actions++;
            }
            if(select){mc.player.getInventory().setSelectedSlot(target);t.actions++;}
            t.context.addProperty("material_sent",tick());
            return Step.waiting(object("stage","preparing_item","item",type,"hotbar_slot",target));
        }
        int target=t.context.get("material_slot").getAsInt(),source=t.context.get("material_source").getAsInt();
        JsonObject view=observed!=null?observed:serverView(t,null,null);if(view==null)return Step.waiting(object("waiting","server_item_selection"));
        if(view.get("menu_id").getAsInt()!=t.context.get("material_menu").getAsInt())throw new BridgeException("interface_closed","Menu changed during item preparation");
        JsonArray inventory=view.getAsJsonArray("inventory_slots");
        boolean swapped=source==target || inventory.get(source).equals(t.context.get("material_displaced"));
        if(swapped && inventory.get(target).equals(t.context.get("material_expected")) && view.get("selected_slot").getAsInt()==target && child(view,"cursor").get("count").getAsInt()==0){
            JsonObject receipt=object("item",type,"selected_slot",target,"source_slot",source,"source","integrated_server","stack",inventory.get(target));
            t.confirmed.add("selected_item",receipt);return Step.success(receipt);
        }
        if(tick()-number(t.context,"material_sent",tick())>20)throw new BridgeException("selection_unconfirmed","Server did not confirm both inventory slots and selection");
        return Step.waiting(object("waiting","server_item_selection"));
    }
    private Step interact(Task t) {
        String handName=string(t.parameters,"hand","main");InteractionHand hand=handName.equals("off")?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
        if(!t.context.has("sent")) {
            if(mc.screen!=null || mc.player.containerMenu.containerId!=0)throw new BridgeException("interface_open","Close the current interface before interacting");
            if(mc.player.isUsingItem())throw new BridgeException("item_busy","Finish using the held item before interacting");
            checkArea(mc.player.position());
            Entity entity=t.parameters.has("entity_id")?entity(t.parameters):null;BlockPos pos=entity==null?block(child(t.parameters,"position")):null;
            BlockHitResult ray=null;
            if(entity!=null){checkArea(entity.position());if(!mc.player.isWithinEntityInteractionRange(entity,0))throw new BridgeException("out_of_reach","Entity out of reach");}
            else {reach(pos);ray=interactionHit(pos);if(ray==null)throw new BridgeException("target_lost","Occluded target");}
            JsonObject before=serverView(t,null,null);if(before==null)return Step.waiting(object("stage","server_interaction_ready"));
            if(number(before,"menu_id",-1)!=0 || bool(before,"using_item",false))throw new BridgeException("item_busy","Server actor has an active interface or item use");
            if(!before.get(handName+"_hand").equals(heldStack(mc.player.getItemInHand(hand))))return Step.waiting(object("stage","held_item_synchronization"));
            ActionBudget.require(t,1);
            interactions.begin(t.id,entity==null?interactionKey(pos):"entity:"+entity.getUUID(),handName,before.get("tick").getAsLong());
            if(entity!=null){Vec3 hit=entity.getBoundingBox().getCenter();look(hit);mc.gameMode.interact(mc.player,entity,new EntityHitResult(entity,hit),hand);}
            else {look(ray.getLocation());mc.gameMode.useItemOn(mc.player,hand,ray);}
            t.actions++;t.context.addProperty("sent",tick());
            return Step.waiting(object("stage","server_interaction_confirmation"));
        }
        JsonObject receipt=interactions.peek(t.id);updateInteractionReceipt(t,receipt);
        if(receipt!=null && (!t.action.equals("container.open") || string(receipt,"outcome").equals("menu_opened")))return Step.success(receipt);
        if(interactions.interrupted(t.id))return Step.failure("interaction_interrupted","Start a new task after checking the actual effect; the click is not repeated",t.result);
        if(tick()-number(t.context,"sent",tick())>20)return Step.failure("interaction_unconfirmed",t.action.equals("container.open")?"No new container opened":"No supported server-confirmed interaction effect",receipt==null?object():receipt);
        return Step.waiting(object("stage","server_interaction_confirmation"));
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
    private void inventoryGuards(Task t,boolean sent) {
        var menu=mc.player.containerMenu;
        if(t.parameters.has("menu_id") && integer(t.parameters,"menu_id",0,0,10000)!=menu.containerId)throw new BridgeException("stale_revision","Menu changed");
        if(!sent && t.parameters.has("menu_revision") && integer(t.parameters,"menu_revision",0,0,Integer.MAX_VALUE)!=menu.getStateId())throw new BridgeException("stale_revision","Menu contents changed");
        if(!sent && !menu.getCarried().isEmpty())throw new BridgeException("cursor_occupied","Clear cursor before changing inventory");
        if(!sent && mc.player.isUsingItem())throw new BridgeException("busy","Stop using the held item before changing inventory");
    }
    private static boolean fits(Slot slot,ItemStack item) {return item.isEmpty() || slot.mayPlace(item) && item.getCount()<=slot.getMaxStackSize(item);}
    private static void exchangeAllowed(Slot from,Slot to,net.minecraft.world.entity.player.Player player) {
        if(!from.isActive() || !to.isActive() || !from.getItem().isEmpty() && !from.mayPickup(player) || !to.getItem().isEmpty() && !to.mayPickup(player))
            throw new BridgeException("permission_denied","Slot cannot be changed; armor may be bound");
        if(!from.getItem().isEmpty() && !to.mayPlace(from.getItem()) || !to.getItem().isEmpty() && !from.mayPlace(to.getItem()))
            throw new BridgeException("invalid_target","Equipment or slot rejects the exchanged item");
        if(!fits(to,from.getItem()) || !fits(from,to.getItem()))throw new BridgeException("inventory_full","Whole stack does not fit; split it with inventory.transfer first");
    }
    private Step swap(Task t) {
        boolean sent=t.context.has("swap_sent");inventoryGuards(t,sent);
        if(mc.player.containerMenu!=mc.player.inventoryMenu)return Step.failure("interface_open","Close the container before equipment or whole-stack exchanges",object());
        var menu=mc.player.containerMenu;
        InventoryChange change=inventoryChanges.get(t.id);
        if(change==null) {
            int from=integer(t.parameters,"from_slot",0,0,menu.slots.size()-1),to=-1;
            if(t.parameters.has("equipment_slot")) {
                String named=string(t.parameters,"equipment_slot");
                for(Slot slot:menu.slots)if(slot.container==mc.player.getInventory() && equipmentSlot(slot.getContainerSlot()).equals(named)){to=slot.index;break;}
                if(to<0)throw new BridgeException("unsupported_capability","Equipment slot is unavailable");
                if(menu.getSlot(from).getItem().isEmpty())throw new BridgeException("missing_materials","Source contains no item to equip");
            } else to=integer(t.parameters,"to_slot",0,0,menu.slots.size()-1);
            if(from==to)throw new BridgeException("invalid_request","Choose two different menu slots");
            Slot source=menu.getSlot(from),target=menu.getSlot(to);
            if(source.container!=mc.player.getInventory() || target.container!=mc.player.getInventory())throw new BridgeException("unsupported_capability","Exchanges support main/hotbar/armor/offhand cells in the player menu");
            if(!Set.of("main","hotbar","armor","offhand").contains(slotRole(source)) || !Set.of("main","hotbar","armor","offhand").contains(slotRole(target)))throw new BridgeException("unsupported_capability","Unsupported player inventory cells");
            exchangeAllowed(source,target,mc.player);
            ItemStack src=source.getItem().copy(),dst=target.getItem().copy();
            List<InventorySwapPlan.Click> clicks=List.of();List<SlotChange> changes=new ArrayList<>();
            changes.add(new SlotChange(from,src,dst));changes.add(new SlotChange(to,dst,src));
            if(!ItemStack.matches(src,dst)) {
                int staging=from,bufferInventory=-1;
                if(!InventorySwapPlan.direct(source.getContainerSlot()) && !InventorySwapPlan.direct(target.getContainerSlot())) {
                    Slot stage=slotRole(source).equals("main")?source:slotRole(target).equals("main")?target:null;
                    if(stage==null)throw new BridgeException("unsupported_capability","Exchange needs a main inventory cell or a hotbar/offhand endpoint");
                    staging=stage.index;
                    Slot buffer=null;
                    for(int pass=0;pass<2 && buffer==null;pass++)for(Slot slot:menu.slots) {
                        if(slot.container!=mc.player.getInventory() || slot.getContainerSlot()<0 || slot.getContainerSlot()>8 || pass==0 && !slot.getItem().isEmpty())continue;
                        if(slot.isActive() && slot.mayPickup(mc.player) && fits(stage,slot.getItem()) && fits(slot,src) && fits(slot,dst)){buffer=slot;break;}
                    }
                    if(buffer==null)throw new BridgeException("inventory_full","No suitable hotbar buffer");
                    bufferInventory=buffer.getContainerSlot();ItemStack held=buffer.getItem().copy();changes.add(new SlotChange(buffer.index,held,held));
                }
                clicks=InventorySwapPlan.plan(from,to,source.getContainerSlot(),target.getContainerSlot(),staging,bufferInventory);
                ActionBudget.require(t,clicks.size());
            }
            change=new InventoryChange(menu.containerId,List.copyOf(changes),clicks);inventoryChanges.put(t.id,change);
        }
        JsonObject view=serverView(t,null,null);if(view==null)return Step.waiting(object("stage",sent?"confirming_inventory_exchange":"checking_inventory_exchange"));
        if(number(view,"menu_id",-1)!=change.menu())return Step.failure("interface_closed","Menu changed during exchange",object());
        if(!sent) {
            if(bool(view,"using_item",false))throw new BridgeException("busy","Server actor is using an item");
            if(!bool(view,"inventory_before_matches",false) || !change.matches(menu,false))throw new BridgeException("stale_revision","Source, destination, buffer or cursor changed before exchange");
            if(t.parameters.has("menu_revision") && number(view,"menu_revision",-1)!=number(t.parameters,"menu_revision",-2))throw new BridgeException("stale_revision","Server menu revision changed");
            if(change.clicks().isEmpty())return Step.success(object("outcome","unchanged","source_slot",view.getAsJsonArray("slots").get(change.slots().get(0).slot()),"target_slot",view.getAsJsonArray("slots").get(change.slots().get(1).slot()),"source","integrated_server"));
            ActionBudget.require(t,change.clicks().size());
            for(var input:change.clicks())click(input.slot(),input.inventoryButton(),ContainerInput.SWAP);
            t.actions+=change.clicks().size();t.context.addProperty("swap_sent",tick());return Step.waiting(object("stage","confirming_inventory_exchange"));
        }
        JsonArray slots=view.getAsJsonArray("slots");
        JsonObject result=object("outcome","unconfirmed","menu_id",change.menu(),"source_slot",slots.get(change.slots().get(0).slot()),"target_slot",slots.get(change.slots().get(1).slot()),"cursor",view.get("cursor"),"tick",view.get("tick"),"source","integrated_server");
        if(change.slots().size()==3)result.add("buffer_slot",slots.get(change.slots().get(2).slot()));
        if(t.parameters.has("equipment_slot"))result.addProperty("equipment_slot",string(t.parameters,"equipment_slot"));
        if(bool(view,"inventory_after_matches",false)) {
            result.addProperty("outcome","exchanged");t.confirmed.addProperty("inventory_swaps",1);if(t.parameters.has("equipment_slot"))t.confirmed.addProperty("equips",1);
            t.confirmed.add("inventory_swap",result);return Step.success(result);
        }
        if(tick()-number(t.context,"swap_sent",tick())>20)return Step.failure("swap_unconfirmed","Server stacks, components, cursor or restored buffer differ; exchange is not repeated",result);
        return Step.waiting(result);
    }
    private Step transfer(Task t) {
        boolean sent=t.context.has("transfer_sent");inventoryGuards(t,sent);
        AbstractContainerMenu menu=mc.player.containerMenu;
        InventoryChange change=inventoryChanges.get(t.id);
        if(change==null) {
            int from=integer(t.parameters,"from_slot",0,0,menu.slots.size()-1),to=integer(t.parameters,"to_slot",0,0,menu.slots.size()-1);
            if(from==to)throw new BridgeException("invalid_request","Source and destination are identical");
            Slot source=menu.getSlot(from),target=menu.getSlot(to);ItemStack src=source.getItem().copy(),dst=target.getItem().copy();
            int count=integer(t.parameters,"count",src.getCount(),1,64);
            if(src.isEmpty() || src.getCount()<count)throw new BridgeException("missing_materials","Source contains too few items");
            if(!source.mayPickup(mc.player)||!target.mayPlace(src))throw new BridgeException("permission_denied","Menu disallows this transfer");
            if(!dst.isEmpty()&&!ItemStack.isSameItemSameComponents(src,dst))throw new BridgeException("inventory_full","Destination contains another item");
            if(target.getMaxStackSize(src)-dst.getCount()<count)throw new BridgeException("inventory_full","Destination has insufficient capacity");
            ActionBudget.require(t,ActionBudget.transferClicks(src.getCount(),count));
            change=new InventoryChange(menu.containerId,List.of(new SlotChange(from,src,src.copyWithCount(src.getCount()-count)),new SlotChange(to,dst,src.copyWithCount(dst.getCount()+count))),List.of());
            inventoryChanges.put(t.id,change);t.context.addProperty("count",count);
        }
        JsonObject v=serverView(t,null,null);if(v==null)return Step.waiting(object("waiting","server_transfer"));
        if(number(v,"menu_id",-1)!=change.menu())return Step.failure("interface_closed","Menu changed during transfer",object());
        SlotChange from=change.slots().get(0),to=change.slots().get(1);int count=t.context.get("count").getAsInt();
        if(!sent) {
            if(!bool(v,"inventory_before_matches",false) || !change.matches(menu,false))throw new BridgeException("stale_revision","Source, destination or cursor changed before transfer");
            if(bool(v,"using_item",false))throw new BridgeException("busy","Server actor is using an item");
            if(t.parameters.has("menu_revision") && number(v,"menu_revision",-1)!=number(t.parameters,"menu_revision",-2))throw new BridgeException("stale_revision","Server menu revision changed");
            int clicks=ActionBudget.transferClicks(from.before().getCount(),count);ActionBudget.require(t,clicks);
            click(from.slot(),0,ContainerInput.PICKUP);
            if(count==from.before().getCount())click(to.slot(),0,ContainerInput.PICKUP);
            else {for(int i=0;i<count;i++)click(to.slot(),1,ContainerInput.PICKUP);click(from.slot(),0,ContainerInput.PICKUP);}
            t.actions+=clicks;t.context.addProperty("transfer_sent",tick());return Step.waiting(object("waiting","server_transfer"));
        }
        JsonArray slots=v.getAsJsonArray("slots");
        JsonObject result=object("transferred",0,"remaining",count,"source_slot",slots.get(from.slot()),"target_slot",slots.get(to.slot()),"cursor",v.get("cursor"),"source","integrated_server");
        if(bool(v,"inventory_after_matches",false)){result.addProperty("transferred",count);result.addProperty("remaining",0);t.confirmed.addProperty("transfer_count",count);t.confirmed.add("transfer",result);return Step.success(result);}
        if(tick()-number(t.context,"transfer_sent",tick())>20)return Step.failure("transfer_unconfirmed","Server stacks or components differ; no transfer count is claimed from counts alone",result);
        return Step.waiting(result);
    }
    private void click(int slot,int button,ContainerInput action){mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,slot,button,action,mc.player);}
    private Map<String,Integer> counts(){Map<String,Integer> counts=new HashMap<>();for(int i=0;i<36;i++){ItemStack s=mc.player.getInventory().getItem(i);if(!s.isEmpty())counts.merge(itemId(s),s.getCount(),Integer::sum);}return counts;}
    private RecipeCatalog catalog(){if(!catalogFuture.isDone())throw new BridgeException("busy","Recipe catalog is loading",true);return catalogFuture.join();}
    private Set<String> stations(){return mc.player.containerMenu instanceof CraftingMenu?Set.of("inventory","crafting_table"):Set.of("inventory");}
    private record CraftPreparation(CraftPlanner.Plan plan,BlockPos station) {}
    private CraftPreparation prepareCraft(JsonObject d) {
        Identifier.parse(string(d,"item"));CraftSequence.validate(d);
        var planner=catalog().planner();int count=integer(d,"count",1,1,4096);var amounts=counts();
        var plan=planner.plan(string(d,"item"),count,amounts,stations());BlockPos target=null;
        if(plan.missingStations().contains("crafting_table") && mc.player.containerMenu.containerId==0) {
            if(d.has("station_position")) {
                target=block(child(d,"station_position"));reach(target);
                if(!mc.level.getBlockState(target).is(Blocks.CRAFTING_TABLE))throw new BridgeException("invalid_target","station_position must be a vanilla crafting table");
                if(interactionHit(target)==null)throw new BridgeException("target_lost","Crafting table is occluded");
            } else if(bool(d,"auto_station",true)) {
                double best=Double.MAX_VALUE;int radius=Math.min(8,(int)Math.ceil(mc.player.blockInteractionRange())+1);BlockPos origin=mc.player.blockPosition();
                for(BlockPos pos:BlockPos.betweenClosed(origin.offset(-radius,-radius,-radius),origin.offset(radius,radius,radius))) {
                    double distance=mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
                    if(distance>=best || distance>mc.player.blockInteractionRange() || !clientChunkLoaded(pos) || !mc.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE))continue;
                    try{checkArea(Vec3.atCenterOf(pos));}catch(BridgeException e){if(e.code.equals("permission_denied"))continue;throw e;}
                    if(interactionHit(pos)!=null){target=pos.immutable();best=distance;}
                }
            }
            if(target!=null)plan=planner.plan(string(d,"item"),count,amounts,Set.of("inventory","crafting_table"));
        }
        return new CraftPreparation(plan,target);
    }
    private JsonObject craftPlan(JsonObject d) {
        var prepared=prepareCraft(d);JsonObject result=GSON.toJsonTree(prepared.plan()).getAsJsonObject();
        result.addProperty("station_open_actions",prepared.station()!=null?1:0);
        if(prepared.station()!=null)result.add("station",object("type","minecraft:crafting_table","position",position(prepared.station()),"source","client_observation"));
        return result;
    }
    private Step openCraftStation(Task t,BlockPos pos) {
        if(!t.context.has("station_sent")) {
            if(mc.screen!=null || mc.player.containerMenu.containerId!=0)throw new BridgeException("interface_open","Close the current interface before opening the crafting table");
            if(mc.player.isUsingItem() || mc.player.isCrouching())throw new BridgeException("item_busy","Stop item use and crouching before opening the crafting table");
            if(!mc.player.containerMenu.getCarried().isEmpty())throw new BridgeException("cursor_occupied","Clear cursor before opening the crafting table");
            checkArea(mc.player.position());reach(pos);BlockHitResult hit=interactionHit(pos);
            if(hit==null || !mc.level.getBlockState(pos).is(Blocks.CRAFTING_TABLE))throw new BridgeException("target_lost","Crafting table is no longer reachable and visible");
            JsonObject before=serverView(t,pos,null);if(before==null)return Step.waiting(object("stage","server_station_ready"));
            if(number(before,"menu_id",-1)!=0 || bool(before,"using_item",false) || bool(before,"crouching",false) || integer(child(before,"cursor"),"count",0,0,64)!=0)throw new BridgeException("item_busy","Server actor is not ready to open the crafting table");
            if(!string(child(before,"block"),"type").equals("minecraft:crafting_table") || !bool(before,"block_in_range",false) || !bool(before,"block_visible",false))throw new BridgeException("target_lost","Server cannot interact with this crafting table");
            if(!before.get("main_hand").equals(heldStack(mc.player.getMainHandItem())))return Step.waiting(object("stage","held_item_synchronization"));
            ActionBudget.require(t,1);interactions.begin(t.id,interactionKey(pos),"main",before.get("tick").getAsLong());
            look(hit.getLocation());mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);t.actions++;t.context.addProperty("station_sent",tick());
            return Step.waiting(object("stage","opening_crafting_table","position",position(pos)));
        }
        JsonObject receipt=interactions.peek(t.id);
        if(receipt!=null && string(receipt,"outcome").equals("menu_opened")) {
            int opened=integer(child(receipt,"after"),"menu_id",-1,0,10000);JsonObject view=serverView(t,pos,null);
            if(view==null)return Step.waiting(object("stage","server_station_menu"));
            if(number(view,"menu_id",-1)!=opened || !bool(view,"crafting_table",false))throw new BridgeException("interface_closed","Confirmed crafting table menu changed");
            if(!(mc.player.containerMenu instanceof CraftingMenu) || mc.player.containerMenu.containerId!=opened)return Step.waiting(object("stage","client_station_menu"));
            t.confirmed.add("craft_station",object("position",position(pos),"menu_id",opened,"source","integrated_server"));
            craftSequences.put(t.id,new CraftSequence(opened));
            craftStations.remove(t.id);t.context.remove("station_sent");interactions.end(t.id);return Step.waiting(object("stage","crafting_table_open","menu_id",opened));
        }
        if(interactions.interrupted(t.id))throw new BridgeException("craft_interrupted","Opening the crafting table was interrupted; inspect the actual menu before a new task");
        if(tick()-number(t.context,"station_sent",tick())>20)throw new BridgeException("craft_unconfirmed","Crafting table did not confirm opening; the click is not repeated");
        return Step.waiting(object("stage","opening_crafting_table"));
    }
    private InventoryChange craftChange(AbstractCraftingMenu menu,Slot source,Slot target,ItemStack targetAfter) {
        List<SlotChange> changes=new ArrayList<>();
        for(Slot slot:menu.getInputGridSlots()){ItemStack before=slot.getItem().copy();changes.add(new SlotChange(slot.index,before,slot==target?targetAfter:before.copy()));}
        if(source!=null){ItemStack before=source.getItem().copy();changes.add(new SlotChange(source.index,before,before.copyWithCount(before.getCount()-1)));}
        return new InventoryChange(menu.containerId,List.copyOf(changes),List.of());
    }
    private void checkCraftGrid(AbstractCraftingMenu menu,RecipeCatalog.Entry recipe,CraftPlanner.Stage step,int iteration,int cell) {
        Map<Integer,String> expected=new HashMap<>();int ingredient=0;
        for(int i=0;i<recipe.grid().size();i++)if(!recipe.grid().get(i).isEmpty()) {
            String item=step.ingredients().get(iteration*recipe.recipe().ingredients().size()+ingredient++);
            if(i<cell)expected.put((i/recipe.width())*menu.getGridWidth()+i%recipe.width(),item);
        }
        for(int i=0;i<menu.getInputGridSlots().size();i++) {
            ItemStack actual=menu.getInputGridSlots().get(i).getItem();String item=expected.get(i);
            if(item==null?!actual.isEmpty():!itemId(actual).equals(item) || actual.getCount()!=1)throw new BridgeException("stale_revision","Craft grid differs from the confirmed ingredient layout");
        }
    }
    private Step craft(Task t) {
        if(!crafts.containsKey(t.id)){var prepared=prepareCraft(t.parameters);crafts.put(t.id,prepared.plan());if(prepared.station()!=null)craftStations.put(t.id,prepared.station());}
        CraftPlanner.Plan plan=crafts.get(t.id);
        if(!plan.executable())return Step.failure(plan.missing().isEmpty()?"missing_station":"missing_materials","Missing ingredients or accessible crafting station",GSON.toJsonTree(plan).getAsJsonObject());
        int stage=integer(t.context,"craft_stage",0,0,10000),iteration=integer(t.context,"craft_iteration",0,0,4096);
        if(stage>=plan.stages().size()) {
            JsonObject view=serverView(t,null,null);if(view==null)return Step.waiting(object("stage","final_craft_verification"));
            JsonObject amounts=child(view,"inventory_counts");String item=string(t.parameters,"item");
            if(integer(amounts,item,0,0,Integer.MAX_VALUE)<integer(t.parameters,"count",1,1,4096))throw new BridgeException("craft_unconfirmed","Craft target is no longer present in server inventory");
            return Step.success(object("item",item,"inventory",amounts,"stages",plan.stages(),"source","integrated_server"));
        }
        if(craftStations.containsKey(t.id))return openCraftStation(t,craftStations.get(t.id));
        if(craftSequences.containsKey(t.id))craftSequences.get(t.id).checkMenu(mc.player.containerMenu.containerId);
        if(!(mc.player.containerMenu instanceof AbstractCraftingMenu menu))throw new BridgeException("interface_open","Close unrelated interfaces before crafting");
        CraftSequence sequence=craftSequences.computeIfAbsent(t.id,key->new CraftSequence(menu.containerId));sequence.checkMenu(menu.containerId);
        if(!menu.getCarried().isEmpty())throw new BridgeException("cursor_occupied","Clear cursor before crafting");
        var step=plan.stages().get(stage);var recipe=catalog().entries.get(step.recipe());
        if(recipe.width()>menu.getGridWidth()||recipe.height()>menu.getGridHeight())throw new BridgeException("missing_station","A crafting table must be open");
        if(sequence.pending()==CraftSequence.Operation.FILL || sequence.pending()==CraftSequence.Operation.CLEAR) {
            JsonObject view=serverView(t,null,null);if(view==null)return Step.waiting(object("stage","server_craft_grid"));
            boolean matched=bool(view,"inventory_after_matches",false) && inventoryChanges.get(t.id).matches(menu,true);
            if(!sequence.confirm((int)number(view,"menu_id",-1),tick(),matched))return Step.waiting(object("stage","server_craft_grid"));
            inventoryChanges.remove(t.id);if(t.context.has("craft_next_cell")){t.context.add("craft_cell",t.context.remove("craft_next_cell"));}
            return Step.waiting(object("stage","craft_grid_confirmed"));
        }
        if(t.context.has("craft_sent")) {
            JsonObject v=serverView(t,null,null);if(v==null)return Step.waiting(object("waiting","server_craft"));
            JsonObject amounts=child(v,"inventory_counts");int actual=amounts.has(step.item())?amounts.get(step.item()).getAsInt():0;
            if(!string(t.context,"craft_item").equals(step.item()))throw new BridgeException("stale_revision","Craft stage changed while awaiting output");
            if(sequence.confirm((int)number(v,"menu_id",-1),tick(),actual>=t.context.get("craft_before").getAsInt()+recipe.recipe().count() && v.get("items_crafted").getAsInt()>=t.context.get("craft_stats_before").getAsInt()+recipe.recipe().count())) {
                t.confirmed.addProperty("crafts_confirmed",integer(t.confirmed,"crafts_confirmed",0,0,100000)+1);
                if(!t.confirmed.has("craft_stages"))t.confirmed.add("craft_stages",new JsonArray());
                t.confirmed.getAsJsonArray("craft_stages").add(object("stage",stage,"iteration",iteration,"recipe",step.recipe(),"item",step.item(),"output",recipe.recipe().count(),"source","integrated_server"));
                inventoryChanges.remove(t.id);
                t.context.remove("craft_sent");t.context.addProperty("craft_cell",0);iteration++;
                if(iteration>=step.crafts()){stage++;iteration=0;}t.context.addProperty("craft_stage",stage);t.context.addProperty("craft_iteration",iteration);
                return Step.waiting(object("stage",stage,"craft_iteration",iteration));
            }
            return Step.waiting(object("waiting","server_craft"));
        }
        int cell=integer(t.context,"craft_cell",0,0,9);
        if(cell==0 && !t.context.has("grid_cleared")) {
            for(Slot grid:menu.getInputGridSlots())if(grid.hasItem()){
                inventoryChanges.computeIfAbsent(t.id,key->craftChange(menu,null,grid,ItemStack.EMPTY));
                JsonObject view=serverView(t,null,null);if(view==null)return Step.waiting(object("stage","server_grid_clear_ready"));sequence.checkMenu((int)number(view,"menu_id",-1));
                if(!bool(view,"inventory_before_matches",false) || !inventoryChanges.get(t.id).matches(menu,false))throw new BridgeException("stale_revision","Craft grid changed before clearing");
                ActionBudget.require(t,1);sequence.begin(CraftSequence.Operation.CLEAR,tick());click(grid.index,0,ContainerInput.QUICK_MOVE);t.actions++;return Step.waiting(object("stage","clearing_grid"));
            }
            t.context.addProperty("grid_cleared",true);
        }
        while(cell<recipe.grid().size() && recipe.grid().get(cell).isEmpty())cell++;
        checkCraftGrid(menu,recipe,step,iteration,cell);
        if(cell<recipe.grid().size()) {
            int ingredientIndex=0;for(int previous=0;previous<cell;previous++)if(!recipe.grid().get(previous).isEmpty())ingredientIndex++;
            String planned=step.ingredients().get(iteration*recipe.recipe().ingredients().size()+ingredientIndex);
            List<String> alternatives=List.of(planned);Slot source=null;
            for(Slot s:menu.slots)if(s.container==mc.player.getInventory() && s.getContainerSlot()<36 && alternatives.contains(itemId(s.getItem()))){source=s;break;}
            if(source==null)throw new BridgeException("missing_materials","Ingredient is unavailable: "+alternatives);
            int index=(cell/recipe.width())*menu.getGridWidth()+(cell%recipe.width());Slot target=menu.getInputGridSlots().get(index);
            if(target.hasItem())throw new BridgeException("stale_revision","Craft grid changed");
            if(!inventoryChanges.containsKey(t.id))inventoryChanges.put(t.id,craftChange(menu,source,target,source.getItem().copyWithCount(1)));
            JsonObject view=serverView(t,null,null);if(view==null)return Step.waiting(object("stage","server_ingredient_ready"));sequence.checkMenu((int)number(view,"menu_id",-1));
            if(bool(view,"using_item",false))throw new BridgeException("item_busy","Server actor is using an item");
            if(!bool(view,"inventory_before_matches",false) || !inventoryChanges.get(t.id).matches(menu,false))throw new BridgeException("stale_revision","Craft ingredient, components, grid or cursor changed before input");
            ActionBudget.require(t,3);sequence.begin(CraftSequence.Operation.FILL,tick());click(source.index,0,ContainerInput.PICKUP);click(target.index,1,ContainerInput.PICKUP);click(source.index,0,ContainerInput.PICKUP);t.actions+=3;
            t.context.addProperty("craft_next_cell",cell+1);return Step.waiting(object("stage","filling_grid","cell",cell));
        }
        ItemStack result=menu.getResultSlot().getItem();
        if(!itemId(result).equals(step.item()))return Step.waiting(object("waiting","recipe_result"));
        boolean room=mc.player.getInventory().getFreeSlot()!=-1 || mc.player.getInventory().getSlotWithRemainingSpace(result)!=-1;
        if(!room)throw new BridgeException("inventory_full","No room for crafting output");
        ActionBudget.require(t,1);t.context.addProperty("craft_item",step.item());
        inventoryChanges.computeIfAbsent(t.id,key->craftChange(menu,null,null,ItemStack.EMPTY));
        JsonObject baseline=serverView(t,null,null);if(baseline==null)return Step.waiting(object("waiting","server_recipe"));
        sequence.checkMenu((int)number(baseline,"menu_id",-1));
        if(!bool(baseline,"inventory_before_matches",false) || !inventoryChanges.get(t.id).matches(menu,false))throw new BridgeException("stale_revision","Craft grid changed before taking output");
        if(!baseline.has("craft_output") || !string(child(baseline,"craft_output"),"type").equals(step.item()))return Step.waiting(object("waiting","server_recipe"));
        t.context.addProperty("craft_stats_before",baseline.get("items_crafted").getAsInt());
        t.context.addProperty("craft_before",integer(child(baseline,"inventory_counts"),step.item(),0,0,Integer.MAX_VALUE));sequence.begin(CraftSequence.Operation.OUTPUT,tick());click(menu.getResultSlot().index,0,ContainerInput.QUICK_MOVE);t.actions++;t.context.addProperty("craft_sent",tick());t.context.remove("grid_cleared");
        return Step.waiting(object("stage","crafting","recipe",step.recipe()));
    }
    private BuildPlanner.Kind placementKind(String type) {
        Identifier id=Identifier.parse(type);if(!id.getNamespace().equals("minecraft"))return BuildPlanner.Kind.SINGLE;
        var block=BuiltInRegistries.BLOCK.getValue(id);
        if(block instanceof net.minecraft.world.level.block.DoorBlock)return BuildPlanner.Kind.DOOR;
        if(block instanceof net.minecraft.world.level.block.BedBlock)return BuildPlanner.Kind.BED;
        return BuildPlanner.Kind.SINGLE;
    }
    private BuildPlanner.Plan buildPlan(JsonObject data){return new BuildPlanner().plan(data,this::placementKind);}
    private BuildPlanner.Placement buildPlacement(BuildPlanner.Plan plan,BuildPlanner.Block block) {
        return plan.placements().stream().filter(p->p.cells().contains(block)).findFirst().orElseThrow();
    }
    private boolean buildOccupiesPlayer(BuildPlanner.Placement placement) {
        return placement.cells().stream().anyMatch(b->mc.player.getBoundingBox().intersects(new AABB(new BlockPos(b.x(),b.y(),b.z()))));
    }
    private boolean visibleBuildSupport(Vec3 eye,BlockPos p,JsonObject wanted) {
        for(Direction face:Direction.values()) {
            if(wanted.has("axis") && !face.getAxis().getName().equals(wanted.get("axis").getAsString()))continue;
            BlockPos support=p.relative(face.getOpposite());BlockState state=mc.level.getBlockState(support);if(state.canBeReplaced() || state.getCollisionShape(mc.level,support).isEmpty())continue;
            Vec3 aim=Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(0.49));
            BlockHitResult ray=mc.level.clip(new ClipContext(eye,aim,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
            if(ray.getType()==HitResult.Type.BLOCK&&ray.getBlockPos().equals(support)&&ray.getDirection()==face)return true;
        }
        return false;
    }
    private Vec3 buildStand(BuildPlanner.Plan plan,BlockPos p,JsonObject wanted,boolean requireVisible) {
        List<Vec3> candidates=new ArrayList<>();int feet=mc.player.blockPosition().getY();
        for(int y=feet-1;y<=feet+1;y++)for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++) {
            Pathfinder.Cell c=new Pathfinder.Cell(p.getX()+dx,y,p.getZ()+dz);if(terrain(c)!=Pathfinder.Terrain.WALKABLE)continue;
            Vec3 at=new Vec3(c.x()+0.5,c.y(),c.z()+0.5),eye=at.add(0,mc.player.getEyeHeight(),0);if(at.distanceTo(mc.player.position())<0.5 || eye.distanceTo(Vec3.atCenterOf(p))>mc.player.blockInteractionRange())continue;
            AABB standing=mc.player.getDimensions(Pose.STANDING).makeBoundingBox(at).inflate(0.05);
            if(plan.blocks().stream().anyMatch(b->standing.intersects(new AABB(new BlockPos(b.x(),b.y(),b.z())))))continue;
            if(requireVisible && !visibleBuildSupport(eye,p,wanted))continue;candidates.add(at);
        }
        candidates.sort(Comparator.comparingDouble(at->at.distanceToSqr(mc.player.position())));
        for(Vec3 at:candidates)try{path(at);return at;}catch(BridgeException blocked){if(!Set.of("path_unreachable","chunk_unavailable").contains(blocked.code))throw blocked;}
        return null;
    }
    private Step buildReposition(Task t,Vec3 stand,String stage,int index) {
        if(stand==null)throw new BridgeException("path_unreachable","No reachable standing place outside the blueprint",true);
        t.context.add("build_stand",vector(stand));routes.remove(t.id);t.context.remove("material_sent");t.context.remove("placement_look_started");placementMaterials.remove(t.id);
        return Step.waiting(object("stage",stage,"confirmed_blocks",index));
    }
    private Step build(Task t) {
        BuildPlanner.Plan plan=builds.computeIfAbsent(t.id,key->buildPlan(t.parameters));
        if(bool(t.parameters,"replace",false))throw new BridgeException("unsupported_capability","Automatic replacement is disabled; authorize block.break separately");
        int index=integer(t.context,"build_index",0,0,4096);
        if(index>=plan.blocks().size()){
            CompletableFuture<Integer> check=buildChecks.get(t.id);
            if(check==null){var dimension=mc.level.dimension();check=mc.getSingleplayerServer().submit(()->{var level=mc.getSingleplayerServer().getLevel(dimension);for(int i=0;i<plan.blocks().size();i++){var b=plan.blocks().get(i);if(!level.hasChunkAt(new BlockPos(b.x(),b.y(),b.z())) || !matches(level.getBlockState(new BlockPos(b.x(),b.y(),b.z())),b.type(),b.properties()))return i;}return -1;});buildChecks.put(t.id,check);return Step.waiting(object("stage","final_verification"));}
            if(!check.isDone())return Step.waiting(object("stage","final_verification"));
            buildChecks.remove(t.id);int wrong=check.join();
            if(wrong>=0){t.context.addProperty("build_index",wrong);return Step.waiting(object("stage","revalidating_changed_block","index",wrong));}
            return Step.success(object("confirmed_blocks",plan.blocks().size(),"source","integrated_server"));
        }
        var b=plan.blocks().get(index);BlockPos p=new BlockPos(b.x(),b.y(),b.z());checkArea(Vec3.atCenterOf(p));
        if(t.context.has("placing") || matches(mc.level.getBlockState(p),b.type(),b.properties()))return buildPlacementProgress(t,plan,index,placeBlock(t,p,b.type(),b.properties()));
        if(t.context.has("build_stand")) {
            Step approaching=move(t,point(child(t.context,"build_stand")),0.4,false);
            if(approaching.done()){t.context.remove("build_stand");routes.remove(t.id);hold(mc.options.keyUp,false);hold(mc.options.keyJump,false);}
            return approaching.error()!=null?approaching:Step.waiting(object("stage","finding_visible_build_face","movement",approaching.result()));
        }
        var structure=buildPlacement(plan,b);
        if(!structure.primary().equals(b))throw new BridgeException("build_conflict","A structure part is missing; inspect both parts and authorize removal separately");
        for(var part:structure.cells()){BlockPos pos=new BlockPos(part.x(),part.y(),part.z());if(!clientChunkLoaded(pos))throw new BridgeException("chunk_unavailable","Structure crosses unloaded chunk",true);if(!mc.level.getBlockState(pos).canBeReplaced())throw new BridgeException("build_conflict","A structure cell is protected; partial structures are not overwritten");}
        if(buildOccupiesPlayer(structure))return buildReposition(t,buildStand(plan,p,b.properties(),true),"clearing_build_cell",index);
        if(mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(p))>mc.player.blockInteractionRange()) {
            return buildReposition(t,buildStand(plan,p,b.properties(),false),"approaching_build_cell",index);
        }
        hold(mc.options.keyUp,false);hold(mc.options.keyJump,false);
        Step placed;
        try {placed=placeBlock(t,p,b.type(),b.properties());}
        catch(BridgeException e) {
            if(!e.code.equals("missing_support") || integer(t.context,"reposition_count",0,0,3)>=3)throw e;
            Vec3 standing=buildStand(plan,p,b.properties(),true);
            if(standing==null)throw e;
            t.context.addProperty("reposition_count",integer(t.context,"reposition_count",0,0,3)+1);
            return buildReposition(t,standing,"repositioning_for_support",index);
        }
        return buildPlacementProgress(t,plan,index,placed);
    }
    private Step buildPlacementProgress(Task t,BuildPlanner.Plan plan,int index,Step placed) {
        if(placed.done() && placed.error()==null){t.context.addProperty("build_index",index+1);t.context.remove("placing");t.context.remove("placement_look_started");t.context.remove("reposition_count");routes.remove(t.id);t.confirmed.addProperty("blocks",index+1);return Step.waiting(object("confirmed_blocks",index+1,"total",plan.blocks().size()));}
        return placed;
    }
    @Override public JsonObject query(String operation,JsonObject d) {
        return switch(operation) {
            case "inventory.inspect" -> {
                JsonObject observed=snapshot(1,false);
                if(d.has("item")){String item=string(d,"item");Identifier.parse(item);JsonArray inventory=new JsonArray(),menu=new JsonArray();
                    for(JsonElement slot:observed.getAsJsonArray("inventory"))if(string(slot.getAsJsonObject(),"type").equals(item))inventory.add(slot);
                    JsonArray empty=new JsonArray();
                    for(JsonElement slot:child(observed,"context").getAsJsonArray("menu_slots")) {
                        JsonObject entry=slot.getAsJsonObject();
                        if(string(entry,"type").equals(item) || Set.of("armor","offhand").contains(string(entry,"role")))menu.add(slot);
                        else if(integer(entry,"count",0,0,Integer.MAX_VALUE)==0 && Set.of("main","hotbar","container").contains(string(entry,"role")) && empty.size()<4)empty.add(slot);
                    }
                    for(JsonElement slot:empty)menu.add(slot);
                    observed.add("inventory",inventory);child(observed,"context").add("menu_slots",menu);
                    child(observed,"context").addProperty("item_filter",item);child(observed,"context").addProperty("omitted_nonmatching_items",true);
                }
                yield object("inventory",observed.get("inventory"),"menu",observed.get("context"));
            }
            case "route.plan" -> object("path",path(point(child(d,"target"))),"modes",List.of("walk","jump"),"unknown_cells","excluded");
            case "craft.plan" -> craftPlan(d);
            case "build.plan" -> {
                var plan=buildPlan(d);JsonArray conflicts=new JsonArray();Map<String,Integer> needed=new HashMap<>();int placements=0;
                for(var b:plan.blocks()){BlockPos p=new BlockPos(b.x(),b.y(),b.z());checkArea(Vec3.atCenterOf(p));if(!clientChunkLoaded(p))throw new BridgeException("chunk_unavailable","Blueprint crosses unloaded chunk");}
                for(var placement:plan.placements()){
                    boolean ready=placement.cells().stream().allMatch(b->matches(mc.level.getBlockState(new BlockPos(b.x(),b.y(),b.z())),b.type(),b.properties()));if(ready)continue;
                    placements++;needed.merge(placement.primary().type(),1,Integer::sum);
                    for(var b:placement.cells()){BlockPos pos=new BlockPos(b.x(),b.y(),b.z());if(!mc.level.getBlockState(pos).canBeReplaced())conflicts.add(position(pos));}
                }
                Map<String,Integer> missing=new HashMap<>(),counts=counts();needed.forEach((k,v)->{int m=v-counts.getOrDefault(k,0);if(m>0)missing.put(k,m);});
                yield object("blocks",plan.blocks(),"materials",needed,"missing",missing,"conflicts",conflicts,"required_placements",placements,"executable",missing.isEmpty()&&conflicts.isEmpty(),"replacement","explicit_break_required","supports","verified_during_execution");
            }
            default -> throw new BridgeException("unsupported_capability",operation);
        };
    }
}
