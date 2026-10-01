package tools.argus.basemaps.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import xaero.map.region.texture.BranchTextureRenderer;

/**
 * Xaero WM 1.45 builds its zoomed-out ("branch") region textures by drawing the 4 child textures into a
 * framebuffer that it first clears to opaque black ({@code 0xFF000000}). Wherever a child has no data the black
 * stays, and GuiMap also uses these branch textures as the fallback for not-yet-loaded / missing tile chunks at
 * full zoom - that is what drew solid black boxes over the basemap in "under" mode.
 *
 * <p>Clearing to fully transparent instead makes "no data" texels (0,0,0,0), exactly what Xaero's leaf textures
 * already use for unexplored pixels and what its map shader discards. Explored pixels are copied unchanged, so
 * real dark terrain is untouched. Without a basemap nothing changes visually (Xaero's map framebuffer is itself
 * cleared to black behind the tiles).
 */
@Mixin(value = BranchTextureRenderer.class, remap = false)
public abstract class BranchTextureRendererMixin {
    @ModifyConstant(method = {"ensureRenderTarget", "render"}, require = 0, constant = @Constant(intValue = 0xFF000000))
    private int basemaps$transparentBranchBackground(int original) {
        return 0x00000000;
    }
}
