package dev.minebridge.core;

import java.util.*;

/** Compare observed main-inventory candidates, not inferred loot or mining time. */
public final class MiningTools {
    public record Candidate(int slot,String item,int count,double speed,boolean correct,int remaining) {}
    private MiningTools() {}
    public static Candidate choose(List<Candidate> candidates,int selected,boolean required,boolean automatic) {
        return candidates.stream().filter(c->c.slot()>=0 && c.slot()<36 && (c.count()>0 || c.slot()==selected)
                && (automatic || c.slot()==selected) && (!required || c.correct()) && c.remaining()!=0
                && Double.isFinite(c.speed()) && c.speed()>0)
                .min(Comparator.comparingDouble(Candidate::speed).reversed()
                        .thenComparingInt(c->c.slot()==selected?0:1)
                        .thenComparing(Comparator.comparingInt((Candidate c)->c.remaining()<0?Integer.MAX_VALUE:c.remaining()).reversed())
                        .thenComparingInt(Candidate::slot))
                .orElseThrow(()->new BridgeException("missing_tool","No available tool can harvest this block"));
    }
}
