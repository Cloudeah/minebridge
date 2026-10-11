package dev.minebridge.core;

import static dev.minebridge.core.Json.*;

import com.google.gson.JsonObject;
import java.util.UUID;

/** World-local identity, never derived from an online account name. */
public record CompanionProfile(
    UUID id,
    UUID owner,
    String name,
    String dimension,
    boolean present,
    boolean autonomous,
    boolean defending) {
  public CompanionProfile {
    java.util.Objects.requireNonNull(id);
    java.util.Objects.requireNonNull(owner);
    validateName(name);
    if (id.equals(owner))
      throw new BridgeException("invalid_request", "Companion UUID must differ from owner");
    if (dimension == null || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))
      throw new BridgeException("invalid_request", "Invalid companion dimension");
  }

  public static CompanionProfile create(UUID owner, String name, String dimension) {
    validateName(name);
    return new CompanionProfile(UUID.randomUUID(), owner, name, dimension, true, true, true);
  }

  public static void validateName(String name) {
    if (name == null || !name.matches("[A-Za-z0-9_]{1,16}"))
      throw new BridgeException(
          "invalid_request", "Имя компаньона: 1–16 латинских букв, цифр или _.");
  }

  public static CompanionProfile read(JsonObject data) {
    String name = string(data, "name");
    validateName(name);
    return new CompanionProfile(
        UUID.fromString(string(data, "id")),
        UUID.fromString(string(data, "owner")),
        name,
        string(data, "dimension"),
        bool(data, "present", true),
        bool(data, "autonomous", true),
        bool(data, "defending", true));
  }

  public CompanionProfile state(String dimension, boolean present, boolean autonomous) {
    return new CompanionProfile(id, owner, name, dimension, present, autonomous, defending);
  }

  public CompanionProfile defence(boolean enabled) {
    return new CompanionProfile(id, owner, name, dimension, present, autonomous, enabled);
  }

  public JsonObject json() {
    return object(
        "id",
        id.toString(),
        "owner",
        owner.toString(),
        "name",
        name,
        "dimension",
        dimension,
        "present",
        present,
        "autonomous",
        autonomous,
        "defending",
        defending);
  }

  public static final String GOAL =
      "Исследуй и выживай рядом с владельцем (до 64 блоков): находи еду, добывай ресурсы,"
          + " крафти снаряжение и помогай при угрозах. Проверяй результаты. Не атакуй игроков,"
          + " не трогай постройки и сундуки владельца; избегай непроверенных опасностей.";
}
