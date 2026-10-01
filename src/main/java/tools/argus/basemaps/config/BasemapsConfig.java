package tools.argus.basemaps.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/** Contents of {@code basemaps/basemaps.json}. */
public final class BasemapsConfig {
    public static final int DEFAULT_TEXTURE_BUDGET_MB = 256;
    public static final int MIN_TEXTURE_BUDGET_MB = 32;
    public static final int MAX_TEXTURE_BUDGET_MB = 4096;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** GPU memory for map tiles (MB). Least-recently-used tiles are freed beyond this. */
    public int textureBudgetMB = DEFAULT_TEXTURE_BUDGET_MB;
    /** Keyed by file/folder name (case-sensitive, as on disk). Sorted so the JSON is stable. */
    public Map<String, ImageSettings> images = new TreeMap<>();

    public void sanitize() {
        textureBudgetMB = Math.max(MIN_TEXTURE_BUDGET_MB, Math.min(MAX_TEXTURE_BUDGET_MB, textureBudgetMB));
        if (images == null) images = new TreeMap<>();
        images = new TreeMap<>(images);
        images.values().removeIf(v -> v == null);
        images.values().forEach(ImageSettings::sanitize);
    }

    /** Settings for {@code fileName}, created with defaults (disabled, centre 0,0, 1 block/pixel) if new. */
    public ImageSettings settingsFor(String fileName) {
        return images.computeIfAbsent(fileName, k -> new ImageSettings());
    }

    /** Reads the file; a missing file gives defaults, a broken file is reported via the exception. */
    public static BasemapsConfig load(Path file) throws IOException {
        if (!Files.exists(file)) return new BasemapsConfig();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            BasemapsConfig c = GSON.fromJson(r, BasemapsConfig.class);
            if (c == null) c = new BasemapsConfig();
            c.sanitize();
            return c;
        } catch (JsonParseException e) {
            throw new IOException("basemaps.json is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** Writes via a temp file + move so a crash mid-write never leaves a half-written config. */
    public void save(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(this, w);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
