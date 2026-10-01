package tools.argus.basemaps.pack;

import tools.argus.basemaps.math.Mipmaps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Builds levels 1..N-1 of a pack folder from level 0 already on disk: each tile is its 2x2 children decoded,
 * halved with an alpha-weighted box filter, cropped to the level's size. Fully transparent tiles are not
 * written. Memory: one 2T x 2T canvas per worker thread.
 */
public final class PyramidBuilder {
    private PyramidBuilder() { }

    /** Tiles in levels 1..levels-1 (for progress totals). */
    public static long coarseTileCount(PackManifest m) {
        long n = 0;
        for (int l = 1; l < m.levels(); l++) n += (long) m.tilesAcross(l) * m.tilesDown(l);
        return n;
    }

    public static void build(Path dir, PackManifest m, ExecutorService pool, Progress progress) throws IOException {
        for (int level = 1; level < m.levels(); level++) {
            final int l = level;
            progress.phase("level " + l + "/" + (m.levels() - 1));
            int across = m.tilesAcross(l), down = m.tilesDown(l);
            List<Future<?>> columns = new ArrayList<>(across);
            for (int x = 0; x < across; x++) {
                final int tx = x;
                columns.add(pool.submit(() -> {
                    buildColumn(dir, m, l, tx, down, progress);
                    return null;
                }));
            }
            waitAll(columns, progress);
        }
    }

    private static void buildColumn(Path dir, PackManifest m, int level, int x, int down, Progress progress) throws IOException {
        int t = m.tileSize();
        Path colDir = dir.resolve(level + "/" + x);
        boolean colMade = false;
        for (int y = 0; y < down; y++) {
            if (progress.cancelled()) throw new CancellationException();
            int[] canvas = null;
            for (int j = 0; j < 2; j++) {
                for (int i = 0; i < 2; i++) {
                    int cx = 2 * x + i, cy = 2 * y + j;
                    if (cx >= m.tilesAcross(level - 1) || cy >= m.tilesDown(level - 1)) continue;
                    Path child = dir.resolve(m.tilePath(level - 1, cx, cy));
                    if (!Files.isRegularFile(child)) continue;
                    TileCodec.Pixels p = TileCodec.read(child);
                    if (canvas == null) canvas = new int[4 * t * t];
                    int w = Math.min(p.width(), t), h = Math.min(p.height(), t);
                    for (int row = 0; row < h; row++) {
                        System.arraycopy(p.argb(), row * p.width(), canvas, (j * t + row) * 2 * t + i * t, w);
                    }
                }
            }
            progress.step(1);
            if (canvas == null) continue;
            int[] half = Mipmaps.halve(canvas, 2 * t, 2 * t);
            int tw = m.tileWidth(level, x), th = m.tileHeight(level, y);
            int[] tile = tw == t && th == t ? half : TileCodec.crop(half, t, 0, 0, tw, th);
            if (TileCodec.isTransparent(tile)) continue;
            if (!colMade) {
                Files.createDirectories(colDir);
                colMade = true;
            }
            TileCodec.writePng(dir.resolve(m.tilePath(level, x, y)), tile, tw, th);
        }
    }

    /** Waits for all futures; rethrows the first failure as IOException (or CancellationException). */
    static void waitAll(List<Future<?>> futures, Progress progress) throws IOException {
        IOException first = null;
        boolean cancelled = false;
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                progress.cancel();
                cancelled = true;
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof CancellationException) {
                    cancelled = true;
                } else if (first == null) {
                    first = c instanceof IOException io ? io : new IOException(String.valueOf(c), c);
                    progress.cancel();   // stop the remaining tasks early
                }
            }
        }
        if (first != null) throw first;
        if (cancelled) throw new CancellationException("cancelled");
    }
}
