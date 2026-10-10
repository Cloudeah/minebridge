package dev.minebridge.core;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.*;
import static dev.minebridge.core.Json.*;

/** Owner-only file; credentials are never sent in capabilities or written to logs. */
public final class OwnerPolicy {
    public String token;
    public int port = 8765;
    public int radius = 12;
    public boolean telemetry = false;
    public boolean allowMultiplayer = false;
    public Set<String> allowedActions = new HashSet<>();
    public List<Area> areas = new ArrayList<>();
    public record Area(String dimension, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public boolean contains(String dim, double x, double y, double z) {
            return dimension.equals(dim) && x >= minX && y >= minY && z >= minZ && x <= maxX && y <= maxY && z <= maxZ;
        }
    }
    public static OwnerPolicy load(Path file) throws IOException {
        OwnerPolicy p;
        if (Files.exists(file)) {
            p = GSON.fromJson(Files.readString(file), OwnerPolicy.class);
            if (p == null || p.token == null || p.token.length() < 32 || p.port < 1024 || p.port > 65535 || p.radius < 1 || p.radius > 24
                    || p.allowedActions == null || p.areas == null) throw new IOException("Invalid owner configuration");
        } else {
            p = new OwnerPolicy();
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            p.token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(p), StandardOpenOption.CREATE_NEW);
        }
        try { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { /* Non-POSIX hosts. */ }
        return p;
    }
    public void check(String action, JsonObject params, String dimension) {
        if (!allowedActions.isEmpty() && !allowedActions.contains(action)) throw new BridgeException("permission_denied", "Action disabled by owner");
        if (!areas.isEmpty() && params.has("target") && params.get("target").isJsonObject()) checkPoint(child(params, "target"), dimension);
        if (!areas.isEmpty() && params.has("position")) checkPoint(child(params, "position"), dimension);
    }
    public void checkPoint(JsonObject position, String dimension) {
        double x = number(position,"x",Double.NaN), y = number(position,"y",Double.NaN), z = number(position,"z",Double.NaN);
        if (!Double.isFinite(x+y+z) || areas.stream().noneMatch(a -> a.contains(dimension,x,y,z)))
            throw new BridgeException("permission_denied", "Position outside owner area");
    }
    public JsonObject publicProfile() { return object("actor_mode","existing_player","telemetry",telemetry,"radius_limit",radius,
            "areas",areas,"allowed_actions",allowedActions,"manual_input","pause","disconnect","pause_and_release"); }
}
