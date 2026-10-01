package tools.argus.basemaps;

import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.argus.basemaps.config.BasemapsConfig;
import tools.argus.basemaps.config.DrawMode;
import tools.argus.basemaps.config.ImageSettings;
import tools.argus.basemaps.config.MapDimension;
import tools.argus.basemaps.image.ImageLoader;
import tools.argus.basemaps.image.StreamingPngDecoder;
import tools.argus.basemaps.math.Fallback;
import tools.argus.basemaps.math.Pyramid;
import tools.argus.basemaps.math.XaeroView;
import tools.argus.basemaps.pack.ImageSlicer;
import tools.argus.basemaps.pack.PackManifest;
import tools.argus.basemaps.pack.Progress;
import tools.argus.basemaps.pack.TileSource;
import tools.argus.basemaps.render.BasemapRenderer;
import tools.argus.basemaps.render.TileCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Owns the basemaps folder, the config, the sources (images / packs), background slicing, the tile cache and
 * the per-frame draw. Everything except the worker threads runs on the client (= render) thread.
 */
public final class BasemapManager {
    public static final Logger LOG = LoggerFactory.getLogger("Basemaps");
    public static final BasemapManager INSTANCE = new BasemapManager();
    public static final String CONFIG_FILE = "basemaps.json";
    public static final String CACHE_DIR = ".cache";
    public static final int SLICE_TILE_SIZE = 256;

    /** Tile uploads per draw pass (two passes per frame): 8 x 256 KB. */
    private static final int UPLOADS_PER_PASS = 8;
    /** Safety cap on tiles considered per image per pass (normally a screen needs ~100-300). */
    private static final long MAX_TILES_PER_IMAGE = 2048;

    private Path dir, cacheDir, configFile;
    private BasemapsConfig config = new BasemapsConfig();
    private final List<BasemapEntry> entries = new ArrayList<>();
    private final ConcurrentLinkedQueue<SliceResult> sliceResults = new ConcurrentLinkedQueue<>();
    private ExecutorService slicer, encodePool;
    private TileCache cache;
    private int nextSourceId = 1;
    private long frame = 10;
    private volatile long lastHookNanos;
    private long lastErrorLogNanos;

    private record SliceResult(BasemapEntry entry, int generation, PackManifest manifest, Throwable error) { }

    private BasemapManager() { }

    public Path dir() { return dir; }
    public BasemapsConfig config() { return config; }
    public List<BasemapEntry> entries() { return Collections.unmodifiableList(entries); }
    public boolean hookSeen() { return lastHookNanos != 0L; }
    public long textureBytes() { return cache == null ? 0 : cache.gpuBytes(); }
    public int textureTiles() { return cache == null ? 0 : cache.gpuTiles(); }

    public void init(Path basemapsDir) {
        this.dir = basemapsDir;
        this.cacheDir = basemapsDir.resolve(CACHE_DIR);
        this.configFile = basemapsDir.resolve(CONFIG_FILE);
        try {
            Files.createDirectories(cacheDir);
            cleanTmp();
        } catch (IOException e) {
            LOG.error("Could not create basemaps folder {}", basemapsDir, e);
        }
        loadConfig();
        scan();
        LOG.info("Basemaps folder: {} ({} source(s))", basemapsDir.toAbsolutePath(), entries.size());
    }

    // ------------------------------------------------------------------ config

    private void loadConfig() {
        try {
            config = BasemapsConfig.load(configFile);
        } catch (IOException e) {
            Path backup = configFile.resolveSibling(CONFIG_FILE + ".broken");
            LOG.error("{} - moved it to {} and started with defaults", e.getMessage(), backup.getFileName());
            try {
                Files.move(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveError) {
                LOG.error("Could not move broken config aside", moveError);
            }
            config = new BasemapsConfig();
        }
    }

    public void save() {
        if (configFile == null) return;
        try {
            config.save(configFile);
        } catch (IOException e) {
            LOG.error("Could not save {}", configFile, e);
        }
    }

    // ------------------------------------------------------------------ folder scan

    /**
     * Re-reads the folder: single images (.png/.jpg), pack folders and pack zips (with basemap.json). New
     * sources get default settings (off); changed files are released so they reload / re-slice.
     */
    public void scan() {
        if (dir == null) return;
        Map<String, Path> found = new HashMap<>();
        Map<String, BasemapEntry.Kind> kinds = new HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.startsWith(".")) continue;
                BasemapEntry.Kind kind = null;
                if (Files.isDirectory(p)) {
                    if (TileSource.isPackDir(p)) kind = BasemapEntry.Kind.PACK_DIR;
                } else if (Files.isRegularFile(p)) {
                    if (ImageLoader.isImageFileName(name)) kind = BasemapEntry.Kind.IMAGE;
                    else if (name.toLowerCase(Locale.ROOT).endsWith(".zip") && TileSource.Zip.looksLikePack(p)) kind = BasemapEntry.Kind.PACK_ZIP;
                }
                if (kind != null) {
                    found.put(name, p);
                    kinds.put(name, kind);
                }
            }
        } catch (IOException e) {
            LOG.error("Could not list {}", dir, e);
            return;
        }

        boolean configChanged = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            BasemapEntry e = entries.get(i);
            if (!found.containsKey(e.name) || kinds.get(e.name) != e.kind) {
                release(e, true);
                entries.remove(i);
            }
        }
        for (Map.Entry<String, Path> f : found.entrySet()) {
            BasemapEntry existing = find(f.getKey());
            long[] attrs = attrs(f.getValue(), kinds.get(f.getKey()));
            if (existing == null) {
                boolean isNew = !config.images.containsKey(f.getKey());
                BasemapEntry e = new BasemapEntry(f.getKey(), f.getValue(), kinds.get(f.getKey()), config.settingsFor(f.getKey()));
                readInfo(e, attrs);
                if (isNew) {
                    configChanged = true;
                    if (e.manifest != null && e.isPack()) e.settings.dimension = MapDimension.byKey(e.manifest.dimension());
                }
                entries.add(e);
            } else if (existing.stamp != attrs[2]) {
                LOG.info("{} changed on disk, reloading", existing.name);
                release(existing, true);
                readInfo(existing, attrs);
            }
        }
        entries.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        if (configChanged) save();
    }

    /** Full reload: re-reads basemaps.json, frees every texture, rescans the folder. */
    public void reloadAll() {
        for (BasemapEntry e : entries) release(e, false);
        entries.clear();
        if (cache != null) cache.clear();
        loadConfig();
        scan();
        LOG.info("Reloaded: {} source(s)", entries.size());
    }

    private BasemapEntry find(String name) {
        for (BasemapEntry e : entries) if (e.name.equals(name)) return e;
        return null;
    }

    /** {size, mtime, stamp}. For pack folders the manifest file is what is watched. */
    private static long[] attrs(Path p, BasemapEntry.Kind kind) {
        try {
            Path watched = kind == BasemapEntry.Kind.PACK_DIR ? p.resolve(PackManifest.FILE_NAME) : p;
            BasicFileAttributes a = Files.readAttributes(watched, BasicFileAttributes.class);
            long mtime = a.lastModifiedTime().toMillis();
            return new long[] {a.size(), mtime, mtime * 31 + a.size()};
        } catch (IOException e) {
            return new long[] {0, 0, -1};
        }
    }

    private void readInfo(BasemapEntry e, long[] attrs) {
        e.fileSize = attrs[0];
        e.fileMtime = attrs[1];
        e.stamp = attrs[2];
        e.infoError = null;
        e.warning = null;
        e.manifest = null;
        e.state = BasemapEntry.State.IDLE;
        e.error = null;
        try {
            if (e.kind == BasemapEntry.Kind.IMAGE) {
                e.probe = ImageLoader.probe(e.path);
                boolean interlaced = e.probe.isPng() && StreamingPngDecoder.readHeader(e.path).interlaced();
                switch (ImageSlicer.mode(e.probe, interlaced)) {
                    case UNSUPPORTED -> e.infoError = ImageSlicer.unsupportedReason(e.probe);
                    case IMAGEIO_BANDS -> e.warning = (e.probe.isJpeg() ? "Big JPEG" : "Big interlaced PNG")
                        + ": slicing is slow (minutes or more). A non-interlaced PNG or a tile pack is much faster.";
                    default -> { }
                }
                Path cached = cacheDirFor(e);
                if (TileSource.isPackDir(cached)) {
                    e.manifest = PackManifest.parse(Files.readString(cached.resolve(PackManifest.FILE_NAME), StandardCharsets.UTF_8));
                }
            } else {
                try (TileSource s = TileSource.open(e.path)) {
                    e.manifest = s.manifest();
                }
            }
        } catch (IOException | RuntimeException ex) {
            e.infoError = (e.isPack() ? "Bad pack: " : "Can't read image: ") + ex.getMessage();
            LOG.warn("Skipping {}: {}", e.name, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ lifecycle of one source

    /** Called by the UI after it changed an entry's settings. Enabling an image starts slicing right away. */
    public void settingsChanged(BasemapEntry e) {
        if (e.settings.enabled) ensureReady(e);
        else release(e, true);
        save();
    }

    /** Opens the tile source, or starts slicing an image into the cache. True when drawable now. */
    private boolean ensureReady(BasemapEntry e) {
        if (e.infoError != null) return false;
        switch (e.state) {
            case READY: return true;
            case SLICING:
            case FAILED: return false;
            default: break;
        }
        try {
            if (e.isPack()) {
                open(e, TileSource.open(e.path));
                return true;
            }
            Path cached = cacheDirFor(e);
            if (TileSource.isPackDir(cached)) {
                open(e, TileSource.open(cached));
                return true;
            }
            startSlice(e, cached);
        } catch (IOException | RuntimeException ex) {
            e.state = BasemapEntry.State.FAILED;
            e.error = "Can't open: " + ex.getMessage();
            LOG.warn("Could not open {}: {}", e.name, ex.getMessage());
        }
        return false;
    }

    private void open(BasemapEntry e, TileSource source) {
        e.source = source;
        e.manifest = source.manifest();
        e.sourceId = nextSourceId++;
        e.state = BasemapEntry.State.READY;
        e.error = null;
    }

    private Path cacheDirFor(BasemapEntry e) {
        return cacheDir.resolve(e.name + "-" + e.fileSize + "-" + e.fileMtime);
    }

    private void startSlice(BasemapEntry e, Path target) {
        e.state = BasemapEntry.State.SLICING;
        Progress progress = new Progress();
        e.slicing = progress;
        final int generation = ++e.sliceGeneration;
        final Path image = e.path;
        final String name = e.name;
        final int w = e.probe.width(), h = e.probe.height();
        deleteOldCaches(name, target);
        LOG.info("Slicing {} ({}x{}) into {}", name, w, h, target);
        slicer().execute(() -> {
            long start = System.nanoTime();
            try {
                // neutral placement (pixel 0,0 at world 0,0, 1 block/px); the user's centre/scale are applied at draw time
                PackManifest m = ImageSlicer.slice(image, target, SLICE_TILE_SIZE, 1.0, w / 2.0, h / 2.0, "overworld",
                    encodePool(), progress);
                LOG.info("Sliced {}: {} levels in {} s", name, m.levels(), (System.nanoTime() - start) / 1_000_000_000L);
                sliceResults.add(new SliceResult(e, generation, m, null));
            } catch (Throwable t) {
                sliceResults.add(new SliceResult(e, generation, null, t));
            }
        });
    }

    private void drainSlices() {
        SliceResult r;
        while ((r = sliceResults.poll()) != null) {
            BasemapEntry e = r.entry;
            if (r.generation != e.sliceGeneration || !entries.contains(e) || e.state != BasemapEntry.State.SLICING) continue;
            e.slicing = null;
            if (r.error instanceof CancellationException) {
                e.state = BasemapEntry.State.IDLE;
            } else if (r.error != null) {
                e.state = BasemapEntry.State.FAILED;
                e.error = "Slicing failed: " + (r.error instanceof OutOfMemoryError ? "out of memory" : r.error.getMessage());
                LOG.warn("Slicing {} failed", e.name, r.error);
            } else {
                e.state = BasemapEntry.State.IDLE;
                ensureReady(e);
            }
        }
    }

    /** Removes cache folders of earlier versions of this file (and leftovers of interrupted slices). */
    private void deleteOldCaches(String fileName, Path keep) {
        Pattern mine = Pattern.compile(Pattern.quote(fileName) + "-\\d+-\\d+(\\.tmp)?");
        try (DirectoryStream<Path> s = Files.newDirectoryStream(cacheDir)) {
            for (Path p : s) {
                String n = p.getFileName().toString();
                if (!p.equals(keep) && mine.matcher(n).matches()) {
                    LOG.info("Deleting old cache {}", n);
                    ImageSlicer.deleteTree(p);
                }
            }
        } catch (IOException e) {
            LOG.warn("Could not clean old caches for {}", fileName, e);
        }
    }

    private void cleanTmp() throws IOException {
        try (DirectoryStream<Path> s = Files.newDirectoryStream(cacheDir, "*.tmp")) {
            for (Path p : s) ImageSlicer.deleteTree(p);
        }
    }

    /** Closes the source and frees its tiles. {@code cancelSlicing}: also stop a running slice. */
    public void release(BasemapEntry e, boolean cancelSlicing) {
        if (e.source != null) {
            if (cache != null) cache.removeSource(e.sourceId);
            try {
                e.source.close();
            } catch (IOException ex) {
                LOG.warn("Could not close {}", e.name, ex);
            }
            e.source = null;
        }
        if (e.state == BasemapEntry.State.SLICING) {
            if (cancelSlicing) {
                e.slicing.cancel();
                e.sliceGeneration++;
                e.slicing = null;
                e.state = BasemapEntry.State.IDLE;
            }
        } else {
            e.state = BasemapEntry.State.IDLE;
            e.error = null;
        }
    }

    /** Leaving a world: free all textures and close packs (slices keep running). */
    public void releaseAll() {
        for (BasemapEntry e : entries) release(e, false);
        if (cache != null) cache.clear();
    }

    public void shutdown() {
        try {
            for (BasemapEntry e : entries) release(e, true);
            if (cache != null) cache.shutdown();
            BasemapRenderer.close();
        } catch (RuntimeException e) {
            LOG.warn("Error while freeing basemaps on shutdown", e);
        }
        if (slicer != null) slicer.shutdownNow();
        if (encodePool != null) encodePool.shutdownNow();
    }

    private ExecutorService slicer() {
        if (slicer == null) slicer = Executors.newSingleThreadExecutor(daemon("Basemaps slicer"));
        return slicer;
    }

    private ExecutorService encodePool() {
        if (encodePool == null) {
            int n = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
            encodePool = Executors.newFixedThreadPool(n, daemon("Basemaps slice worker"));
        }
        return encodePool;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + " " + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        };
    }

    private TileCache cache() {
        if (cache == null) {
            int threads = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
            cache = new TileCache((long) config.textureBudgetMB << 20, threads);
        }
        return cache;
    }

    /** World X/Z of level-0 pixel (0,0) and blocks per level-0 pixel, from settings + manifest. */
    public static double[] placement(BasemapEntry e) {
        PackManifest m = e.manifest;
        ImageSettings s = e.settings;
        if (e.isPack()) return new double[] {m.originX() + s.offsetX, m.originZ() + s.offsetZ, m.blocksPerPixel()};
        double bpp = s.blocksPerPixel;
        return new double[] {s.centerX - m.widthPx() * bpp / 2.0, s.centerZ - m.heightPx() * bpp / 2.0, bpp};
    }

    // ------------------------------------------------------------------ per-frame draw

    /**
     * Called from inside Xaero's GuiMap render, while Xaero's primary map framebuffer is bound and {@code pose}
     * is the matrix Xaero uses for its region textures.
     */
    public void drawPass(DrawMode pass, Matrix4f pose, double cameraX, double cameraZ, double scale, String dimensionId) {
        long now = System.nanoTime();
        lastHookNanos = now;
        frame++;
        try {
            drainSlices();
            if (entries.isEmpty() || dimensionId == null || !(scale > 0.0)) return;
            TileCache c = cache();
            c.setBudget((long) config.textureBudgetMB << 20);
            c.drain();
            c.upload(UPLOADS_PER_PASS, frame, frame - 2);

            Minecraft mc = Minecraft.getInstance();
            int fbWidth = mc.getWindow().getWidth(), fbHeight = mc.getWindow().getHeight();
            double fboScale = XaeroView.fboScale(scale);
            int flooredX = XaeroView.flooredCamera(cameraX, fboScale);
            int flooredZ = XaeroView.flooredCamera(cameraZ, fboScale);
            double[] vis = XaeroView.visibleBounds(cameraX, cameraZ, fbWidth, fbHeight, scale);
            double margin = 2.0 / scale + 1.0;
            double left = vis[0] - margin, top = vis[1] - margin, right = vis[2] + margin, bottom = vis[3] + margin;

            List<BasemapRenderer.Quad> quads = new ArrayList<>();
            for (BasemapEntry e : entries) {
                ImageSettings s = e.settings;
                if (!s.enabled || s.mode != pass || s.opacity <= 0 || !s.dimension.matches(dimensionId)) continue;
                if (!ensureReady(e)) continue;
                collectQuads(e, c, now, scale, left, top, right, bottom, flooredX, flooredZ, quads);
            }
            BasemapRenderer.drawQuads(pass, pose, quads);
            c.housekeeping();
        } catch (Throwable t) {
            // never take Xaero's map screen down with us; log at most every 10 s
            if (now - lastErrorLogNanos > 10_000_000_000L) {
                lastErrorLogNanos = now;
                LOG.error("Basemaps draw failed ({} pass)", pass, t);
            }
        }
    }

    private void collectQuads(BasemapEntry e, TileCache c, long now, double scale, double left, double top, double right,
                              double bottom, int flooredX, int flooredZ, List<BasemapRenderer.Quad> quads) {
        PackManifest m = e.manifest;
        double[] pl = placement(e);
        double ox = pl[0], oz = pl[1], ps0 = pl[2];
        int t = m.tileSize(), levels = m.levels();
        int level = Pyramid.selectLevel(scale, ps0, levels);
        Pyramid.TileRange range = Pyramid.visibleTiles(ox, oz, ps0, m.widthPx(), m.heightPx(), t, level, left, top, right, bottom);
        while (range.count() > MAX_TILES_PER_IMAGE && level < levels - 1) {
            level++;
            range = Pyramid.visibleTiles(ox, oz, ps0, m.widthPx(), m.heightPx(), t, level, left, top, right, bottom);
        }
        if (range.isEmpty()) return;

        int alpha = Math.round(e.settings.opacity * 2.55F);
        double psL = Pyramid.pixelSize(ps0, level);
        TileSource src = e.source;
        int sid = e.sourceId;
        Fallback.Lookup lookup = (l, x, y) -> {
            TileCache.Key k = new TileCache.Key(sid, l, x, y);
            Fallback.State st = c.state(k);
            if (st == Fallback.State.MISSING) c.want(k, src, now);   // request it (coarse levels load first)
            return st;
        };
        for (int ty = range.y0(); ty <= range.y1(); ty++) {
            int th = m.tileHeight(level, ty);
            for (int tx = range.x0(); tx <= range.x1(); tx++) {
                int tw = m.tileWidth(level, tx);
                if (tw <= 0 || th <= 0) continue;
                Fallback.Choice ch = Fallback.resolve(level, tx, ty, tw, th, t, levels, lookup);
                if (ch == null) continue;
                TileCache.Tile tile = c.use(new TileCache.Key(sid, ch.level(), ch.x(), ch.y()), frame);
                if (tile == null || tile.gpu() == null) continue;
                double wx0 = ox + (double) tx * t * psL, wz0 = oz + (double) ty * t * psL;
                double wx1 = wx0 + tw * psL, wz1 = wz0 + th * psL;
                quads.add(new BasemapRenderer.Quad(tile.gpu().view(),
                    (float) (wx0 - flooredX), (float) (wz0 - flooredZ), (float) (wx1 - flooredX), (float) (wz1 - flooredZ),
                    uv(ch.px0(), tile.width()), uv(ch.py0(), tile.height()), uv(ch.px1(), tile.width()), uv(ch.py1(), tile.height()),
                    alpha));
            }
        }
    }

    private static float uv(double px, int size) {
        return (float) Math.max(0.0, Math.min(1.0, px / size));
    }
}
