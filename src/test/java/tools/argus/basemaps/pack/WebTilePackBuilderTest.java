package tools.argus.basemaps.pack;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Web-tile (x/y.png, 256 px, 1 block/px) pack builder on a tiny synthetic set with negative indices. */
class WebTilePackBuilderTest {
    private static ExecutorService pool;
    @TempDir Path dir;

    @BeforeAll
    static void setUp() { pool = Executors.newFixedThreadPool(3); }

    @AfterAll
    static void tearDown() { pool.shutdownNow(); }

    /** World pixel colour (depends on world x,z so we can check placement), fully opaque. */
    private static int world(long x, long z) {
        return 0xFF000000 | (int) ((x & 255) << 16) | (int) ((z & 255) << 8) | (int) (((x >> 8) + (z >> 8) * 3) & 255);
    }

    /** Writes web tile (tx, tz) as a palette PNG like the ARGUS map, or ARGB when the palette can't hold it. */
    private void writeWebTile(Path tiles, int tx, int tz) throws IOException {
        BufferedImage img = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++)
            for (int x = 0; x < 256; x++) img.setRGB(x, y, world(tx * 256L + x, tz * 256L + y));
        Path f = tiles.resolve(tx + "/" + tz + ".png");
        Files.createDirectories(f.getParent());
        assertTrue(ImageIO.write(img, "png", f.toFile()));
    }

    @Test
    void buildsAlignedPackWithCroppedEdgesAndCoarserLevels() throws IOException {
        Path tiles = dir.resolve("tiles");
        for (int tx = -3; tx <= 2; tx++) for (int tz = -2; tz <= 1; tz++) {
            if (tx == 0 && tz == -1) continue;   // a hole in the source map
            writeWebTile(tiles, tx, tz);
        }
        writeWebTile(tiles, 40, 40);   // far outlier, outside the range: must be ignored
        // also a palette tile with transparency (unmapped) at (1, 0)
        byte[] r = {0, (byte) 200}, g = {0, 10}, b = {0, 10}, a = {0, (byte) 255};
        BufferedImage pal = new BufferedImage(256, 256, BufferedImage.TYPE_BYTE_BINARY, new IndexColorModel(1, 2, r, g, b, a));
        for (int y = 0; y < 256; y++) for (int x = 0; x < 128; x++) pal.getRaster().setSample(x, y, 0, 1);
        assertTrue(ImageIO.write(pal, "png", tiles.resolve("1/0.png").toFile()));

        Path out = dir.resolve("pack");
        // X [-300, 300), Z [-256, 256): x tiles -2..1 (edge tiles cropped), z tiles -1..0 (no crop)
        var range = new WebTilePackBuilder.Range(-300, 300, -256, 256);
        PackManifest m = WebTilePackBuilder.build(tiles, out, range, "overworld", pool, new Progress());
        assertEquals(-512, m.originX());
        assertEquals(-256, m.originZ());
        assertEquals(4 * 256, m.widthPx());
        assertEquals(2 * 256, m.heightPx());
        assertEquals(3, m.levels());   // 1024 -> 512 -> 256
        assertEquals(m, TileSource.open(out).manifest());

        // fully inside tile: copied byte for byte (pack (1,1) = web (-1, 0))
        assertArrayEquals(Files.readAllBytes(tiles.resolve("-1/0.png")), Files.readAllBytes(out.resolve("0/1/1.png")));
        // the hole stays missing (pack (2,0) = web (0,-1))
        assertFalse(Files.exists(out.resolve("0/2/0.png")));
        // west edge tile (web -2): world x < -300 -> transparent, the rest unchanged
        TileCodec.Pixels west = TileCodec.read(out.resolve("0/0/0.png"));
        for (int x = 0; x < 256; x++) {
            long wx = -512 + x;
            int expected = wx < -300 ? 0 : world(wx, -256);
            assertEquals(expected, west.argb()[x], "x=" + x);
        }
        // east edge tile (web 1, z 0 = the palette tile): world x >= 300 cut, i.e. local x >= 44
        TileCodec.Pixels east = TileCodec.read(out.resolve("0/3/1.png"));
        assertEquals(0xFFC80A0A, east.argb()[0]);
        assertEquals(0xFFC80A0A, east.argb()[43]);
        assertEquals(0, east.argb()[44]);
        // nothing from outside the range
        assertFalse(Files.exists(out.resolve("0/4")));
        // level 1 (512 px wide = 2 tiles) and level 2 (single tile) exist
        assertTrue(Files.exists(out.resolve("1/0/0.png")));
        assertTrue(Files.exists(out.resolve("1/1/0.png")));
        TileCodec.Pixels top = TileCodec.read(out.resolve("2/0/0.png"));
        assertEquals(256, top.width());
        assertEquals(128, top.height());
        // level-1 pixel = average of the 2x2 block below it: pick an opaque interior block
        TileCodec.Pixels l1 = TileCodec.read(out.resolve("1/0/0.png"));
        int[] l0 = TileCodec.read(out.resolve("0/1/0.png")).argb();   // pack (1,0) -> level-1 (0,0) right half
        int c00 = l0[0], c10 = l0[1], c01 = l0[256], c11 = l0[257];
        int expected = tools.argus.basemaps.math.Mipmaps.halve(new int[] {c00, c10, c01, c11}, 2, 2)[0];
        assertEquals(expected, l1.argb()[128]);   // level-1 x = 256/2 = 128, y = 0
    }

    @Test
    void manifestForNegativeRange() {
        PackManifest m = WebTilePackBuilder.manifestFor(new WebTilePackBuilder.Range(-30000, 30000, -30000, 30000), "overworld");
        assertEquals(-30208, m.originX());   // floor(-30000 / 256) = -118 -> -30208
        assertEquals(60416, m.widthPx());    // tiles -118..117 -> 236 tiles
        assertEquals(236, m.tilesAcross(0));
        assertEquals(9, m.levels());
    }
}
