package tools.argus.basemaps.config;

import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Per-source settings, stored in basemaps.json under the image file / pack folder / pack zip name.
 * Single images are placed by centre + blocks per pixel; tile packs by their manifest plus an optional offset.
 */
public final class ImageSettings {
    /** World coordinates are limited to +-30,000,000 in vanilla; allow a little more for odd servers. */
    public static final double MAX_ABS_COORD = 60_000_000.0;
    public static final double MIN_BLOCKS_PER_PIXEL = 1.0 / 64.0;
    public static final double MAX_BLOCKS_PER_PIXEL = 4096.0;

    public boolean enabled = false;
    /** Single images: world X/Z of the image centre. */
    public double centerX = 0.0;
    public double centerZ = 0.0;
    /** Single images: blocks per source pixel. */
    public double blocksPerPixel = 1.0;
    /** Tile packs: shift (blocks) added to the manifest's origin. */
    public double offsetX = 0.0;
    public double offsetZ = 0.0;
    /** 0-100 %. */
    public int opacity = 100;
    public MapDimension dimension = MapDimension.OVERWORLD;
    public DrawMode mode = DrawMode.UNDER;

    /** Repairs anything invalid (e.g. hand-edited JSON) to a sane value. */
    public void sanitize() {
        if (!Double.isFinite(centerX) || Math.abs(centerX) > MAX_ABS_COORD) centerX = 0.0;
        if (!Double.isFinite(centerZ) || Math.abs(centerZ) > MAX_ABS_COORD) centerZ = 0.0;
        if (!Double.isFinite(offsetX) || Math.abs(offsetX) > MAX_ABS_COORD) offsetX = 0.0;
        if (!Double.isFinite(offsetZ) || Math.abs(offsetZ) > MAX_ABS_COORD) offsetZ = 0.0;
        if (!validBlocksPerPixel(blocksPerPixel)) blocksPerPixel = 1.0;
        opacity = Math.max(0, Math.min(100, opacity));
        if (dimension == null) dimension = MapDimension.OVERWORLD;
        if (mode == null) mode = DrawMode.UNDER;
    }

    public static boolean validBlocksPerPixel(double v) {
        return Double.isFinite(v) && v >= MIN_BLOCKS_PER_PIXEL && v <= MAX_BLOCKS_PER_PIXEL;
    }

    /** Parses a centre coordinate typed by the user. Empty/invalid/out-of-range input gives empty. */
    public static OptionalDouble parseCoordinate(String text) {
        OptionalDouble v = parseNumber(text);
        if (v.isEmpty() || Math.abs(v.getAsDouble()) > MAX_ABS_COORD) return OptionalDouble.empty();
        return v;
    }

    /** Parses blocks-per-pixel typed by the user (e.g. "8", "0.5", "1/2" is not accepted). */
    public static OptionalDouble parseBlocksPerPixel(String text) {
        OptionalDouble v = parseNumber(text);
        if (v.isEmpty() || !validBlocksPerPixel(v.getAsDouble())) return OptionalDouble.empty();
        return v;
    }

    private static OptionalDouble parseNumber(String text) {
        if (text == null) return OptionalDouble.empty();
        String t = text.trim().replace("_", "");
        if (t.isEmpty() || t.length() > 32) return OptionalDouble.empty();
        // plain decimal only: no hex, no exponents, no NaN/Infinity words
        if (!t.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)")) return OptionalDouble.empty();
        try {
            double d = Double.parseDouble(t);
            return Double.isFinite(d) ? OptionalDouble.of(d) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    /** Formats a number for a text field: no trailing ".0", no exponent. */
    public static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.6f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
