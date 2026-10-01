package tools.argus.basemaps.math;

/**
 * Chooses what to draw for a wanted tile: the tile itself if loaded, otherwise the nearest loaded ancestor
 * (coarser level) cropped to the wanted tile's area, so there are never holes while fine tiles stream in.
 * Pure math.
 */
public final class Fallback {
    private Fallback() { }

    /** Load state of a tile as seen by the renderer. */
    public enum State {
        /** On the GPU, drawable. */
        READY,
        /** Known to be fully transparent / missing in the pack: draw nothing, no fallback needed. */
        EMPTY,
        /** Not available yet (not requested, queued, decoding, failed). */
        MISSING
    }

    /** Looks up the state of tile (level, x, y). */
    @FunctionalInterface
    public interface Lookup {
        State state(int level, int x, int y);
    }

    /**
     * Result: draw tile (level, x, y) of the pyramid, but only the sub-rectangle [u0,u1] x [v0,v1] of it,
     * expressed in that tile's level pixels relative to its top-left corner.
     */
    public record Choice(int level, int x, int y, double px0, double py0, double px1, double py1) { }

    /**
     * @param childW pixel width of the wanted tile at its own level (edge tiles may be narrower than tileSize)
     * @param childH pixel height of the wanted tile
     * @return what to draw, or null to draw nothing (tile empty, or nothing loaded yet in its ancestry)
     */
    public static Choice resolve(int level, int x, int y, int childW, int childH, int tileSize, int levels, Lookup lookup) {
        for (int a = level; a < levels; a++) {
            int d = a - level;
            int ax = x >> d, ay = y >> d;
            State s = lookup.state(a, ax, ay);
            if (s == State.EMPTY) return null;   // an empty ancestor means this area is empty at that level
            if (s == State.READY) {
                double scale = 1.0 / (1L << d);
                double px0 = ((x - ((long) ax << d)) * (double) tileSize) * scale;
                double py0 = ((y - ((long) ay << d)) * (double) tileSize) * scale;
                return new Choice(a, ax, ay, px0, py0, px0 + childW * scale, py0 + childH * scale);
            }
        }
        return null;
    }
}
