package dev.minebridge.core;

/** Reserve a complete click group before picking up a cursor stack. */
public final class ActionBudget {
    private ActionBudget() {}
    public static void require(Task task,int actions) {
        if(actions<1 || actions>task.maxActions-task.actions)
            throw new BridgeException("action_limit","Insufficient action budget for the next complete operation");
    }
    public static int transferClicks(int sourceCount,int count) {
        if(count<1 || sourceCount<count || count>64)
            throw new BridgeException("invalid_request","Invalid transfer count");
        return sourceCount==count?2:count+2;
    }
}
