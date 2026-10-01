package tools.argus.basemaps.mixin;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import tools.argus.basemaps.BasemapManager;
import tools.argus.basemaps.config.DrawMode;
import xaero.map.WorldMap;
import xaero.map.gui.GuiMap;

/**
 * Hooks Xaero's World Map screen (xaero.map.gui.GuiMap, WM 1.46 for 26.2). Minecraft 26.x is unobfuscated, so
 * the render method is targeted by its plain Mojang name, {@code extractRenderState} (26.2's name for
 * Screen.render).
 *
 * <p>Inside that method Xaero binds its primary map framebuffer, fills the region loop with textured quads, then
 * flushes them. We inject at two single-occurrence points in that pass:
 * <ul>
 *   <li>UNDER: just before {@code WorldMapShaderHelper.setBrightness(F)}, which sits right before the two
 *       {@code rendererProvider.draw(...)} calls that actually draw the tiles. Xaero's map shader discards fully
 *       transparent (unexplored) texels, so anything we draw here only shows through where nothing is mapped.</li>
 *   <li>OVER: at the write to {@code prevLoadingLeaves}, which comes after the tiles are drawn and before the
 *       chunk-highlight overlays / XaeroPlus highlights are flushed and before waypoints and the HUD.</li>
 * </ul>
 * Both use {@code require = 0}: if a future Xaero version moves things, the overlay silently stops drawing
 * instead of crashing the game (the Basemaps screen then reports that the hook was never called).
 */
@Mixin(value = GuiMap.class, remap = false)
public abstract class GuiMapMixin {
    @Shadow private double cameraX;
    @Shadow private double cameraZ;
    @Shadow private double scale;
    @Shadow private ResourceKey<Level> lastViewedDimensionId;

    @Inject(method = "extractRenderState", require = 0,
        at = @At(value = "INVOKE", target = "Lxaero/lib/client/graphics/shader/WorldMapShaderHelper;setBrightness(F)V"))
    private void basemaps$drawUnder(CallbackInfo ci) {
        basemaps$draw(DrawMode.UNDER);
    }

    @Inject(method = "extractRenderState", require = 0,
        at = @At(value = "FIELD", target = "Lxaero/map/gui/GuiMap;prevLoadingLeaves:Z", opcode = Opcodes.PUTFIELD))
    private void basemaps$drawOver(CallbackInfo ci) {
        basemaps$draw(DrawMode.OVER);
    }

    private void basemaps$draw(DrawMode pass) {
        ResourceKey<Level> dim = lastViewedDimensionId;
        if (dim == null || WorldMap.worldMapClientOnly == null) return;
        BasemapManager.INSTANCE.drawPass(pass,
            WorldMap.worldMapClientOnly.getMapScreenPoseStack().last().pose(),
            cameraX, cameraZ, scale, dim.identifier().toString());
    }
}
