package tools.argus.basemaps.math;

/**
 * Re-derives the numbers Xaero's World Map 1.45 uses inside {@code GuiMap.render} (method_25394) while it draws
 * map tiles into its primary framebuffer, so our quads land in exactly the same space. Pure math.
 *
 * <p>In that pass Xaero draws every region texture at local position
 * {@code (worldX - flooredCameraX, worldZ - flooredCameraZ)} through its map pose stack; the pose stack already
 * holds the screen centring, the GUI-scale undo, {@code fboScale} and the sub-block camera offset. Copying the
 * floored-camera computation here (instead of capturing Xaero's locals) keeps the mixin free of local capture.
 */
public final class XaeroView {
    private XaeroView() { }

    /** Xaero: {@code scale >= 1 ? max(1, floor(scale)) : scale}. {@code scale} is framebuffer pixels per block. */
    public static double fboScale(double scale) {
        return scale >= 1.0 ? Math.max(1.0, Math.floor(scale)) : scale;
    }

    /** Xaero's {@code flooredCameraX} / {@code flooredCameraZ} for one axis. */
    public static int flooredCamera(double camera, double fboScale) {
        if (fboScale < 1.0) {
            double pixelInBlocks = 1.0 / fboScale;
            int inFullPixels = (int) Math.floor(camera / pixelInBlocks);
            double fboOffset = inFullPixels * pixelInBlocks;
            return (int) Math.floor(fboOffset);
        }
        return (int) Math.floor(camera);
    }

    /**
     * Visible world rectangle {left, top, right, bottom}, computed the way Xaero computes its region loop bounds
     * (including Xaero's integer halving of the framebuffer size).
     */
    public static double[] visibleBounds(double cameraX, double cameraZ, int fbWidth, int fbHeight, double scale) {
        double left = cameraX - (fbWidth / 2) / scale;
        double right = left + fbWidth / scale;
        double top = cameraZ - (fbHeight / 2) / scale;
        double bottom = top + fbHeight / scale;
        return new double[] {left, top, right, bottom};
    }

    /**
     * Where a world point ends up on the framebuffer (pixels from the top-left), ignoring Xaero's sub-pixel
     * FBO snapping. Used only by tests to sanity-check the model: the camera is at the framebuffer centre and
     * {@code scale} framebuffer pixels make one block.
     */
    public static double[] worldToFramebuffer(double worldX, double worldZ, double cameraX, double cameraZ,
                                              int fbWidth, int fbHeight, double scale) {
        return new double[] {
            (fbWidth / 2) + (worldX - cameraX) * scale,
            (fbHeight / 2) + (worldZ - cameraZ) * scale
        };
    }
}
