package tools.argus.basemaps.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.argus.basemaps.config.BasemapsConfig;
import tools.argus.basemaps.config.DrawMode;
import tools.argus.basemaps.config.ImageSettings;
import tools.argus.basemaps.config.MapDimension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Header probing, config file round trip and user-input validation. */
class ImageLoaderTest {
    @TempDir Path dir;

    @Test
    void recognisesImageExtensions() {
        assertTrue(ImageLoader.isImageFileName("map.PNG"));
        assertTrue(ImageLoader.isImageFileName("a.jpg"));
        assertTrue(ImageLoader.isImageFileName("a.jpeg"));
        assertFalse(ImageLoader.isImageFileName("basemaps.json"));
        assertFalse(ImageLoader.isImageFileName("pack.zip"));
    }

    @Test
    void probeReadsSizeAndFormat() throws IOException {
        Path png = dir.resolve("a.png"), jpg = dir.resolve("b.jpg");
        assertTrue(ImageIO.write(new BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB), "png", png.toFile()));
        assertTrue(ImageIO.write(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB), "jpg", jpg.toFile()));
        ImageLoader.Probe p = ImageLoader.probe(png);
        assertEquals(300, p.width());
        assertEquals(200, p.height());
        assertTrue(p.isPng());
        assertTrue(ImageLoader.probe(jpg).isJpeg());
    }

    @Test
    void corruptFileIsAnIOException() throws IOException {
        Path p = dir.resolve("broken.png");
        Files.write(p, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9});
        assertThrows(IOException.class, () -> ImageLoader.probe(p));
    }

    @Test
    void configRoundTripAndSanitize() throws IOException {
        Path file = dir.resolve("basemaps.json");
        BasemapsConfig c = new BasemapsConfig();
        ImageSettings s = c.settingsFor("world.png");
        s.enabled = true;
        s.centerX = 1500.5;
        s.blocksPerPixel = 8;
        s.offsetZ = -64;
        s.opacity = 60;
        s.dimension = MapDimension.NETHER;
        s.mode = DrawMode.OVER;
        c.textureBudgetMB = 512;
        c.save(file);
        String json = Files.readString(file);
        assertTrue(json.contains("\"nether\"") && json.contains("\"over\""), json);

        BasemapsConfig back = BasemapsConfig.load(file);
        ImageSettings b = back.images.get("world.png");
        assertTrue(b.enabled);
        assertEquals(1500.5, b.centerX);
        assertEquals(8, b.blocksPerPixel);
        assertEquals(-64, b.offsetZ);
        assertEquals(60, b.opacity);
        assertEquals(MapDimension.NETHER, b.dimension);
        assertEquals(DrawMode.OVER, b.mode);
        assertEquals(512, back.textureBudgetMB);

        Files.writeString(file, "{\"textureBudgetMB\": 999999, \"images\": {\"x.png\": {\"opacity\": 400, \"blocksPerPixel\": -3, \"dimension\": \"mars\"}}}");
        BasemapsConfig fixed = BasemapsConfig.load(file);
        assertEquals(BasemapsConfig.MAX_TEXTURE_BUDGET_MB, fixed.textureBudgetMB);
        ImageSettings x = fixed.images.get("x.png");
        assertEquals(100, x.opacity);
        assertEquals(1.0, x.blocksPerPixel);
        assertEquals(MapDimension.OVERWORLD, x.dimension);
        assertFalse(x.enabled);

        Files.writeString(file, "{ this is not json");
        assertThrows(IOException.class, () -> BasemapsConfig.load(file));
    }

    @Test
    void userInputParsing() {
        assertEquals(-1234.5, ImageSettings.parseCoordinate(" -1234.5 ").getAsDouble());
        assertEquals(30000, ImageSettings.parseCoordinate("30_000").getAsDouble());
        assertTrue(ImageSettings.parseCoordinate("abc").isEmpty());
        assertTrue(ImageSettings.parseCoordinate("").isEmpty());
        assertTrue(ImageSettings.parseCoordinate("NaN").isEmpty());
        assertTrue(ImageSettings.parseCoordinate("1e5").isEmpty());
        assertTrue(ImageSettings.parseCoordinate("99999999999").isEmpty());
        assertEquals(0.5, ImageSettings.parseBlocksPerPixel("0.5").getAsDouble());
        assertEquals(16, ImageSettings.parseBlocksPerPixel("16").getAsDouble());
        assertTrue(ImageSettings.parseBlocksPerPixel("0").isEmpty());
        assertTrue(ImageSettings.parseBlocksPerPixel("-2").isEmpty());
        assertEquals("8", ImageSettings.format(8.0));
        assertEquals("0.5", ImageSettings.format(0.5));
        assertEquals("-30000", ImageSettings.format(-30000));
    }
}
