package tools.argus.basemaps.pack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import tools.argus.basemaps.math.Pyramid;

import java.io.IOException;
import java.util.Locale;

/**
 * {@code basemap.json} of a tile pack:
 * <pre>
 * {"format":"basemaps-pack-1","tileSize":256,"blocksPerPixelAtLevel0":1,"originX":-30208,"originZ":-30208,
 *  "widthPx":60416,"heightPx":60416,"levels":9,"dimension":"overworld","tileExt":"png"}
 * </pre>
 * originX/originZ are the world X of the left edge and world Z of the top edge of level-0 pixel (0,0).
 * Tiles live at {@code L/x/y.<tileExt>}; L = 0 is full resolution; a missing tile is transparent.
 */
public record PackManifest(int tileSize, double blocksPerPixel, double originX, double originZ,
                           long widthPx, long heightPx, int levels, String dimension, String tileExt) {
    public static final String FORMAT = "basemaps-pack-1";
    public static final String FILE_NAME = "basemap.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public PackManifest {
        validate(tileSize, blocksPerPixel, originX, originZ, widthPx, heightPx, levels, dimension, tileExt);
    }

    /** A manifest for a new pack, with the level count computed so the top level is a single tile. */
    public static PackManifest create(int tileSize, double bpp, double originX, double originZ, long w, long h, String dimension) {
        return new PackManifest(tileSize, bpp, originX, originZ, w, h, Pyramid.levelCount(w, h, tileSize), dimension, "png");
    }

    private static void validate(int tileSize, double bpp, double ox, double oz, long w, long h, int levels, String dim, String ext) {
        if (tileSize < 16 || tileSize > 4096) throw new IllegalArgumentException("tileSize must be 16..4096, got " + tileSize);
        if (!Double.isFinite(bpp) || bpp <= 0 || bpp > 1_000_000) throw new IllegalArgumentException("blocksPerPixelAtLevel0 must be > 0");
        if (!Double.isFinite(ox) || !Double.isFinite(oz) || Math.abs(ox) > 1e9 || Math.abs(oz) > 1e9)
            throw new IllegalArgumentException("originX/originZ must be finite world coordinates");
        if (w < 1 || h < 1 || w > (1L << 40) || h > (1L << 40)) throw new IllegalArgumentException("widthPx/heightPx out of range");
        int max = Pyramid.levelCount(w, h, tileSize);
        if (levels < 1 || levels > max) throw new IllegalArgumentException("levels must be 1.." + max + ", got " + levels);
        if (!dim.equals("overworld") && !dim.equals("nether") && !dim.equals("end"))
            throw new IllegalArgumentException("dimension must be overworld, nether or end");
        if (!ext.equals("png") && !ext.equals("jpg") && !ext.equals("jpeg")) throw new IllegalArgumentException("tileExt must be png or jpg");
    }

    /** Parses and validates; every problem becomes an IOException with a readable message. */
    public static PackManifest parse(String json) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) throw new IOException("basemap.json must be a JSON object");
            JsonObject o = root.getAsJsonObject();
            String format = str(o, "format", null);
            if (!FORMAT.equals(format)) throw new IOException("basemap.json: \"format\" must be \"" + FORMAT + "\" (got " + format + ")");
            return new PackManifest(
                (int) num(o, "tileSize", 256),
                num(o, "blocksPerPixelAtLevel0", 1),
                num(o, "originX", Double.NaN),
                num(o, "originZ", Double.NaN),
                (long) num(o, "widthPx", Double.NaN),
                (long) num(o, "heightPx", Double.NaN),
                (int) num(o, "levels", Double.NaN),
                str(o, "dimension", "overworld").toLowerCase(Locale.ROOT),
                str(o, "tileExt", "png").toLowerCase(Locale.ROOT));
        } catch (JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException e) {
            throw new IOException("basemap.json is not valid: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new IOException("basemap.json: " + e.getMessage(), e);
        }
    }

    private static double num(JsonObject o, String key, double def) throws IOException {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) {
            if (Double.isNaN(def)) throw new IOException("basemap.json: missing \"" + key + "\"");
            return def;
        }
        double v = e.getAsDouble();
        if (!Double.isFinite(v)) throw new IOException("basemap.json: \"" + key + "\" is not a number");
        return v;
    }

    private static String str(JsonObject o, String key, String def) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("format", FORMAT);
        o.addProperty("tileSize", tileSize);
        o.addProperty("blocksPerPixelAtLevel0", blocksPerPixel);
        o.addProperty("originX", originX);
        o.addProperty("originZ", originZ);
        o.addProperty("widthPx", widthPx);
        o.addProperty("heightPx", heightPx);
        o.addProperty("levels", levels);
        o.addProperty("dimension", dimension);
        o.addProperty("tileExt", tileExt);
        return GSON.toJson(o);
    }

    /** Relative path of a tile inside the pack, e.g. {@code 0/12/7.png}. */
    public String tilePath(int level, int x, int y) {
        return level + "/" + x + "/" + y + "." + tileExt;
    }

    public int tilesAcross(int level) { return Pyramid.tilesAcross(widthPx, level, tileSize); }
    public int tilesDown(int level) { return Pyramid.tilesAcross(heightPx, level, tileSize); }
    public int tileWidth(int level, int x) { return Pyramid.tileSpan(widthPx, level, tileSize, x); }
    public int tileHeight(int level, int y) { return Pyramid.tileSpan(heightPx, level, tileSize, y); }
}
