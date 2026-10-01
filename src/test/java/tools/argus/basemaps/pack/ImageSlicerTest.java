package tools.argus.basemaps.pack;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.argus.basemaps.math.Mipmaps;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Band slicer on a synthetic 5000x3000 image, checked pixel-exactly against the reference. */
class ImageSlicerTest {
    private static final int W = 5000, H = 3000, T = 256;
    private static ExecutorService pool;
    @TempDir static Path dir;
    private static Path png;
    private static int[] reference;

    /** Opaque colour encoding the coordinates; a transparent hole in the top-left 600x300 corner. */
    private static int colour(int x, int y) {
        if (x < 600 && y < 300) return 0;
        return 0xFF000000 | ((x * 7) & 255) << 16 | ((y * 3) & 255) << 8 | ((x ^ y) & 255);
    }

    @BeforeAll
    static void setUp() throws IOException {
        pool = Executors.newFixedThreadPool(4);
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        reference = new int[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) reference[y * W + x] = colour(x, y);
        img.setRGB(0, 0, W, H, reference, 0, W);
        png = dir.resolve("synthetic.png");
        assertTrue(ImageIO.write(img, "png", png.toFile()));
    }

    @AfterAll
    static void tearDown() {
        pool.shutdownNow();
    }

    private static void assertLevel0Exact(Path pack, PackManifest m) throws IOException {
        int across = m.tilesAcross(0), down = m.tilesDown(0);
        assertEquals(20, across);
        assertEquals(12, down);
        for (int ty = 0; ty < down; ty++) {
            for (int tx = 0; tx < across; tx++) {
                Path f = pack.resolve(m.tilePath(0, tx, ty));
                int tw = m.tileWidth(0, tx), th = m.tileHeight(0, ty);
                int[] expected = TileCodec.crop(reference, W, tx * T, ty * T, tw, th);
                if (TileCodec.isTransparent(expected)) {
                    assertFalse(Files.exists(f), "transparent tile should be omitted: " + f);
                    continue;
                }
                TileCodec.Pixels p = TileCodec.read(f);
                assertEquals(tw, p.width(), f.toString());
                assertEquals(th, p.height(), f.toString());
                assertArrayEquals(expected, p.argb(), "tile " + tx + "," + ty);
            }
        }
    }

    @Test
    void streamingPngSliceIsPixelExact() throws IOException {
        Path out = dir.resolve("pack-stream");
        Progress progress = new Progress();
        PackManifest m = ImageSlicer.slice(png, out, T, 2.0, 100, -50, "nether", pool, progress);
        assertTrue(TileSource.isPackDir(out));
        assertFalse(Files.exists(dir.resolve("pack-stream.tmp")));
        assertEquals(W, m.widthPx());
        assertEquals(H, m.heightPx());
        assertEquals(6, m.levels());   // 5000 -> 2500 -> 1250 -> 625 -> 313 -> 157 fits
        assertEquals(100 - W * 2.0 / 2, m.originX());
        assertEquals(-50 - H * 2.0 / 2, m.originZ());
        assertEquals("nether", m.dimension());
        assertEquals(100, progress.percent());
        // tile (0,0) is fully inside the transparent hole: 256x256 within 600x300
        assertFalse(Files.exists(out.resolve("0/0/0.png")));
        assertLevel0Exact(out, m);

        // a level-1 tile is exactly the alpha-weighted 2x2 reduction of its children
        int[] canvas = new int[4 * T * T];
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
            int[] c = TileCodec.crop(reference, W, (6 + i) * T, (4 + j) * T, T, T);
            for (int r = 0; r < T; r++) System.arraycopy(c, r * T, canvas, (j * T + r) * 2 * T + i * T, T);
        }
        int[] expected1 = Mipmaps.halve(canvas, 2 * T, 2 * T);
        assertArrayEquals(expected1, TileCodec.read(out.resolve(m.tilePath(1, 3, 2))).argb());
        // top level is one tile of the right size
        TileCodec.Pixels top = TileCodec.read(out.resolve(m.tilePath(5, 0, 0)));
        assertEquals(157, top.width());
        assertEquals(94, top.height());
    }

    @Test
    void imageIoBandModeMatchesToo() throws IOException {
        long old = ImageSlicer.bandReadBytes;
        ImageSlicer.bandReadBytes = 4L * W * 512;   // force 512-row ImageIO bands (6 reads)
        try {
            Path out = dir.resolve("pack-bands");
            PackManifest m = ImageSlicer.slice(png, out, T, 1.0, 0, 0, "overworld", pool, new Progress(),
                ImageSlicer.Mode.IMAGEIO_BANDS);
            assertLevel0Exact(out, m);
        } finally {
            ImageSlicer.bandReadBytes = old;
        }
    }

    @Test
    void wholeReadModeMatchesToo() throws IOException {
        Path out = dir.resolve("pack-whole");
        PackManifest m = ImageSlicer.slice(png, out, T, 1.0, 0, 0, "overworld", pool, new Progress(), ImageSlicer.Mode.WHOLE);
        assertLevel0Exact(out, m);
    }

    @Test
    void smallJpegJustWorks() throws IOException {
        BufferedImage img = new BufferedImage(700, 500, BufferedImage.TYPE_INT_RGB);
        Path jpg = dir.resolve("photo.jpg");
        assertTrue(ImageIO.write(img, "jpg", jpg.toFile()));
        Path out = dir.resolve("pack-jpg");
        PackManifest m = ImageSlicer.slice(jpg, out, T, 1.0, 0, 0, "overworld", pool, new Progress());
        assertEquals(3, m.tilesAcross(0));
        assertEquals(2, m.tilesDown(0));
        assertEquals(700 - 512, TileCodec.read(out.resolve("0/2/1.png")).width());
    }

    @Test
    void cancelledSliceLeavesNothingBehind() {
        Path out = dir.resolve("pack-cancel");
        Progress p = new Progress();
        p.cancel();
        assertThrows(java.util.concurrent.CancellationException.class,
            () -> ImageSlicer.slice(png, out, T, 1.0, 0, 0, "overworld", pool, p));
        assertFalse(Files.exists(out));
        assertFalse(Files.exists(dir.resolve("pack-cancel.tmp")));
    }

    @Test
    void readsBackThroughTileSourceAndZip() throws IOException {
        Path out = dir.resolve("pack-zip-src");
        PackManifest m = ImageSlicer.slice(png, out, T, 1.0, 0, 0, "overworld", pool, new Progress());
        Path zip = dir.resolve("pack.zip");
        PackZip.zip(out, zip, new Progress());
        try (TileSource folder = TileSource.open(out); TileSource zipped = TileSource.open(zip)) {
            assertEquals(m, folder.manifest());
            assertEquals(m, zipped.manifest());
            assertNull(zipped.read(0, 0, 0));   // transparent tile: missing
            assertArrayEquals(folder.read(0, 7, 5), zipped.read(0, 7, 5));
            assertArrayEquals(folder.read(5, 0, 0), zipped.read(5, 0, 0));
        }
        assertTrue(TileSource.Zip.looksLikePack(zip));
    }
}
