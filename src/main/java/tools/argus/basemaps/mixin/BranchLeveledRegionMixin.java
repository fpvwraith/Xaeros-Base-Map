package tools.argus.basemaps.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import xaero.map.region.BranchLeveledRegion;

/**
 * Xaero saves branch textures to {@code <world>/<dim>/<layer>/cache/<level>/x_z.xwmc}, so textures built before
 * Basemaps was installed would keep their baked-in black background forever. Branch caches made with the
 * transparent background (see {@link BranchTextureRendererMixin}) go to a separate {@code cache-basemaps}
 * folder instead: Xaero rebuilds them from its leaf caches as you view the map (its normal "no branch cache"
 * path), and Xaero's own {@code cache} folder is left untouched for when Basemaps is removed. Leaf caches
 * ({@code cache_<version>}) are not affected.
 */
@Mixin(value = BranchLeveledRegion.class, remap = false)
public abstract class BranchLeveledRegionMixin {
    private static final String FOLDER = "cache-basemaps";

    @ModifyConstant(method = "findCacheFile", require = 0, constant = @Constant(stringValue = "cache"))
    private String basemaps$separateBranchCache(String original) {
        return FOLDER;
    }
}
