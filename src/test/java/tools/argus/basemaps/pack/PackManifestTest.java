package tools.argus.basemaps.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class PackManifestTest {
    private static final String GOOD = """
        {"format":"basemaps-pack-1","tileSize":256,"blocksPerPixelAtLevel0":1,"originX":-30208,"originZ":-30208,
         "widthPx":60416,"heightPx":60416,"levels":9,"dimension":"overworld","tileExt":"png"}""";

    @Test
    void parsesTheDocumentedFormat() throws IOException {
        PackManifest m = PackManifest.parse(GOOD);
        assertEquals(256, m.tileSize());
        assertEquals(1.0, m.blocksPerPixel());
        assertEquals(-30208, m.originX());
        assertEquals(60416, m.widthPx());
        assertEquals(9, m.levels());
        assertEquals("overworld", m.dimension());
        assertEquals("0/12/7.png", m.tilePath(0, 12, 7));
        assertEquals(236, m.tilesAcross(0));
        assertEquals(1, m.tilesAcross(8));
    }

    @Test
    void roundTrips() throws IOException {
        PackManifest m = PackManifest.create(512, 2.5, -1000.5, 77, 5000, 3000, "nether");
        PackManifest back = PackManifest.parse(m.toJson());
        assertEquals(m, back);
        assertEquals(5, back.levels());   // 5000 -> 2500 -> 1250 -> 625 -> 313 fits 512
    }

    @Test
    void defaultsForOptionalFields() throws IOException {
        PackManifest m = PackManifest.parse("""
            {"format":"basemaps-pack-1","originX":0,"originZ":0,"widthPx":300,"heightPx":300,"levels":2}""");
        assertEquals(256, m.tileSize());
        assertEquals(1.0, m.blocksPerPixel());
        assertEquals("overworld", m.dimension());
        assertEquals("png", m.tileExt());
    }

    @Test
    void rejectsBadManifests() {
        String[] bad = {
            "",
            "[]",
            "{ nope",
            GOOD.replace("basemaps-pack-1", "basemaps-pack-9"),
            GOOD.replace("\"tileSize\":256", "\"tileSize\":3"),
            GOOD.replace("\"blocksPerPixelAtLevel0\":1", "\"blocksPerPixelAtLevel0\":0"),
            GOOD.replace("\"widthPx\":60416,", ""),
            GOOD.replace("\"widthPx\":60416", "\"widthPx\":-5"),
            GOOD.replace("\"levels\":9", "\"levels\":10"),     // more levels than the image has
            GOOD.replace("\"levels\":9", "\"levels\":0"),
            GOOD.replace("overworld", "moon"),
            GOOD.replace("\"tileExt\":\"png\"", "\"tileExt\":\"gif\""),
            GOOD.replace("\"originX\":-30208", "\"originX\":\"west\""),
        };
        for (String json : bad) {
            assertThrows(IOException.class, () -> PackManifest.parse(json), json);
        }
    }

    @Test
    void fewerLevelsThanMaximumIsAllowed() throws IOException {
        assertEquals(3, PackManifest.parse(GOOD.replace("\"levels\":9", "\"levels\":3")).levels());
    }
}
