package dev.minebridge.mod;

import com.google.gson.*;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.minebridge.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.client.event.*;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.minebridge.core.Json.*;

/** Native color and depth from the same framebuffer, without an object recognition model. */
final class ClientVision implements AutoCloseable {
    private final Minecraft mc;
    private final MinecraftWorld world;
    private final BridgeEngine engine;
    private final ExecutorService encoding=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"minebridge-vision");t.setDaemon(true);return t;});
    private final AtomicBoolean busy=new AtomicBoolean();
    private Matrix4f viewProjection=new Matrix4f(),projection=new Matrix4f();
    private Matrix4f viewRotation=new Matrix4f();
    private Vec3 camera=Vec3.ZERO;
    private JsonObject metadata;
    private volatile boolean closed;
    ClientVision(Minecraft mc,MinecraftWorld world,BridgeEngine engine){this.mc=mc;this.world=world;this.engine=engine;}
    void extract(ExtractLevelRenderStateEvent event) {
        var state=event.getRenderState().cameraRenderState;camera=state.pos;
        projection=new Matrix4f(state.projectionMatrix);viewRotation=new Matrix4f(state.viewRotationMatrix);viewProjection=new Matrix4f(projection).mul(viewRotation);
        JsonObject snapshot=engine.snapshot();
        metadata=object("protocol_version",BridgeEngine.VERSION,"session_id",engine.sessionId,"frame_id",id(),"observation_id",snapshot.get("observation_id"),
                "actor_id",world.actorId(),"dimension",world.dimension(),"world_revision",engine.revision(),"game_tick",world.tick(),"capture_time",System.currentTimeMillis(),
                "camera",object("position",MinecraftWorld.vector(camera),"yaw",state.yRot,"pitch",state.xRot,"projection",projection.get(new float[16]),"view_rotation",state.viewRotationMatrix.get(new float[16]),"matrix_layout","column_major","coordinate_system","world_xyz_blocks"),
                "sync_quality",object("rgb_depth","same_framebuffer","objects","client_tick_geometry","structural","latest_tick_snapshot","exact_object_masks",false),
                "observation",snapshot,"limitations",new String[]{"transparency_and_particles_may_not_write_depth","tick_geometry_can_differ_from_interpolated_render","shader_and_postprocess_effects_may_change_alignment","synchronous_readback_cost_requires_measurement"});
    }
    void capture(RenderLevelStageEvent.AfterLevel event) {
        if(closed || metadata==null || busy.get())return;
        JsonObject request=engine.takeVisionRequest();if(request==null)return;
        var target=mc.getMainRenderTarget();
        if(!(target.getColorTexture() instanceof GlTexture color) || !(target.getDepthTexture() instanceof GlTexture depth)) {
            engine.event("vision.error",object("request_id",request.get("request_id"),"code","unsupported_capability","message","Native capture currently supports OpenGL render targets"));return;
        }
        int sourceWidth=target.width,sourceHeight=target.height;
        if((long)sourceWidth*sourceHeight>8_388_608){engine.event("vision.error",object("code","limit_exceeded","message","Source framebuffer exceeds 8 megapixels"));return;}
        int width=integer(request,"width",640,64,640),height=integer(request,"height",360,64,360);
        int x=0,y=0,rw=sourceWidth,rh=sourceHeight;
        if(request.has("crop")){JsonObject crop=child(request,"crop");x=integer(crop,"x",0,0,sourceWidth-1);y=integer(crop,"y",0,0,sourceHeight-1);rw=integer(crop,"width",sourceWidth-x,1,sourceWidth-x);rh=integer(crop,"height",sourceHeight-y,1,sourceHeight-y);}
        busy.set(true);
        ByteBuffer rgba=MemoryUtil.memAlloc(sourceWidth*sourceHeight*4),rawDepth=MemoryUtil.memAlloc(sourceWidth*sourceHeight*4).order(ByteOrder.nativeOrder());
        int previous=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),alignment=GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT),packBuffer=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int framebuffer=GL30.glGenFramebuffers();
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,framebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_COLOR_ATTACHMENT0,GL11.GL_TEXTURE_2D,color.glId(),0);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER,GL30.GL_DEPTH_ATTACHMENT,GL11.GL_TEXTURE_2D,depth.glId(),0);
            if(GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER)!=GL30.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Incomplete capture framebuffer");
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT,1);
            GL11.glReadPixels(0,0,sourceWidth,sourceHeight,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,rgba);
            GL11.glReadPixels(0,0,sourceWidth,sourceHeight,GL11.GL_DEPTH_COMPONENT,GL11.GL_FLOAT,rawDepth);
            float[] inverse=projection.invert(new Matrix4f()).get(new float[16]);
            boolean zeroToOne=RenderSystem.getDevice().isZZeroToOne();
            int[] pixels=new int[width*height];float[] distances=new float[width*height];
            for(int py=0;py<height;py++)for(int px=0;px<width;px++) {
                int sx=x+(int)((px+0.5)*rw/width),sy=sourceHeight-1-(y+(int)((py+0.5)*rh/height)),index=(sy*sourceWidth+sx)*4,out=py*width+px;
                pixels[out]=0xff000000|(rgba.get(index)&255)<<16|(rgba.get(index+1)&255)<<8|(rgba.get(index+2)&255);
                distances[out]=DepthMath.axial(rawDepth.getFloat(index),(sx+0.5f)/sourceWidth*2-1,(sy+0.5f)/sourceHeight*2-1,inverse,zeroToOne);
            }
            JsonObject packet=metadata.deepCopy();packet.add("request_id",request.has("request_id")?request.get("request_id"):JsonNull.INSTANCE);
            packet.add("crop",object("x",x,"y",y,"width",rw,"height",rh,"source_width",sourceWidth,"source_height",sourceHeight));
            packet.add("objects",objects(width,height,x,y,rw,rh,sourceWidth,sourceHeight,distances));
            long captured=packet.get("capture_time").getAsLong();
            encoding.execute(()->{
                try {
                    BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,width,height,pixels,0,width);
                    ByteArrayOutputStream png=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",png))throw new IllegalStateException("PNG encoder unavailable");
                    packet.addProperty("age_ms_at_enqueue",System.currentTimeMillis()-captured);
                    if(!closed)engine.publishVision(VisionPacket.encode(packet,png.toByteArray(),distances,width,height));
                } catch(Exception error){if(!closed)mc.execute(()->{if(!closed)engine.event("vision.error",object("code","encoding_failed"));});}
                finally {busy.set(false);}
            });
        } catch(Exception error){busy.set(false);engine.event("vision.error",object("request_id",request.get("request_id"),"code","capture_failed","message",error.getClass().getSimpleName()));}
        finally {GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,previous);GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,packBuffer);GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT,alignment);GL30.glDeleteFramebuffers(framebuffer);MemoryUtil.memFree(rgba);MemoryUtil.memFree(rawDepth);}
    }
    private JsonArray objects(int width,int height,int cropX,int cropY,int cropWidth,int cropHeight,int sourceWidth,int sourceHeight,float[] depth) {
        JsonArray objects=new JsonArray();
        for(Entity e:mc.level.entitiesForRendering()) {
            if(e==mc.player && !mc.gameRenderer.getMainCamera().isDetached() || e.isInvisibleTo(mc.player))continue;
            AABB bounds=e.getBoundingBox();
            Vec3 center=bounds.getCenter();
            // Projection alone is never used as evidence of visibility.
            HitResult hit=mc.level.clip(new ClipContext(camera,center,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
            if(hit.getType()!=HitResult.Type.MISS)continue;
            Vector4f projected=new Vector4f((float)(center.x-camera.x),(float)(center.y-camera.y),(float)(center.z-camera.z),1);viewProjection.transform(projected);
            if(projected.w<=0)continue;
            int cx=(int)(((projected.x/projected.w+1)*0.5*sourceWidth-cropX)*width/cropWidth),cy=(int)(((1-projected.y/projected.w)*0.5*sourceHeight-cropY)*height/cropHeight);
            if(cx<0||cy<0||cx>=width||cy>=height)continue;
            float nativeDepth=depth[cy*width+cx];Vector3f cameraSpace=new Vector3f((float)(center.x-camera.x),(float)(center.y-camera.y),(float)(center.z-camera.z));viewRotation.transformPosition(cameraSpace);
            double radius=Math.sqrt(bounds.getXsize()*bounds.getXsize()+bounds.getYsize()*bounds.getYsize()+bounds.getZsize()*bounds.getZsize())*0.5;
            if(!Float.isFinite(nativeDepth)||nativeDepth < -cameraSpace.z-radius-0.15 || nativeDepth > -cameraSpace.z+radius+0.15)continue;
            double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;boolean inFront=true;
            for(double xx:new double[]{bounds.minX,bounds.maxX})for(double yy:new double[]{bounds.minY,bounds.maxY})for(double zz:new double[]{bounds.minZ,bounds.maxZ}) {
                Vector4f p=new Vector4f((float)(xx-camera.x),(float)(yy-camera.y),(float)(zz-camera.z),1);viewProjection.transform(p);
                if(p.w<=0){inFront=false;continue;}
                double screenX=((p.x/p.w+1)*0.5*sourceWidth-cropX)*width/cropWidth,screenY=((1-p.y/p.w)*0.5*sourceHeight-cropY)*height/cropHeight;
                minX=Math.min(minX,screenX);minY=Math.min(minY,screenY);maxX=Math.max(maxX,screenX);maxY=Math.max(maxY,screenY);
            }
            if(!inFront || maxX<0 || maxY<0 || minX>=width || minY>=height)continue;
            objects.add(object("entity_id",e.getUUID().toString(),"type",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),"position",MinecraftWorld.vector(e.position()),"distance",camera.distanceTo(center),"box",new double[]{Math.max(0,minX),Math.max(0,minY),Math.min(width,maxX),Math.min(height,maxY)},"visibility","center_ray_and_native_depth_consistent","visible_fraction","unknown","occlusion_limits","Sparse evidence; bounding rectangles are not instance masks"));
        }
        // Blocks are tied to observed positions and an actual camera ray, not inferred from pixels.
        for(JsonElement entry:metadata.getAsJsonObject("observation").getAsJsonArray("blocks")) {
            JsonObject b=entry.getAsJsonObject();var pos=new net.minecraft.core.BlockPos(b.get("x").getAsInt(),b.get("y").getAsInt(),b.get("z").getAsInt());Vec3 center=Vec3.atCenterOf(pos);
            BlockHitResult ray=mc.level.clip(new ClipContext(camera,center,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
            if(ray.getType()!=HitResult.Type.BLOCK||!ray.getBlockPos().equals(pos))continue;
            Vector4f p=new Vector4f((float)(ray.getLocation().x-camera.x),(float)(ray.getLocation().y-camera.y),(float)(ray.getLocation().z-camera.z),1);viewProjection.transform(p);if(p.w<=0)continue;
            double px=((p.x/p.w+1)*0.5*sourceWidth-cropX)*width/cropWidth,py=((1-p.y/p.w)*0.5*sourceHeight-cropY)*height/cropHeight;if(px<0||py<0||px>=width||py>=height)continue;
            objects.add(object("kind","block","position",MinecraftWorld.position(pos),"type",MinecraftWorld.blockId(mc.level.getBlockState(pos)),"properties",MinecraftWorld.properties(mc.level.getBlockState(pos)),"surface_pixel",new double[]{px,py},"visible_face",ray.getDirection().getName(),"visibility","camera_outline_ray"));
            if(objects.size()>=1024)break;
        }
        return objects;
    }
    @Override public void close(){closed=true;encoding.shutdownNow();}
}
