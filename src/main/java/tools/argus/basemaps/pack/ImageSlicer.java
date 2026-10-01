package tools.argus.basemaps.pack;

import tools.argus.basemaps.image.ImageLoader;
import tools.argus.basemaps.image.StreamingPngDecoder;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Slices one big image into a tile pack folder, reading it in horizontal bands of one tile row:
 * memory is one band ({@code width x tileSize} ARGB ints) plus the tiles being encoded.
 *
 * <ul>
 *   <li>Non-interlaced PNG (any size, also above Java's 2.1-gigapixel limit): streamed row by row.</li>
 *   <li>Anything up to {@link #WHOLE_READ_PIXELS}: one ImageIO read (fast; covers e.g. 7500x7500 JPEGs).</li>
 *   <li>Bigger JPEGs (and interlaced PNGs) up to the ImageIO limit: ImageIO band reads with
 *       setSourceRegion. Each band re-decodes the file from the top, so this is slow for huge files.</li>
 *   <li>Larger than the ImageIO limit and not a PNG: refused with a clear message.</li>
 * </ul>
 */
public final class ImageSlicer {
    /** Up to this many pixels (~80 MP, 320 MB as ARGB) an image is read in one go. */
    public static final long WHOLE_READ_PIXELS = 80_000_000L;
    /** Target size of one ImageIO band read in band mode. */
    static long bandReadBytes = 128L << 20;   // non-final so tests can force several bands

    private ImageSlicer() { }

    /** How an image will be read, for UI warnings. */
    public enum Mode { STREAM_PNG, WHOLE, IMAGEIO_BANDS, UNSUPPORTED }

    public static Mode mode(ImageLoader.Probe probe, boolean interlacedPng) {
        if (probe.isPng() && !interlacedPng) return Mode.STREAM_PNG;
        if (probe.pixels() <= WHOLE_READ_PIXELS) return Mode.WHOLE;
        if (probe.pixels() <= ImageLoader.IMAGEIO_PIXEL_LIMIT) return Mode.IMAGEIO_BANDS;
        return Mode.UNSUPPORTED;
    }

    /** User-facing reason an image can't be sliced, or null. */
    public static String unsupportedReason(ImageLoader.Probe probe) {
        return probe.isJpeg() ? "JPEG over 2.1 gigapixels: Java can't decode it. Convert it to PNG or make a tile pack."
            : "Image over 2.1 gigapixels: Java can't decode it in this format. Convert it to PNG or make a tile pack.";
    }

    /**
     * Slices {@code image} into the pack folder {@code outDir} (built in {@code outDir + ".tmp"}, then renamed,
     * so an interrupted slice never looks complete). The manifest places level-0 pixel (0,0) so that the image
     * centre is at (centerX, centerZ) with {@code bpp} blocks per pixel.
     */
    public static PackManifest slice(Path image, Path outDir, int tileSize, double bpp, double centerX, double centerZ,
                                     String dimension, ExecutorService pool, Progress progress) throws IOException {
        return slice(image, outDir, tileSize, bpp, centerX, centerZ, dimension, pool, progress, null);
    }

    /** {@code forcedMode} (tests only) overrides the automatic reader choice. */
    static PackManifest slice(Path image, Path outDir, int tileSize, double bpp, double centerX, double centerZ,
                              String dimension, ExecutorService pool, Progress progress, Mode forcedMode) throws IOException {
        ImageLoader.Probe probe = ImageLoader.probe(image);
        boolean interlaced = probe.isPng() && StreamingPngDecoder.readHeader(image).interlaced();
        Mode mode = forcedMode != null ? forcedMode : mode(probe, interlaced);
        if (mode == Mode.UNSUPPORTED) throw new IOException(unsupportedReason(probe));

        int w = probe.width(), h = probe.height();
        PackManifest m = PackManifest.create(tileSize, bpp, centerX - w * bpp / 2.0, centerZ - h * bpp / 2.0, w, h, dimension);
        Path tmp = outDir.resolveSibling(outDir.getFileName() + ".tmp");
        deleteTree(tmp);
        Files.createDirectories(tmp);
        try {
            progress.total((long) m.tilesAcross(0) * m.tilesDown(0) + PyramidBuilder.coarseTileCount(m));
            progress.phase(mode == Mode.IMAGEIO_BANDS ? "level 0 (slow: big JPEG)" : "level 0");
            BandWriter bands = new BandWriter(tmp, m, pool, progress);
            switch (mode) {
                case STREAM_PNG -> StreamingPngDecoder.decodeRows(image, 1, bands::row, progress::cancelled);
                case WHOLE -> readWhole(image, bands);
                case IMAGEIO_BANDS -> readBands(image, w, h, tileSize, bands, progress);
                default -> throw new IllegalStateException();
            }
            if (progress.cancelled()) throw new CancellationException();
            bands.finish();
            PyramidBuilder.build(tmp, m, pool, progress);
            Files.writeString(tmp.resolve(PackManifest.FILE_NAME), m.toJson(), StandardCharsets.UTF_8);
            deleteTree(outDir);
            Files.move(tmp, outDir);
            progress.phase("done");
            return m;
        } catch (IOException | RuntimeException e) {
            try {
                deleteTree(tmp);
            } catch (IOException ignored) { }
            throw e;
        }
    }

    private static void readWhole(Path image, BandWriter bands) throws IOException {
        BufferedImage img;
        try {
            img = ImageIO.read(image.toFile());
        } catch (RuntimeException e) {
            throw new IOException("corrupt or unsupported image: " + e, e);
        }
        if (img == null) throw new IOException("no decoder for this image");
        int w = img.getWidth();
        int[] row = new int[w];
        for (int y = 0; y < img.getHeight(); y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            bands.row(y, row);
        }
    }

    private static void readBands(Path image, int w, int h, int tileSize, BandWriter bands, Progress progress) throws IOException {
        int bandH = (int) Math.max(tileSize, bandReadBytes / (4L * w) / tileSize * tileSize);
        int[] row = new int[w];
        for (int y0 = 0; y0 < h; y0 += bandH) {
            if (progress.cancelled()) throw new CancellationException();
            int bh = Math.min(bandH, h - y0);
            BufferedImage band;
            try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
                if (!readers.hasNext()) throw new IOException("no decoder for this image");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in, true, true);
                    ImageReadParam param = reader.getDefaultReadParam();
                    param.setSourceRegion(new Rectangle(0, y0, w, bh));
                    band = reader.read(0, param);
                } finally {
                    reader.dispose();
                }
            } catch (RuntimeException e) {
                throw new IOException("corrupt or unsupported image: " + e, e);
            }
            for (int r = 0; r < bh; r++) {
                band.getRGB(0, r, w, 1, row, 0, w);
                bands.row(y0 + r, row);
            }
        }
    }

    /** Collects rows into one tile-row band and writes its tiles (in parallel) when the band is full. */
    static final class BandWriter {
        private final Path dir;
        private final PackManifest m;
        private final ExecutorService pool;
        private final Progress progress;
        private final int w, t;
        private final int[] band;
        private int bandIndex = 0, rowsInBand = 0;

        BandWriter(Path dir, PackManifest m, ExecutorService pool, Progress progress) throws IOException {
            this.dir = dir;
            this.m = m;
            this.pool = pool;
            this.progress = progress;
            this.w = (int) m.widthPx();
            this.t = m.tileSize();
            if ((long) w * t > Integer.MAX_VALUE - 8) throw new IOException("image too wide to slice");
            this.band = new int[w * t];
            for (int x = 0; x < m.tilesAcross(0); x++) Files.createDirectories(dir.resolve("0/" + x));
        }

        void row(int y, int[] argb) throws IOException {
            if (y != bandIndex * t + rowsInBand) throw new IOException("rows out of order");
            System.arraycopy(argb, 0, band, rowsInBand * w, w);
            if (++rowsInBand == t) flush();
        }

        void finish() throws IOException {
            if (rowsInBand > 0) flush();
        }

        private void flush() throws IOException {
            final int by = bandIndex, rows = rowsInBand;
            List<Future<?>> tasks = new ArrayList<>();
            int across = m.tilesAcross(0);
            int perTask = Math.max(1, across / 16);
            for (int x0 = 0; x0 < across; x0 += perTask) {
                final int from = x0, to = Math.min(across, x0 + perTask);
                tasks.add(pool.submit(() -> {
                    for (int tx = from; tx < to; tx++) {
                        if (progress.cancelled()) throw new CancellationException();
                        int tw = Math.min(t, w - tx * t);
                        int[] tile = TileCodec.crop(band, w, tx * t, 0, tw, rows);
                        if (!TileCodec.isTransparent(tile)) TileCodec.writePng(dir.resolve(m.tilePath(0, tx, by)), tile, tw, rows);
                        progress.step(1);
                    }
                    return null;
                }));
            }
            PyramidBuilder.waitAll(tasks, progress);   // band is reused, so wait before reading more rows
            bandIndex++;
            rowsInBand = 0;
        }
    }

    /** Deletes a folder tree (used only on our own .tmp / cache folders). */
    public static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            List<Path> all = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
            for (Path q : all) Files.deleteIfExists(q);
        }
    }
}
