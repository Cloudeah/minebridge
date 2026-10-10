package dev.minebridge.core;

import com.google.gson.JsonObject;
import java.util.Set;
import java.util.List;

/** All methods are called on the game thread. An action may only report success after observation. */
public interface WorldAccess {
    String actorId();
    String worldId();
    String dimension();
    long tick();
    Set<String> capabilities();
    JsonObject snapshot(int radius, boolean telemetry);
    default void validate(String action,JsonObject parameters) {}
    Step step(Task task);
    void releaseControls();
    default void taskFinished(Task task) {}
    default List<String> movementModes() { return List.of(); }
    default JsonObject query(String operation, JsonObject data) { throw new BridgeException("unsupported_capability", operation); }
    default boolean visionAvailable() { return false; }
    record Step(boolean done, String error, String detail, JsonObject result) {
        public static Step waiting(JsonObject progress) { return new Step(false, null, null, progress); }
        public static Step success(JsonObject result) { return new Step(true, null, null, result); }
        public static Step failure(String code, String detail, JsonObject result) { return new Step(true, code, detail, result); }
    }
}
