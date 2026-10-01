package tools.argus.basemaps.math;

/**
 * Where an image sits in the world. Pure math, no Minecraft types.
 *
 * <p>The image centre is at world ({@code centerX}, {@code centerZ}); every source pixel is
 * {@code blocksPerPixel} blocks wide. Pixel column 0 is the west (smallest X) edge and pixel row 0 is the
 * north (smallest Z) edge, the same orientation as Xaero's map (north up).
 *
 * <p>Used for single images (centre + blocks per pixel); tile packs carry their origin in the manifest.
 *
 * @param pixelSize blocks per pixel of the image this placement describes (original or working copy)
 */
public record Placement(double minX, double minZ, double maxX, double maxZ, double pixelSize) {

    /**
     * @param width          original image width in pixels
     * @param height         original image height in pixels
     * @param centerX        world X of the image centre
     * @param centerZ        world Z of the image centre
     * @param blocksPerPixel blocks per original pixel (may be fractional)
     */
    public static Placement of(int width, int height, double centerX, double centerZ, double blocksPerPixel) {
        double w = width * blocksPerPixel, h = height * blocksPerPixel;
        double minX = centerX - w / 2.0, minZ = centerZ - h / 2.0;
        return new Placement(minX, minZ, minX + w, minZ + h, blocksPerPixel);
    }

    public double width() { return maxX - minX; }
    public double height() { return maxZ - minZ; }

    /** True when this placement overlaps the given world rectangle (edges only touching do not count). */
    public boolean intersects(double left, double top, double right, double bottom) {
        return maxX > left && minX < right && maxZ > top && minZ < bottom;
    }

    /** World rectangle of a pixel sub-rectangle of this image, as {minX, minZ, maxX, maxZ}. */
    public double[] subRect(int px, int py, int pw, int ph) {
        return new double[] {
            minX + px * pixelSize, minZ + py * pixelSize,
            minX + (px + pw) * pixelSize, minZ + (py + ph) * pixelSize
        };
    }
}
