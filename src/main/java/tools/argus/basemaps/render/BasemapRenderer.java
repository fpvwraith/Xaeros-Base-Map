package tools.argus.basemaps.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import tools.argus.basemaps.config.DrawMode;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;

/**
 * Low-level GPU side: tile texture upload and one batched draw of textured quads into whatever framebuffer is
 * currently Minecraft's main target (inside Xaero's map render that is Xaero's own map framebuffer).
 * Render thread only.
 */
public final class BasemapRenderer {
    /**
     * Vanilla's GUI textured pipeline (position_tex_color shaders, straight-alpha blending, no depth state, so
     * Xaero's map depth buffer is never touched), minus culling because Xaero's map matrix mirrors Y.
     */
    public static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("basemaps", "pipeline/basemap"))
        .withCull(false)
        .build();

    private static ByteBufferBuilder allocator;
    /** One growable vertex buffer per pass, so the under and over draws of one frame never share bytes. */
    private static final GpuBuffer[] VERTEX_BUFFERS = new GpuBuffer[DrawMode.values().length];

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
            GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        try {
            device.createCommandEncoder().writeToTexture(texture, rgba, 0, 0, 0, 0, width, height);
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
        return RenderSystem.getSamplerCache().getSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR, FilterMode.NEAREST, false);
    }

    /**
     * Draws all quads in one render pass (one vertex upload, one indexed draw per quad with its own texture).
     * Positions are in Xaero's map-pass local space (blocks relative to Xaero's floored camera) and are transformed
     * by {@code pose}, exactly as Xaero transforms its own region textures.
     */
    public static void drawQuads(DrawMode slot, Matrix4f pose, List<Quad> quads) {
        if (quads.isEmpty()) return;
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null
            ? RenderSystem.outputColorTextureOverride
            : Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView();
        if (color == null) return;

        if (allocator == null) allocator = new ByteBufferBuilder(64 * 1024);
        BufferBuilder builder = new BufferBuilder(allocator, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (Quad q : quads) {
            // same vertex order / UV orientation as Xaero's GuiMap.buildTexturedModalSubRectWithLighting
            builder.addVertex(pose, q.x0, q.z1, 0.0F).setUv(q.u0, q.v1).setColor(255, 255, 255, q.alpha);
            builder.addVertex(pose, q.x1, q.z1, 0.0F).setUv(q.u1, q.v1).setColor(255, 255, 255, q.alpha);
            builder.addVertex(pose, q.x1, q.z0, 0.0F).setUv(q.u1, q.v0).setColor(255, 255, 255, q.alpha);
            builder.addVertex(pose, q.x0, q.z0, 0.0F).setUv(q.u0, q.v0).setColor(255, 255, 255, q.alpha);
        }
        try (MeshData mesh = builder.buildOrThrow()) {
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            ByteBuffer data = mesh.vertexBuffer();
            int bytes = data.remaining();
            GpuBufferSlice vertices = vertexBuffer(slot, bytes).slice(0, bytes);
            encoder.writeToBuffer(vertices, data);

            RenderSystem.AutoStorageIndexBuffer shapeIndices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            GpuBuffer indices = shapeIndices.getBuffer(mesh.drawState().indexCount());
            GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrixCopy(), new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), new Vector3f(), new Matrix4f());
            GpuSampler sampler = sampler();

            // No scissor: Xaero 1.46 turns the render-type scissor off right before drawing its own tiles (and the
            // over pass runs after that), so unscissored is exactly how Xaero's tiles are drawn.
            try (RenderPass pass = encoder.createRenderPass(() -> "Basemaps tiles", color, Optional.empty())) {
                pass.setPipeline(PIPELINE);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", transforms);
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(indices, shapeIndices.type());
                GpuTextureView bound = null;
                for (int i = 0; i < quads.size(); i++) {
                    GpuTextureView tex = quads.get(i).texture;
                    if (tex != bound) {
                        pass.bindTexture("Sampler0", tex, sampler);
                        bound = tex;
                    }
                    pass.drawIndexed(6, 1, i * 6, 0, 0);
                }
            }
        }
    }

    private static GpuBuffer vertexBuffer(DrawMode slot, int bytes) {
        int i = slot.ordinal();
        GpuBuffer buffer = VERTEX_BUFFERS[i];
        if (buffer == null || buffer.size() < bytes) {
            long size = Math.max(bytes, buffer == null ? 64 * 1024 : buffer.size() * 2);
            if (buffer != null) buffer.close();
            buffer = RenderSystem.getDevice().createBuffer(() -> "Basemaps vertices " + slot,
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size);
            VERTEX_BUFFERS[i] = buffer;
        }
        return buffer;
    }

    public static void close() {
        if (allocator != null) {
            allocator.close();
            allocator = null;
        }
        for (int i = 0; i < VERTEX_BUFFERS.length; i++) {
            if (VERTEX_BUFFERS[i] != null) {
                VERTEX_BUFFERS[i].close();
                VERTEX_BUFFERS[i] = null;
            }
        }
    }
}
