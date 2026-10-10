package dev.minebridge.core;

import com.google.gson.JsonObject;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import static dev.minebridge.core.Json.*;

public final class VisionPacket {
    private VisionPacket() {}
    /** MBV1 | uint32 BE JSON length | UTF-8 metadata | PNG | float32 LE axial depth in blocks. */
    public static byte[] encode(JsonObject metadata,byte[] png,float[] depth,int width,int height) {
        if(width<1 || height<1 || width>640 || height>360 || depth.length!=width*height || png.length>4*1024*1024) throw new IllegalArgumentException("Invalid frame size");
        JsonObject m=metadata.deepCopy();m.add("rgb",object("format","png","bytes",png.length,"width",width,"height",height));
        m.add("depth",object("format","float32-le","bytes",depth.length*4,"width",width,"height",height,"units","blocks","metric","camera_axial","invalid","NaN"));
        byte[] json=m.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer b=ByteBuffer.allocate(8+json.length+png.length+depth.length*4).order(ByteOrder.BIG_ENDIAN);
        b.put(new byte[]{'M','B','V','1'}).putInt(json.length).put(json).put(png).order(ByteOrder.LITTLE_ENDIAN);
        for(float f:depth)b.putFloat(f);
        return b.array();
    }
}
