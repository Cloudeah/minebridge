package dev.minebridge.core;

import com.google.gson.JsonObject;
import java.util.Set;
import java.util.List;

/** All methods are called on the game thread. An action may only report success after observation. */
public interface WorldAccess {
    String actorId();
    default String actorMode(){return "existing_player";}
    String worldId();
    String dimension();
    long tick();
    Set<String> capabilities();
    default Set<String> actionFeatures(String action) { return Set.of(); }
    JsonObject snapshot(int radius, boolean telemetry);
    default void validate(String action,JsonObject parameters) {}
    Step step(Task task);
    /** May only poll or release an already sent action; never send another mutation. */
    default boolean awaitingConfirmation(Task task) { return false; }
    void releaseControls();
    /** Collect already observed receipts before publishing a terminal result; sends no actions. */
    default void collectConfirmed(Task task) {}
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
