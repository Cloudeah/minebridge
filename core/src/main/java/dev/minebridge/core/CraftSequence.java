package dev.minebridge.core;

import com.google.gson.JsonObject;
import static dev.minebridge.core.Json.*;

/** One menu and one outstanding crafting click group; uncertain input is never repeated. */
public final class CraftSequence {
    public enum Operation { CLEAR, FILL, OUTPUT }
    private final int menu;
    private Operation pending;
    private long sent;
    private String failure;
    public CraftSequence(int menu){this.menu=menu;}
    public static void validate(JsonObject p) {
        bool(p,"auto_station",true);
        if(p.has("station_position")) {
            JsonObject position=child(p,"station_position");
            for(String axis:new String[]{"x","y","z"}) {
                double value=number(position,axis,Double.NaN),limit=axis.equals("y")?2048:30000000;
                if(!Double.isFinite(value) || value!=Math.rint(value) || Math.abs(value)>limit)
                    throw new BridgeException("invalid_request","station_position requires bounded integer x/y/z");
            }
        }
    }
    public Operation pending(){return pending;}
    public void checkMenu(int actual) {
        if(actual!=menu)failure="interface_closed";
        checkFailure();
    }
    public void begin(Operation operation,long tick) {
        checkFailure();
        if(pending!=null)throw new BridgeException("invalid_state","A crafting click group is already pending");
        pending=operation;sent=tick;
    }
    public boolean confirm(int actualMenu,long tick,boolean matched) {
        checkMenu(actualMenu);
        if(pending==null)throw new BridgeException("invalid_state","No crafting click group to confirm");
        if(tick<sent)return false;
        if(matched){pending=null;return true;}
        if(tick-sent>20){failure="craft_unconfirmed";checkFailure();}
        return false;
    }
    public void released(){if(pending!=null)failure="craft_interrupted";}
    private void checkFailure(){if(failure!=null)throw new BridgeException(failure,"Check actual menu and items before starting a new craft task; uncertain clicks are not repeated");}
}
