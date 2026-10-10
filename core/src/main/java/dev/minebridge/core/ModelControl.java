package dev.minebridge.core;

import com.google.gson.JsonObject;
import java.util.Set;
import static dev.minebridge.core.Json.*;

/** Owner-created goals; external controllers may claim and report, but never create goals. */
public final class ModelControl {
    private static final Set<String> ACTIVE=Set.of("queued","thinking","executing");
    private JsonObject status=object("state","idle","step",0,"detail","");
    public JsonObject status(){return status.deepCopy();}
    public boolean active(){return ACTIVE.contains(string(status,"state"));}
    public void start(String goal,int steps,boolean vision) {
        if(active())throw new BridgeException("busy","Сначала останови текущую цель.");
        if(goal==null || goal.isBlank() || goal.length()>2000)throw new BridgeException("invalid_request","Цель должна содержать 1–2000 символов.");
        if(steps<1 || steps>30)throw new BridgeException("invalid_request","Число шагов: 1–30.");
        status=object("goal_id",id(),"goal",goal.strip(),"steps",steps,"vision",vision,"state","queued","step",0,
                "detail","Ждём Windows-контроллер и ячейку решений Colab.");
    }
    public JsonObject claim(JsonObject request) {
        requireGoal(request);
        if(!string(status,"state").equals("queued"))throw new BridgeException("busy","Goal already claimed or ended",true);
        status.addProperty("state","thinking");status.addProperty("detail","Контроллер принял цель.");return status();
    }
    public JsonObject report(JsonObject request) {
        requireGoal(request);
        if(!active() || string(status,"state").equals("queued"))throw new BridgeException("stale_goal","Goal is not running; late reports are ignored",true);
        String state=string(request,"state");
        if(!Set.of("thinking","executing","completed","waiting","failed").contains(state))throw new BridgeException("invalid_request","Unknown model state");
        int step=integer(request,"step",0,0,status.get("steps").getAsInt());
        String detail=string(request,"detail","Нет пояснения.");
        if(detail.length()>1000)throw new BridgeException("invalid_request","Model detail too long");
        status.addProperty("state",state);status.addProperty("step",step);status.addProperty("detail",detail);return status();
    }
    public void requireGoal(JsonObject request) {
        if(!status.has("goal_id") || !string(request,"goal_id").equals(string(status,"goal_id")))
            throw new BridgeException("stale_goal","Refresh the owner goal",true);
    }
    public void requireActive(String goal) {
        requireGoal(object("goal_id",goal));
        if(!active() || string(status,"state").equals("queued"))throw new BridgeException("stale_goal","Goal is not running",true);
    }
    public void cancel(String detail) {
        if(active()){status.addProperty("state","cancelled");status.addProperty("detail",detail);}
    }
}
