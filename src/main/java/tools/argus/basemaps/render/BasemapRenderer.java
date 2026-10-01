package tools.argus.basemaps.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.gl.ScissorState;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.OptionalInt;

/**
 * Low-level GPU side: tile texture upload and one batched draw of textured quads into whatever framebuffer is
 * currently Minecraft's main target (inside Xaero's map render that is Xaero's own map framebuffer).
 * Render thread only.
 */
public final class BasemapRenderer {
    /**
     * Vanilla position_tex_color shaders; straight-alpha blending; no depth test/write (Xaero's map FBO has a
     * depth buffer we must not disturb); no culling because Xaero's map matrix mirrors Y.
     */
    public static final RenderPipeline PIPELINE = RenderPipeline.builder()
        .withLocation(Identifier.of("basemaps", "pipeline/basemap"))
        .withVertexShader("core/position_tex_color")
        .withFragmentShader("core/position_tex_color")
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withSampler("Sampler0")
        .withBlend(BlendFunction.TRANSLUCENT)
        .withVertexFormat(VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.QUADS)
        .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
        .withDepthWrite(false)
        .withCull(false)
        .build();

    private static BufferAllocator allocator;

    private BasemapRenderer() { }

    /** GPU copy of one tile. */
    public record GpuTile(GpuTexture texture, GpuTextureView view) {
        public void close() {
            view.close();
            texture.close();
        }
    }

    /** One quad: local map-pass coordinates, UVs (0..1) and alpha (0..255). */
    public record Quad(GpuTextureView texture, float x0, float z0, float x1, float z1,
                       float u0, float v0, float u1, float v1, int alpha) { }

    /** Creates a single-level RGBA texture from tightly packed RGBA bytes. */
    public static GpuTile upload(String label, int width, int height, ByteBuffer rgba) {
        GpuDevice device = RenderSystem.getDevice();
        GpuTexture texture = device.createTexture(() -> label, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
            TextureFormat.RGBA8, width, height, 1, 1);
        try {
            device.createCommandEncoder().writeToTexture(texture, rgba, NativeImage.Format.RGBA, 0, 0, 0, 0, width, height);
            return new GpuTile(texture, device.createTextureView(texture));
        } catch (RuntimeException e) {
            texture.close();
            throw e;
        }
    }

    /**
     * Linear when shrinking (the level choice keeps that within 2x), nearest when magnifying, i.e. pixel-crisp
     * once zoomed in past 1 image pixel per screen pixel.
     */
    private static GpuSampler sampler() {
        return RenderSystem.getSamplerCache().get(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR, FilterMode.NEAREST, false);
    }

    /**
     * Draws all quads in one render pass (one vertex upload, one indexed draw per quad with its own texture).
     * Positions are in Xaero's map-pass local space (blocks relative to Xaero's floored camera) and are transformed
     * by {@code pose}, exactly as Xaero transforms its own region textures.
     */
    public static void drawQuads(Matrix4f pose, List<Quad> quads) {
        if (quads.isEmpty()) return;
        if (allocator == null) allocator = new BufferAllocator(64 * 1024);
        BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        for (Quad q : quads) {
            // same vertex order / UV orientation as Xaero's GuiMap.buildTexturedModalSubRectWithLighting
            builder.vertex(pose, q.x0, q.z1, 0.0F).texture(q.u0, q.v1).color(255, 255, 255, q.alpha);
            builder.vertex(pose, q.x1, q.z1, 0.0F).texture(q.u1, q.v1).color(255, 255, 255, q.alpha);
            builder.vertex(pose, q.x1, q.z0, 0.0F).texture(q.u1, q.v0).color(255, 255, 255, q.alpha);
            builder.vertex(pose, q.x0, q.z0, 0.0F).texture(q.u0, q.v0).color(255, 255, 255, q.alpha);
        }
        try (BuiltBuffer built = builder.end()) {
            GpuBuffer vertices = PIPELINE.getVertexFormat().uploadImmediateVertexBuffer(built.getBuffer());
            RenderSystem.ShapeIndexBuffer shapeIndices = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
            GpuBuffer indices = shapeIndices.getIndexBuffer(built.getDrawParameters().indexCount());
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().write(
                RenderSystem.getModelViewMatrix(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), new Matrix4f());

            Framebuffer target = MinecraftClient.getInstance().getFramebuffer();
            GpuTextureView color = RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride : target.getColorAttachmentView();
            if (color == null) return;
            GpuSampler sampler = sampler();

            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                    .createRenderPass(() -> "Basemaps tiles", color, OptionalInt.empty())) {
                pass.setPipeline(PIPELINE);
                ScissorState scissor = RenderSystem.getScissorStateForRenderTypeDraws();   // same as vanilla/Xaero draws
                if (scissor.isEnabled()) pass.enableScissor(scissor.getX(), scissor.getY(), scissor.getWidth(), scissor.getHeight());
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(indices, shapeIndices.getIndexType());
                GpuTextureView bound = null;
                for (int i = 0; i < quads.size(); i++) {
                    GpuTextureView tex = quads.get(i).texture;
                    if (tex != bound) {
                        pass.bindTexture("Sampler0", tex, sampler);
                        bound = tex;
                    }
                    pass.drawIndexed(0, i * 6, 6, 1);
                }
            }
        }
    }

    public static void close() {
        if (allocator != null) {
            allocator.close();
            allocator = null;
        }
    }
}
