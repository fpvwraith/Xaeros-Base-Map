package tools.argus.basemaps.math;

/**
 * Tile-pyramid geometry. Pure math, no Minecraft types.
 *
 * <p>Level 0 is the full-resolution image ({@code width x height} pixels) cut into {@code tileSize} tiles, with
 * tile (0,0) at the top-left (north-west). Level L is the image halved L times: {@code ceil(width / 2^L)} pixels
 * wide, each pixel {@code 2^L} level-0 pixels. Tile indices are never negative; world placement comes from an
 * origin (world X/Z of the level-0 top-left corner) and the level-0 pixel size in blocks.
 */
public final class Pyramid {
    private Pyramid() { }

    /** Number of levels so that the last level fits in a single tile. */
    public static int levelCount(long width, long height, int tileSize) {
        check(width, height, tileSize);
        int levels = 1;
        long longest = Math.max(width, height);
        while (longest > tileSize) {
            longest = (longest + 1) / 2;
            levels++;
        }
        return levels;
    }

    /** Width (or height) in pixels of a level. */
    public static long levelSize(long size, int level) {
        return (size + (1L << level) - 1) >> level;
    }

    /** Number of tiles across a level along one axis. */
    public static int tilesAcross(long size, int level, int tileSize) {
        return (int) ((levelSize(size, level) + tileSize - 1) / tileSize);
    }

    /** Pixel width of tile column {@code tx} at a level (edge tiles are narrower). 0 if out of range. */
    public static int tileSpan(long size, int level, int tileSize, int t) {
        long start = (long) t * tileSize, len = levelSize(size, level);
        if (t < 0 || start >= len) return 0;
        return (int) Math.min(tileSize, len - start);
    }

    /** Blocks per pixel at a level. */
    public static double pixelSize(double level0PixelSize, int level) {
        return level0PixelSize * (1L << level);
    }

    /**
     * Coarsest level whose pixels are still at most one screen pixel ("one image pixel per screen pixel or
     * finer"). {@code screenPxPerBlock} is Xaero's scale (framebuffer pixels per block).
     * Zoomed in past 1:1 this is always level 0.
     */
    public static int selectLevel(double screenPxPerBlock, double level0PixelSize, int levels) {
        double screenPxPerImagePx = screenPxPerBlock * level0PixelSize;
        if (!(screenPxPerImagePx > 0) || levels <= 1) return 0;
        if (screenPxPerImagePx >= 1.0) return 0;
        // largest L with screenPxPerImagePx * 2^L <= 1 (tiny epsilon so exact powers of two don't flap)
        int l = (int) Math.floor(Math.log(1.0 / screenPxPerImagePx) / Math.log(2.0) + 1e-9);
        return Math.max(0, Math.min(levels - 1, l));
    }

    /** Inclusive tile index rectangle. */
    public record TileRange(int x0, int y0, int x1, int y1) {
        public static final TileRange EMPTY = new TileRange(0, 0, -1, -1);

        public boolean isEmpty() { return x1 < x0 || y1 < y0; }
        public long count() { return isEmpty() ? 0 : (long) (x1 - x0 + 1) * (y1 - y0 + 1); }
        public boolean contains(int x, int y) { return x >= x0 && x <= x1 && y >= y0 && y <= y1; }
    }

    /**
     * Tiles of a level that intersect the world rectangle [left, right) x [top, bottom). World coordinates may
     * be negative; the image's own origin may be anywhere.
     */
    public static TileRange visibleTiles(double originX, double originZ, double level0PixelSize, long width, long height,
                                         int tileSize, int level, double left, double top, double right, double bottom) {
        double tileWorld = tileSize * pixelSize(level0PixelSize, level);
        int across = tilesAcross(width, level, tileSize), down = tilesAcross(height, level, tileSize);
        double imgRight = originX + width * level0PixelSize, imgBottom = originZ + height * level0PixelSize;
        if (right <= originX || left >= imgRight || bottom <= originZ || top >= imgBottom) return TileRange.EMPTY;
        int x0 = (int) Math.max(0, Math.floor((left - originX) / tileWorld));
        int y0 = (int) Math.max(0, Math.floor((top - originZ) / tileWorld));
        int x1 = (int) Math.min(across - 1, Math.ceil((right - originX) / tileWorld) - 1);
        int y1 = (int) Math.min(down - 1, Math.ceil((bottom - originZ) / tileWorld) - 1);
        if (x1 < x0 || y1 < y0) return TileRange.EMPTY;
        return new TileRange(x0, y0, x1, y1);
    }

    private static void check(long width, long height, int tileSize) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("image must be at least 1x1");
        if (tileSize < 1) throw new IllegalArgumentException("tileSize must be >= 1");
    }
}
