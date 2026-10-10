package dev.minebridge.mod;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod("minebridge")
public final class MineBridge {
    public static final String ID="minebridge";
    public MineBridge() {
        DedicatedBridge dedicated=new DedicatedBridge();
        NeoForge.EVENT_BUS.addListener(dedicated::commands);
        NeoForge.EVENT_BUS.addListener(dedicated::tick);
        NeoForge.EVENT_BUS.addListener(dedicated::stopped);
    }
}
