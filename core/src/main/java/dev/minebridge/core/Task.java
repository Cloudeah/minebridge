package dev.minebridge.core;

import com.google.gson.JsonObject;
import java.util.List;
import static dev.minebridge.core.Json.*;

public final class Task {
    public enum State { accepted, queued, running, paused, succeeded, failed, cancelled }
    public final String id = Json.id();
    public final String action;
    public final JsonObject parameters;
    public final int priority;
    public final long deadline;
    public final int maxActions;
    public final JsonObject context = new JsonObject();
    public final JsonObject confirmed = new JsonObject();
    public List<String> dependencies=List.of();
    public State state = State.accepted;
    public String error;
    public String detail;
    public int actions;
    public long lastProgress = -100;
    public JsonObject result = new JsonObject();
    public Task(String action, JsonObject parameters, int priority, long deadline, int maxActions) {
        this.action = action; this.parameters = parameters.deepCopy(); this.priority = priority;
        this.deadline = deadline; this.maxActions = maxActions;
    }
    public boolean terminal() { return state == State.succeeded || state == State.failed || state == State.cancelled; }
    public JsonObject json() {
        return object("task_id", id, "action", action, "state", state.name(), "priority", priority,"depends_on",dependencies,
                "deadline_tick", deadline, "actions", actions, "result", result, "confirmed_changes", confirmed,
                "error", error, "detail", detail);
    }
}
