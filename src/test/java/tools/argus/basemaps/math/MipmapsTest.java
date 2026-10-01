package tools.argus.basemaps.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MipmapsTest {
    @Test
    void levelCountKeepsEveryLevelAtLeastOnePixel() {
        assertEquals(12, Mipmaps.levelCount(2048, 2048));
        assertEquals(1, Mipmaps.levelCount(1, 2048));
        assertEquals(2, Mipmaps.levelCount(3, 2048));
        assertEquals(11, Mipmaps.levelCount(1356, 2048));
        for (int w = 1; w < 3000; w += 37) {
            int h = 2048;
            int levels = Mipmaps.levelCount(w, h);
            assertTrue((w >> (levels - 1)) >= 1 && (h >> (levels - 1)) >= 1, "w=" + w);
        }
    }

    @Test
    void halveAveragesOpaquePixels() {
        int[] src = {0xFF000000, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF000000};
        int[] dst = Mipmaps.halve(src, 2, 2);
        assertEquals(1, dst.length);
        int c = dst[0];
        assertEquals(255, c >>> 24);
        assertEquals(128, (c >> 16) & 255);
    }

    @Test
    void transparentPixelsDoNotDarkenColour() {
        int[] src = {0xFFFF0000, 0x00000000, 0x00000000, 0x00000000};
        int c = Mipmaps.halve(src, 2, 2)[0];
        assertEquals(255, (c >> 16) & 255);   // still pure red
        assertEquals(64, c >>> 24);           // a quarter opaque
    }

    @Test
    void halveSizes() {
        assertEquals(512 * 256, Mipmaps.halve(new int[1024 * 512], 1024, 512).length);
        assertEquals(1, Mipmaps.halve(new int[3], 3, 1).length);   // 3x1 -> 1x1 (clamped)
    }
}
