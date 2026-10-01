package tools.argus.basemaps.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PyramidTest {
    @Test
    void levelCountStopsWhenOneTileFits() {
        assertEquals(1, Pyramid.levelCount(256, 256, 256));
        assertEquals(1, Pyramid.levelCount(100, 30, 256));
        assertEquals(2, Pyramid.levelCount(257, 10, 256));
        assertEquals(2, Pyramid.levelCount(512, 512, 256));
        assertEquals(9, Pyramid.levelCount(60416, 60416, 256));   // ARGUS +-30k pack: 236 tiles -> 8 halvings
        assertEquals(9, Pyramid.levelCount(60000, 60000, 256));
        assertEquals(11, Pyramid.levelCount(140000, 140000, 256)); // +-70k at 1:1
        // the top level really is a single tile
        for (long w : new long[] {1, 255, 256, 257, 5000, 60000, 140001}) {
            int top = Pyramid.levelCount(w, 3000, 256) - 1;
            assertEquals(1, Pyramid.tilesAcross(w, top, 256));
            assertEquals(1, Pyramid.tilesAcross(3000, top, 256));
        }
    }

    @Test
    void levelSizesAndEdgeTiles() {
        assertEquals(5000, Pyramid.levelSize(5000, 0));
        assertEquals(2500, Pyramid.levelSize(5000, 1));
        assertEquals(1250, Pyramid.levelSize(5000, 2));
        assertEquals(625, Pyramid.levelSize(5000, 3));
        assertEquals(313, Pyramid.levelSize(5000, 4));     // rounds up
        assertEquals(20, Pyramid.tilesAcross(5000, 0, 256));
        assertEquals(256, Pyramid.tileSpan(5000, 0, 256, 18));
        assertEquals(5000 - 19 * 256, Pyramid.tileSpan(5000, 0, 256, 19));
        assertEquals(0, Pyramid.tileSpan(5000, 0, 256, 20));
        assertEquals(0, Pyramid.tileSpan(5000, 0, 256, -1));
    }

    @Test
    void levelSelectionFromZoom() {
        int levels = 9;
        // zoomed in past 1:1 -> full resolution
        assertEquals(0, Pyramid.selectLevel(4.0, 1.0, levels));
        assertEquals(0, Pyramid.selectLevel(1.0, 1.0, levels));
        // 1 screen px per 2 blocks -> level 1 (each level-1 pixel = 2 blocks = exactly 1 screen px)
        assertEquals(1, Pyramid.selectLevel(0.5, 1.0, levels));
        // between: 0.7 px/block -> level 0 pixel is 0.7 px; level 1 pixel would be 1.4 px (too coarse) -> 0
        assertEquals(0, Pyramid.selectLevel(0.7, 1.0, levels));
        assertEquals(1, Pyramid.selectLevel(0.3, 1.0, levels));   // level 1 = 0.6 px, level 2 = 1.2 px
        assertEquals(3, Pyramid.selectLevel(1.0 / 8, 1.0, levels));
        // clamps at the top level when zoomed far out
        assertEquals(8, Pyramid.selectLevel(1.0 / 100000, 1.0, levels));
        // image blocks-per-pixel counts: an 8 blocks/px image at 1 px/block is already magnified -> level 0
        assertEquals(0, Pyramid.selectLevel(1.0, 8.0, levels));
        assertEquals(2, Pyramid.selectLevel(1.0 / 32, 8.0, levels));   // 8/32 = 0.25 px per pixel -> 2^2
        // never more than one image pixel per screen pixel at the chosen level (unless clamped)
        for (double s = 0.001; s < 4; s *= 1.07) {
            int l = Pyramid.selectLevel(s, 1.0, 20);
            assertTrue(s * (1L << l) <= 1.0 + 1e-9 || l == 0, "scale " + s);
            assertTrue(l == 0 || s * (1L << (l + 1)) > 1.0, "not the coarsest ok level at " + s);
        }
    }

    @Test
    void visibleTilesWithNegativeWorldCoordinates() {
        // ARGUS-style pack: origin -30208, 60416 px, 256 tiles, 1 block/px
        double o = -30208;
        long size = 60416;
        // view around world 0,0, 1000 blocks wide/high
        Pyramid.TileRange r = Pyramid.visibleTiles(o, o, 1.0, size, size, 256, 0, -500, -500, 500, 500);
        // tile index of world x: floor((x + 30208) / 256); -500 -> 116, 499.x -> 119
        assertEquals(new Pyramid.TileRange(116, 116, 119, 119), r);
        assertEquals(16, r.count());
        // at level 3 a tile covers 2048 blocks
        Pyramid.TileRange r3 = Pyramid.visibleTiles(o, o, 1.0, size, size, 256, 3, -500, -500, 500, 500);
        assertEquals(new Pyramid.TileRange(14, 14, 14, 14), r3);   // (30208-500)/2048 = 14.5, (30208+500)/2048 = 14.99
        // view partly off the west edge -> clamped to tile 0
        Pyramid.TileRange west = Pyramid.visibleTiles(o, o, 1.0, size, size, 256, 0, -31000, 0, -29900, 10);
        assertEquals(0, west.x0());
        assertEquals(1, west.x1());   // -29900 -> (308)/256 = 1.2
        // completely outside -> empty
        assertTrue(Pyramid.visibleTiles(o, o, 1.0, size, size, 256, 0, 40000, 0, 41000, 10).isEmpty());
        assertTrue(Pyramid.visibleTiles(o, o, 1.0, size, size, 256, 0, -40000, -40000, -30208, -30000).isEmpty());
        // a right edge exactly on a tile boundary does not pull in the next tile
        Pyramid.TileRange exact = Pyramid.visibleTiles(0, 0, 1.0, 1024, 1024, 256, 0, 0, 0, 512, 256);
        assertEquals(new Pyramid.TileRange(0, 0, 1, 0), exact);
    }

    @Test
    void visibleTilesForScaledImage() {
        // 7500 px at 8 blocks/px centred on 0,0: origin -30000, a level-0 tile = 2048 blocks
        Pyramid.TileRange r = Pyramid.visibleTiles(-30000, -30000, 8.0, 7500, 7500, 256, 0, -1, -1, 1, 1);
        assertEquals(new Pyramid.TileRange(14, 14, 14, 14), r);   // 30000/2048 = 14.6
    }
}
