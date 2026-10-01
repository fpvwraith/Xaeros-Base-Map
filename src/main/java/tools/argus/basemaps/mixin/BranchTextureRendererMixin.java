package tools.argus.basemaps.mixin;

import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import xaero.map.region.texture.BranchTextureRenderer;

/**
 * Xaero WM builds its zoomed-out ("branch") region textures by drawing the 4 child textures into a framebuffer
 * that it first clears to opaque black (WM 1.46: its {@code black4f} = (0,0,0,1), passed to
 * {@code TextureUtils.clearRenderTarget}). Wherever a child has no data the black stays, and GuiMap also uses
 * these branch textures as the fallback for not-yet-loaded / missing tile chunks at full zoom - that is what drew
 * solid black boxes over the basemap in "under" mode.
 *
 * <p>Clearing to fully transparent instead makes "no data" texels (0,0,0,0), exactly what Xaero's leaf textures
 * already use for unexplored pixels and what its map shader discards. Explored pixels are copied unchanged, so
 * real dark terrain is untouched. Without a basemap nothing changes visually (Xaero's map framebuffer is itself
 * cleared to black behind the tiles).
 */
@Mixin(value = BranchTextureRenderer.class, remap = false)
public abstract class BranchTextureRendererMixin {
    @Unique
    private static final Vector4fc BASEMAPS$TRANSPARENT = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);

    @ModifyArg(method = {"ensureRenderTarget", "render"}, require = 0, index = 1,
        at = @At(value = "INVOKE",
            target = "Lxaero/lib/client/graphics/util/TextureUtils;clearRenderTarget(Lcom/mojang/blaze3d/pipeline/RenderTarget;Lorg/joml/Vector4fc;)V"))
    private Vector4fc basemaps$transparentBranchBackground(Vector4fc original) {
        return BASEMAPS$TRANSPARENT;
    }
}
