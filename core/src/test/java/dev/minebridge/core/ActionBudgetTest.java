package dev.minebridge.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.minebridge.core.Json.*;

class ActionBudgetTest {
    @Test void refusesPartialCursorOperationsBeforeAnyClick() {
        Task task=new Task("inventory.transfer",object(),0,100,3);
        task.actions=1;
        assertDoesNotThrow(()->ActionBudget.require(task,2));
        assertEquals("action_limit",assertThrows(BridgeException.class,()->ActionBudget.require(task,3)).code);
        assertEquals(1,task.actions);
    }
    @Test void wholeStackNeedsTwoClicksButSplitNeedsReturnClick() {
        assertEquals(2,ActionBudget.transferClicks(64,64));
        assertEquals(5,ActionBudget.transferClicks(64,3));
        assertThrows(BridgeException.class,()->ActionBudget.transferClicks(2,3));
    }
}
