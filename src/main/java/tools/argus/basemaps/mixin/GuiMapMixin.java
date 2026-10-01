package tools.argus.basemaps.mixin;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
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
 * Hooks Xaero's World Map screen (xaero.map.gui.GuiMap, WM 1.45). Its render method is the Screen.render
 * override, which keeps its intermediary name {@code method_25394} in both the Yarn-remapped compile jar and at
 * runtime, so it is targeted by that literal name with {@code remap = false}.
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
    @Shadow private RegistryKey<World> lastViewedDimensionId;

    @Inject(method = "method_25394", require = 0,
        at = @At(value = "INVOKE", target = "Lxaero/lib/client/graphics/shader/WorldMapShaderHelper;setBrightness(F)V"))
    private void basemaps$drawUnder(CallbackInfo ci) {
        basemaps$draw(DrawMode.UNDER);
    }

    @Inject(method = "method_25394", require = 0,
        at = @At(value = "FIELD", target = "Lxaero/map/gui/GuiMap;prevLoadingLeaves:Z", opcode = Opcodes.PUTFIELD))
    private void basemaps$drawOver(CallbackInfo ci) {
        basemaps$draw(DrawMode.OVER);
    }

    private void basemaps$draw(DrawMode pass) {
        RegistryKey<World> dim = lastViewedDimensionId;
        if (dim == null || WorldMap.worldMapClientOnly == null) return;
        BasemapManager.INSTANCE.drawPass(pass,
            WorldMap.worldMapClientOnly.getMapScreenPoseStack().peek().getPositionMatrix(),
            cameraX, cameraZ, scale, dim.getValue().toString());
    }
}
