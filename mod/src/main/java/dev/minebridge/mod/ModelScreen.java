package dev.minebridge.mod;

import com.google.gson.JsonObject;
import dev.minebridge.core.BridgeEngine;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Local owner controls; networking and model inference stay off the Minecraft thread. */
final class ModelScreen extends Screen {
    private final BridgeEngine engine;
    private final KeyMapping armKey,stopKey,panelKey;
    private EditBox goal,steps;
    private Checkbox vision;
    private Button start;
    private String draft="",stepDraft="1",error="";
    private boolean visionDraft;
    private int left,top,panelWidth;
    ModelScreen(BridgeEngine engine,KeyMapping armKey,KeyMapping stopKey,KeyMapping panelKey){super(Component.translatable("screen.minebridge.title"));this.engine=engine;this.armKey=armKey;this.stopKey=stopKey;this.panelKey=panelKey;}
    @Override protected void init() {
        panelWidth=Math.min(400,width-24);left=(width-panelWidth)/2;top=Math.max(8,(height-232)/2);
        goal=addRenderableWidget(new EditBox(font,left,top+38,panelWidth,20,Component.translatable("screen.minebridge.goal")));
        goal.setMaxLength(2000);goal.setValue(draft);goal.setResponder(value->draft=value);
        steps=addRenderableWidget(new EditBox(font,left+58,top+66,40,20,Component.translatable("screen.minebridge.steps")));
        steps.setMaxLength(2);steps.setValue(stepDraft);steps.setResponder(value->stepDraft=value);
        vision=addRenderableWidget(Checkbox.builder(Component.translatable("screen.minebridge.vision"),font).pos(left+114,top+66)
                .selected(visionDraft).onValueChange((box,value)->visionDraft=value).build());
        int half=(panelWidth-8)/2;
        start=addRenderableWidget(Button.builder(Component.translatable("screen.minebridge.start"),button->startGoal()).bounds(left,top+96,half,20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.minebridge.stop"),button->{engine.ownerStop();error="";}).bounds(left+half+8,top+96,half,20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.minebridge.arm"),button->engine.ownerArm()).bounds(left,top+122,half,20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.minebridge.close"),button->onClose()).bounds(left+half+8,top+122,half,20).build());
        setInitialFocus(goal);
    }
    private void startGoal() {
        try {
            engine.ownerModelStart(goal.getValue(),Integer.parseInt(steps.getValue()),vision.selected());
            error="";onClose();
        } catch(NumberFormatException ignored){error=Component.translatable("screen.minebridge.invalid_steps").getString();}
        catch(RuntimeException failure){error=failure.getMessage();}
    }
    @Override public void tick() {
        String state=engine.modelStatus().get("state").getAsString();
        start.active=engine.armed() && !java.util.Set.of("queued","thinking","executing").contains(state);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean keyPressed(KeyEvent event) {
        if(event.key()==stopKey.getKey().getValue()){engine.ownerStop();return true;}
        if(event.key()==armKey.getKey().getValue()){engine.ownerArm();return true;}
        if(event.key()==panelKey.getKey().getValue()){onClose();return true;}
        return super.keyPressed(event);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick) {
        graphics.centeredText(font,title,width/2,top,0xffffffff);
        graphics.text(font,Component.translatable("screen.minebridge.goal"),left,top+25,0xffdddddd);
        graphics.text(font,Component.translatable("screen.minebridge.steps"),left,top+72,0xffdddddd);
        JsonObject status=engine.modelStatus();
        graphics.text(font,Component.translatable(status.get("controller_connected").getAsBoolean()?"screen.minebridge.connected":"screen.minebridge.disconnected"),left,top+152,0xffdddddd);
        graphics.text(font,Component.translatable("screen.minebridge.state",Component.translatable("screen.minebridge.state."+status.get("state").getAsString()),status.get("step").getAsInt()),left,top+166,0xffdddddd);
        String detail=error.isEmpty()?status.get("detail").getAsString():error;
        int y=top+181;
        for(var line:font.split(Component.literal(detail),panelWidth)) {
            if(y>top+218)break;
            graphics.text(font,line,left,y,error.isEmpty()?0xffcccccc:0xffff7777);y+=10;
        }
        super.extractRenderState(graphics,mouseX,mouseY,partialTick);
    }
}
