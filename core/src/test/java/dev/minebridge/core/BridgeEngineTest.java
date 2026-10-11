package dev.minebridge.core;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static dev.minebridge.core.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class BridgeEngineTest {
    @TempDir Path directory;
    FakeWorld world;OwnerPolicy owner;BridgeEngine engine;Peer peer;
    static final class FakeWorld implements WorldAccess {
        long tick;int steps,releases,queries;double health=20;boolean done,confirmationOnly;String dim="minecraft:overworld";JsonArray confirmedBlockUpdates=new JsonArray();JsonObject finalConfirmed=object();
        public String actorId(){return "player";} public String worldId(){return "world";} public String dimension(){return dim;} public long tick(){return tick;}
        public Set<String> capabilities(){return Set.of("move.to","block.break","inventory.inspect");}
        public JsonObject query(String operation,JsonObject parameters){queries++;return object("inventory",List.of());}
        public JsonObject snapshot(int radius,boolean telemetry){return object("actor",object("health",health),"blocks",List.of(),"entities",List.of());}
        public Step step(Task t){if(confirmationOnly && t.actions>0)return done?Step.success(object("steps",steps)):Step.waiting(object("steps",steps));steps++;t.actions++;t.confirmed.addProperty("steps",steps);if(!confirmedBlockUpdates.isEmpty())t.confirmed.add("block_updates",confirmedBlockUpdates.deepCopy());return done?Step.success(object("steps",steps)):Step.waiting(object("steps",steps));}
        public boolean awaitingConfirmation(Task t){return confirmationOnly && t.actions>0;}
        public void releaseControls(){releases++;}
        public void collectConfirmed(Task t){for(var entry:finalConfirmed.entrySet())t.confirmed.add(entry.getKey(),entry.getValue().deepCopy());}
    }
    static final class Peer implements BridgeEngine.Peer {
        final List<JsonObject> messages=new ArrayList<>();String closed;
        public void text(JsonObject m){messages.add(m.deepCopy());}public void binary(byte[] bytes){}public void close(String why){closed=why;}
        JsonObject reply(String id){return messages.stream().filter(m->m.has("request_id")&&!m.get("request_id").isJsonNull()&&m.get("request_id").getAsString().equals(id)).reduce((a,b)->b).orElseThrow().getAsJsonObject("data");}
    }
    @BeforeEach void setup() throws Exception {world=new FakeWorld();owner=new OwnerPolicy();owner.token="owner-test-token";engine=new BridgeEngine(world,owner,new WorldMemory(directory,"world"),e->{});peer=new Peer();}
    private JsonObject message(String type,String request,JsonObject data){return object("protocol_version","1.0","session_id",engine.sessionId,"message_id",id(),"request_id",request,"actor_id","player","type",type,"data",data);}
    private JsonObject send(String type,String request,JsonObject data){engine.receive(peer,message(type,request,data).toString());world.tick++;engine.tick();return peer.reply(request);}
    private void auth(){send("session.authenticate","auth",object("token",owner.token));engine.ownerArm();}
    private String submit(String request){return send("task.submit",request,object("action","move.to","parameters",object("target",object("x",2,"y",0,"z",0)))).get("task_id").getAsString();}
    @Test void unauthenticatedCannotReadOrAct(){assertEquals("permission_denied",send("observe.snapshot","a",object()).get("code").getAsString());assertEquals(0,world.steps);}
    @Test void wrongTokenDoesNotAcquireSession(){assertEquals("permission_denied",send("session.authenticate","a",object("token","wrong")).get("code").getAsString());assertEquals("permission_denied",send("session.capabilities","b",object()).get("code").getAsString());}
    @Test void helloRejectsCompanion(){assertEquals("unsupported_capability",send("session.hello","a",object("actor_mode","companion")).get("code").getAsString());}
    @Test void ownerMustArmBeforeMutation(){send("session.authenticate","auth",object("token",owner.token));assertEquals("permission_denied",send("move.to","a",object("target",object("x",0,"y",0,"z",0))).get("code").getAsString());}
    @Test void unsupportedIsRejectedBeforeAcceptance(){auth();assertEquals("unsupported_capability",send("fly.to","a",object()).get("code").getAsString());assertEquals(0,world.steps);}
    @Test void capabilitiesExposeOnlySupportedActionsAllowedByOwner(){
        owner.allowedActions.addAll(Set.of("move.to","fly.to"));auth();
        assertEquals(List.of("move.to"),engine.capabilities().getAsJsonArray("actions").asList().stream().map(JsonElement::getAsString).toList());
        assertEquals("permission_denied",send("block.break","blocked",object()).get("code").getAsString());
        assertEquals(0,world.steps);
        assertEquals("unsupported_capability",send("fly.to","unsupported",object()).get("code").getAsString());
        submit("allowed");assertEquals(1,world.steps);
    }
    @Test void emptyOwnerAllowlistPreservesAllSupportedCapabilities(){
        auth();JsonArray actions=send("session.capabilities","caps",object()).getAsJsonArray("actions");
        assertEquals(Set.of("move.to","block.break","inventory.inspect"),new HashSet<>(actions.asList().stream().map(JsonElement::getAsString).toList()));
    }
    @Test void ownerAllowlistAlsoAppliesToAdvertisedQueries(){
        owner.allowedActions.add("move.to");auth();
        assertEquals("permission_denied",send("inventory.inspect","blocked-query",object()).get("code").getAsString());
        assertEquals(0,world.queries);
        owner.allowedActions.add("inventory.inspect");
        assertTrue(send("session.capabilities","refreshed-caps",object()).getAsJsonArray("actions").toString().contains("inventory.inspect"));
        assertTrue(send("inventory.inspect","allowed-query",object()).has("inventory"));assertEquals(1,world.queries);
    }
    @Test void acceptedIsNotSuccess(){auth();String task=submit("a");assertEquals("running",send("task.status","s",object("task_id",task)).get("state").getAsString());}
    @Test void terminalEventCollectsPendingServerReceiptsBeforePublishingAndDoesNotChangeLater(){
        auth();String task=submit("a");world.finalConfirmed=object("hits",1,"damage",3.5);
        JsonObject cancelled=send("task.cancel","cancel",object("task_id",task));
        assertEquals(1,cancelled.getAsJsonObject("confirmed_changes").get("hits").getAsInt());
        JsonObject event=peer.messages.stream().filter(m->m.get("type").getAsString().equals("task.cancelled")).reduce((a,b)->b).orElseThrow();
        assertEquals(3.5,event.getAsJsonObject("data").getAsJsonObject("confirmed_changes").get("damage").getAsDouble());
        int steps=world.steps;world.finalConfirmed.addProperty("hits",2);
        JsonObject later=send("task.status","later",object("task_id",task));
        assertEquals(1,later.getAsJsonObject("confirmed_changes").get("hits").getAsInt());assertEquals(steps,world.steps);
    }
    @Test void duplicateSubmitReturnsSameTask(){auth();String first=submit("a"),second=submit("a");assertEquals(first,second);assertEquals(1,send("task.list","list",object()).getAsJsonArray("tasks").size());}
    @Test void duplicateWithDifferentParametersIsRejected(){auth();submit("a");assertEquals("request_id_conflict",send("move.to","a",object("target",object("x",9,"y",0,"z",0))).get("code").getAsString());}
    @Test void staleRevisionDoesNotExecute(){auth();JsonObject m=message("block.break","break",object());m.addProperty("expected_revision",-1);engine.receive(peer,m.toString());engine.tick();assertEquals("stale_revision",peer.reply("break").get("code").getAsString());assertEquals(0,world.steps);}
    @Test void cancellationKeepsConfirmedChangesAndStopsSteps(){auth();String task=submit("a");int before=world.steps;JsonObject cancelled=send("task.cancel","c",object("task_id",task));assertEquals("cancelled",cancelled.get("state").getAsString());assertEquals(before,cancelled.getAsJsonObject("confirmed_changes").get("steps").getAsInt());world.tick++;engine.tick();assertEquals(before,world.steps);assertTrue(world.releases>0);}
    @Test void terminalResultIsPublishedOnce(){auth();world.done=true;String task=submit("a");send("task.cancel","c",object("task_id",task));long terminal=peer.messages.stream().filter(m->m.get("type").getAsString().equals("task.succeeded")).count();assertEquals(1,terminal);assertEquals("succeeded",send("task.status","s",object("task_id",task)).get("state").getAsString());}
    @Test void emergencyStopRequiresOwnerRearm(){auth();submit("a");send("control.stop","stop",object());assertFalse(engine.armed());assertEquals("permission_denied",send("move.to","new",object()).get("code").getAsString());engine.ownerArm();assertTrue(engine.armed());}
    @Test void disconnectPausesAndDoesNotResumeOnAuthentication(){auth();String task=submit("a");engine.disconnected(peer);world.tick++;engine.tick();int steps=world.steps;send("session.authenticate","again",object("token",owner.token));assertEquals(steps,world.steps);assertEquals("paused",send("task.status","s",object("task_id",task)).get("state").getAsString());send("task.resume","r",object("task_id",task));assertTrue(world.steps>steps);}
    @Test void manualInputPauses(){auth();String task=submit("a");engine.manualInput();int steps=world.steps;world.tick++;engine.tick();assertEquals(steps,world.steps);assertEquals("paused",send("task.status","s",object("task_id",task)).get("state").getAsString());}
    @Test void timeoutIsDistinctFromCancellation(){auth();String task=send("task.submit","a",object("action","move.to","parameters",object(),"timeout_ticks",1)).get("task_id").getAsString();world.tick+=2;engine.tick();JsonObject result=send("task.status","s",object("task_id",task));assertEquals("failed",result.get("state").getAsString());assertEquals("timeout",result.get("error").getAsString());}
    @Test void actionBudgetStopsExecution(){auth();String task=send("task.submit","a",object("action","move.to","parameters",object(),"max_actions",1)).get("task_id").getAsString();world.tick++;engine.tick();assertEquals(1,world.steps);assertEquals("action_limit",send("task.status","s",object("task_id",task)).get("error").getAsString());}
    @Test void exactActionBudgetAllowsConfirmationWithoutAnotherMutation(){auth();world.confirmationOnly=true;String task=send("task.submit","a",object("action","move.to","parameters",object(),"max_actions",1)).get("task_id").getAsString();world.tick++;engine.tick();assertEquals(1,world.steps);world.done=true;JsonObject result=send("task.status","s",object("task_id",task));world.tick++;engine.tick();result=send("task.status","final",object("task_id",task));assertEquals("succeeded",result.get("state").getAsString());assertEquals(1,world.steps);assertEquals(1,result.get("actions").getAsInt());}
    @Test void confirmationWaitStillHasADeadlineAndOwnerCancellation(){auth();world.confirmationOnly=true;String task=send("task.submit","a",object("action","move.to","parameters",object(),"max_actions",1,"timeout_ticks",3)).get("task_id").getAsString();world.tick+=4;engine.tick();assertEquals("timeout",send("task.status","s",object("task_id",task)).get("error").getAsString());assertEquals(1,world.steps);String next=submit("b");engine.ownerStop();assertEquals("cancelled",send("task.status","cancelled",object("task_id",next)).get("state").getAsString());assertEquals(2,world.steps);}
    @Test void ownerAreaRejectsOutOfBounds(){auth();owner.areas.add(new OwnerPolicy.Area(world.dimension(),0,0,0,1,1,1));assertEquals("permission_denied",send("move.to","a",object("target",object("x",2,"y",0,"z",0))).get("code").getAsString());}
    @Test void replayDetectsHistoryGap(){auth();for(int i=0;i<600;i++)engine.event("test",object());JsonObject result=send("session.resume","r",object("after_sequence",0));assertTrue(result.get("history_gap").getAsBoolean());assertTrue(result.has("snapshot"));assertEquals(0,result.getAsJsonArray("events").size());}
    @Test void replayRetainsTerminalResults(){auth();world.done=true;String task=submit("a");JsonObject result=send("session.resume","r",object("after_sequence",0));assertFalse(result.get("history_gap").getAsBoolean());assertEquals(task,result.getAsJsonArray("tasks").get(0).getAsJsonObject().get("task_id").getAsString());}
    @Test void actorDimensionChangeStopsControl(){auth();submit("a");world.dim="minecraft:the_nether";engine.tick();assertFalse(engine.armed());assertEquals("actor_or_dimension_changed",peer.closed);}
    @Test void healthChangesAreHighPriorityEvents(){auth();world.health=0;world.tick+=4;engine.tick();assertTrue(peer.messages.stream().anyMatch(m->m.get("type").getAsString().equals("actor.death")));}
    @Test void malformedInputDoesNotCrashLoop(){engine.receive(peer,"[]");engine.receive(peer,"{");engine.tick();assertEquals(2,peer.messages.size());assertEquals("error",peer.messages.getFirst().get("type").getAsString());}
    @Test void dependentTaskWaitsForPrerequisite(){auth();String first=submit("first");String second=send("task.submit","second",object("action","move.to","parameters",object(),"depends_on",List.of(first))).get("task_id").getAsString();assertEquals("queued",send("task.status","status",object("task_id",second)).get("state").getAsString());world.done=true;world.tick++;engine.tick();world.tick++;engine.tick();assertEquals("succeeded",send("task.status","finished",object("task_id",second)).get("state").getAsString());}
    @Test void cancelledPrerequisiteFailsDependentWithoutExecutingIt(){auth();String first=submit("first");String second=send("task.submit","second",object("action","move.to","parameters",object(),"depends_on",List.of(first))).get("task_id").getAsString();int steps=world.steps;send("task.cancel","cancel",object("task_id",first));assertEquals(steps,world.steps);assertEquals("dependency_failed",send("task.status","status",object("task_id",second)).get("error").getAsString());}
    @Test void deeplyNestedMessageIsRejectedWithoutOverflow(){engine.receive(peer,"[".repeat(1000)+"0"+"]".repeat(1000));engine.tick();assertEquals("error",peer.messages.getFirst().get("type").getAsString());}
    @Test void manualInputWhileModelThinksInvalidatesDecisionWithoutActiveTask(){auth();long epoch=send("observe.snapshot","snapshot",object()).get("control_epoch").getAsLong();engine.manualInput();assertEquals("stale_control",send("task.submit","late",object("action","move.to","parameters",object(),"expected_control_epoch",epoch)).get("code").getAsString());assertEquals(0,world.steps);}
    @Test void stopAndRearmCannotAuthorizeAnOldDecision(){auth();long epoch=engine.snapshot().get("control_epoch").getAsLong();engine.ownerStop();engine.ownerArm();assertEquals("stale_control",send("task.submit","late",object("action","move.to","parameters",object(),"expected_control_epoch",epoch)).get("code").getAsString());assertEquals(0,world.steps);}
    @Test void unchangedControlAllowsSlowInferenceAcrossGameTicks(){auth();long epoch=engine.snapshot().get("control_epoch").getAsLong();world.tick+=1000;engine.tick();assertEquals("accepted",send("task.submit","fresh",object("action","move.to","parameters",object(),"expected_control_epoch",epoch)).get("status").getAsString());assertEquals(1,world.steps);}
    @Test void malformedControlEpochCannotExecute(){auth();for(var epoch:List.of(-1,0.5,"1")){assertEquals("invalid_request",send("task.submit",id(),object("action","move.to","parameters",object(),"expected_control_epoch",epoch)).get("code").getAsString());}assertEquals(0,world.steps);}
    @Test void compactProfilesAreAdvertisedAndValidated(){auth();assertTrue(engine.capabilities().getAsJsonArray("observation_profiles").toString().contains("compact"));assertEquals("compact",send("observe.snapshot","compact",object("profile","compact")).get("observation_profile").getAsString());assertEquals("unsupported_capability",send("observe.snapshot","bad",object("profile","huge")).get("code").getAsString());}
    @Test void modelGoalsRequireOwnerPermissionAndCannotBeCreatedOverNetwork(){assertThrows(BridgeException.class,()->engine.ownerModelStart("goal",1,false));assertEquals("permission_denied",send("model.status","noauth",object()).get("code").getAsString());auth();assertEquals("unsupported_capability",send("model.start","remote",object("goal","goal")).get("code").getAsString());}
    @Test void ownerStopRejectsGoalTasksAndLateReports(){auth();engine.ownerModelStart("goal",1,false);String goal=engine.modelStatus().get("goal_id").getAsString();send("model.claim","claim",object("goal_id",goal));engine.ownerStop();engine.ownerArm();assertEquals("cancelled",engine.modelStatus().get("state").getAsString());assertEquals("stale_goal",send("task.submit","late",object("action","move.to","parameters",object(),"model_goal_id",goal)).get("code").getAsString());assertEquals("stale_goal",send("model.report","late-report",object("goal_id",goal,"state","completed")).get("code").getAsString());assertEquals(0,world.steps);}
    @Test void aNewOwnerGoalStillDetectsManualInputInTheSameGameTick(){auth();engine.manualInput();engine.ownerModelStart("goal",1,false);engine.manualInput();assertEquals("cancelled",engine.modelStatus().get("state").getAsString());}
    @Test void queryCapabilitiesAreSeparateAndOwnerFiltered(){
        auth();JsonArray queries=engine.capabilities().getAsJsonArray("queries");assertTrue(queries.toString().contains("map.query"));assertTrue(queries.toString().contains("map.nearest"));assertTrue(queries.toString().contains("memory.query"));assertTrue(queries.toString().contains("inventory.inspect"));assertFalse(queries.toString().contains("memory.annotate"));
        owner.allowedActions.clear();owner.allowedActions.add("memory.query");queries=engine.capabilities().getAsJsonArray("queries");assertEquals(List.of("memory.query"),queries.asList().stream().map(JsonElement::getAsString).toList());
    }
    @Test void directMemoryQueriesWorkWithoutArmingButRespectTheOperationAllowlist(){
        owner.allowedActions.add("memory.query");send("session.authenticate","auth",object("token",owner.token));assertFalse(engine.armed());
        JsonObject result=send("memory.query","read",object("x",0,"y",0,"z",0));assertEquals("world",result.get("world_id").getAsString());
        owner.allowedActions.clear();owner.allowedActions.add("map.nearest");
        assertEquals("permission_denied",send("memory.query","blocked",object("x",0,"y",0,"z",0)).get("code").getAsString());
    }
    @Test void modelQueryRequiresArmingEpochCapabilityAndExactOperationPermission(){
        auth();long epoch=engine.snapshot().get("control_epoch").getAsLong();JsonObject args=object();
        JsonObject result=send("model.query","allowed",object("operation","inventory.inspect","arguments",args,"expected_control_epoch",epoch));assertTrue(result.has("inventory"));assertEquals(1,world.queries);
        assertEquals("unsupported_capability",send("model.query","not-advertised",object("operation","memory.annotate","arguments",args,"expected_control_epoch",engine.snapshot().get("control_epoch").getAsLong())).get("code").getAsString());
        owner.allowedActions.clear();owner.allowedActions.add("model.query");
        assertEquals("permission_denied",send("model.query","wrong-allowlist",object("operation","memory.query","arguments",object("x",0,"y",0,"z",0),"expected_control_epoch",engine.snapshot().get("control_epoch").getAsLong())).get("code").getAsString());
    }
    @Test void modelQueryChecksControlEpochAndOptionalActiveGoal(){
        auth();long epoch=engine.snapshot().get("control_epoch").getAsLong();engine.manualInput();
        assertEquals("stale_control",send("model.query","stale",object("operation","memory.query","arguments",object("x",0,"y",0,"z",0),"expected_control_epoch",epoch)).get("code").getAsString());
        engine.ownerStop();long fresh=engine.snapshot().get("control_epoch").getAsLong();assertEquals("permission_denied",send("model.query","unarmed",object("operation","memory.query","arguments",object("x",0,"y",0,"z",0),"expected_control_epoch",fresh)).get("code").getAsString());
        engine.ownerArm();long rearmed=engine.snapshot().get("control_epoch").getAsLong();
        assertEquals("stale_goal",send("model.query","stale-goal",object("operation","memory.query","arguments",object("x",0,"y",0,"z",0),"expected_control_epoch",rearmed,"model_goal_id","old-goal")).get("code").getAsString());
    }
    @Test void modelStatusSummarizesTasksAndTerminalBlockUpdatesUpdateMemory() throws Exception {
        auth();engine.ownerModelStart("inspect and break",2,false);String goal=engine.modelStatus().get("goal_id").getAsString();send("model.claim","claim",object("goal_id",goal));
        world.confirmedBlockUpdates.add(object("dimension",world.dimension(),"x",3,"y",4,"z",5,"type","minecraft:air","properties",object(),"tick",44));
        String task=send("task.submit","partial",object("action","block.break","parameters",object("target",object("x",3,"y",4,"z",5)),"model_goal_id",goal)).get("task_id").getAsString();
        JsonObject active=engine.modelStatus().getAsJsonObject("active_task");assertEquals(task,active.get("task_id").getAsString());assertEquals(goal,active.get("model_goal_id").getAsString());
        assertFalse(active.getAsJsonObject("confirmed_changes").has("block_updates"));assertEquals(1,active.getAsJsonObject("confirmed_changes").get("blocks").getAsInt());
        send("task.cancel","cancel-partial",object("task_id",task));JsonObject status=engine.modelStatus();JsonObject recent=status.getAsJsonObject("recent_task");
        assertEquals("cancelled",recent.get("state").getAsString());assertFalse(recent.getAsJsonObject("confirmed_changes").has("block_updates"));assertEquals(1,recent.getAsJsonObject("confirmed_changes").get("blocks").getAsInt());
        JsonObject fact=send("memory.query","air-check",object("x",3,"y",4,"z",5)).getAsJsonArray("blocks").get(0).getAsJsonObject();
        assertEquals("minecraft:air",fact.get("type").getAsString());assertEquals("block.break",fact.get("confirmed_action").getAsString());assertEquals(44,fact.get("last_seen_tick").getAsLong());
    }
    @Test void ownerGoalSummaryPreservesCombatCountersWithoutPublishingDetailedReceipts(){
        auth();engine.ownerModelStart("shoot once",2,false);String goal=engine.modelStatus().get("goal_id").getAsString();send("model.claim","claim",object("goal_id",goal));
        String task=send("task.submit","shot",object("action","move.to","parameters",object(),"model_goal_id",goal)).get("task_id").getAsString();
        world.finalConfirmed=object("shots",1,"hits",1,"damage",3.5,"combat",object("projectile_id",id(),"private_unbounded_payload",List.of(1,2,3)));
        send("task.cancel","cancel",object("task_id",task));
        JsonObject summary=engine.modelStatus().getAsJsonObject("recent_task").getAsJsonObject("confirmed_changes");
        assertEquals(1,summary.get("shots").getAsInt());assertEquals(1,summary.get("hits").getAsInt());assertEquals(3.5,summary.get("damage").getAsDouble());assertFalse(summary.has("combat"));
    }
}
