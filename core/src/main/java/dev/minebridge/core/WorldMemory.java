package dev.minebridge.core;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static dev.minebridge.core.Json.*;

/** Stores observed and server-confirmed block facts separately from external annotations. */
public final class WorldMemory {
    private static final int MAX_BLOCKS = 20_000;
    private static final int MAX_NOTES = 2_000;
    private static final String UNKNOWN_DESCRIPTION = "Unrecorded cells have no inferred state or traversability";

    private final Path file;
    private final String worldId;
    private final LinkedHashMap<String, JsonObject> blocks = new LinkedHashMap<>();
    private final Set<String> refreshedCells = new HashSet<>();
    private final List<JsonObject> notes = new ArrayList<>();
    private boolean dirty;

    public WorldMemory(Path directory, String worldId) throws IOException {
        if (worldId == null || worldId.isBlank()) throw new IllegalArgumentException("worldId must not be blank");
        Files.createDirectories(directory);
        file = directory.resolve(UUID.nameUUIDFromBytes(worldId.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".json");
        this.worldId = worldId;
        if (Files.exists(file)) load();
    }

    private void load() throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(file));
        } catch (RuntimeException e) {
            throw new IOException("Invalid world memory file", e);
        }
        if (!parsed.isJsonObject()) throw new IOException("Invalid world memory root");
        JsonObject root = parsed.getAsJsonObject();
        if (!root.has("world_id") || !root.get("world_id").isJsonPrimitive()
                || !root.getAsJsonPrimitive("world_id").isString()
                || !worldId.equals(root.get("world_id").getAsString())) {
            throw new IOException("World memory identity mismatch");
        }

        // Older files contain only world_id, blocks, and notes. Missing collections are empty.
        loadBlocks(root.get("blocks"));
        loadNotes(root.get("notes"));
        trimBlocks();
        if (notes.size() > MAX_NOTES) notes.subList(0, notes.size() - MAX_NOTES).clear();
    }

    private void loadBlocks(JsonElement stored) throws IOException {
        if (stored == null || stored.isJsonNull()) return;
        if (!stored.isJsonArray()) throw new IOException("Invalid world memory blocks");
        for (JsonElement element : stored.getAsJsonArray()) {
            if (!isFactBlock(element)) continue;
            JsonObject block = element.getAsJsonObject().deepCopy();
            blocks.remove(key(block));
            blocks.put(key(block), block);
        }
    }

    private void loadNotes(JsonElement stored) throws IOException {
        if (stored == null || stored.isJsonNull()) return;
        if (!stored.isJsonArray()) throw new IOException("Invalid world memory notes");
        for (JsonElement element : stored.getAsJsonArray()) {
            if (element != null && element.isJsonObject()) notes.add(element.getAsJsonObject().deepCopy());
        }
    }

    private static String key(JsonObject block) {
        return string(block, "dimension") + "\u0000" + coordinateKey(block, "x") + ","
                + coordinateKey(block, "y") + "," + coordinateKey(block, "z");
    }

    private static String coordinateKey(JsonObject block, String field) {
        double value = finiteCoordinate(block, field);
        return Double.toString(value == 0 ? 0 : value);
    }

    /** Records an engine snapshot while preserving the block's original observation source. */
    public void observe(JsonObject snapshot, long tick) {
        if (snapshot == null || !snapshot.has("blocks") || !snapshot.get("blocks").isJsonArray()) return;
        long now = System.currentTimeMillis();
        for (JsonElement element : snapshot.getAsJsonArray("blocks")) {
            if (!isFactBlock(element)) continue;
            JsonObject block = element.getAsJsonObject().deepCopy();
            JsonElement originalSource = block.has("source") ? block.get("source").deepCopy() : JsonNull.INSTANCE;
            block.add("observation_source", originalSource);
            block.addProperty("provenance", "engine_observation");
            block.addProperty("last_seen_tick", tick);
            block.addProperty("last_seen_time", now);
            putBlock(block);
        }
    }

    /** Stores the actual block state confirmed by the integrated server. */
    public void confirmBlock(JsonObject block, long tick, String action, String taskId) {
        if (!isFactBlock(block)) throw invalid("block must contain dimension, finite x/y/z, and type");
        if (!block.has("properties") || !block.get("properties").isJsonObject())
            throw invalid("Expected object: properties");
        requireText(action, "action");
        if (taskId != null && !taskId.isBlank() && taskId.length() > 4096)
            throw invalid("Invalid length: taskId");

        JsonObject confirmed = block.deepCopy();
        confirmed.addProperty("source", "integrated_server_confirmation");
        confirmed.addProperty("provenance", "integrated_server_confirmation");
        confirmed.remove("observation_source");
        confirmed.addProperty("confirmed_action", action);
        confirmed.add("task_id", taskId == null || taskId.isBlank() ? JsonNull.INSTANCE : new JsonPrimitive(taskId));
        long now = System.currentTimeMillis();
        confirmed.addProperty("last_seen_tick", tick);
        confirmed.addProperty("last_seen_time", now);
        confirmed.addProperty("confirmed_tick", tick);
        confirmed.addProperty("confirmed_time", now);
        putBlock(confirmed);
    }

    private void putBlock(JsonObject block) {
        String key = key(block);
        blocks.remove(key);
        blocks.put(key, block);
        refreshedCells.add(key);
        dirty = true;
        trimBlocks();
    }

    private void trimBlocks() {
        while (blocks.size() > MAX_BLOCKS) {
            String oldest = blocks.keySet().iterator().next();
            blocks.remove(oldest);
            refreshedCells.remove(oldest);
        }
    }

    /** Returns nearby known facts in distance order. */
    public JsonObject query(JsonObject query, String dimension, long tick) {
        return search(query, dimension, tick, false);
    }

    /** Returns the nearest matching known fact, with the same filters and metadata as query. */
    public JsonObject nearest(JsonObject query, String dimension, long tick) {
        return search(query, dimension, tick, true);
    }

    private JsonObject search(JsonObject query, String dimension, long tick, boolean nearestOnly) {
        if (query == null) throw invalid("Expected query object");
        requireText(dimension, "dimension");
        double x = requiredFiniteNumber(query, "x");
        double y = requiredFiniteNumber(query, "y");
        double z = requiredFiniteNumber(query, "z");
        int radius = integer(query, "radius", 16, 1, 64);
        int limit = integer(query, "limit", 32, 1, 128);
        Integer maxAge = query.has("max_age_ticks") ? integer(query, "max_age_ticks", 0, 0, 120_000) : null;
        TypeFilter typeFilter = typeFilter(query);

        List<RankedBlock> matches = new ArrayList<>();
        for (JsonObject block : blocks.values()) {
            if (!dimension.equals(safeString(block, "dimension"))) continue;
            String type = safeString(block, "type");
            if (typeFilter.enabled && !typeFilter.ids.contains(type)) continue;
            double bx = finiteCoordinate(block, "x");
            double by = finiteCoordinate(block, "y");
            double bz = finiteCoordinate(block, "z");
            double dx = bx - x, dy = by - y, dz = bz - z;
            // The radius is spherical. Check component bounds first to avoid overflow.
            if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) continue;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared > (double) radius * radius) continue;

            Long seenTick = optionalLong(block, "last_seen_tick");
            if (maxAge != null && !withinAge(seenTick, tick, maxAge)) continue;
            double distance = Math.sqrt(distanceSquared);
            JsonObject copy = block.deepCopy();
            if (seenTick == null) copy.add("last_seen_tick", JsonNull.INSTANCE);
            Long seenTime = optionalLong(block, "last_seen_time");
            if (seenTime == null) copy.add("last_seen_time", JsonNull.INSTANCE);
            copy.addProperty("distance", distance);
            copy.addProperty("freshness", seenTick != null && seenTick == tick && refreshedCells.contains(key(block)) ? "observed" : "remembered");
            matches.add(new RankedBlock(copy, distance));
        }

        matches.sort(Comparator.comparingDouble(RankedBlock::distance)
                .thenComparingDouble(b -> finiteCoordinate(b.block, "x"))
                .thenComparingDouble(b -> finiteCoordinate(b.block, "y"))
                .thenComparingDouble(b -> finiteCoordinate(b.block, "z"))
                .thenComparing(b -> safeString(b.block, "type")));

        List<JsonObject> dimensionNotes = new ArrayList<>();
        for (JsonObject note : notes) {
            if (dimension.equals(safeString(note, "dimension"))) dimensionNotes.add(note);
        }

        int blockCount = nearestOnly ? Math.min(matches.size(), 1) : Math.min(matches.size(), limit);
        int noteStart = Math.max(0, dimensionNotes.size() - limit);
        JsonArray foundBlocks = new JsonArray();
        for (int i = 0; i < blockCount; i++) foundBlocks.add(matches.get(i).block.deepCopy());
        JsonArray foundNotes = new JsonArray();
        for (int i = noteStart; i < dimensionNotes.size(); i++) foundNotes.add(dimensionNotes.get(i).deepCopy());

        JsonObject result = object("world_id", worldId, "dimension", dimension, "blocks", foundBlocks,
                "blocks_available", matches.size(), "notes", foundNotes, "notes_available", dimensionNotes.size(),
                "truncated", matches.size() > blockCount || dimensionNotes.size() > limit,
                "unknown", UNKNOWN_DESCRIPTION);
        if (nearestOnly) result.addProperty("found", !foundBlocks.isEmpty());
        return result;
    }

    private static boolean withinAge(Long seenTick, long currentTick, int maxAge) {
        if (seenTick == null || seenTick > currentTick) return false;
        long oldest = currentTick < Long.MIN_VALUE + maxAge ? Long.MIN_VALUE : currentTick - maxAge;
        return seenTick >= oldest;
    }

    private static TypeFilter typeFilter(JsonObject query) {
        if (query.has("block") && query.has("blocks")) throw invalid("Use either block or blocks");
        if (query.has("block")) return new TypeFilter(true, Set.of(string(query, "block")));
        if (!query.has("blocks")) return new TypeFilter(false, Set.of());
        if (!query.get("blocks").isJsonArray()) throw invalid("Expected array: blocks");
        JsonArray values = query.getAsJsonArray("blocks");
        if (values.size() > 16) throw invalid("Too many block ids");
        Set<String> ids = new HashSet<>();
        for (JsonElement value : values) {
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw invalid("Expected string in blocks");
            String id = value.getAsString();
            requireText(id, "blocks[]");
            ids.add(id);
        }
        return new TypeFilter(true, Set.copyOf(ids));
    }

    /** Saves an external AI annotation in its own collection, never as a block fact. */
    public JsonObject annotate(JsonObject data, String dimension) {
        if (notes.size() >= MAX_NOTES) throw new BridgeException("limit_exceeded", "Annotation limit reached");
        requireText(dimension, "dimension");
        String text = string(data, "text");
        JsonObject note = object("note_id", id(), "text", text, "dimension", dimension,
                "source", "external_ai", "time", System.currentTimeMillis());
        if (data.has("position")) {
            JsonObject position = child(data, "position");
            JsonObject copy = position.deepCopy();
            requiredFiniteNumber(copy, "x");
            requiredFiniteNumber(copy, "y");
            requiredFiniteNumber(copy, "z");
            note.add("position", copy);
        }
        notes.add(note);
        dirty = true;
        return note.deepCopy();
    }

    public void flush() throws IOException {
        if (!dirty) return;
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(object("world_id", worldId, "blocks", blocks.values(), "notes", notes)),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        dirty = false;
    }

    private static boolean isFactBlock(JsonElement element) {
        if (element == null || !element.isJsonObject()) return false;
        JsonObject block = element.getAsJsonObject();
        return isText(block, "dimension") && isText(block, "type")
                && hasFiniteNumber(block, "x") && hasFiniteNumber(block, "y") && hasFiniteNumber(block, "z");
    }

    private static boolean hasFiniteNumber(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.getAsJsonPrimitive(field).isNumber()) return false;
        return Double.isFinite(object.get(field).getAsDouble());
    }

    private static double finiteCoordinate(JsonObject object, String field) {
        if (!hasFiniteNumber(object, field)) throw invalid("Expected finite coordinate: " + field);
        return object.get(field).getAsDouble();
    }

    private static double requiredFiniteNumber(JsonObject object, String field) {
        return finiteCoordinate(object, field);
    }

    private static Long optionalLong(JsonObject object, String field) {
        if (!hasFiniteNumber(object, field)) return null;
        double value = object.get(field).getAsDouble();
        if (value != Math.rint(value) || value < Long.MIN_VALUE || value > Long.MAX_VALUE) return null;
        return (long) value;
    }

    private static boolean isText(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonPrimitive() || !object.getAsJsonPrimitive(field).isString()) return false;
        String value = object.get(field).getAsString();
        return !value.isBlank() && value.length() <= 4096;
    }

    private static String safeString(JsonObject object, String field) {
        return isText(object, field) ? object.get(field).getAsString() : "";
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 4096) throw invalid("Invalid string: " + field);
        return value;
    }

    private static BridgeException invalid(String message) {
        return new BridgeException("invalid_request", message);
    }

    private record TypeFilter(boolean enabled, Set<String> ids) {}
    private record RankedBlock(JsonObject block, double distance) {}
}
