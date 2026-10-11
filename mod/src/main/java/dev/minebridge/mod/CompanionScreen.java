package dev.minebridge.mod;

import static dev.minebridge.core.Json.*;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.function.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Owner-only integrated-world lifecycle controls; no commands or cheats required. */
final class CompanionScreen extends Screen {
  private final CompanionManager manager = CompanionManager.INSTANCE;
  private final UUID owner;
  private final BooleanSupplier selected;
  private final Consumer<Boolean> select;
  private final Runnable stop, arm;
  private final KeyMapping armKey, stopKey, panelKey;
  private int left, top;
  private Button create, show, respawn, autonomy, target;
  private EditBox name;

  CompanionScreen(
      UUID owner,
      BooleanSupplier selected,
      Consumer<Boolean> select,
      Runnable stop,
      Runnable arm,
      KeyMapping armKey,
      KeyMapping stopKey,
      KeyMapping panelKey) {
    super(Component.translatable("screen.minebridge.companion"));
    this.owner = owner;
    this.selected = selected;
    this.select = select;
    this.stop = stop;
    this.arm = arm;
    this.armKey = armKey;
    this.stopKey = stopKey;
    this.panelKey = panelKey;
  }

  protected void init() {
    left = (width - 380) / 2;
    top = Math.max(8, (height - 228) / 2);
    name =
        addRenderableWidget(
            new EditBox(
                font,
                left,
                top + 30,
                180,
                20,
                Component.translatable("screen.minebridge.companion_name")));
    name.setMaxLength(16);
    name.setValue("BridgeBuddy");
    create =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("screen.minebridge.companion_create"),
                    b -> {
                      manager.create(owner, name.getValue());
                      select.accept(true);
                    })
                .bounds(left + 190, top + 30, 190, 20)
                .build());
    show =
        addRenderableWidget(
            Button.builder(
                    Component.literal(""),
                    b -> {
                      if (bool(manager.status(), "present", false)) manager.hide(owner);
                      else manager.show(owner);
                    })
                .bounds(left, top + 58, 180, 20)
                .build());
    respawn =
        addRenderableWidget(
            Button.builder(
                    Component.translatable("screen.minebridge.companion_respawn"),
                    b -> manager.respawn(owner))
                .bounds(left + 190, top + 58, 190, 20)
                .build());
    autonomy =
        addRenderableWidget(
            Button.builder(
                    Component.literal(""),
                    b -> manager.autonomous(owner, !bool(manager.status(), "autonomous", true)))
                .bounds(left, top + 86, 380, 20)
                .build());
    target =
        addRenderableWidget(
            Button.builder(Component.literal(""), b -> select.accept(!selected.getAsBoolean()))
                .bounds(left, top + 114, 380, 20)
                .build());
    addRenderableWidget(
        Button.builder(Component.translatable("screen.minebridge.stop"), b -> stop.run())
            .bounds(left, top + 142, 180, 20)
            .build());
    addRenderableWidget(
        Button.builder(Component.translatable("screen.minebridge.close"), b -> onClose())
            .bounds(left + 190, top + 142, 190, 20)
            .build());
    tick();
  }

  public void tick() {
    JsonObject s = manager.status();
    boolean exists = bool(s, "exists", false), present = bool(s, "present", false);
    create.active = !exists;
    name.active = !exists;
    show.active = exists;
    show.setMessage(
        Component.translatable(
            present ? "screen.minebridge.companion_hide" : "screen.minebridge.companion_show"));
    respawn.active = present && number(s, "health", 0) <= 0;
    autonomy.active = exists;
    autonomy.setMessage(
        Component.translatable(
            bool(s, "autonomous", true)
                ? "screen.minebridge.companion_auto_on"
                : "screen.minebridge.companion_auto_off"));
    target.setMessage(
        Component.translatable(
            selected.getAsBoolean()
                ? "screen.minebridge.companion_target"
                : "screen.minebridge.player_target"));
  }

  public boolean isPauseScreen() {
    return false;
  }

  public boolean keyPressed(KeyEvent e) {
    if (e.key() == stopKey.getKey().getValue()) {
      stop.run();
      return true;
    }
    if (e.key() == armKey.getKey().getValue()) {
      arm.run();
      return true;
    }
    if (e.key() == panelKey.getKey().getValue()) {
      onClose();
      return true;
    }
    return super.keyPressed(e);
  }

  public void extractRenderState(GuiGraphicsExtractor g, int x, int y, float delta) {
    g.centeredText(font, title, width / 2, top, 0xffffffff);
    JsonObject s = manager.status();
    String health = String.format(java.util.Locale.ROOT, "%.1f", number(s, "health", 0));
    g.text(
        font,
        Component.translatable(
            "screen.minebridge.companion_status",
            string(s, "name", "—"),
            health,
            integer(s, "food", 0, 0, 20),
            bool(s, "armed", false) ? "F8 ✓" : "F8 —"),
        left,
        top + 172,
        0xffdddddd);
    String detail = s.has("detail") ? s.get("detail").getAsString() : "";
    if (s.has("reflex") && !s.get("reflex").getAsString().isEmpty())
      detail = s.get("reflex").getAsString();
    int line = top + 188;
    for (var text : font.split(Component.literal(detail), 380)) {
      if (line > top + 216) break;
      g.text(font, text, left, line, 0xffcccccc);
      line += 10;
    }
    super.extractRenderState(g, x, y, delta);
  }
}
