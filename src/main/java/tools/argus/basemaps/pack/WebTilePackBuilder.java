package tools.argus.basemaps.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Builds a pack from a folder of web-map tiles {@code <x>/<y>.png} at 1 block per pixel, where tile (x, y)
 * covers world X [x*256, x*256+256) and Z [y*256, y*256+256). The pack grid is aligned to the web tiles, so
 * tiles fully inside the crop range are copied byte for byte (no re-encoding); tiles on the crop edge are
 * decoded, the pixels outside the range made transparent, and re-encoded. Coarser levels are built from 2x2
 * children by {@link PyramidBuilder}.
 */
public final class WebTilePackBuilder {
    public static final int TILE = 256;

    private WebTilePackBuilder() { }

    /** Crop range in world blocks: X in [minX, maxX), Z in [minZ, maxZ). */
    public record Range(long minX, long maxX, long minZ, long maxZ) {
        public Range {
            if (maxX <= minX || maxZ <= minZ) throw new IllegalArgumentException("empty range");
        }
    }

    /** The pack manifest the builder will produce for a range (grid aligned to whole web tiles). */
    public static PackManifest manifestFor(Range r, String dimension) {
        long x0 = Math.floorDiv(r.minX(), TILE), x1 = Math.floorDiv(r.maxX() - 1, TILE);
        long z0 = Math.floorDiv(r.minZ(), TILE), z1 = Math.floorDiv(r.maxZ() - 1, TILE);
        return PackManifest.create(TILE, 1.0, x0 * TILE, z0 * TILE, (x1 - x0 + 1) * TILE, (z1 - z0 + 1) * TILE, dimension);
    }

    public static PackManifest build(Path tilesDir, Path outDir, Range range, String dimension,
                                     ExecutorService pool, Progress progress) throws IOException {
        if (!Files.isDirectory(tilesDir)) throw new IOException("not a folder: " + tilesDir);
        PackManifest m = manifestFor(range, dimension);
        long wx0 = Math.floorDiv(range.minX(), TILE), wz0 = Math.floorDiv(range.minZ(), TILE);
        Path tmp = outDir.resolveSibling(outDir.getFileName() + ".tmp");
        ImageSlicer.deleteTree(tmp);
        Files.createDirectories(tmp);
        try {
            int across = m.tilesAcross(0), down = m.tilesDown(0);
            progress.total((long) across * down + PyramidBuilder.coarseTileCount(m));
            progress.phase("level 0 (copying tiles)");
            List<Future<?>> cols = new ArrayList<>();
            for (int px = 0; px < across; px++) {
                final int tx = px;
                cols.add(pool.submit(() -> {
                    copyColumn(tilesDir, tmp, m, range, wx0 + tx, wz0, tx, down, progress);
                    return null;
                }));
            }
            PyramidBuilder.waitAll(cols, progress);
            PyramidBuilder.build(tmp, m, pool, progress);
            Files.writeString(tmp.resolve(PackManifest.FILE_NAME), m.toJson(), StandardCharsets.UTF_8);
            ImageSlicer.deleteTree(outDir);
            Files.move(tmp, outDir);
            progress.phase("done");
            return m;
        } catch (IOException | RuntimeException e) {
            try {
                ImageSlicer.deleteTree(tmp);
            } catch (IOException ignored) { }
            throw e;
        }
    }

    private static void copyColumn(Path tilesDir, Path out, PackManifest m, Range r, long webX, long webZ0, int tx,
                                   int down, Progress progress) throws IOException {
        Path srcCol = tilesDir.resolve(Long.toString(webX));
        if (!Files.isDirectory(srcCol)) {
            progress.step(down);
            return;
        }
        Path dstCol = out.resolve("0/" + tx);
        boolean made = false;
        long bx0 = webX * TILE;
        for (int ty = 0; ty < down; ty++) {
            if (progress.cancelled()) throw new CancellationException();
            progress.step(1);
            long webZ = webZ0 + ty, bz0 = webZ * TILE;
            Path src = srcCol.resolve(webZ + ".png");
            if (!Files.isRegularFile(src)) continue;
            Path dst = dstCol.resolve(ty + ".png");
            boolean inside = bx0 >= r.minX() && bx0 + TILE <= r.maxX() && bz0 >= r.minZ() && bz0 + TILE <= r.maxZ();
            if (inside && is256Png(src)) {
                if (!made) { Files.createDirectories(dstCol); made = true; }
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                continue;
            }
            TileCodec.Pixels p = TileCodec.read(src);
            if (p.width() != TILE || p.height() != TILE) {
                throw new IOException(src + " is " + p.width() + "x" + p.height() + ", expected 256x256");
            }
            int[] px = p.argb();
            for (int y = 0; y < TILE; y++) {
                long bz = bz0 + y;
                for (int x = 0; x < TILE; x++) {
                    long bx = bx0 + x;
                    if (bx < r.minX() || bx >= r.maxX() || bz < r.minZ() || bz >= r.maxZ()) px[y * TILE + x] = 0;
                }
            }
            if (TileCodec.isTransparent(px)) continue;
            if (!made) { Files.createDirectories(dstCol); made = true; }
            TileCodec.writePng(dst, px, TILE, TILE);
        }
    }

    /** Checks the PNG header says 256x256 without decoding. */
    static boolean is256Png(Path file) throws IOException {
        byte[] h = new byte[24];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.readNBytes(h, 0, 24) < 24) return false;
        }
        if ((h[0] & 255) != 137 || h[1] != 'P' || h[2] != 'N' || h[3] != 'G') return false;
        int w = ((h[16] & 255) << 24) | ((h[17] & 255) << 16) | ((h[18] & 255) << 8) | (h[19] & 255);
        int ht = ((h[20] & 255) << 24) | ((h[21] & 255) << 16) | ((h[22] & 255) << 8) | (h[23] & 255);
        return w == TILE && ht == TILE;
    }
}
