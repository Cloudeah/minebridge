package dev.minebridge.core;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import static dev.minebridge.core.Json.*;

/** Single game-thread executor. Network callbacks enqueue immutable messages only. */
public final class BridgeEngine {
    public static final String VERSION="1.0";
    public static final long DEDUP_MS=600_000;
    public interface Peer {
        void text(JsonObject message);
        void binary(byte[] frame);
        void close(String reason);
    }
    private record Incoming(Peer peer, String text, boolean disconnect) {}
    private record Cached(String type, String payload, JsonObject reply, long time) {}
    private final WorldAccess world;
    private final OwnerPolicy policy;
    private final WorldMemory memory;
    private final Consumer<JsonObject> audit;
    private final ConcurrentLinkedQueue<Incoming> incoming=new ConcurrentLinkedQueue<>();
    private final LinkedHashMap<String,Cached> dedup=new LinkedHashMap<>();
    private final LinkedHashMap<String,Task> tasks=new LinkedHashMap<>();
    private final ArrayDeque<JsonObject> journal=new ArrayDeque<>();
    public final String sessionId=id();
    private volatile Peer controller;
    private Task active;
    private boolean armed;
    private boolean stopped;
    private long sequence,revision;
    private String fingerprint="";
    private JsonObject observation=new JsonObject();
    private long lastObservation=-100;
    private long observationTime;
    private long controlEpoch,lastManualTick=-1,lastManualEpoch=-1;
    private final ModelControl model=new ModelControl();
    private int subscriptionTicks;
    private String subscriptionProfile="full";
    private long lastSent=-100;
    private boolean eventSubscription;
    private JsonObject visionRequest;
    private long visionDeadline;
    private int visionPeriod;
    private JsonObject visionProfile;
    private long lastVision=-100;
    private final String actorId, dimension;
    public BridgeEngine(WorldAccess world, OwnerPolicy policy, WorldMemory memory, Consumer<JsonObject> audit) {
        this.world=world; this.policy=policy; this.memory=memory; this.audit=audit;
        actorId=world.actorId(); dimension=world.dimension();
    }
    public void receive(Peer peer,String message) {
        if (message.length()>65536 || incoming.size()>=128) { peer.close("input_limit_exceeded"); return; }
        incoming.add(new Incoming(peer,message,false));
    }
    public void disconnected(Peer peer) { incoming.add(new Incoming(peer,"",true)); }
    public long revision() { return revision; }
    public boolean armed() { return armed; }
    public void ownerArm() { controlEpoch++;armed=true; stopped=false; event("control.armed",object()); }
    public void ownerStop() { controlEpoch++;armed=false; stopped=true;model.cancel("Остановлено владельцем.");stopAll("owner_stop"); event("control.stopped",object("source","owner")); }
    public JsonObject modelStatus(){
        JsonObject status=model.status();status.addProperty("controller_connected",controller!=null);status.addProperty("armed",armed);
        String goalId=status.has("goal_id")?status.get("goal_id").getAsString():null;
        Task current=active!=null&&!active.terminal()&&matchesCurrentGoal(active,goalId)?active:null;
        if(current==null)current=tasks.values().stream().filter(t->!t.terminal()&&matchesCurrentGoal(t,goalId)).reduce((a,b)->b).orElse(null);
        Task recent=tasks.values().stream().filter(Task::terminal).filter(t->matchesCurrentGoal(t,goalId)).reduce((a,b)->b).orElse(null);
        if(current!=null)status.add("active_task",taskSummary(current));
        if(recent!=null)status.add("recent_task",taskSummary(recent));
        return status;
    }
    private static boolean matchesCurrentGoal(Task task,String goalId) {
        if(!task.context.has("model_goal_id"))return true;
        return goalId!=null&&goalId.equals(task.context.get("model_goal_id").getAsString());
    }
    public void ownerModelStart(String goal,int steps,boolean vision) {
        requireArmed();if(vision && !world.visionAvailable())throw new BridgeException("unsupported_capability","Зрение недоступно.");
        if(tasks.values().stream().anyMatch(t->!t.terminal()))throw new BridgeException("busy","Сначала останови текущие задачи клавишей F9.");
        model.start(goal,steps,vision);controlEpoch++;event("model.updated",modelStatus());
    }
    public void manualInput() {
        if (active!=null) { transition(active,Task.State.paused); world.releaseControls(); active=null; }
        if(armed && (world.tick()!=lastManualTick || controlEpoch!=lastManualEpoch)){lastManualTick=world.tick();controlEpoch++;lastManualEpoch=controlEpoch;model.cancel("Ручной ввод: цель остановлена.");event("control.manual_override",object());}
    }
    public void tick() {
        if (!world.actorId().equals(actorId) || !world.dimension().equals(dimension)) {
            ownerStop(); if(controller!=null) controller.close("actor_or_dimension_changed"); incoming.clear(); return;
        }
        if (world.tick()-lastObservation>=4) refresh();
        // Scan the bounded batch for stop/cancel before ordinary commands in that same batch.
        List<Incoming> batch=new ArrayList<>();
        for(int i=0;i<128;i++) { Incoming in=incoming.poll(); if(in==null) break; batch.add(in); }
        batch.sort(Comparator.comparingInt(in->in.disconnect || in.text.contains("\"control.stop\"") || in.text.contains("\"task.cancel\"") ? 0 : 1));
        for(Incoming in:batch) {
            if(in.disconnect) { if(in.peer==controller) { controlEpoch++;model.cancel("Windows-контроллер отключился.");world.releaseControls(); for(Task t:tasks.values()) if(!t.terminal() && t.state!=Task.State.paused) transition(t,Task.State.paused); active=null; controller=null; subscriptionTicks=0;subscriptionProfile="full"; visionPeriod=0; visionRequest=null; } }
            else handle(in.peer,in.text);
        }
        dedup.entrySet().removeIf(e->System.currentTimeMillis()-e.getValue().time>DEDUP_MS);
        if(armed && !stopped && controller!=null) runTask();
        if(subscriptionTicks>0 && controller!=null && world.tick()-lastSent>=subscriptionTicks) { controller.text(envelope("observe.snapshot",null,ObservationProfiles.apply(snapshot(),subscriptionProfile))); lastSent=world.tick(); }
        if(visionRequest!=null && world.tick()>visionDeadline) { event("vision.error",object("request_id",visionRequest.get("request_id"),"code","timeout")); visionRequest=null; }
        if(visionPeriod>0 && visionRequest==null && world.tick()-lastVision>=visionPeriod) { visionRequest=visionProfile.deepCopy(); visionRequest.addProperty("stream",true); visionDeadline=world.tick()+40; }
        if(world.tick()%100==0) { try { memory.flush(); } catch(IOException e) { event("memory.error",object("code","persistence_failed")); } }
    }
    private void refresh() {
        JsonObject next=world.snapshot(policy.radius,policy.telemetry);
        String hash=next.toString();
        if(!hash.equals(fingerprint)) { revision++; fingerprint=hash;JsonObject previous=observation;observation=next;detectChanges(previous,next); memory.observe(next,world.tick()); event("observe.changed",object("revision",revision)); }
        lastObservation=world.tick();
        observationTime=System.currentTimeMillis();
    }
    public JsonObject snapshot() {
        JsonObject s=observation.deepCopy();
        s.addProperty("observation_id",id()); s.addProperty("world_id",world.worldId()); s.addProperty("dimension",world.dimension());
        s.addProperty("actor_id",world.actorId()); s.addProperty("game_tick",lastObservation); s.addProperty("capture_time",observationTime); s.addProperty("age_ms",System.currentTimeMillis()-observationTime); s.addProperty("revision",revision);
        s.addProperty("control_epoch",controlEpoch);
        s.add("active_task",active==null ? JsonNull.INSTANCE : active.json()); return s;
    }
    private void detectChanges(JsonObject before,JsonObject after) {
        if(before.has("actor") && after.has("actor")) {
            JsonObject a=child(before,"actor"),b=child(after,"actor");
            if(a.has("health") && b.has("health") && a.get("health").getAsDouble()>0 && b.get("health").getAsDouble()<=0){controlEpoch++;model.cancel("Игрок погиб: цель остановлена.");}
            for(String field:List.of("health","food","effects","selected_slot"))if(a.has(field)&&b.has(field)&&!a.get(field).equals(b.get(field)))
                event(field.equals("health") && b.get(field).getAsDouble()<=0?"actor.death":"actor."+field,object("before",a.get(field),"after",b.get(field)));
        }
        if(before.has("inventory")&&after.has("inventory")&&!before.get("inventory").equals(after.get("inventory")))event("inventory.changed",object());
        if(before.has("entities")&&after.has("entities")) {
            Set<String> old=new HashSet<>(),now=new HashSet<>();
            for(JsonElement e:before.getAsJsonArray("entities"))old.add(string(e.getAsJsonObject(),"entity_id"));
            for(JsonElement e:after.getAsJsonArray("entities"))now.add(string(e.getAsJsonObject(),"entity_id"));
            for(String entity:now)if(!old.contains(entity))event("entity.observed",object("entity_id",entity));
            for(String entity:old)if(!now.contains(entity))event("entity.observation_lost",object("entity_id",entity,"death_confirmed",false));
        }
        if(before.has("context")&&after.has("context")) {
            JsonObject a=child(before,"context"),b=child(after,"context");
            if(a.has("menu_id")&&b.has("menu_id")&&!a.get("menu_id").equals(b.get("menu_id")))event("interface.changed",object("before",a.get("menu_id"),"after",b.get("menu_id")));
        }
    }
    private void handle(Peer peer,String text) {
        String request=null;
        try {
            JsonReader reader=new JsonReader(new StringReader(text));reader.setStrictness(Strictness.STRICT);reader.setNestingLimit(32);
            JsonElement parsed=JsonParser.parseReader(reader);
            if(reader.peek()!=JsonToken.END_DOCUMENT)throw new BridgeException("invalid_request","Trailing JSON content");
            if(!parsed.isJsonObject()) throw new BridgeException("invalid_request","Expected message object");
            JsonObject m=parsed.getAsJsonObject();
            if(!VERSION.equals(string(m,"protocol_version"))) throw new BridgeException("protocol_mismatch","Supported version: "+VERSION);
            string(m,"message_id"); String type=string(m,"type"); request=string(m,"request_id"); JsonObject data=child(m,"data");
            if(type.equals("session.hello")) {
                String mode=string(data,"actor_mode","existing_player");
                if(!mode.equals("existing_player")) throw new BridgeException("unsupported_capability","Only existing_player is implemented");
                peer.text(envelope("response",request,object("status","hello","session_id",sessionId,"authentication_required",true,"actor_mode","existing_player"))); return;
            }
            if(!sessionId.equals(string(m,"session_id"))) throw new BridgeException("session_mismatch","Perform session.hello; do not reuse commands across worlds");
            if(type.equals("session.authenticate")) {
                if(!MessageDigest.isEqual(policy.token.getBytes(StandardCharsets.UTF_8),string(data,"token").getBytes(StandardCharsets.UTF_8))) throw new BridgeException("permission_denied","Authentication failed");
                if(controller!=null && controller!=peer) throw new BridgeException("session_busy","One active controller is allowed");
                controller=peer; peer.text(envelope("response",request,object("status","authenticated","capabilities",capabilities()))); return;
            }
            if(controller!=peer) throw new BridgeException("permission_denied","Authenticate before using the bridge");
            Cached old=dedup.get(request);
            if(old!=null) {
                if(!old.type.equals(type) || !old.payload.equals(data.toString())) throw new BridgeException("request_id_conflict","request_id was used with different parameters");
                peer.text(old.reply.deepCopy()); return;
            }
            if(dedup.size()>=4096) throw new BridgeException("limit_exceeded","Deduplication window full; wait before sending new commands");
            if(!type.startsWith("session.") && m.has("actor_id") && !world.actorId().equals(string(m,"actor_id"))) throw new BridgeException("actor_mismatch","Wrong actor");
            if(m.has("expected_revision")) {
                refresh();
                if(!m.get("expected_revision").isJsonPrimitive() || !m.getAsJsonPrimitive("expected_revision").isNumber()
                        || m.get("expected_revision").getAsDouble()!=revision) throw new BridgeException("stale_revision","Refresh observation",true);
            }
            JsonObject result=dispatch(type,data,request,m);
            JsonObject response=envelope("response",request,result);
            dedup.put(request,new Cached(type,data.toString(),response,System.currentTimeMillis())); peer.text(response);
        } catch(BridgeException e) { peer.text(envelope("error",request,object("code",e.code,"message",e.getMessage(),"retryable",e.retryable,"revision",revision))); }
        catch(Exception e) { peer.text(envelope("error",request,object("code","invalid_request","message","Malformed request or unavailable context","retryable",false))); }
    }
    private JsonObject dispatch(String type,JsonObject d,String request,JsonObject message) {
        return switch(type) {
            case "session.capabilities" -> capabilities();
            case "session.ping" -> object("game_tick",world.tick(),"time",System.currentTimeMillis());
            case "session.resume" -> replay(d);
            case "observe.snapshot" -> {String profile=ObservationProfiles.validate(string(d,"profile","full"));refresh(); yield ObservationProfiles.apply(snapshot(),profile); }
            case "observe.subscribe" -> {String profile=ObservationProfiles.validate(string(d,"profile","full"));subscriptionTicks=integer(d,"interval_ticks",10,4,1200);subscriptionProfile=profile;eventSubscription=bool(d,"events",true); yield object("interval_ticks",subscriptionTicks,"events",eventSubscription,"profile",profile); }
            case "observe.unsubscribe" -> { subscriptionTicks=0; eventSubscription=false; yield object("status","unsubscribed"); }
            case "map.query", "memory.query", "map.nearest" -> {requireQueryCapability(type);yield type.equals("map.nearest")?memory.nearest(d,world.dimension(),world.tick()):memory.query(d,world.dimension(),world.tick());}
            case "memory.annotate" -> memory.annotate(d,world.dimension());
            case "model.query" -> modelQuery(d);
            case "task.submit" -> { requireActor(message); yield submit(string(d,"action"),child(d,"parameters"),d); }
            case "task.status" -> task(d).json();
            case "task.list" -> object("tasks",tasks.values().stream().map(Task::json).toList());
            case "task.cancel" -> { Task t=task(d); if(!t.terminal()) finish(t,Task.State.cancelled,"cancelled","Cancelled by controller",t.result); yield t.json(); }
            case "task.pause" -> { Task t=task(d); if(t.terminal()) throw new BridgeException("invalid_transition","Terminal task cannot pause"); transition(t,Task.State.paused); if(t==active) { active=null;world.releaseControls(); } yield t.json(); }
            case "task.resume" -> { requireArmed(); Task t=task(d); if(t.state!=Task.State.paused) throw new BridgeException("invalid_transition","Only paused tasks can resume"); t.context.addProperty("revalidate",true); transition(t,Task.State.queued); yield t.json(); }
            case "control.stop" -> { controlEpoch++;stopped=true;armed=false;model.cancel("Остановлено контроллером.");stopAll("control.stop"); event("control.stopped",object("source","controller")); yield object("status","stopped","owner_rearm_required",true); }
            case "model.status" -> modelStatus();
            case "model.claim" -> {requireArmed();model.requireGoal(d);yield model.claim(d);}
            case "model.report" -> model.report(d);
            case "vision.capture", "vision.subscribe" -> {
                if(!world.visionAvailable()) throw new BridgeException("unsupported_capability","Vision requires a rendering client");
                JsonObject profile=object("width",integer(d,"width",640,64,640),"height",integer(d,"height",360,64,360),"request_id",request);
                profile.addProperty("observation_profile",ObservationProfiles.validate(string(d,"observation_profile","full")));
                if(d.has("crop"))profile.add("crop",child(d,"crop").deepCopy());
                if(visionRequest!=null) throw new BridgeException("busy","Capture already pending",true);
                if(type.equals("vision.subscribe")) {visionPeriod=integer(d,"interval_ticks",10,4,1200);visionProfile=profile.deepCopy();visionProfile.remove("request_id");}
                visionRequest=profile; visionDeadline=world.tick()+40; yield object("status","accepted","request_id",request);
            }
            case "vision.unsubscribe" -> { visionPeriod=0;visionRequest=null;yield object("status","unsubscribed"); }
            case "route.plan", "craft.plan", "build.plan", "inventory.inspect" -> { requireCapability(type);yield world.query(type,d); }
            default -> { requireActor(message);requireControlEpoch(d); yield submit(type,d,object()); }
        };
    }
    private void requireActor(JsonObject m) { if(!actorId.equals(string(m,"actor_id"))) throw new BridgeException("actor_mismatch","actor_id required for actions"); }
    private void requireArmed() { if(!armed || stopped) throw new BridgeException("permission_denied","Owner must arm control in game (F8)"); }
    private void requireCapability(String action) {
        if(!world.capabilities().contains(action)) throw new BridgeException("unsupported_capability",action);
        if(!policy.allowedActions.isEmpty() && !policy.allowedActions.contains(action))
            throw new BridgeException("permission_denied","Action disabled by owner");
    }
    private Set<String> supportedQueries() {
        Set<String> supported=new TreeSet<>(Set.of("map.query","map.nearest","memory.query"));
        for(String operation:List.of("route.plan","craft.plan","build.plan","inventory.inspect"))
            if(world.capabilities().contains(operation))supported.add(operation);
        return supported;
    }
    private Set<String> availableQueries() {
        Set<String> available=new TreeSet<>(supportedQueries());
        if(!policy.allowedActions.isEmpty())available.retainAll(policy.allowedActions);
        return available;
    }
    private void requireQueryCapability(String operation) {
        if(!supportedQueries().contains(operation))throw new BridgeException("unsupported_capability",operation);
        if(!policy.allowedActions.isEmpty()&&!policy.allowedActions.contains(operation))
            throw new BridgeException("permission_denied","Query disabled by owner");
    }
    private JsonObject modelQuery(JsonObject data) {
        requireArmed();
        if(!data.has("expected_control_epoch"))throw new BridgeException("invalid_request","expected_control_epoch is required");
        requireControlEpoch(data);
        if(data.has("model_goal_id"))model.requireActive(string(data,"model_goal_id"));
        String operation=string(data,"operation");
        if(!availableQueries().contains(operation)) {
            if(supportedQueries().contains(operation))throw new BridgeException("permission_denied","Query disabled by owner");
            throw new BridgeException("unsupported_capability","Query is not advertised: "+operation);
        }
        JsonObject arguments=child(data,"arguments");
        return switch(operation) {
            case "map.query", "memory.query" -> memory.query(arguments,world.dimension(),world.tick());
            case "map.nearest" -> memory.nearest(arguments,world.dimension(),world.tick());
            default -> world.query(operation,arguments);
        };
    }
    private JsonObject taskSummary(Task task) {
        JsonObject result=summarizeResult(task.result),confirmed=summarizeConfirmed(task.confirmed);
        JsonObject summary=object("task_id",task.id,"action",task.action,"state",task.state.name(),"actions",task.actions,
                "max_actions",task.maxActions,"result",result,"confirmed_changes",confirmed);
        if(task.context.has("model_goal_id"))summary.add("model_goal_id",task.context.get("model_goal_id").deepCopy());
        return summary;
    }
    private static JsonObject summarizeResult(JsonObject source) {
        JsonObject summary=new JsonObject();
        for(String field:List.of("stage","waiting","confirmed_blocks","total","outcome")) {
            if(source!=null&&source.has(field)&&source.get(field).isJsonPrimitive())summary.add(field,source.get(field).deepCopy());
        }
        return summary;
    }
    private static JsonObject summarizeConfirmed(JsonObject source) {
        JsonObject summary=new JsonObject();
        addNonNegativeCounter(summary,"jumps",source,"jumps");
        addNonNegativeCounter(summary,"blocks",source,"blocks");
        addNonNegativeCounter(summary,"crafts_confirmed",source,"crafts_confirmed");
        addNonNegativeCounter(summary,"follow_ticks",source,"follow_ticks");
        addNonNegativeCounter(summary,"crossbow_loads",source,"crossbow_loads");
        addNonNegativeCounter(summary,"interactions",source,"interactions");
        addNonNegativeCounter(summary,"inventory_swaps",source,"inventory_swaps");
        addNonNegativeCounter(summary,"equips",source,"equips");
        addNonNegativeCounter(summary,"selected_slot",source,"selected_slot");
        addNonNegativeCounter(summary,"shots",source,"shots");
        addNonNegativeCounter(summary,"hits",source,"hits");
        if(source!=null&&source.has("damage")&&source.get("damage").isJsonPrimitive()&&source.getAsJsonPrimitive("damage").isNumber()) {
            double damage=source.get("damage").getAsDouble();if(Double.isFinite(damage)&&damage>=0)summary.addProperty("damage",damage);
        }
        addNestedCounter(summary,"drop_count",source,"drop","count");
        addNestedCounter(summary,"pickup_count",source,"pickup","count");
        addNestedCounter(summary,"transfer_count",source,"transfer","transferred");
        if(source!=null&&source.has("selected_item")&&source.get("selected_item").isJsonObject())
            addNonNegativeCounter(summary,"selected_slot",source.getAsJsonObject("selected_item"),"selected_slot");
        if(source!=null&&source.has("block_updates")&&source.get("block_updates").isJsonArray())
            putCounter(summary,"blocks",Math.max(summary.has("blocks")?summary.get("blocks").getAsInt():0,source.getAsJsonArray("block_updates").size()));
        return summary;
    }
    private static void addNestedCounter(JsonObject target,String key,JsonObject source,String objectKey,String countKey) {
        if(source!=null&&source.has(objectKey)&&source.get(objectKey).isJsonObject())
            addNonNegativeCounter(target,key,source.getAsJsonObject(objectKey),countKey);
    }
    private static void addNonNegativeCounter(JsonObject target,String key,JsonObject source,String sourceKey) {
        if(source==null||!source.has(sourceKey)||!source.get(sourceKey).isJsonPrimitive()||!source.getAsJsonPrimitive(sourceKey).isNumber())return;
        try {int value=source.get(sourceKey).getAsInt();if(value>=0)putCounter(target,key,value);}
        catch(RuntimeException ignored) { /* Ignore malformed or unconfirmed counters in the GUI summary. */ }
    }
    private static void putCounter(JsonObject target,String key,int value) {
        if(value>=0)target.addProperty(key,value);
    }
    private JsonObject submit(String action,JsonObject parameters,JsonObject options) {
        requireArmed();requireControlEpoch(options);
        if(options.has("model_goal_id"))model.requireActive(string(options,"model_goal_id"));
        requireCapability(action);world.validate(action,parameters);policy.check(action,parameters,world.dimension());
        if(tasks.size()>=256) {
            Set<String> required=new HashSet<>();for(Task waiting:tasks.values())if(!waiting.terminal())required.addAll(waiting.dependencies);
            tasks.entrySet().removeIf(e->e.getValue().terminal()&&!required.contains(e.getKey()));
            if(tasks.size()>=256)throw new BridgeException("limit_exceeded","Task limit reached");
        }
        Task t=new Task(action,parameters,integer(options,"priority",0,-100,100),world.tick()+integer(options,"timeout_ticks",1200,1,12000),integer(options,"max_actions",10000,1,100000));
        if(options.has("model_goal_id"))t.context.addProperty("model_goal_id",string(options,"model_goal_id"));
        if(options.has("depends_on")) {
            if(!options.get("depends_on").isJsonArray()||options.getAsJsonArray("depends_on").size()>32)throw new BridgeException("invalid_request","depends_on must contain at most 32 task IDs");
            List<String> dependencies=new ArrayList<>();
            for(JsonElement e:options.getAsJsonArray("depends_on")){String dependency=e.getAsString();if(!tasks.containsKey(dependency))throw new BridgeException("task_not_found","Unknown dependency");dependencies.add(dependency);}
            t.dependencies=List.copyOf(dependencies);
        }
        tasks.put(t.id,t);event("task.accepted",t.json());transition(t,Task.State.queued);return object("status","accepted","task_id",t.id);
    }
    private Task task(JsonObject d) { Task t=tasks.get(string(d,"task_id")); if(t==null) throw new BridgeException("task_not_found","Unknown or expired task"); return t; }
    private void runTask() {
        for(Task t:tasks.values()) if(!t.terminal() && world.tick()>=t.deadline) finish(t,Task.State.failed,"timeout","Deadline exceeded",t.result);
        for(Task t:tasks.values())if(!t.terminal() && t.dependencies.stream().anyMatch(d->!tasks.containsKey(d)||tasks.get(d).state==Task.State.failed||tasks.get(d).state==Task.State.cancelled))finish(t,Task.State.failed,"dependency_failed","A prerequisite task did not succeed",t.result);
        if(active==null) {
            active=tasks.values().stream().filter(t->t.state==Task.State.queued && t.dependencies.stream().allMatch(d->tasks.get(d).state==Task.State.succeeded)).max(Comparator.comparingInt(t->t.priority)).orElse(null);
            if(active!=null) transition(active,Task.State.running);
        }
        if(active==null) return;
        Task t=active;
        try {
            policy.check(t.action,t.parameters,world.dimension());
            if(t.actions>=t.maxActions && !world.awaitingConfirmation(t)) { finish(t,Task.State.failed,"action_limit","Action budget exhausted",t.result);return; }
            WorldAccess.Step step=world.step(t); t.result=step.result();
            if(step.done()) { refresh();finish(t,step.error()==null ? Task.State.succeeded : Task.State.failed,step.error(),step.detail(),step.result()); }
            else if(world.tick()-t.lastProgress>=10) { t.lastProgress=world.tick();event("task.progress",t.json()); }
        } catch(BridgeException e) { finish(t,Task.State.failed,e.code,e.getMessage(),t.result); }
        catch(Exception e) { finish(t,Task.State.failed,"executor_error",e.getClass().getSimpleName(),t.result); }
    }
    private void transition(Task t,Task.State state) { if(t.terminal()) return;t.state=state;event("task."+state.name(),t.json()); }
    private void finish(Task t,Task.State state,String error,String detail,JsonObject result) {
        if(t.terminal()) return;
        t.error=error;t.detail=detail;t.result=result;
        world.collectConfirmed(t);
        rememberConfirmedBlocks(t);
        if(t==active) {world.releaseControls();active=null;}
        transition(t,state);
        world.taskFinished(t);
    }
    private void rememberConfirmedBlocks(Task task) {
        if(!task.confirmed.has("block_updates")||!task.confirmed.get("block_updates").isJsonArray())return;
        for(JsonElement element:task.confirmed.getAsJsonArray("block_updates")) {
            if(element==null||!element.isJsonObject())continue;
            JsonObject update=element.getAsJsonObject();Long tick=confirmationTick(update);
            if(tick==null) {event("memory.error",object("code","invalid_block_confirmation"));continue;}
            JsonObject block=update.deepCopy();block.remove("tick");
            try {memory.confirmBlock(block,tick,task.action,task.id);}
            catch(RuntimeException e) {event("memory.error",object("code","invalid_block_confirmation"));}
        }
    }
    private static Long confirmationTick(JsonObject update) {
        if(!update.has("tick")||!update.get("tick").isJsonPrimitive()||!update.getAsJsonPrimitive("tick").isNumber())return null;
        try {return new java.math.BigDecimal(update.get("tick").getAsString()).longValueExact();}
        catch(NumberFormatException|ArithmeticException e) {return null;}
    }
    private void stopAll(String reason) { world.releaseControls();for(Task t:tasks.values()) if(!t.terminal()) finish(t,Task.State.cancelled,reason,"Control stopped",t.result);active=null; }
    public JsonObject capabilities() {
        Set<String> actions=new TreeSet<>(world.capabilities());
        if(!policy.allowedActions.isEmpty())actions.retainAll(policy.allowedActions);
        JsonObject features=object();for(String action:actions){Set<String> supported=world.actionFeatures(action);if(!supported.isEmpty())features.add(action,GSON.toJsonTree(new TreeSet<>(supported)));}
        return object("actions",actions,"action_features",features,"queries",availableQueries(),"vision",world.visionAvailable(),"formats",List.of("MBV1/png/float32-le"),"actor_id",world.actorId(),"profile",policy.publicProfile(),"armed",armed,
                "deduplication_window_ms",DEDUP_MS,"deduplication_capacity",4096,"event_history",512,"max_tasks",256,"vision_max_fps",5,"vision_max_resolution",List.of(640,360),"movement_modes",world.movementModes(),
                "observation_profiles",ObservationProfiles.NAMES,"decision_guards",List.of("control_epoch","model_goal_id"),"model_control",true);
    }
    private void requireControlEpoch(JsonObject data) {
        if(!data.has("expected_control_epoch"))return;
        double expected=number(data,"expected_control_epoch",-1);
        if(expected<0 || expected!=Math.rint(expected) || expected>9_007_199_254_740_991L)
            throw new BridgeException("invalid_request","Invalid expected_control_epoch");
        if(expected!=controlEpoch)throw new BridgeException("stale_control","Owner control changed while the model was thinking; refresh the observation",true);
    }
    private JsonObject envelope(String type,String request,JsonObject data) { return object("protocol_version",VERSION,"session_id",sessionId,"message_id",id(),"type",type,"request_id",request,"data",data); }
    public void event(String type,JsonObject data) {
        JsonObject e=envelope(type,null,data); e.addProperty("event_id",id());e.addProperty("sequence",++sequence);e.addProperty("game_tick",world.tick());e.addProperty("revision",revision);
        journal.addLast(e);while(journal.size()>512) journal.removeFirst();audit.accept(e.deepCopy());
        if(controller!=null && (eventSubscription || type.startsWith("task.") || type.startsWith("control.") || type.equals("model.updated") || type.equals("vision.error") || type.equals("actor.health") || type.equals("actor.death"))) controller.text(e);
    }
    private JsonObject replay(JsonObject d) {
        long after=(long)number(d,"after_sequence",0);
        boolean gap=after>sequence || (!journal.isEmpty() && after<journal.getFirst().get("sequence").getAsLong()-1);
        return object("history_gap",gap,"events",gap ? List.of() : journal.stream().filter(e->e.get("sequence").getAsLong()>after).toList(),"snapshot",snapshot(),"tasks",tasks.values().stream().map(Task::json).toList(),"last_sequence",sequence,"require_plan_revalidation",true);
    }
    public JsonObject takeVisionRequest() { JsonObject r=visionRequest;visionRequest=null;if(r!=null) lastVision=world.tick();return r; }
    public void publishVision(byte[] bytes) { if(controller!=null) controller.binary(bytes); }
    public void close() { ownerStop();if(controller!=null) controller.close("world_closed");try{memory.flush();}catch(IOException e){audit.accept(object("type","memory.error","code","persistence_failed"));} }
}
