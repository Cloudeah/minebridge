package dev.minebridge.core;

import com.google.gson.*;
import java.util.UUID;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private Json() {}
    public static JsonObject object(Object... pairs) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) o.add((String) pairs[i], GSON.toJsonTree(pairs[i + 1]));
        return o;
    }
    public static String id() { return UUID.randomUUID().toString(); }
    public static String string(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isString())
            throw new BridgeException("invalid_request", "Expected string: " + key);
        String s = o.get(key).getAsString();
        if (s.isBlank() || s.length() > 4096) throw new BridgeException("invalid_request", "Invalid length: " + key);
        return s;
    }
    public static String string(JsonObject o, String key, String fallback) { return o.has(key) ? string(o, key) : fallback; }
    public static JsonObject child(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonObject()) throw new BridgeException("invalid_request", "Expected object: " + key);
        return o.getAsJsonObject(key);
    }
    public static double number(JsonObject o, String key, double fallback) {
        if (!o.has(key)) return fallback;
        if (!o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isNumber())
            throw new BridgeException("invalid_request", "Expected number: " + key);
        double n = o.get(key).getAsDouble();
        if (!Double.isFinite(n)) throw new BridgeException("invalid_request", "Non-finite number: " + key);
        return n;
    }
    public static int integer(JsonObject o, String key, int fallback, int min, int max) {
        double d = number(o, key, fallback);
        if (d != Math.rint(d) || d < min || d > max) throw new BridgeException("invalid_request", "Out of range: " + key);
        return (int) d;
    }
    public static boolean bool(JsonObject o, String key, boolean fallback) {
        if (!o.has(key)) return fallback;
        if (!o.get(key).isJsonPrimitive() || !o.getAsJsonPrimitive(key).isBoolean()) throw new BridgeException("invalid_request", "Expected boolean: " + key);
        return o.get(key).getAsBoolean();
    }
}
