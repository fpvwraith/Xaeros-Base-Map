package tools.argus.basemaps.render;

import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.argus.basemaps.math.Fallback;
import tools.argus.basemaps.math.LruBudget;
import tools.argus.basemaps.pack.TileCodec;
import tools.argus.basemaps.pack.TileSource;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * On-demand tile loading and GPU texture cache shared by all sources.
 *
 * <p>The render thread asks for tiles ({@link #want}); worker threads read + decode them into off-heap RGBA
 * buffers; the render thread uploads a few per frame ({@link #upload}) and evicts least-recently-used textures
 * beyond the byte budget. Coarser levels are decoded first. Requests that stop being wanted (scrolled away) are
 * dropped before decoding. Everything except the workers runs on the render thread.
 */
public final class TileCache {
    private static final Logger LOG = LoggerFactory.getLogger("Basemaps");
    /** Max requests queued or decoding at once (bounds CPU-side memory: ~256 KB each at 256px tiles). */
    private static final int MAX_IN_FLIGHT = 96;
    /** A queued request not re-wanted for this long is dropped (the view moved on). */
    private static final long STALE_NANOS = 1_500_000_000L;

    public record Key(int source, int level, int x, int y) { }

    enum State { QUEUED, DECODED, READY, EMPTY, FAILED }

    public static final class Tile {
        final Key key;
        State state = State.QUEUED;
        volatile long wantedNanos;
        ByteBuffer cpu;
        int width, height;
        BasemapRenderer.GpuTile gpu;
        long lastSeenNanos;

        Tile(Key key, long now) {
            this.key = key;
            this.wantedNanos = now;
            this.lastSeenNanos = now;
        }

        public int width() { return width; }
        public int height() { return height; }
        public BasemapRenderer.GpuTile gpu() { return gpu; }
    }

    private record Request(Tile tile, TileSource source, long seq) { }

    private record Result(Tile tile, State state, ByteBuffer cpu, int width, int height, String error) { }

    private final Map<Key, Tile> tiles = new HashMap<>();
    private final LruBudget<Key> gpuBudget;
    private final List<Tile> decoded = new ArrayList<>();
    private final PriorityBlockingQueue<Request> queue = new PriorityBlockingQueue<>(64,
        Comparator.<Request>comparingInt(r -> -r.tile.key.level()).thenComparingLong(Request::seq));
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private final AtomicLong seq = new AtomicLong();
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean stopped;
    private int inFlight;
    private long cpuBytes;
    private long lastHousekeeping;
    private long lastErrorLog;

    public TileCache(long budgetBytes, int threads) {
        this.gpuBudget = new LruBudget<>(budgetBytes);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(this::workerLoop, "Basemaps tile loader " + (i + 1));
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            t.start();
            workers.add(t);
        }
    }

    public void setBudget(long bytes) { gpuBudget.setBudget(bytes); }
    public long gpuBytes() { return gpuBudget.used(); }
    public long budgetBytes() { return gpuBudget.budget(); }
    public int gpuTiles() { return gpuBudget.size(); }
    public long cpuBytes() { return cpuBytes; }

    // ---------------------------------------------------------------- render thread

    public Fallback.State state(Key key) {
        Tile t = tiles.get(key);
        if (t == null) return Fallback.State.MISSING;
        return switch (t.state) {
            case READY -> Fallback.State.READY;
            case EMPTY -> Fallback.State.EMPTY;
            default -> Fallback.State.MISSING;
        };
    }

    /** The tile if it is on the GPU, marking it used in {@code frame}. */
    public Tile use(Key key, long frame) {
        Tile t = tiles.get(key);
        if (t == null || t.state != State.READY) return null;
        gpuBudget.touch(key, frame);
        return t;
    }

    /** Asks for a tile to be loaded (no-op if already loaded/known empty/queued, apart from keeping it wanted). */
    public void want(Key key, TileSource source, long now) {
        Tile t = tiles.get(key);
        if (t != null) {
            t.wantedNanos = now;
            t.lastSeenNanos = now;
            return;
        }
        if (inFlight >= MAX_IN_FLIGHT) return;   // asked again next frame
        t = new Tile(key, now);
        tiles.put(key, t);
        inFlight++;
        queue.add(new Request(t, source, seq.incrementAndGet()));
    }

    /** Applies finished decodes. */
    public void drain() {
        Result r;
        while ((r = results.poll()) != null) {
            inFlight--;
            Tile t = r.tile;
            if (tiles.get(t.key) != t) {   // removed meanwhile
                if (r.cpu != null) MemoryUtil.memFree(r.cpu);
                continue;
            }
            if (r.state == null) {   // dropped as stale
                tiles.remove(t.key);
                continue;
            }
            t.state = r.state;
            if (r.state == State.DECODED) {
                t.cpu = r.cpu;
                t.width = r.width;
                t.height = r.height;
                cpuBytes += r.cpu.capacity();
                decoded.add(t);
            } else if (r.state == State.FAILED) {
                long now = System.nanoTime();
                if (now - lastErrorLog > 10_000_000_000L) {
                    lastErrorLog = now;
                    LOG.warn("Basemaps tile {} failed: {}", t.key, r.error);
                }
            }
        }
    }

    /**
     * Uploads up to {@code max} decoded tiles (coarsest first), evicting least-recently-used textures not used
     * since {@code protectFrom} to stay within the budget.
     */
    public void upload(int max, long frame, long protectFrom) {
        if (decoded.isEmpty()) return;
        long now = System.nanoTime();
        decoded.sort(Comparator.comparingInt((Tile t) -> -t.key.level()));
        int done = 0;
        for (Iterator<Tile> it = decoded.iterator(); it.hasNext() && done < max; ) {
            Tile t = it.next();
            if (tiles.get(t.key) != t || t.state != State.DECODED) {
                it.remove();
                continue;
            }
            if (now - t.wantedNanos > STALE_NANOS * 2) {   // scrolled away before upload
                it.remove();
                drop(t);
                continue;
            }
            long bytes = (long) t.width * t.height * 4;
            if (!gpuBudget.fits(bytes, protectFrom)) continue;   // everything on screen already; try later
            for (Key k : gpuBudget.evict(bytes, protectFrom)) freeTile(tiles.remove(k));
            try {
                t.gpu = BasemapRenderer.upload("basemaps tile " + t.key, t.width, t.height, t.cpu);
                t.state = State.READY;
                gpuBudget.add(t.key, bytes, frame);
            } catch (RuntimeException e) {
                t.state = State.FAILED;
                LOG.warn("Basemaps: texture upload failed for {}", t.key, e);
            }
            freeCpu(t);
            it.remove();
            done++;
        }
    }

    /** Frees everything belonging to one source (pack closed / settings reset). */
    public void removeSource(int source) {
        for (Iterator<Map.Entry<Key, Tile>> it = tiles.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, Tile> e = it.next();
            if (e.getKey().source() == source) {
                it.remove();
                gpuBudget.remove(e.getKey());
                freeTile(e.getValue());
            }
        }
    }

    /** Periodic cleanup of bookkeeping for tiles nobody looked at for a while (cheap; every few seconds). */
    public void housekeeping() {
        long now = System.nanoTime();
        if (now - lastHousekeeping < 5_000_000_000L) return;
        lastHousekeeping = now;
        boolean trimKnownEmpty = tiles.size() > 50_000;   // "empty" markers are tiny; only trim when there are lots
        for (Iterator<Map.Entry<Key, Tile>> it = tiles.entrySet().iterator(); it.hasNext(); ) {
            Tile t = it.next().getValue();
            boolean old = now - t.lastSeenNanos > 30_000_000_000L;
            if (old && (t.state == State.FAILED || (trimKnownEmpty && t.state == State.EMPTY))) it.remove();
            else if (t.state == State.DECODED && now - t.wantedNanos > STALE_NANOS * 2) {
                it.remove();
                freeTile(t);
            }
        }
    }

    public void clear() {
        for (Tile t : tiles.values()) {
            gpuBudget.remove(t.key);
            freeTile(t);
        }
        tiles.clear();
        decoded.clear();
    }

    public void shutdown() {
        stopped = true;
        for (Thread t : workers) t.interrupt();
        clear();
        Result r;
        while ((r = results.poll()) != null) if (r.cpu != null) MemoryUtil.memFree(r.cpu);
    }

    private void drop(Tile t) {
        tiles.remove(t.key);
        freeTile(t);
    }

    private void freeTile(Tile t) {
        if (t == null) return;
        freeCpu(t);
        if (t.gpu != null) {
            try {
                t.gpu.close();
            } catch (RuntimeException e) {
                LOG.warn("Basemaps: failed to free a tile texture", e);
            }
            t.gpu = null;
        }
    }

    private void freeCpu(Tile t) {
        if (t.cpu != null) {
            cpuBytes -= t.cpu.capacity();
            MemoryUtil.memFree(t.cpu);
            t.cpu = null;
        }
    }

    // ---------------------------------------------------------------- workers

    private void workerLoop() {
        while (!stopped) {
            Request req;
            try {
                req = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            Tile t = req.tile;
            if (System.nanoTime() - t.wantedNanos > STALE_NANOS) {
                results.add(new Result(t, null, null, 0, 0, null));
                continue;
            }
            try {
                byte[] bytes = req.source.read(t.key.level(), t.key.x(), t.key.y());
                if (bytes == null) {
                    results.add(new Result(t, State.EMPTY, null, 0, 0, null));
                    continue;
                }
                TileCodec.Pixels p = TileCodec.decode(bytes);
                if (p.isTransparent()) {
                    results.add(new Result(t, State.EMPTY, null, 0, 0, null));
                    continue;
                }
                ByteBuffer buf = MemoryUtil.memAlloc(p.width() * p.height() * 4);
                TileCodec.toRgba(p.argb(), buf);
                results.add(new Result(t, State.DECODED, buf, p.width(), p.height(), null));
            } catch (Throwable e) {
                results.add(new Result(t, State.FAILED, null, 0, 0, String.valueOf(e)));
            }
        }
    }
}
